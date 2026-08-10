package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageReportTest {

    private fun entries(vararg pairs: Pair<String, Long>) = pairs.map { UsageEntry(it.first, it.second) }

    @Test
    fun `a short list passes through, busiest first`() {
        val capped = UsageReport.cap(entries("a.b" to 10, "c.d" to 90), 40)
        assertEquals(listOf("c.d", "a.b"), capped.map { it.categoryId })
    }

    @Test
    fun `the total survives capping — that is the whole point`() {
        val many = (1..60).map { UsageEntry("pkg.$it", it.toLong()) }
        val capped = UsageReport.cap(many, 10)
        assertEquals(10, capped.size)
        // Under-reporting a child's screen time is the one error this must not make: the parent's
        // home card, the ledger and the weekly report all sum these entries.
        assertEquals(many.sumOf { it.seconds }, capped.sumOf { it.seconds })
    }

    @Test
    fun `the tail is folded into one named bucket, and the busiest are kept whole`() {
        val many = (1..60).map { UsageEntry("pkg.$it", it.toLong()) }
        val capped = UsageReport.cap(many, 10)
        assertEquals(UsageReport.OTHER, capped.last().categoryId)
        // The nine busiest are 60..52, reported individually.
        assertEquals(
            (60 downTo 52).map { "pkg.$it" },
            capped.dropLast(1).map { it.categoryId },
        )
    }

    @Test
    fun `the bucket key can never collide with an app`() {
        // Every real package name contains a dot; the sentinel deliberately does not.
        assertTrue(!UsageReport.OTHER.contains('.'))
    }

    @Test
    fun `zero-second entries are dropped rather than shipped`() {
        assertEquals(entries("a.b" to 5), UsageReport.cap(entries("a.b" to 5, "c.d" to 0), 40))
        assertTrue(UsageReport.cap(entries("a.b" to 0), 40).isEmpty())
    }

    @Test
    fun `an empty report stays empty instead of gaining an empty bucket`() {
        assertTrue(UsageReport.cap(emptyList(), 40).isEmpty())
    }

    @Test
    fun `exactly at the cap nothing is folded`() {
        val ten = (1..10).map { UsageEntry("pkg.$it", it.toLong()) }
        val capped = UsageReport.cap(ten, 10)
        assertEquals(10, capped.size)
        assertTrue(capped.none { it.categoryId == UsageReport.OTHER })
    }

    @Test
    fun `one over the cap folds the two smallest into the bucket`() {
        val eleven = (1..11).map { UsageEntry("pkg.$it", it.toLong()) }
        val capped = UsageReport.cap(eleven, 10)
        assertEquals(10, capped.size)
        assertEquals(UsageReport.OTHER, capped.last().categoryId)
        assertEquals(1L + 2L, capped.last().seconds)
        assertEquals(eleven.sumOf { it.seconds }, capped.sumOf { it.seconds })
    }
}
