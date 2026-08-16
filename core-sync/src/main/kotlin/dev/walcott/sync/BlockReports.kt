package dev.walcott.sync

import kotlinx.serialization.Serializable

/** One thing that was blocked — a domain or a package — and how many times. */
@Serializable
data class BlockCount(val key: String, val count: Long)

/** A past day's totals, carried so a device that was offline leaves no hole in the history. */
@Serializable
data class DayBlockTotals(val epochDay: Long, val net: Long = 0, val rule: Long = 0)

/**
 * What a child's filter and rules blocked, as the child counts it.
 *
 * Today's totals are exact and the breakdowns are the biggest few, because the shape of the
 * message decides what can be said: a snapshot has a few hundred bytes to spare, and a phone
 * with the tracker list on refuses lookups for dozens of distinct domains a day. So the totals
 * — the number a parent actually reads — never approximate anything, and the lists fold their
 * tail into [BlockReports.OTHER] rather than truncating, which keeps them summing to the total.
 *
 * The parent accumulates these into its own history (see BlockLedger); nothing here is meant to
 * be the archive.
 */
@Serializable
data class BlockReport(
    /** The child's own local day these counters belong to. */
    val epochDay: Long = 0,
    /** DNS lookups the filter refused today. */
    val netToday: Long = 0,
    /** Apps a rule closed today (an app out of time, or a device-wide window starting). */
    val ruleToday: Long = 0,
    val domains: List<BlockCount> = emptyList(),
    val netApps: List<BlockCount> = emptyList(),
    val ruleApps: List<BlockCount> = emptyList(),
    /** Totals for the days before today this device still remembers, oldest first. */
    val recentDays: List<DayBlockTotals> = emptyList(),
) {
    /** Nothing was blocked and nothing is worth sending. */
    fun isEmpty(): Boolean =
        netToday == 0L && ruleToday == 0L && recentDays.all { it.net == 0L && it.rule == 0L }

    /** The same report carrying only its totals, for a message that is running out of room. */
    fun totalsOnly(): BlockReport =
        copy(domains = emptyList(), netApps = emptyList(), ruleApps = emptyList())
}

/** Bounds for what a [BlockReport] carries. Pure, so the folding is unit-tested. */
object BlockReports {

    /**
     * Where a capped tail goes. Deliberately the same string the child's own database uses for
     * its compaction, so a folded tail merges with a folded tail instead of becoming two rows
     * that mean the same thing.
     */
    const val OTHER = "__other__"

    /** Entries per breakdown. Three of these ride every snapshot; five each is ~350 bytes. */
    const val MAX_KEYS = 5

    /** Past days carried for catch-up. A week covers a phone that was off for the weekend. */
    const val MAX_RECENT_DAYS = 7

    /**
     * The [max] biggest entries, everything else added up into [OTHER]. Zero counts are dropped
     * (they cost bytes to say nothing) and an empty list stays empty rather than growing an
     * "other: 0" row.
     */
    fun cap(entries: List<BlockCount>, max: Int = MAX_KEYS): List<BlockCount> {
        require(max > 0) { "max must be positive" }
        val real = entries.filter { it.count > 0 }.sortedByDescending { it.count }
        if (real.size <= max) return real
        val kept = real.take(max - 1)
        val rest = real.drop(max - 1).sumOf { it.count }
        return if (rest > 0) kept + BlockCount(OTHER, rest) else kept
    }
}
