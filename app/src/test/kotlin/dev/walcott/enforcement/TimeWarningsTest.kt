package dev.walcott.enforcement

import dev.walcott.rules.BlockReason
import dev.walcott.rules.ClosingSoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * How often the child hears about the same closing. The enforcement loop asks several times a
 * minute, so everything here is really one question: what makes a countdown a NEW one.
 */
class TimeWarningsTest {

    private val nowMinute = 29_000_000L

    private fun budget(minutes: Long) =
        ClosingSoon(BlockReason.BUDGET_EXHAUSTED, "com.game", Duration.ofMinutes(minutes))

    private fun bedtime(minutes: Long) =
        ClosingSoon(BlockReason.BEDTIME, "", Duration.ofMinutes(minutes))

    @Test
    fun `each threshold is announced once, however often it is asked`() {
        val warnings = TimeWarnings()
        assertEquals(30, warnings.due(budget(28), nowMinute))
        repeat(20) { assertNull(warnings.due(budget(28), nowMinute)) }
        // Still the 30-minute warning's territory further down the countdown.
        assertNull(warnings.due(budget(9), nowMinute + 19))
        assertEquals(5, warnings.due(budget(4), nowMinute + 24))
        assertNull(warnings.due(budget(1), nowMinute + 27))
    }

    @Test
    fun `nothing closing, nothing said`() {
        assertNull(TimeWarnings().due(null, nowMinute))
        // Beyond the horizon there is no threshold to announce.
        assertNull(TimeWarnings().due(budget(45), nowMinute))
    }

    @Test
    fun `extra time is a new countdown and earns new warnings`() {
        val warnings = TimeWarnings()
        assertEquals(5, warnings.due(budget(3), nowMinute))
        // The parent grants 20 minutes: the same app, a deadline that moved on its own.
        assertEquals(30, warnings.due(budget(23), nowMinute + 1))
        assertEquals(5, warnings.due(budget(4), nowMinute + 20))
    }

    @Test
    fun `putting an app down for a moment does not repeat the warning`() {
        // Its minutes only run while it is in use, so a pause leaves the deadline where it was.
        val warnings = TimeWarnings()
        assertEquals(30, warnings.due(budget(20), nowMinute))
        assertNull(warnings.due(budget(20), nowMinute + 15))
    }

    @Test
    fun `tomorrow's bedtime is announced again`() {
        val warnings = TimeWarnings()
        assertEquals(30, warnings.due(bedtime(30), nowMinute))
        assertEquals(5, warnings.due(bedtime(5), nowMinute + 25))
        // A day later, the same window, a wall-clock deadline 24 hours further on.
        val tomorrow = nowMinute + 24 * 60
        assertEquals(30, warnings.due(bedtime(30), tomorrow))
    }

    @Test
    fun `a delayed sample of the same bedtime is not a second bedtime`() {
        // The loop samples: the same deadline read a minute later is the same deadline.
        val warnings = TimeWarnings()
        assertEquals(30, warnings.due(bedtime(28), nowMinute))
        assertNull(warnings.due(bedtime(27), nowMinute + 1))
        assertNull(warnings.due(bedtime(25), nowMinute + 3))
    }

    @Test
    fun `an app and the bedtime that follows it are separate warnings`() {
        val warnings = TimeWarnings()
        assertEquals(5, warnings.due(budget(4), nowMinute))
        assertEquals(30, warnings.due(bedtime(28), nowMinute))
    }
}
