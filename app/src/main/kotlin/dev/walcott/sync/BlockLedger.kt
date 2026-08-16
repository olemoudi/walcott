package dev.walcott.sync

import kotlinx.serialization.Serializable

/**
 * The parent's history of what was blocked on a child's phone, and the reason it can be read
 * over "all time" without growing without end.
 *
 * A child reports today's counters and the totals of the few days before it; the history lives
 * here. The problem that shape creates is the one this file exists to solve: a family runs this
 * for years, every day adds domains and apps, and a naive per-day map would be a JSON blob that
 * grows for ever on the parent's phone — rewritten in full on every sync, until one day it is
 * the reason the app is slow and then the reason it dies.
 *
 * So the ledger is bounded on every axis, by construction rather than by hoping:
 *
 * - **A window of [KEEP_DAYS] days**, each with at most [TOP_PER_DAY] keys per breakdown plus a
 *   folded tail. That answers today, this week and this month exactly.
 * - **An archive**, which is what a day becomes when it leaves the window: counted into running
 *   totals and into key tables capped at [TOP_ALL_TIME]. Folded exactly once, when the day is
 *   dropped, so nothing is counted twice however often a snapshot is replayed.
 * - **All time = archive + window.** Its totals are exact for as long as the family has been
 *   running this; only the long tail of its per-key breakdown is approximate, which is the half
 *   nobody reads.
 *
 * The upper bound on one child's ledger is therefore a constant: 31 days × 3 breakdowns × 7
 * entries, plus 3 × 41 archive entries. It does not depend on how long the family has used the
 * app, how many apps the child installs, or how chatty the trackers are.
 *
 * Pure, so all of that is a unit test rather than a claim (see BlockLedgerTest).
 */
object BlockLedger {

    /** Days kept with their breakdown. One more than the month the UI offers, as slack. */
    const val KEEP_DAYS = 31L

    /** Keys kept per breakdown per day; the tail is folded into [BlockReports.OTHER]. */
    const val TOP_PER_DAY = 6

    /** Keys kept per breakdown in the all-time archive. */
    const val TOP_ALL_TIME = 40

    /** One day as the parent keeps it: two totals and three breakdowns. */
    @Serializable
    data class Day(
        val net: Long = 0,
        val rule: Long = 0,
        val domains: Map<String, Long> = emptyMap(),
        val netApps: Map<String, Long> = emptyMap(),
        val ruleApps: Map<String, Long> = emptyMap(),
    )

    /** Everything older than the window, added up once. [days] is how many days it stands for. */
    @Serializable
    data class Archive(
        val net: Long = 0,
        val rule: Long = 0,
        val days: Int = 0,
        val domains: Map<String, Long> = emptyMap(),
        val netApps: Map<String, Long> = emptyMap(),
        val ruleApps: Map<String, Long> = emptyMap(),
    )

    @Serializable
    data class Ledger(
        val days: Map<Long, Day> = emptyMap(),
        val archive: Archive = Archive(),
    )

    /** What a range adds up to, with the breakdowns the screen lists. */
    data class Totals(
        val net: Long = 0,
        val rule: Long = 0,
        val domains: Map<String, Long> = emptyMap(),
        val netApps: Map<String, Long> = emptyMap(),
        val ruleApps: Map<String, Long> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = net == 0L && rule == 0L
    }

    /**
     * Folds a child's report into [previous].
     *
     * Counters only grow within their day and snapshots are replayed out of order, so every
     * value is taken as a maximum rather than added — the same rule the usage ledger lives by.
     * That is what makes this safe to call on every snapshot, including the same one twice.
     */
    fun merge(previous: Ledger, report: BlockReport, todayEpochDay: Long = report.epochDay): Ledger {
        val days = previous.days.toMutableMap()
        // A day that has already left the window is closed: its counts are in the archive, and
        // re-admitting it would archive them a second time when it expires again. Replays are
        // routine (the ntfy cursor hands over a backlog), so this is the normal path, not a
        // hypothetical — and the archive is the one number that cannot be recomputed.
        val cutoff = todayEpochDay - KEEP_DAYS

        if (report.epochDay > cutoff) {
            val existing = days[report.epochDay] ?: Day()
            days[report.epochDay] = Day(
                net = maxOf(existing.net, report.netToday),
                rule = maxOf(existing.rule, report.ruleToday),
                domains = mergeKeys(existing.domains, report.domains),
                netApps = mergeKeys(existing.netApps, report.netApps),
                ruleApps = mergeKeys(existing.ruleApps, report.ruleApps),
            )
        }

        // Catch-up totals for days this parent may have missed while the device was offline.
        // Totals only: the breakdown of a day nobody was listening for is gone, and saying so
        // by leaving the maps empty beats inventing one.
        for (past in report.recentDays) {
            if (past.epochDay == report.epochDay || past.epochDay <= cutoff) continue
            val day = days[past.epochDay] ?: Day()
            days[past.epochDay] = day.copy(
                net = maxOf(day.net, past.net),
                rule = maxOf(day.rule, past.rule),
            )
        }

        return prune(Ledger(days, previous.archive), todayEpochDay)
    }

