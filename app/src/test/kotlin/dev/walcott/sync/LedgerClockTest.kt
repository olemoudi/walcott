package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the parent's ledgers do with a day the child made up.
 *
 * The days these ledgers are keyed by come from the child, and a child's clock can be wrong — by
 * accident after a battery pull, or on purpose, since moving the clock is one of the two ways to
 * walk past every rule this app has (which is why the child fails closed over it). None of that
 * stops the child from publishing a snapshot stamped with the day it believes in, and the parent
 * used to file it without a second thought.
 *
 * Two different disasters came out of that, and this pins both shut.
 */
class LedgerClockTest {

    private val today = 20_000L

    private fun day(epochDay: Long, seconds: Long) =
        DayUsage(epochDay, listOf(UsageEntry("com.example.app", seconds)))

    @Test
    fun `a day in the future is never pruned, so it is never admitted`() {
        // Pruning asks how OLD a day is. A row dated 2099 is never old, so without an upper bound
        // it is a row that outlives the family — one per wrong day, on every parent's phone.
        val poisoned = UsageLedger.merge(
            previous = mapOf(today to 60L),
            history = listOf(day(today + 5_000, 999L)),
            todayEpochDay = today,
            usageTodaySeconds = 60L,
        )
        assertEquals(setOf(today), poisoned.keys)
    }

    @Test
    fun `the same bound applies to the per-app ledger`() {
        val poisoned = UsageLedger.mergeByApp(
            previous = mapOf(today to mapOf("com.example.app" to 60L)),
            history = listOf(day(today + 5_000, 999L)),
            todayEpochDay = today,
            usageToday = listOf(UsageEntry("com.example.app", 60L)),
        )
        assertEquals(setOf(today), poisoned.keys)
    }

    @Test
    fun `real days are still kept, right to the edge of the window`() {
        val kept = UsageLedger.merge(
            previous = emptyMap(),
            history = listOf(
                day(today - 1, 10L),
                day(today - UsageLedger.KEEP_DAYS + 1, 20L),
                day(today - UsageLedger.KEEP_DAYS, 30L), // just outside
            ),
            todayEpochDay = today,
            usageTodaySeconds = 5L,
        )
        assertTrue(today in kept.keys && (today - 1) in kept.keys)
        assertTrue((today - UsageLedger.KEEP_DAYS + 1) in kept.keys)
        assertTrue((today - UsageLedger.KEEP_DAYS) !in kept.keys)
    }

    @Test
    fun `a day this device cannot believe is refused as an anchor`() {
        // The worse of the two failures. The reported day is what the window is measured FROM, so
        // one snapshot claiming 2099 makes every real day older than the window: the parent's
        // month of history for that child is dropped in a single write.
        assertNull(UsageLedger.believableDay(today + 5_000, parentToday = today))
        assertNull(UsageLedger.believableDay(0, parentToday = today))
        assertNull(UsageLedger.believableDay(today - UsageLedger.KEEP_DAYS - 1, parentToday = today))
    }

    @Test
    fun `a family either side of the date line is still believed`() {
        // Not hypothetical, and the reason this is not simply "no future days": a child in
        // Auckland is a day ahead of a parent in Madrid for most of their afternoon.
        assertEquals(today + 1, UsageLedger.believableDay(today + 1, parentToday = today))
        assertEquals(today - 1, UsageLedger.believableDay(today - 1, parentToday = today))
        assertEquals(today, UsageLedger.believableDay(today, parentToday = today))
    }

    @Test
    fun `a blocked-day report from the future does not become a permanent row`() {
        val report = BlockReport(
            epochDay = today,
            netToday = 3,
            ruleToday = 1,
            recentDays = listOf(
                DayBlockTotals(epochDay = today - 1, net = 2, rule = 0),
                DayBlockTotals(epochDay = today + 900, net = 7, rule = 7),
            ),
        )
        val ledger = BlockLedger.merge(BlockLedger.Ledger(), report, todayEpochDay = today)
        assertTrue(ledger.days.keys.all { it <= today }, "no day may be filed ahead of the report")
        assertTrue((today - 1) in ledger.days.keys)
    }
}
