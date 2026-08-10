package dev.walcott.sync

/**
 * Bounding a per-app usage report without lying about the total.
 *
 * Usage is counted per app, and the number of apps a child touches in a day has no ceiling — but
 * a snapshot that outgrows the message cap takes the child off the air entirely (see
 * [SnapshotFit]). So the list is capped.
 *
 * Capping by simple truncation would be the wrong kind of cheap: the parent's home card, the
 * weekly report and [ChildStats.usedTodayOn] all SUM these entries, so dropping the tail would
 * quietly under-report a child's screen time — the one number the whole feature exists to show,
 * wrong in the direction that makes a phone look better than it is. The remainder is collapsed
 * into one [OTHER] entry instead, so every total stays exact however hard the list is squeezed.
 */
object UsageReport {

    /**
     * The bucket the long tail is folded into. Not a package name — every real one contains a
     * dot — so it can never collide with an app's own counter.
     */
    const val OTHER = "other"

    /** Entries carried for today. Generous: a child rarely opens forty apps in a day. */
    const val MAX_TODAY = 40

    /**
     * Entries carried per past day. Tighter, because seven days multiply: nothing reads a past
     * day's breakdown yet (the weekly report shows daily totals), so this is headroom for when
     * something does, not a bill worth paying now.
     */
    const val MAX_PER_HISTORY_DAY = 8

    /**
     * The busiest [max] entries, with everything else folded into [OTHER].
     *
     * Zero-second entries are dropped: they cost bytes to say nothing. An empty result stays
     * empty rather than gaining an "other: 0" row.
     */
    fun cap(entries: List<UsageEntry>, max: Int): List<UsageEntry> {
        require(max > 0) { "max must be positive" }
        val real = entries.filter { it.seconds > 0 }
        if (real.size <= max) return real.sortedByDescending { it.seconds }
        val sorted = real.sortedByDescending { it.seconds }
        val kept = sorted.take(max - 1)
        val rest = sorted.drop(max - 1).sumOf { it.seconds }
        return if (rest > 0) kept + UsageEntry(OTHER, rest) else kept
    }
}