    /**
     * Drops days that have left the window, after adding them into the archive.
     *
     * The two halves are one operation on purpose: a day may only be counted into the archive at
     * the moment it stops being reachable in the window, or all-time would double-count it on
     * the next merge.
     */
    fun prune(ledger: Ledger, todayEpochDay: Long, keepDays: Long = KEEP_DAYS): Ledger {
        val cutoff = todayEpochDay - keepDays
        val expiring = ledger.days.filterKeys { it <= cutoff }
        // Days from the future (a child whose clock is ahead) are kept, not archived: they will
        // fall out of the window on their own once the parent's own calendar catches up.
        if (expiring.isEmpty()) return ledger
        var archive = ledger.archive
        for (day in expiring.values) {
            archive = Archive(
                net = archive.net + day.net,
                rule = archive.rule + day.rule,
                days = archive.days + 1,
                domains = capKeys(plus(archive.domains, day.domains), TOP_ALL_TIME),
                netApps = capKeys(plus(archive.netApps, day.netApps), TOP_ALL_TIME),
                ruleApps = capKeys(plus(archive.ruleApps, day.ruleApps), TOP_ALL_TIME),
            )
        }
        return Ledger(ledger.days - expiring.keys, archive)
    }

    /**
     * The totals over the [days] days ending today, today included — or over everything the
     * parent has ever recorded when [days] is null.
     */
    fun totals(ledger: Ledger, todayEpochDay: Long, days: Int?): Totals {
        val window = if (days == null) {
            ledger.days
        } else {
            ledger.days.filterKeys { it > todayEpochDay - days && it <= todayEpochDay }
        }
        var net = 0L
        var rule = 0L
        val domains = mutableMapOf<String, Long>()
        val netApps = mutableMapOf<String, Long>()
        val ruleApps = mutableMapOf<String, Long>()
        for (day in window.values) {
            net += day.net
            rule += day.rule
            addInto(domains, day.domains)
            addInto(netApps, day.netApps)
            addInto(ruleApps, day.ruleApps)
        }
        if (days == null) {
            net += ledger.archive.net
            rule += ledger.archive.rule
            addInto(domains, ledger.archive.domains)
            addInto(netApps, ledger.archive.netApps)
            addInto(ruleApps, ledger.archive.ruleApps)
        }
        return Totals(net, rule, domains, netApps, ruleApps)
    }

    /** How many of the last [days] the ledger has anything at all for; 0 = nothing recorded yet. */
    fun daysCovered(ledger: Ledger, todayEpochDay: Long, days: Int): Int =
        ((todayEpochDay - days + 1)..todayEpochDay).count { day ->
            ledger.days[day]?.let { it.net > 0 || it.rule > 0 } == true
        }

    /** Every child's ledger added together, for a screen about the family rather than one child. */
    fun combine(ledgers: Collection<Ledger>): Ledger {
        if (ledgers.size == 1) return ledgers.first()
        val days = mutableMapOf<Long, Day>()
        for (ledger in ledgers) {
            for ((day, value) in ledger.days) {
                val into = days[day] ?: Day()
                days[day] = Day(
                    net = into.net + value.net,
                    rule = into.rule + value.rule,
                    domains = capKeys(plus(into.domains, value.domains), TOP_PER_DAY),
                    netApps = capKeys(plus(into.netApps, value.netApps), TOP_PER_DAY),
                    ruleApps = capKeys(plus(into.ruleApps, value.ruleApps), TOP_PER_DAY),
                )
            }
        }
        val archive = ledgers.map { it.archive }.fold(Archive()) { acc, other ->
            Archive(
                net = acc.net + other.net,
                rule = acc.rule + other.rule,
                days = maxOf(acc.days, other.days),
                domains = capKeys(plus(acc.domains, other.domains), TOP_ALL_TIME),
                netApps = capKeys(plus(acc.netApps, other.netApps), TOP_ALL_TIME),
                ruleApps = capKeys(plus(acc.ruleApps, other.ruleApps), TOP_ALL_TIME),
            )
        }
        return Ledger(days, archive)
    }

    /** Max per key (a replayed report must not add), then capped to [TOP_PER_DAY]. */
    private fun mergeKeys(existing: Map<String, Long>, incoming: List<BlockCount>): Map<String, Long> {
        if (incoming.isEmpty()) return existing
        val merged = existing.toMutableMap()
        for (entry in incoming) {
            merged[entry.key] = maxOf(merged[entry.key] ?: 0L, entry.count)
        }
        return capKeys(merged, TOP_PER_DAY)
    }

    private fun plus(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        if (b.isEmpty()) return a
        val out = a.toMutableMap()
        addInto(out, b)
        return out
    }

    private fun addInto(into: MutableMap<String, Long>, from: Map<String, Long>) {
        for ((key, value) in from) into[key] = (into[key] ?: 0L) + value
    }

    /**
     * The [max] biggest keys, everything else summed into [BlockReports.OTHER]. The map's total
     * is unchanged, which is what lets a capped breakdown still agree with its own total.
     */
    private fun capKeys(map: Map<String, Long>, max: Int): Map<String, Long> {
        if (map.size <= max) return map
        val sorted = map.entries.sortedByDescending { it.value }
        val kept = sorted.take(max - 1).associateTo(mutableMapOf()) { it.key to it.value }
        val rest = sorted.drop(max - 1).sumOf { it.value }
        // Added to whatever OTHER already held rather than replacing it: a tail folded twice is
        // still one tail, and overwriting here would quietly lose the first fold's counts.
        if (rest > 0) kept[BlockReports.OTHER] = (kept[BlockReports.OTHER] ?: 0L) + rest
        return kept
    }
}
