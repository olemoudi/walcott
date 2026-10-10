package dev.walcott.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.debug.DebugLog
import dev.walcott.runHeld
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Wakes the enforcement loop while the screen is off, at the minute the rules give something back.
 *
 * The loop parks with the screen off, and for closing things that is right: nobody is using an app
 * that cannot be seen. For OPENING them it was wrong. Bedtime ended at seven with the phone in a
 * pocket, and every app suspended for the night stayed suspended until someone turned the screen
 * on — and a suspended app cannot ring. The parent's call on a messaging app went unanswered on a
 * phone whose rules said it should be ringing; so did one after a homework window, or a pause.
 *
 * An alarm rather than a timer, because `delay()` does not tick in deep sleep; and an exact one
 * (see [dev.walcott.OnTimeAlarm]), because an inexact alarm may arrive up to an hour late, which
 * for "bedtime is over" is most of the way back to holding it until morning.
 *
 * The loop decides when ([dev.walcott.rules.CloseWatch.nextOpening]) and arms this before it
 * parks. Anything else that can give something back while the screen is off — a parent ending a
 * pause, granting minutes, changing a rule — nudges it through [poke] instead.
 */
object EnforcementWakeAlarm {

    private const val TAG = "WalcottEnforce"
    private const val REQUEST_CODE = 4715

    /** A moment past the minute, so a clock read a hair early cannot find the window still shut. */
    private const val SLACK_MS = 2_000L

    private val pokes = MutableStateFlow(0L)

    /**
     * How many times the loop has been asked for a pass. A counter rather than an event, because
     * the loop reads it BEFORE it decides to park and waits for it to move: a poke that lands in
     * between is still seen, where an event emitted with nobody listening yet would be lost.
     */
    val pokeCount: StateFlow<Long> = pokes

    /** Asks the parked loop for one pass now. Cheap and idempotent: several pokes are one pass. */
    fun poke() {
        pokes.update { it + 1 }
    }

    private val passes = MutableStateFlow(0L)

    /** How many passes the loop has finished — what a wake-up waits on before letting the CPU sleep. */
    val passCount: StateFlow<Long> = passes

    /** Called by the loop at the end of every pass, applied or not. */
    fun passDone() {
        passes.update { it + 1 }
    }

    /**
     * Keeps the CPU up through the pass an alarm asked for. Always with a timeout, always released.
     *
     * The alarm wakes the phone only for as long as its receiver runs, and the pass runs after the
     * receiver has returned — on the loop, which first reads the database. Without this the
     * processor was free to suspend again in between, and the apps due back stayed suspended
     * until something else woke the phone (the same hole LocationAlarm closes the same way).
     */
    internal fun wakeLock(context: Context): android.os.PowerManager.WakeLock? = runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "walcott:apps-due-back")
            .apply { setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS) }
    }.getOrNull()

    /** Long enough for a pass on a slow phone whose service the alarm itself had to start. */
    internal const val PASS_WAIT_MS = 15_000L
    private const val WAKELOCK_TIMEOUT_MS = PASS_WAIT_MS + 5_000L

    /** Wakes the phone at [atMs]; cancels the alarm for null or a moment already past. */
    fun arm(context: Context, atMs: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (atMs == null || atMs <= System.currentTimeMillis()) {
            runCatching { alarms.cancel(pendingIntent(context)) }
            return
        }
        runCatching {
            dev.walcott.OnTimeAlarm.set(alarms, atMs + SLACK_MS, pendingIntent(context))
        }.onFailure { DebugLog.e(TAG, "could not schedule the wake-up for apps due back", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, EnforcementWakeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Fires when apps are due back on a phone with its screen off; see [EnforcementWakeAlarm].
 *
 * Starts the service as well as poking it: an alarm is exactly how a loop that died with the
 * screen off comes back, and a Device Owner — the only kind of phone whose apps are suspended at
 * all — may start a foreground service from the background.
 */
class EnforcementWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lock = EnforcementWakeAlarm.wakeLock(context)
        val passesBefore = EnforcementWakeAlarm.passCount.value
        runCatching { EnforcementService.start(context) }
            .onFailure { DebugLog.w("WalcottEnforce", "could not start enforcement for apps due back", it) }
        EnforcementWakeAlarm.poke()
        runHeld {
            try {
                val done = kotlinx.coroutines.withTimeoutOrNull(EnforcementWakeAlarm.PASS_WAIT_MS) {
                    EnforcementWakeAlarm.passCount.first { it > passesBefore }
                }
                if (done == null) DebugLog.w("WalcottEnforce", "no pass within ${EnforcementWakeAlarm.PASS_WAIT_MS / 1000}s of the wake-up")
            } finally {
                runCatching { lock?.release() }
            }
        }
    }
}
