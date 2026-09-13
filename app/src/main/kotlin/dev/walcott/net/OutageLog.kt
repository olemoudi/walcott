package dev.walcott.net

/**
 * Which of a run of identical failures earns a line in the debug log.
 *
 * The packet loop fails the same way on every lookup while the phone is offline: in airplane mode
 * or on a Wi-Fi without internet that is a warning per DNS query, and the diagnostics log is a
 * 128 KB ring — full of that one line within minutes, every earlier line pushed out, and the file
 * rewritten continuously for as long as the outage lasts. So a failure is logged when it starts
 * an outage (the first, or the first after something worked) and then at most once per
 * [intervalMs], saying how many were held back.
 *
 * Pure, with the clock passed in, so the policy is tested rather than trusted.
 */
class OutageLog(private val intervalMs: Long = 60_000L) {

    @Volatile private var failing = false
    private var loggedAtMs = 0L
    private var heldBack = 0

    /**
     * A failure at [nowMs]. Returns how many failures were held back since the last line when
     * this one should be logged, or null when it should be held back too.
     */
    @Synchronized
    fun failed(nowMs: Long): Int? {
        if (failing && nowMs - loggedAtMs < intervalMs) {
            heldBack++
            return null
        }
        val count = heldBack
        failing = true
        loggedAtMs = nowMs
        heldBack = 0
        return count
    }

    /** Something worked, so the next failure starts a new outage and is logged at once. */
    fun succeeded() {
        // A field read on the hot path; the lock only when there is an outage to end.
        if (!failing) return
        synchronized(this) { failing = false }
    }

    companion object {
        /** The suffix a log line carries for [count] held-back failures, empty when there were none. */
        fun heldBackSuffix(count: Int): String = if (count > 0) " ($count more like it since the last report)" else ""
    }
}
