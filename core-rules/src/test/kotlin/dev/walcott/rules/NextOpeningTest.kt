package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a phone whose screen is off has to wake up and give apps back.
 *
 * The enforcement loop parks with the screen off; this is what it arms an alarm for. Too late and
 * a call on a suspended app does not ring; too early only costs a wakeup, which is why midnight is
 * an acceptable answer whenever nothing opens before it.
 */
class NextOpeningTest {

    // A Wednesday (a school day).
    private val wednesday: LocalDateTime = LocalDateTime.of(2026, 3, 4, 19, 0, 37)
    private val game = "com.game"
    private val chat = "com.chat"

    private fun config(
        perApp: Map<String, AppPolicy> = emptyMap(),
        bedtime: Map<DayType, TimeWindow> = emptyMap(),
        windows: Map<DayType, List<TimeWindow>> = emptyMap(),
        today: TodayException = TodayException(),
    ) = FamilyConfig(
        version = 1,
        perAppPolicies = perApp,
        bedtime = bedtime,
        blockedWindows = windows,
        todayException = today,
    )

    @Test
    fun `nothing blocked means nothing to wake up for`() {
        assertNull(CloseWatch.nextOpening(config(), emptySet(), wednesday))
    }

    @Test
    fun `the end of a screen-free window is the minute it opens, on the minute`() {
        val homework = TimeWindow(LocalTime.of(18, 0), LocalTime.of(19, 30))
        val config = config(windows = mapOf(DayType.SCHOOL to listOf(homework)))
        assertEquals(
            LocalDateTime.of(2026, 3, 4, 19, 30),
            CloseWatch.nextOpening(config, setOf(game, chat), wednesday),
        )
    }

    @Test
    fun `a pause that runs out gives everything back when it does`() {
        val config = config(today = TodayException(pauseUntil = LocalDateTime.of(2026, 3, 4, 19, 45)))
        assertEquals(
            LocalDateTime.of(2026, 3, 4, 19, 45),
            CloseWatch.nextOpening(config, setOf(chat), wednesday),
        )
    }

    @Test
    fun `a bedtime across midnight wakes at midnight first, and at its end from there`() {
        val night = TimeWindow(LocalTime.of(21, 0), LocalTime.of(7, 0))
        val config = config(bedtime = DayType.entries.associateWith { night })
        val lateEvening = LocalDateTime.of(2026, 3, 4, 22, 10)
        val midnight = LocalDateTime.of(2026, 3, 5, 0, 0)
        assertEquals(midnight, CloseWatch.nextOpening(config, setOf(chat), lateEvening))
        assertEquals(LocalDateTime.of(2026, 3, 5, 7, 0), CloseWatch.nextOpening(config, setOf(chat), midnight))
    }

    @Test
    fun `an app out of time stays out until the day starts again`() {
        val config = config(perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))))
        assertEquals(
            LocalDateTime.of(2026, 3, 5, 0, 0),
            CloseWatch.nextOpening(config, setOf(game), wednesday, usageToday = mapOf(game to Duration.ofMinutes(60))),
        )
    }

    @Test
    fun `the first app to open decides, not the last`() {
        // The chat app's own window ends at 19:10; the game is out of time for the day.
        val config = config(
            perApp = mapOf(
                game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60))),
                chat to AppPolicy(blockedWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(18, 0), LocalTime.of(19, 10))))),
            ),
        )
        assertEquals(
            LocalDateTime.of(2026, 3, 4, 19, 10),
            CloseWatch.nextOpening(config, setOf(game, chat), wednesday, usageToday = mapOf(game to Duration.ofMinutes(60))),
        )
    }

    @Test
    fun `a window ending across a daylight-saving jump is still found`() {
        // Europe moves 02:00 → 03:00 on the last Sunday of March; the walk is in local time, and the
        // caller turns the answer into an instant.
        val sunday = LocalDateTime.of(2026, 3, 29, 1, 30)
        val window = TimeWindow(LocalTime.of(1, 0), LocalTime.of(3, 15))
        val config = config(windows = DayType.entries.associateWith { listOf(window) })
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 15), CloseWatch.nextOpening(config, setOf(chat), sunday))
    }
}
