package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UsageLedgerTest {

    private val today = 20_000L

    private fun day(epochDay: Long, seconds: Long) = DayUsage(epochDay, listOf(UsageEntry("games", seconds)))

    // --- merge ---

    @Test
    fun `folds history days and today's counters into the ledger`() {
        val out = UsageLedger.merge(
            previous = emptyMap(),
            history = listOf(day(today - 2, 1200), day(today - 1, 3600)),
            todayEpochDay = today,
            usageTodaySeconds = 600,
        )
        assertEquals(mapOf(today - 2 to 1200L, today - 1 to 3600L, today to 600L), out)
    }

    @Test
    fun `a replayed older snapshot cannot shrink a day`() {
        val previous = mapOf(today to 900L, today - 1 to 3600L)
        val out = UsageLedger.merge(previous, listOf(day(today - 1, 1000)), today, 300)
        assertEquals(3600L, out[today - 1])
        assertEquals(900L, out[today])
    }

    @Test
    fun `sums categories within a day and prunes past the retention window`() {
        val history = listOf(DayUsage(today - 1, listOf(UsageEntry("games", 100), UsageEntry("video", 50))))
        val out = UsageLedger.merge(mapOf(today - UsageLedger.KEEP_DAYS to 999L), history, today, 0)
        assertEquals(150L, out[today - 1])
        assertNull(out[today - UsageLedger.KEEP_DAYS])
    }

    // --- averageDaily ---

    @Test
    fun `averages over the last N days excluding today`() {
        val ledger = mapOf(today - 3 to 3000L, today - 2 to 0L, today - 1 to 6000L, today to 99_999L)
        val avg = UsageLedger.averageDaily(ledger, today, days = 15)!!
        assertEquals(3, avg.daysCounted)
        assertEquals(3000L, avg.seconds)
    }

    @Test
    fun `days before the ledger started are unknown, not zero`() {
        // Only 2 full days on record: the other 13 must not drag the average down.
        val ledger = mapOf(today - 2 to 3600L, today - 1 to 1800L)
        val avg = UsageLedger.averageDaily(ledger, today, days = 15)!!
        assertEquals(2, avg.daysCounted)
        assertEquals(2700L, avg.seconds)
    }

    @Test
    fun `a gap after the ledger started counts as a zero-usage day`() {
        val ledger = mapOf(today - 3 to 3000L, today - 1 to 3000L) // today-2 missing = unused
        val avg = UsageLedger.averageDaily(ledger, today, days = 15)!!
        assertEquals(3, avg.daysCounted)
        assertEquals(2000L, avg.seconds)
    }

    @Test
    fun `no full day on record yet yields null`() {
        assertNull(UsageLedger.averageDaily(emptyMap(), today))
        assertNull(UsageLedger.averageDaily(mapOf(today to 600L), today))
    }

    @Test
    fun `the window is capped at N days even with older data`() {
        val ledger = (1L..25L).associate { (today - it) to 1500L }
        val avg = UsageLedger.averageDaily(ledger, today, days = 15)!!
        assertEquals(15, avg.daysCounted)
        assertEquals(1500L, avg.seconds)
    }
}
