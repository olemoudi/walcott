package dev.walcott.net

import dev.walcott.data.WalcottRepository
import dev.walcott.debug.DebugLog
import dev.walcott.rules.Curfew
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who resolves nothing right now, because the phone is supposed to be shut
 * (see [dev.walcott.rules.Curfew]).
 *
 * Two halves, and they are held apart because they are known differently.
 *
 * The **observed** half comes from the enforcement loop: apps caught outstaying a closed window.
 * Catching them means watching what is on screen, which only that loop does. It is also what
 * tells the filter it is wanted at all, so it is a flow.
 *
 * The **standing** half — every browser, whenever a window is running — is worked out here, from
 * the rules and the clock, by whoever asks. That is not a detail. This object is read by the DNS
 * tunnel, and the tunnel can be brought up by the system with no enforcement loop behind it: the
 * always-on VPN is a device policy and survives a reboot, so a phone restarted at midnight has a
 * filter again within seconds while the enforcement service can be a watchdog period behind it.
 * A curfew only the loop could compute was therefore one a child could reboot their way out of —
 * measured at over four minutes on an emulator, and bounded only by the watchdog's fifteen.
 *
 * Nothing is stored and no deadline is remembered. The window ending is simply the next answer,
 * which is what makes the lift exact and makes a stuck curfew impossible.
 *
 * That last promise is kept by the standing half on behalf of both: the observed set is pushed in
 * by a loop, so it is only as current as the loop is alive, and it is the clock — not the loop —
 * that decides when it stops counting (see [Curfew.Standing.with]).
 */
object NetworkCurfew {

    /** How long the standing half is trusted before the clock is read again. */
    private const val STANDING_TTL_MS = 1_000L

    /**
     * How long the last answer survives while the rules cannot be read at all.
     *
     * Reading them is a DataStore read and can fail — a corrupt file, a disk that is full. Both
     * ways of answering that are wrong for ever: forgetting the curfew hands the child the
     * evening back on a failure they did not have to cause, and holding it hands them a browser
     * that resolves nothing tomorrow afternoon with no rule anywhere to explain it. So the last
     * answer is held for a few minutes — longer than any transient, far shorter than a night —
     * and then let go, loudly.
     */
    private const val STALE_GRACE_MS = 10 * 60 * 1000L

    private val _packages = MutableStateFlow<Set<String>>(emptySet())

    /**
     * What the enforcement loop last worked out — the observed half plus its own copy of the
     * standing one. Followed by the filter's on/off decision: a curfew is a reason to have a
     * tunnel, and this is the half that can say so before anyone makes a DNS query.
     */
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    @Volatile private var standing = Curfew.Standing(windowOpen = false, packages = emptySet())

    /** When the standing half was last worked out, and when it was last worked out SUCCESSFULLY. */
    @Volatile private var standingAt = 0L
    @Volatile private var standingFreshAt = 0L

    /** Whether the rules are currently unreadable, so the failure is logged once and not per query. */
    @Volatile private var readFailing = false

    /**
     * The standing half alone, as of the last [cutOffNow].
     *
     * Told apart from the whole answer for the one question that cannot be asked any other way:
     * WHICH half named a package. The two are indistinguishable in the sum, so a device test
     * proving the filter derives the window for itself — rather than being handed it by an
     * enforcement loop that may not be running — has nothing else to look at.
     */
    val standingNow: Set<String> get() = standing.packages

    /** Replaces the observed set; logs only real changes, since the loop asserts this every tick. */
    fun set(value: Set<String>) {
        if (value == _packages.value) return
        DebugLog.i(
            TAG,
            if (value.isEmpty()) "curfew lifted" else "curfew: ${value.sorted().joinToString()} resolve nothing",
        )
        _packages.value = value
    }

    /**
     * Everyone cut off at this instant — the one answer, so the packet loop, the watchdog's
     * re-assert and anything diagnosing this cannot disagree about it.
     *
     * Re-derives the standing half at most once a [STANDING_TTL_MS]: this sits in the path of
     * every DNS query the phone makes, and a window is a thing that turns over on the hour.
     */
    suspend fun cutOffNow(repository: WalcottRepository): Set<String> {
        val since = android.os.SystemClock.elapsedRealtime()
        if (since - standingAt > STANDING_TTL_MS) {
            standingAt = since
            val fresh = runCatching {
                Curfew.standing(
                    repository.configNow(),
                    // Through the shared inventory, which caches this and drops it when a package
                    // arrives — so a browser installed this afternoon is in tonight's window.
                    repository.inventory.browserPackages(),
                    java.time.LocalDateTime.now(),
                )
            }.onFailure {
                // Once per outage, not once per lookup: this sits in the path of every DNS query.
                if (!readFailing) {
                    readFailing = true
                    DebugLog.e(TAG, "could not read the rules; holding the last curfew for now", it)
                }
            }.getOrNull()
            when {
                fresh != null -> {
                    readFailing = false
                    // Said out loud, and only on a change. An app that silently stops resolving is
                    // the hardest kind of fault to explain afterwards, and on a phone whose
                    // enforcement loop is not running this line is the only record that it was a
                    // rule and not the network.
                    if (fresh != standing) {
                        DebugLog.i(
                            TAG,
                            when {
                                !fresh.windowOpen -> "window over: everything resolves again"
                                fresh.packages.isEmpty() -> "window running, and no browser on this phone"
                                else -> "window: ${fresh.packages.sorted().joinToString()} resolve nothing"
                            },
                        )
                    }
                    standing = fresh
                    standingFreshAt = since
                }
                since - standingFreshAt > STALE_GRACE_MS -> {
                    // Held long enough that it no longer proves anything about the hour.
                    if (standing.windowOpen) {
                        DebugLog.w(TAG, "rules unreadable for too long; lifting the curfew")
                    }
                    standing = Curfew.Standing(windowOpen = false, packages = emptySet())
                }
            }
        }
        return standing.with(_packages.value)
    }

    private const val TAG = "WalcottCurfew"
}
