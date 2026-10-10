package dev.walcott.debug

import android.app.admin.DevicePolicyManager
import android.content.Context
import dev.walcott.BuildConfig
import java.io.File

/**
 * What the breaker remembers from one process to the next: how many starts in a row died before
 * enforcement got going, which build they were, and since when the phone has been left open
 * because of it (0 = it has not).
 */
data class BreakerState(val earlyCrashes: Int, val versionCode: Int, val safeModeSinceMs: Long) {
    companion object {
        val CLEAN = BreakerState(0, 0, 0)
    }
}

/**
 * The breaker's decisions, kept pure so every one of them is tested on the JVM rather than by
 * crashing a phone.
 */
object CrashBreakerRules {

    /**
     * Starts in a row that died before enforcement first applied the rules, after which the phone
     * is opened. Three, because one is an accident and two can be a reboot racing an update; the
     * cost of waiting for the third is a couple of minutes of a phone that is stuck anyway.
     */
    const val EARLY_CRASHES_TO_TRIP = 3

    /**
     * Times in a row the enforcement loop may fail inside one living process — caught and
     * restarted, not crashed — without once applying the rules, before the phone is opened. A
     * loop stuck like that leaves whatever it last suspended suspended, exactly like a crash
     * loop, and never dies loudly enough to be counted as one.
     */
    const val LOOP_FAILURES_TO_TRIP = 5

    /** And for at least this long: see [CrashBreaker.recordLoopFailure]. */
    const val LOOP_FAILURE_MIN_SPAN_MS = 10 * 60 * 1000L

    /**
     * The state a process starts from. A new build starts clean: it is the fix, or at least
     * deserves its own three chances. A phone already open stays open. A phone that has just
     * died [EARLY_CRASHES_TO_TRIP] times in a row is opened now — but only a Device Owner, the
     * only kind of phone whose apps this app can leave suspended.
     */
    fun atStart(stored: BreakerState, versionCode: Int, deviceOwner: Boolean, nowMs: Long): BreakerState = when {
        stored.versionCode != versionCode -> BreakerState(0, versionCode, 0)
        !deviceOwner -> stored.copy(safeModeSinceMs = 0)
        stored.safeModeSinceMs > 0 -> stored
        stored.earlyCrashes >= EARLY_CRASHES_TO_TRIP -> stored.copy(safeModeSinceMs = nowMs)
        else -> stored
    }

    /**
     * Whether a crash counts towards opening the phone: only one that came before enforcement had
     * applied the rules once in this process, and with none of this app's screens showing.
     *
     * The second half is what keeps the door shut to the person holding the phone. A screen of
     * this app that crashes is something a child can find and repeat; three of them must never
     * add up to a phone with no rules. Nothing they can do reaches a start-up that dies on its
     * own in the background.
     */
    fun countsAsEarly(enforcedOnce: Boolean, screenShown: Boolean): Boolean = !enforcedOnce && !screenShown

    fun afterCrash(state: BreakerState, early: Boolean): BreakerState =
        if (early) state.copy(earlyCrashes = state.earlyCrashes + 1) else state

    /** Enforcement applied the rules: whatever came before was not a loop. */
    fun afterEnforced(state: BreakerState): BreakerState = state.copy(earlyCrashes = 0)

    /** The phone goes back to its rules (a new build, the parent, a new family). */
    fun left(state: BreakerState): BreakerState = state.copy(earlyCrashes = 0, safeModeSinceMs = 0)
}

/** The breaker's file: one line, `<earlyCrashes> <versionCode> <safeModeSinceMs>`. */
internal object BreakerStateFormat {

    fun format(state: BreakerState): String = "${state.earlyCrashes} ${state.versionCode} ${state.safeModeSinceMs}"

    /**
     * Anything unreadable reads as [BreakerState.CLEAN]. A corrupt file must not be able to open
     * a phone on its own, and losing the count costs at most another three crashes.
     */
    fun parse(text: String?): BreakerState {
        val parts = text?.trim()?.split(' ') ?: return BreakerState.CLEAN
        if (parts.size != 3) return BreakerState.CLEAN
        val crashes = parts[0].toIntOrNull() ?: return BreakerState.CLEAN
        val version = parts[1].toIntOrNull() ?: return BreakerState.CLEAN
        val since = parts[2].toLongOrNull() ?: return BreakerState.CLEAN
        if (crashes < 0 || since < 0) return BreakerState.CLEAN
        return BreakerState(crashes, version, since)
    }
}

