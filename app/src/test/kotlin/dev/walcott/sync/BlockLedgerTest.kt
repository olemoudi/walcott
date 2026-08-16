package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockLedgerTest {

    private fun report(
        day: Long,
        net: Long = 0,
        rule: Long = 0,
        domains: List<Pair<String, Long>> = emptyList(),
        netApps: List<Pair<String, Long>> = emptyList(),
        ruleApps: List<Pair<String, Long>> = emptyList(),
        recent: List<DayBlockTotals> = emptyList(),
    ) = BlockReport(
        epochDay = day,
        netToday = net,
        ruleToday = rule,
        domains = domains.map { BlockCount(it.first, it.second) },
        netApps = netApps.map { BlockCount(it.first, it.second) },
        ruleApps = ruleApps.map { BlockCount(it.first, it.second) },
        recentDays = recent,
    )

    // --- merging ---

    @Test
    fun `a report becomes the day it describes`() {
        val ledger = BlockLedger.merge(
            BlockLedger.Ledger(),
            report(100, net = 12, rule = 2, domains = listOf("ads.com" to 12), ruleApps = listOf("com.game" to 2)),
        )
        val totals = BlockLedger.totals(ledger, 100, days = 1)
        assertEquals(12, totals.net)
        assertEquals(2, totals.rule)
        assertEquals(12, totals.domains["ads.com"])
        assertEquals(2, totals.ruleApps["com.game"])
    }

    @Test
    fun `the same report twice counts once`() {
        // Snapshots are replayed by design (the ntfy cursor replays a backlog), so a merge that
        // added would inflate every number the parent reads.
        val one = report(100, net = 12, domains = listOf("ads.com" to 12))
        var ledger = BlockLedger.merge(BlockLedger.Ledger(), one)
        ledger = BlockLedger.merge(ledger, one)
        ledger = BlockLedger.merge(ledger, one)
        assertEquals(12, BlockLedger.totals(ledger, 100, days = 1).net)
    }

    @Test
    fun `a day only ever grows within itself`() {
        var ledger = BlockLedger.merge(BlockLedger.Ledger(), report(100, net = 30))
        // An older snapshot of the same day arriving late must not roll the counter back.
        ledger = BlockLedger.merge(ledger, report(100, net = 5))
        assertEquals(30, BlockLedger.totals(ledger, 100, days = 1).net)
    }

    @Test
    fun `catch-up days fill the totals of days nobody was listening for`() {
        val ledger = BlockLedger.merge(
            BlockLedger.Ledger(),
            report(
                100, net = 4,
                recent = listOf(DayBlockTotals(98, net = 10, rule = 1), DayBlockTotals(99, net = 20, rule = 2)),
            ),
        )
        val week = BlockLedger.totals(ledger, 100, days = 7)
        assertEquals(34, week.net)
        assertEquals(3, week.rule)
    }

    // --- ranges ---

    @Test
    fun `each range covers exactly its own days`() {
        var ledger = BlockLedger.Ledger()
        for (day in 71L..100L) ledger = BlockLedger.merge(ledger, report(day, net = 1), todayEpochDay = 100)
        assertEquals(1, BlockLedger.totals(ledger, 100, days = 1).net)
        assertEquals(7, BlockLedger.totals(ledger, 100, days = 7).net)
        assertEquals(30, BlockLedger.totals(ledger, 100, days = 30).net)
        assertEquals(30, BlockLedger.totals(ledger, 100, days = null).net)
    }

    @Test
    fun `all time keeps counting after a day has left the window`() {
        var ledger = BlockLedger.Ledger()
        for (day in 1L..60L) ledger = BlockLedger.merge(ledger, report(day, net = 2, rule = 1), todayEpochDay = day)
        val all = BlockLedger.totals(ledger, 60, days = null)
        assertEquals(120, all.net)
        assertEquals(60, all.rule)
        // ...and the month is still only the month.
        assertEquals(60, BlockLedger.totals(ledger, 60, days = 30).net)
    }

    @Test
    fun `a day is archived exactly once, however many times it is merged`() {
        var ledger = BlockLedger.Ledger()
        for (day in 1L..40L) ledger = BlockLedger.merge(ledger, report(day, net = 1), todayEpochDay = day)
        val before = BlockLedger.totals(ledger, 40, days = null).net
        // Replaying an old day that has already been archived must not double it.
        ledger = BlockLedger.merge(ledger, report(1, net = 1), todayEpochDay = 40)
        assertEquals(before, BlockLedger.totals(ledger, 40, days = null).net)
    }

    // --- the bound ---

    @Test
    fun `five years of daily reports stay a constant size`() {
        // This is the requirement, as a test: a family runs this for years, every day brings new
        // domains, and the parent's stored ledger must not grow with either.
        var ledger = BlockLedger.Ledger()
        var expected = 0L
        for (day in 1L..(365L * 5)) {
            val domains = (1..200).map { "d$day-$it" to 1L }
            val apps = (1..40).map { "app$day-$it" to 1L }
            ledger = BlockLedger.merge(
                ledger,
                report(day, net = 200, rule = 40, domains = domains, netApps = apps, ruleApps = apps),
                todayEpochDay = day,
            )
            expected += 200
        }
        val lastDay = 365L * 5

        // Bounded by construction, not by luck.
        assertTrue(
            ledger.days.size <= BlockLedger.KEEP_DAYS + 1,
            "kept ${ledger.days.size} days",
        )
        for ((day, value) in ledger.days) {
            assertTrue(value.domains.size <= BlockLedger.TOP_PER_DAY, "day $day kept ${value.domains.size} domains")
            assertTrue(value.netApps.size <= BlockLedger.TOP_PER_DAY, "day $day kept ${value.netApps.size} apps")
            assertTrue(value.ruleApps.size <= BlockLedger.TOP_PER_DAY, "day $day kept ${value.ruleApps.size} apps")
        }
        assertTrue(ledger.archive.domains.size <= BlockLedger.TOP_ALL_TIME, "archive grew to ${ledger.archive.domains.size}")
        assertTrue(ledger.archive.netApps.size <= BlockLedger.TOP_ALL_TIME)
        assertTrue(ledger.archive.ruleApps.size <= BlockLedger.TOP_ALL_TIME)

        // And the number a parent reads is still exact after five years.
        assertEquals(expected, BlockLedger.totals(ledger, lastDay, days = null).net)
    }

    @Test
    fun `a capped breakdown still adds up to its own total`() {
        // The tail is folded into OTHER rather than dropped, so a chart drawn from the
        // breakdown cannot disagree with the headline number above it.
        val domains = (1..50).map { "d$it" to it.toLong() }
        val ledger = BlockLedger.merge(
            BlockLedger.Ledger(),
            report(10, net = domains.sumOf { it.second }, domains = domains),
        )
        val totals = BlockLedger.totals(ledger, 10, days = 1)
        assertEquals(totals.net, totals.domains.values.sum())
        assertTrue(totals.domains.size <= BlockLedger.TOP_PER_DAY)
    }

    // --- family view ---

    @Test
    fun `combining children adds their days and their archives`() {
        val a = BlockLedger.merge(BlockLedger.Ledger(), report(10, net = 3, domains = listOf("x.com" to 3)))
        val b = BlockLedger.merge(BlockLedger.Ledger(), report(10, net = 5, domains = listOf("x.com" to 5)))
        val totals = BlockLedger.totals(BlockLedger.combine(listOf(a, b)), 10, days = 1)
        assertEquals(8, totals.net)
        assertEquals(8, totals.domains["x.com"])
    }

    @Test
    fun `days covered counts only days that recorded something`() {
        var ledger = BlockLedger.merge(BlockLedger.Ledger(), report(100, net = 1), todayEpochDay = 100)
        ledger = BlockLedger.merge(ledger, report(98, net = 1), todayEpochDay = 100)
        assertEquals(2, BlockLedger.daysCovered(ledger, 100, days = 7))
        assertEquals(1, BlockLedger.daysCovered(ledger, 100, days = 1))
    }

    @Test
    fun `an empty ledger answers zero rather than throwing`() {
        val totals = BlockLedger.totals(BlockLedger.Ledger(), 100, days = 30)
        assertTrue(totals.isEmpty)
        assertEquals(0, BlockLedger.daysCovered(BlockLedger.Ledger(), 100, days = 7))
    }
}
