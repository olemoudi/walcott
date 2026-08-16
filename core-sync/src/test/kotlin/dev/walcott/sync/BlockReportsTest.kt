package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockReportsTest {

    private fun counts(vararg pairs: Pair<String, Long>) = pairs.map { BlockCount(it.first, it.second) }

    @Test
    fun `a short list is returned biggest first and untouched`() {
        val out = BlockReports.cap(counts("a" to 1, "b" to 9), max = 5)
        assertEquals(listOf("b", "a"), out.map { it.key })
    }

    @Test
    fun `the tail is folded rather than dropped, so the sum survives`() {
        val entries = (1..20).map { BlockCount("d$it", it.toLong()) }
        val out = BlockReports.cap(entries, max = 5)
        assertEquals(5, out.size)
        assertEquals(entries.sumOf { it.count }, out.sumOf { it.count })
        assertEquals(BlockReports.OTHER, out.last().key)
    }

    @Test
    fun `zero counts cost bytes to say nothing`() {
        val out = BlockReports.cap(counts("a" to 0, "b" to 3), max = 5)
        assertEquals(listOf("b"), out.map { it.key })
        assertTrue(BlockReports.cap(counts("a" to 0), max = 5).isEmpty())
    }

    @Test
    fun `a report with nothing in it knows it is empty`() {
        assertTrue(BlockReport().isEmpty())
        assertTrue(BlockReport(epochDay = 5, recentDays = listOf(DayBlockTotals(4))).isEmpty())
        assertFalse(BlockReport(netToday = 1).isEmpty())
        assertFalse(BlockReport(ruleToday = 1).isEmpty())
        assertFalse(BlockReport(recentDays = listOf(DayBlockTotals(4, net = 2))).isEmpty())
    }

    @Test
    fun `totals-only keeps every number and drops every breakdown`() {
        val full = BlockReport(
            epochDay = 10,
            netToday = 40,
            ruleToday = 3,
            domains = counts("a" to 40),
            netApps = counts("p" to 40),
            ruleApps = counts("q" to 3),
            recentDays = listOf(DayBlockTotals(9, net = 7, rule = 1)),
        )
        val thin = full.totalsOnly()
        assertEquals(40, thin.netToday)
        assertEquals(3, thin.ruleToday)
        assertEquals(full.recentDays, thin.recentDays)
        assertTrue(thin.domains.isEmpty() && thin.netApps.isEmpty() && thin.ruleApps.isEmpty())
    }
}
