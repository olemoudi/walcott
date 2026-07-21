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
        return merged.filterKeys { it > todayEpochDay - keepDays }
    }

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