/**
 * Safe mode for a phone this app has stopped being able to run on.
 *
 * A Device Owner's suspensions and restrictions outlive the process that set them — reboots
 * included — and the only code that ever lifts them is this app's own. So a build that dies at
 * start-up on a child's phone froze it in whatever state it was in: suspended at bedtime meant
 * suspended for good. Every way out ran in the process that was dying — the PIN, the rescue
 * code, a release from the parent, the emergency release — and the two doors outside it, safe
 * boot and adb, are locked by default precisely because those exist. What was left was a factory
 * reset. The parent's phone runs each build first, but it never runs the child's half of it.
 *
 * So the breaker runs first thing in every process, before anything that could be what is
 * crashing, and once the phone has died at start-up [CrashBreakerRules.EARLY_CRASHES_TO_TRIP]
 * times in a row it OPENS the phone: every app given back, the filter's always-on VPN let go.
 * Device Owner and the device restrictions stay, so nothing needs re-enrolling. Enforcement then
 * refuses to start ([active]), while the family channel and the updater carry on: the parent is
 * told, and the next build — the fix — starts clean. The parent can also put the rules back by
 * hand ([dev.walcott.sync.RemoteAction.REAPPLY_POLICY]).
 *
 * Written synchronously to a plain file, like [CrashCounter], because the moment it matters most
 * is from inside the uncaught-exception handler of a process that is about to die.
 */
object CrashBreaker {

    private const val TAG = "WalcottBreaker"
    internal const val FILE_NAME = "crash-breaker.txt"

    @Volatile private var file: File? = null
    @Volatile private var state = BreakerState.CLEAN
    @Volatile private var appContext: Context? = null

    /** Whether enforcement has applied the rules at least once in this process. */
    @Volatile private var enforcedOnce = false

    /** Loop failures in a row since the rules were last applied, and when the first of them was. */
    @Volatile private var loopFailures = 0
    @Volatile private var firstLoopFailureAtMs = 0L

    /** Whether one of this app's screens is in front, kept by the application's lifecycle hooks. */
    @Volatile var screenShown = false

    /** Whether the phone is open because this app cannot run on it: nothing may enforce. */
    val active: Boolean get() = state.safeModeSinceMs > 0

    /** When the phone was opened, for the parent; 0 when it has not been. */
    val safeModeSinceMs: Long get() = state.safeModeSinceMs

    /**
     * Call first thing in [android.app.Application.onCreate], right after the crash logger, so
     * nothing that could be the crash runs before it. Touches only a file and the device policy
     * service, and never throws.
     */
    fun onProcessStart(context: Context) {
        runCatching {
            appContext = context.applicationContext
            val f = File(context.filesDir, FILE_NAME)
            file = f
            val stored = BreakerStateFormat.parse(f.takeIf { it.exists() }?.readText())
            val owner = isDeviceOwner(context)
            val next = CrashBreakerRules.atStart(stored, BuildConfig.VERSION_CODE, owner, System.currentTimeMillis())
            if (next != stored) write(next)
            state = next
            if (next.safeModeSinceMs > 0 && stored.safeModeSinceMs == 0L) {
                DebugLog.e(TAG, "${stored.earlyCrashes} starts in a row died before enforcing: opening this phone")
            }
            if (active) openPhone(context)
        }.onFailure { DebugLog.e(TAG, "the crash breaker could not start", it) }
    }

    /**
     * From the uncaught-exception handler, on the dying thread: counts the crash if it is early,
     * and makes sure there will be a next start to count. Answers whether this crash is one the
     * breaker is handling — an early one, on a Device Owner phone not yet opened — in which case
     * the process should die quietly rather than through Android's crash handling (see
     * WalcottApplication.installCrashLogger).
     *
     * Android's own handling is what made a crash loop slow to break, measured on an emulator: the
     * second crash inside a minute puts "Walcott keeps stopping" in front of whoever holds the
     * phone, holds the dying process until somebody dismisses it (five minutes otherwise), and
     * then marks the process bad — no sticky restart, its alarms and broadcasts dropped. The
     * third start the count waits for then came from whatever next ran a job, hours away under
     * Doze, with the phone frozen in its rules the whole time. Dying quietly, the process is
     * restarted like any other that was killed: the sticky service, the device-admin binding (see
     * WalcottDeviceAdminService), and the alarm armed here as the last resort, half a minute out
     * (see [CrashRevivalAlarm]).
     */
    fun recordCrash(): Boolean {
        return runCatching {
            val early = CrashBreakerRules.countsAsEarly(enforcedOnce, screenShown)
            if (!early) return@runCatching false
            write(CrashBreakerRules.afterCrash(state, early = true).also { state = it })
            // Only where the breaker can trip: on any other phone nothing is frozen, and a build
            // that dies at every start would be started again every half minute for nothing.
            val context = appContext ?: return@runCatching false
            if (active || !isDeviceOwner(context)) return@runCatching false
            CrashRevivalAlarm.schedule(context)
            true
        }.getOrDefault(false)
    }

