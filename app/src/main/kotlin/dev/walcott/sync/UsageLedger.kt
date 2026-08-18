package dev.walcott.sync

/**
 * Pure math behind [SyncState.usageHistory], the parent-side ledger of per-child daily
 * screen time. A child snapshot only carries a 7-day window and the parent otherwise keeps
 * just the latest snapshot, so without accumulating here a "last 15 days" average could
 * never exist. Pure so it's unit-tested on the JVM.
 */
object UsageLedger {

    /** Days of history kept per child; enough for a 15-day average with margin. */
    const val KEEP_DAYS = 30L

    /**
     * The days a ledger will hold, given whose day [todayEpochDay] is.
     *
     * The upper bound is the half that was missing, and it is not hypothetical: the days come
     * from the child, a child's clock can be wrong (or moved on purpose — this app blocks apps
     * over exactly that), and a day in the future is never pruned, because pruning asks how OLD a
     * day is. One phone with its clock in 2099 left a row on the parent that nothing would ever
     * remove, and a wandering clock leaves one per wrong day.
     *
     * A day cannot be in the child's own future either, so no slack is needed here: this is the
     * child's calendar being compared against itself.
     */
    fun window(todayEpochDay: Long, keepDays: Long = KEEP_DAYS): LongRange =
        (todayEpochDay - keepDays + 1)..todayEpochDay

    /**
     * How far ahead of THIS device a child's own day may be and still be believed: one day.
     *
     * A family really can straddle the date line — a child in Auckland is a day ahead of a parent
     * in Madrid for most of their afternoon — so the check cannot be "not in the future". What it
     * is for is the other case, where the number is not a timezone but a broken clock.
     */
    const val FUTURE_SLACK_DAYS = 1L

    /**
     * The child's own day, when this device's clock can believe it — null when it cannot.
     *
     * Null is the important answer. The reported day is the ANCHOR the window is measured from,
     * so folding a snapshot that claims to be from 2099 does not merely add a bad row: every real
     * day is suddenly older than the window and the parent's accumulated history for that child is
     * dropped in one write. Refusing the snapshot's day costs one report; believing it costs a
     * month of them.
     */
    fun believableDay(reportedDay: Long, parentToday: Long, keepDays: Long = KEEP_DAYS): Long? =
        reportedDay.takeIf { it in (parentToday - keepDays)..(parentToday + FUTURE_SLACK_DAYS) }

    /** Ledger key for a snapshot: the stable childId, or deviceId for legacy devices. */
    fun keyOf(childId: String, deviceId: String): String = childId.ifBlank { deviceId }

    /**
     * Merges a snapshot into [previous]: per-day totals from its history window plus today's
     * live counters. Counters only grow within a day, but replayed snapshots arrive out of
     * order, so the max wins. Days that fell out of [keepDays] are pruned.
     */
    fun merge(
        previous: Map<Long, Long>,
        history: List<DayUsage>,
        todayEpochDay: Long,
        usageTodaySeconds: Long,
        keepDays: Long = KEEP_DAYS,
    ): Map<Long, Long> {
        val merged = previous.toMutableMap()
        for (day in history) {
            merged[day.epochDay] = maxOf(merged[day.epochDay] ?: 0, day.usage.sumOf { it.seconds })
        }
        merged[todayEpochDay] = maxOf(merged[todayEpochDay] ?: 0, usageTodaySeconds)
        return merged.filterKeys { it in window(todayEpochDay, keepDays) }
    }

    /**
     * The same merge, keeping WHICH apps the day went to rather than only how much.
     *
     * A separate ledger from [merge] rather than a replacement for it: that one holds real
     * accumulated history on every parent's phone today, and moving the averages onto a map
     * that starts empty would blank a month of them. The two agree by construction anyway —
     * a day's per-app entries sum to its total, because the child folds the tail of the list
     * into one OTHER bucket instead of truncating it (see UsageReport.cap).
     *
     * The cap is why this is worth keeping at all: a snapshot carries only the last seven days,
     * so a month of app-by-app history exists nowhere unless the parent accumulates it here.
     */
    fun mergeByApp(
        previous: Map<Long, Map<String, Long>>,
        history: List<DayUsage>,
        todayEpochDay: Long,
        usageToday: List<UsageEntry>,
        keepDays: Long = KEEP_DAYS,
    ): Map<Long, Map<String, Long>> {
        val merged = previous.toMutableMap()
        fun fold(day: Long, entries: List<UsageEntry>) {
            val existing = merged[day].orEmpty()
            // Max per app, for the same reason the totals use it: snapshots are replayed and
            // arrive out of order, and a counter only ever grows within its day.
            val byApp = existing.toMutableMap()
            for (entry in entries) {
                byApp[entry.categoryId] = maxOf(byApp[entry.categoryId] ?: 0L, entry.seconds)
            }
            merged[day] = byApp
        }
        history.forEach { fold(it.epochDay, it.usage) }
        fold(todayEpochDay, usageToday)
        return merged.filterKeys { it in window(todayEpochDay, keepDays) }
    }

    /**
     * Several members' per-app ledgers added into one, day by day.
     *
     * Written once because two screens ask the same question of the same shape of data — where
     * the family's time went, and which apps are worth putting a limit on — and a second copy of
     * this fold is a second place for the two of them to start disagreeing.
     */
    fun mergeAcross(ledgers: Collection<Map<Long, Map<String, Long>>>): Map<Long, Map<String, Long>> {
        val byDay = mutableMapOf<Long, MutableMap<String, Long>>()
        ledgers.forEach { ledger ->
            ledger.forEach { (day, byApp) ->
                val into = byDay.getOrPut(day) { mutableMapOf() }
                byApp.forEach { (pkg, seconds) -> into[pkg] = (into[pkg] ?: 0L) + seconds }
            }
        }
        return byDay
    }

    /**
     * Seconds per app over the [days] days ending today, today included — "where did the last
     * month go", which is a question about a period that is still running.
     */
    fun totalsByApp(
        ledger: Map<Long, Map<String, Long>>,
        todayEpochDay: Long,
        days: Int,
    ): Map<String, Long> {
        val totals = mutableMapOf<String, Long>()
        for (day in (todayEpochDay - days + 1)..todayEpochDay) {
            ledger[day]?.forEach { (pkg, seconds) -> totals[pkg] = (totals[pkg] ?: 0L) + seconds }
        }
        return totals
    }

    /** How many of the last [days] the ledger actually has anything for; 0 means no data yet. */
    fun daysCovered(ledger: Map<Long, Map<String, Long>>, todayEpochDay: Long, days: Int): Int =
        ((todayEpochDay - days + 1)..todayEpochDay).count { !ledger[it].isNullOrEmpty() }

    /** Mean seconds/day over [daysCounted] days (see [averageDaily] for which days count). */
    data class Average(val seconds: Long, val daysCounted: Int)

    /**
     * Mean daily screen time over the [days] days ending yesterday (today is excluded — it
     * isn't over). A day with no entry counts as zero only from the ledger's oldest day on:
     * before that the ledger simply wasn't recording, and unknown must not read as zero.
     * Null until at least one full day is on record.
     */
    fun averageDaily(ledger: Map<Long, Long>, todayEpochDay: Long, days: Int = 15): Average? {
        val oldest = ledger.keys.minOrNull() ?: return null
        val counted = ((todayEpochDay - days) until todayEpochDay).filter { it >= oldest }
        if (counted.isEmpty()) return null
        return Average(counted.sumOf { ledger[it] ?: 0L } / counted.size, counted.size)
    }
}
