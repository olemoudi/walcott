package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * What the child's own screen is allowed to say about their numbers.
 *
 * The rules being pinned here are all of the form "don't say it unless it is worth saying":
 * a line that pads itself out with something obvious is the one that teaches them to stop
 * reading the rest.
 */
class InsightsTest {

    private val instagram = "com.instagram.android"
    private val chat = "com.whatsapp"
    private val monday = LocalDate.of(2026, 8, 10).toEpochDay()

    private fun days(vararg entries: Pair<Long, Map<String, Duration>>) = entries.toMap()
    private fun mins(n: Long) = Duration.ofMinutes(n)

    @Test
    fun `a phone barely touched has nothing to say`() {
        val quiet = days(monday to mapOf(instagram to mins(3)))
        assertTrue(Insights.candidates(emptyMap(), quiet, quiet, emptyMap()).isEmpty())
        assertNull(Insights.forToday(emptyMap(), quiet, quiet, emptyMap(), rotation = 0))
    }

    @Test
    fun `the app that took the week is named with what it took`() {
        val week = days(
            monday to mapOf(instagram to mins(120), chat to mins(20)),
            monday + 1 to mapOf(instagram to mins(90)),
        )
        val top = Insights.candidates(emptyMap(), week, week, emptyMap())
            .filterIsInstance<Insight.TopAppWeek>().single()
        assertEquals(instagram, top.packageName)
        assertEquals(mins(210), top.time)
    }

    @Test
    fun `one app is only called out when it really dominates`() {
        // Half the week is what having two apps looks like, not a fact about one of them.
        val even = days(monday to mapOf(instagram to mins(60), chat to mins(59)))
        assertTrue(Insights.candidates(emptyMap(), even, even, emptyMap()).none { it is Insight.OneAppShare })
        val lopsided = days(monday to mapOf(instagram to mins(180), chat to mins(20)))
        val share = Insights.candidates(emptyMap(), lopsided, lopsided, emptyMap())
            .filterIsInstance<Insight.OneAppShare>().single()
        assertEquals(90, share.percent)
    }

    @Test
    fun `a month is measured in the largest unit you can still picture`() {
        // 16 hours: two nights of sleep beats nine films, which beats twenty-four albums.
        assertEquals(Yardstick.NIGHT_OF_SLEEP to 2, Insights.yardstickFor(Duration.ofHours(16)))
        // Three hours is no nights at all, and one film is not a comparison — so: matches.
        assertEquals(Yardstick.FOOTBALL_MATCH to 2, Insights.yardstickFor(Duration.ofHours(3)))
    }

    @Test
    fun `a stretch too small or too vast to picture gets no yardstick`() {
        // Under two albums there is nothing to compare it to…
        assertNull(Insights.yardstickFor(Duration.ofMinutes(45)))
        // …and past fifteen nights the comparison is a number again.
        assertNull(Insights.yardstickFor(Duration.ofHours(200)))
    }

    @Test
    fun `the week is only compared when there is a week to compare it to`() {
        val week = days(monday to mapOf(instagram to mins(300)))
        // A ledger that only started this week has no honest "less than last week".
        assertTrue(Insights.candidates(emptyMap(), week, week, emptyMap()).none { it is Insight.WeekDelta })
        val before = days(monday - 7 to mapOf(instagram to mins(400)))
        val delta = Insights.candidates(emptyMap(), week, week, before)
            .filterIsInstance<Insight.WeekDelta>().single()
        assertEquals(mins(100), delta.difference)
        assertTrue(delta.down)
    }

    @Test
    fun `a difference of minutes is not a difference worth reporting`() {
        val week = days(monday to mapOf(instagram to mins(300)))
        val before = days(monday - 7 to mapOf(instagram to mins(310)))
        assertTrue(Insights.candidates(emptyMap(), week, week, before).none { it is Insight.WeekDelta })
    }

    @Test
    fun `the longest day is named by its weekday`() {
        val week = days(
            monday to mapOf(instagram to mins(30)),
            monday + 5 to mapOf(instagram to mins(200)),
        )
        val busiest = Insights.candidates(emptyMap(), week, week, emptyMap())
            .filterIsInstance<Insight.BusiestDay>().single()
        assertEquals(LocalDate.ofEpochDay(monday + 5).dayOfWeek, busiest.day)
        assertEquals(mins(200), busiest.time)
    }

    @Test
    fun `the line changes with the day and holds still within it`() {
        val week = days(
            monday to mapOf(instagram to mins(600), chat to mins(30)),
            monday + 3 to mapOf(instagram to mins(400)),
        )
        val before = days(monday - 7 to mapOf(instagram to mins(200)))
        val shown = (0..6).map { Insights.forToday(emptyMap(), week, week, before, rotation = it) }
        assertTrue(shown.all { it != null })
        // Asked twice on the same day it is the same line; over a week it is not one line.
        assertEquals(shown[0], Insights.forToday(emptyMap(), week, week, before, rotation = 0))
        assertTrue(shown.distinct().size > 1, "the same sentence every day is one nobody reads")
    }

    @Test
    fun `a rotation that has wrapped or gone negative still lands on a real line`() {
        val week = days(monday to mapOf(instagram to mins(300)))
        assertNotNull(Insights.forToday(emptyMap(), week, week, emptyMap(), rotation = -37))
        assertNotNull(Insights.forToday(emptyMap(), week, week, emptyMap(), rotation = Int.MAX_VALUE))
    }
}