    /**
     * The enforcement loop applied the rules. The first time in a process, it is also the proof
     * that this build starts, and the count of crashed starts goes back to zero.
     */
    fun markEnforced() {
        loopFailures = 0
        if (enforcedOnce) return
        enforcedOnce = true
        if (state.earlyCrashes != 0) runCatching { write(CrashBreakerRules.afterEnforced(state).also { state = it }) }
    }

    /**
     * The enforcement loop failed and is about to be restarted. Answers whether that was one time
     * too many — in which case the phone has just been opened and the loop must stop.
     */
    fun recordLoopFailure(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (loopFailures++ == 0) firstLoopFailureAtMs = now
        // A count AND a stretch of time: five failures are a quarter of a minute apart, and a
        // phone whose storage is a moment slow at boot must not be opened for it.
        val lasting = now - firstLoopFailureAtMs >= CrashBreakerRules.LOOP_FAILURE_MIN_SPAN_MS
        if (loopFailures < CrashBreakerRules.LOOP_FAILURES_TO_TRIP || !lasting || active) return active
        if (!isDeviceOwner(context)) return false
        DebugLog.e(TAG, "the enforcement loop failed $loopFailures times without applying the rules: opening this phone")
        runCatching { write(state.copy(safeModeSinceMs = System.currentTimeMillis()).also { state = it }) }
        openPhone(context)
        return true
    }

    /**
     * Puts the rules back in charge: the parent asked, or the phone joined a family afresh.
     * Answers whether there was anything to leave.
     */
    fun leave(why: String): Boolean {
        if (!active && state.earlyCrashes == 0) return false
        val wasActive = active
        loopFailures = 0
        runCatching { write(CrashBreakerRules.left(state).also { state = it }) }
        if (wasActive) DebugLog.w(TAG, "leaving safe mode: $why")
        return wasActive
    }

    /**
     * Gives every app back and lets the filter's VPN go. One bulk call for the suspensions, made
     * here on whatever thread starts the process: it is a single binder round trip, and the phone
     * being usable is the one thing that must not wait for anything else to start.
     */
    private fun openPhone(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!isDeviceOwner(context)) return
        val admin = dev.walcott.WalcottAdminReceiver.componentName(context)
        runCatching {
            val packages = context.packageManager.getInstalledApplications(0).map { it.packageName }.distinct()
            val refused = dpm.setPackagesSuspended(admin, packages.toTypedArray(), false)
            DebugLog.w(TAG, "safe mode: ${packages.size} package(s) given back, ${refused?.size ?: 0} refused")
        }.onFailure { DebugLog.e(TAG, "safe mode could not give the apps back", it) }
        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
            .onFailure { DebugLog.e(TAG, "safe mode could not let the always-on VPN go", it) }
        runCatching { dev.walcott.net.WalcottVpnService.stop(context) }
    }

    /**
     * Debug builds only (see the call in WalcottApplication): dies at start-up while the drill
     * file says so, one count per start, the way a broken build would. What the harness needs to
     * see Android's own crash handling — the process marked bad, the sticky restart withheld —
     * which the counting drill (an ordinary kill) never reaches (see SafeModeScenarioTest).
     */
    fun crashDrill(context: Context) {
        val drill = File(context.filesDir, CRASH_DRILL_FILE)
        val left = runCatching { drill.readText().trim().toInt() }.getOrDefault(0)
        if (left <= 0) return
        runCatching { drill.writeText((left - 1).toString()) }
        throw IllegalStateException("crash drill: $left start(s) left to die")
    }

    internal const val CRASH_DRILL_FILE = "crash-drill.txt"

    private fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    private fun write(next: BreakerState) {
        file?.writeText(BreakerStateFormat.format(next))
    }
}
