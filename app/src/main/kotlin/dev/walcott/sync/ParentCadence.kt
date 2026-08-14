package dev.walcott.sync

/**
 * How soon the parent's catch-up should run again (see [ParentCheckAlarm]).
 *
 * With the parent's app closed there is no socket — the process is gone — so this alarm is the
 * only thing that fetches a child's request, and its interval IS the latency a waiting child
 * sees. At a flat half hour that averaged fifteen minutes, which is a long time to stare at a
 * phone after asking for ten more minutes.
 *
 * So the default is now the fastest the OS will actually honour: `setAndAllowWhileIdle` is
 * throttled to roughly one firing per app every nine minutes in Doze, and asking for less simply
 * gets deferred, so [FAST_MS] is a floor rather than a preference.
 *
 * The saving comes back in the one case that is a FACT rather than a guess: a child's phone that
 * has not checked in for an hour — switched off, out of coverage, flat — cannot be the source of
 * anything new, so polling for it three times an hour buys nothing. Predicting quiet from the
 * rules instead (bedtime, screen-free windows) was the obvious-looking alternative and is wrong
 * in exactly the wrong direction: hitting a block is the archetypal reason a child asks for
 * something in the first place.
 *
 * [SLOW_MS] is deliberately the interval this used to run at unconditionally, so the worst case
 * here can never be worse than the behaviour it replaces.
 */
object ParentCadence {

    /** While a child device is alive and could be asking. The OS floor, near enough. */
    const val FAST_MS = 10 * 60 * 1000L

    /** While nothing is alive to ask. The old fixed interval, so this is never a regression. */
    const val SLOW_MS = 30 * 60 * 1000L

    /**
     * Silence after which a child device is treated as unable to ask for anything. A child
     * publishes at least every ~30 minutes ([HeartbeatAlarm.INTERVAL_MS]) whether or not anyone
     * is using it, so an hour is two missed check-ins — the same threshold the parent's own UI
     * uses to stop calling a device fresh ([Staleness.RESTING_AFTER_MS]).
     */
    const val QUIET_AFTER_MS = Staleness.RESTING_AFTER_MS

    /**
     * The interval to arm the next catch-up with. [newestChildSeenMs] is the most recent check-in
     * across every child of every family, or null when none has ever reported.
     *
     * Every uncertain answer resolves to [FAST_MS]: a wasted wakeup costs a fraction of a percent
     * of a battery, while a wrongly slow one costs exactly the delay this exists to remove. Skew
     * is handled by [Staleness.silenceMs], so a check-in stamped in the future reads as recent.
     */
    fun nextIntervalMs(newestChildSeenMs: Long?, nowMs: Long): Long {
        val silence = Staleness.silenceMs(newestChildSeenMs, nowMs) ?: return SLOW_MS
        return if (silence >= QUIET_AFTER_MS) SLOW_MS else FAST_MS
    }
}
