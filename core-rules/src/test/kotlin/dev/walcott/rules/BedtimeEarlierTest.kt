package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tonight's bedtime moved EARLIER (a negative [TodayException.bedtimeDelayMinutes]).
 *
 * The half this could not say. "Half an hour more, it's a Friday" was one field; "bed half an
 * hour early, you were up all night" meant editing the standing rule and remembering to put it
 * back — which is the exact thing every one-off exception here exists to avoid.
 */
class BedtimeEarlierTest {

    private val night = LocalDate.of(2026, 3, 2)
    private val evening = LocalDateTime.of(2026, 3, 2, 19, 0)
    private val window = TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30))

    private fun config(delayMinutes: Int) = FamilyConfig(
        version = 1,
        bedtime = mapOf(DayType.SCHOOL to window),
        todayException = TodayException(bedtimeNight = night, bedtimeDelayMinutes = delayMinutes),
    )

    @Test
    fun `a negative delay brings tonight's bedtime forward`() {
        assertEquals(LocalTime.of(20, 30), config(-60).bedtimeAt(evening)?.start)
        // And the other end of the night is untouched: it is a bedtime that starts earlier, not
        // a longer morning.
        assertEquals(LocalTime.of(7, 30), config(-60).bedtimeAt(evening)?.end)
    }

    @Test
    fun `the phone is actually shut at the earlier hour`() {
        // The point of the whole thing, asked of the engine rather than of the window.
        val at2045 = LocalDateTime.of(2026, 3, 2, 20, 45)
        assertNull(FamilyConfig(version = 1, bedtime = mapOf(DayType.SCHOOL to window)).let {
            RuleEngine.deviceWideBlock(it, at2045)
        })
        assertEquals(BlockReason.BEDTIME, RuleEngine.deviceWideBlock(config(-60), at2045))
    }

    @Test
    fun `zero puts tonight back exactly as the rules have it`() {
        assertEquals(window, config(0).bedtimeAt(evening))
    }

    @Test
    fun `an exception dated to another night changes nothing`() {
        val config = FamilyConfig(
            version = 1,
            bedtime = mapOf(DayType.SCHOOL to window),
            todayException = TodayException(
                bedtimeNight = night.minusDays(1),
                bedtimeDelayMinutes = -60,
            ),
        )
        assertEquals(window, config.bedtimeAt(evening))
    }

    @Test
    fun `a delay longer than the night still leaves no bedtime, and only forwards`() {
        // The old guard, unchanged: a LATER bedtime can crawl past its own end and must become
        // no bedtime rather than a window that blocks the whole of the next day. Moving the
        // start backwards only ever lengthens the night, so it has no such edge.
        assertNull(config(delayMinutes = (10 * 60) + 1).bedtimeAt(evening))
        assertEquals(LocalTime.of(11, 30), config(-600).bedtimeAt(evening)?.start)
    }
}
