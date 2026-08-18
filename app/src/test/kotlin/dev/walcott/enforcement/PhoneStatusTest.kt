package dev.walcott.enforcement

import dev.walcott.rules.DayType
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.TimeWindow
import dev.walcott.rules.TodayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The one sentence on the child's permanent notification.
 *
 * It is the only text this app shows without being opened, so what it picks matters more than
 * where it is drawn: a phone that is shut has to say when it opens, and one that is being used
 * has to say how long is left — and it must never promise a wall for an app it cannot block.
 */
class PhoneStatusTest {

    private val game = "com.game"
    private val browser = "com.browser"
    private val managed = setOf(game)

    /** A Monday, mid-afternoon. */
    private val monday = LocalDateTime.of(2026, 3, 2, 16, 0)

    private fun config(
        default: Duration? = Duration.ofHours(1),
        bedtime: TimeWindow? = null,
        screenFree: List<TimeWindow> = emptyList(),
        exception: TodayException = TodayException(),
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = default?.let { d -> DayType.entries.associateWith { d } }.orEmpty(),
        bedtime = bedtime?.let { w -> DayType.entries.associateWith { w } }.orEmpty(),
        blockedWindows = if (screenFree.isEmpty()) emptyMap() else DayType.entries.associateWith { screenFree },
        todayException = exception,
    )

    @Test
    fun `an app in use reports what is left of it`() {
        val status = StatusLine.of(
            config(), game, managed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(48)),
        )
        assertEquals(PhoneStatus.AppRemaining(game, Duration.ofMinutes(12)), status)
    }

    @Test
    fun `extra time is inside the number, like everywhere else`() {
        val status = StatusLine.of(
            config(), game, managed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(60)),
            extraTime = mapOf(dev.walcott.rules.ExtraTime.ALL_APPS to Duration.ofMinutes(20)),
        )
        assertEquals(PhoneStatus.AppRemaining(game, Duration.ofMinutes(20)), status)
    }

    @Test
    fun `an app this phone cannot block is not promised a wall`() {
        // Screen time is counted for more apps than can be suspended (system browsers, galleries).
        // "22m left" over one of them is a countdown to nothing.
        val status = StatusLine.of(
            config(), browser, managed, monday,
            usageToday = mapOf(browser to Duration.ofMinutes(38)),
        )
        assertEquals(PhoneStatus.Quiet, status)
    }

    @Test
    fun `an app with no limit today has nothing to say`() {
        assertEquals(PhoneStatus.Quiet, StatusLine.of(config(default = null), game, managed, monday))
    }

    @Test
    fun `a screen that is off says nothing`() {
        assertEquals(PhoneStatus.Quiet, StatusLine.of(config(), null, managed, monday))
    }

    @Test
    fun `a closed phone says when it opens, whatever is in the foreground`() {
        val bedtime = config(bedtime = TimeWindow(LocalTime.of(15, 0), LocalTime.of(7, 30)))
        assertEquals(PhoneStatus.Bedtime(LocalTime.of(7, 30)), StatusLine.of(bedtime, game, managed, monday))

        val screenFree = config(screenFree = listOf(TimeWindow(LocalTime.of(15, 30), LocalTime.of(17, 0))))
        assertEquals(
            PhoneStatus.ScreenFree(LocalTime.of(17, 0)),
            StatusLine.of(screenFree, game, managed, monday),
        )
    }

    @Test
    fun `a pause outranks the rules it is suspending`() {
        val paused = config(
            bedtime = TimeWindow(LocalTime.of(15, 0), LocalTime.of(7, 30)),
            exception = TodayException(pauseUntil = monday.plusMinutes(20)),
        )
        assertEquals(PhoneStatus.Paused(LocalTime.of(16, 20)), StatusLine.of(paused, game, managed, monday))
    }

    @Test
    fun `tonight's moved bedtime is the hour it reports`() {
        val cfg = config(
            bedtime = TimeWindow(LocalTime.of(15, 0), LocalTime.of(7, 30)),
            exception = TodayException(
                bedtimeNight = LocalDate.of(2026, 3, 2),
                bedtimeDelayMinutes = 120,
            ),
        )
        // 16:00 is inside the configured bedtime and outside tonight's, so the phone is open and
        // the line goes back to being about the app in use.
        assertEquals(
            PhoneStatus.AppRemaining(game, Duration.ofHours(1)),
            StatusLine.of(cfg, game, managed, monday),
        )
    }

    @Test
    fun `a phone that cannot trust its rules says so before anything else`() {
        val status = StatusLine.of(config(), game, managed, monday, failClosed = true)
        assertEquals(PhoneStatus.FailClosed, status)
    }
}
