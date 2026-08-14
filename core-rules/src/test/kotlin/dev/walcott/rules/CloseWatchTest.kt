package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the child is told is coming. The warning has to agree with the block it warns about,
 * which is why this asks the engine rather than reading the windows itself.
 */
class CloseWatchTest {

    // A Wednesday (a school day), well away from any edge.
    private val wednesday: LocalDateTime = LocalDateTime.of(2026, 3, 4, 19, 0)
    private val game = "com.game"

    private fun config(
        defaultBudget: Map<DayType, Duration> = emptyMap(),
        perApp: Map<String, AppPolicy> = emptyMap(),
        bedtime: Map<DayType, TimeWindow> = emptyMap(),
        windows: Map<DayType, List<TimeWindow>> = emptyMap(),
        essentials: Set<String> = emptySet(),
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = defaultBudget,
        perAppPolicies = perApp,
        bedtime = bedtime,
        blockedWindows = windows,
        essentialPackages = essentials,
    )

    @Test
    fun `an app with time left and no schedule has nothing coming`() {
        val config = config(perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofHours(2)))))
        assertNull(CloseWatch.nextClose(config, game, wednesday))
    }

    @Test
    fun `its own time running out is the close, and it is what is left of it`() {
        val config = config(perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))))
        val closing = CloseWatch.nextClose(config, game, wednesday, usageToday = mapOf(game to Duration.ofMinutes(53)))
        assertEquals(BlockReason.BUDGET_EXHAUSTED, closing?.reason)
        assertEquals(Duration.ofMinutes(7), closing?.left)
        assertEquals(game, closing?.packageName)
    }

    @Test
    fun `an app that ran out already is not warned about`() {
        // It is blocked, the child can see it, and "0 minutes left" is not news.
        val config = config(perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))))
        assertNull(CloseWatch.nextClose(config, game, wednesday, usageToday = mapOf(game to Duration.ofMinutes(60))))
    }

    @Test
    fun `extra time pushes the close back and can take it out of sight`() {
        val config = config(perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))))
        val used = mapOf(game to Duration.ofMinutes(53))
        assertNull(
            CloseWatch.nextClose(config, game, wednesday, used, extraTime = mapOf(game to Duration.ofHours(1))),
        )
    }

    @Test
    fun `bedtime is seen coming, to the minute`() {
        val config = config(bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(19, 20), LocalTime.of(7, 0))))
        val closing = CloseWatch.nextClose(config, game, wednesday)
        assertEquals(BlockReason.BEDTIME, closing?.reason)
        assertEquals(Duration.ofMinutes(20), closing?.left)
    }

    @Test
    fun `bedtime further off than the horizon is not yet news`() {
        val config = config(bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 0))))
        assertNull(CloseWatch.nextClose(config, game, wednesday))
    }

    @Test
    fun `whichever comes first is the one reported`() {
        val config = config(
            perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))),
            bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(19, 25), LocalTime.of(7, 0))),
        )
        // 15 minutes of app time left, bedtime in 25: the app closes first.
        val soonest = CloseWatch.nextClose(config, game, wednesday, mapOf(game to Duration.ofMinutes(45)))
        assertEquals(BlockReason.BUDGET_EXHAUSTED, soonest?.reason)
        assertEquals(Duration.ofMinutes(15), soonest?.left)
    }

    @Test
    fun `a screen-free window that does not run today is not announced`() {
        // Same clock, but the window is Mondays only — the child hears nothing on Wednesday.
        val monday = TimeWindow(LocalTime.of(19, 10), LocalTime.of(20, 0), days = setOf(DayOfWeek.MONDAY))
        val config = config(windows = mapOf(DayType.SCHOOL to listOf(monday)))
        assertNull(CloseWatch.nextClose(config, game, wednesday))
        assertNull(CloseWatch.nextDeviceWideClose(config, wednesday))
    }

    @Test
    fun `a screen-free window that does run today is announced as one`() {
        val soon = TimeWindow(LocalTime.of(19, 10), LocalTime.of(20, 0), days = setOf(DayOfWeek.WEDNESDAY))
        val config = config(windows = mapOf(DayType.SCHOOL to listOf(soon)))
        val closing = CloseWatch.nextDeviceWideClose(config, wednesday)
        assertEquals(BlockReason.BLOCKED_WINDOW, closing?.reason)
        assertEquals(Duration.ofMinutes(10), closing?.left)
        // Device-wide: not about any one app, so nothing to name.
        assertEquals("", closing?.packageName)
    }

    @Test
    fun `nothing is announced from inside a window that is already running`() {
        val running = TimeWindow(LocalTime.of(18, 0), LocalTime.of(20, 0))
        val config = config(windows = mapOf(DayType.SCHOOL to listOf(running)))
        assertNull(CloseWatch.nextDeviceWideClose(config, wednesday))
    }

    @Test
    fun `the phone is never warned about — it is never taken away`() {
        val config = config(
            defaultBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(20)),
            bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(19, 5), LocalTime.of(7, 0))),
            essentials = setOf("com.dialer"),
        )
        assertNull(CloseWatch.nextClose(config, "com.dialer", wednesday))
    }

    @Test
    fun `the warning to give is the smallest threshold reached`() {
        assertNull(CloseWatch.thresholdFor(Duration.ofMinutes(31)))
        assertEquals(30, CloseWatch.thresholdFor(Duration.ofMinutes(30)))
        assertEquals(30, CloseWatch.thresholdFor(Duration.ofMinutes(6)))
        assertEquals(5, CloseWatch.thresholdFor(Duration.ofMinutes(5)))
        assertEquals(5, CloseWatch.thresholdFor(Duration.ofMinutes(2)))
        assertEquals(1, CloseWatch.thresholdFor(Duration.ofMinutes(1)))
        assertEquals(1, CloseWatch.thresholdFor(Duration.ZERO))
    }

    @Test
    fun `the last minute is announced even after the five-minute one was`() {
        // The rungs are only useful if each earns its own warning: TimeWarnings keeps the
        // smallest already said, so a descending countdown must keep clearing that bar.
        val warnings = dev.walcott.rules.CloseWatch.WARN_MINUTES
        assertEquals(listOf(30, 5, 1), warnings)
        val ladder = listOf(30, 5, 1).map { CloseWatch.thresholdFor(Duration.ofMinutes(it.toLong())) }
        assertEquals(listOf(30, 5, 1), ladder)
    }

    @Test
    fun `an app is shown as running low before it closes, not after`() {
        // The reported dead zone: the card read "1m left" and offered nothing to act on.
        assertTrue(CloseWatch.runningLow(Duration.ofMinutes(1), blocked = false))
        assertTrue(CloseWatch.runningLow(Duration.ofSeconds(90), blocked = false))
        assertTrue(CloseWatch.runningLow(Duration.ofMinutes(10), blocked = false))
        // Blocked is the clearest yes, and carries no remaining at all.
        assertTrue(CloseWatch.runningLow(null, blocked = true))
        // With real time left it stays off the home: every limited app on one screen was the
        // list the child had to scroll past to reach anything they came to do.
        assertFalse(CloseWatch.runningLow(Duration.ofMinutes(11), blocked = false))
        assertFalse(CloseWatch.runningLow(Duration.ofHours(2), blocked = false))
        // An app with no limit at all has nothing to ask about.
        assertFalse(CloseWatch.runningLow(null, blocked = false))
    }

    @Test
    fun `the card is already on screen by the time the banner fires`() {
        // The banner's loudest rungs must never announce something the home is not showing,
        // or the child is told to act on a card that isn't there.
        CloseWatch.WARN_MINUTES.filter { it <= CloseWatch.RUNNING_LOW_BELOW.toMinutes() }
            .forEach { rung ->
                assertTrue(
                    CloseWatch.runningLow(Duration.ofMinutes(rung.toLong()), blocked = false),
                    "the $rung-minute warning fires on an app the home would hide",
                )
            }
    }

    @Test
    fun `opening an app only announces once it is inside the warning horizon`() {
        // "9h 54m left" is not news. A banner that fires on every opening is one a child learns
        // to look past — including on the openings where it mattered.
        assertTrue(CloseWatch.worthAnnouncingOnOpen(Duration.ofMinutes(30)))
        assertTrue(CloseWatch.worthAnnouncingOnOpen(Duration.ofMinutes(12)))
        assertTrue(CloseWatch.worthAnnouncingOnOpen(Duration.ofSeconds(30)))
        assertFalse(CloseWatch.worthAnnouncingOnOpen(Duration.ofMinutes(31)))
        assertFalse(CloseWatch.worthAnnouncingOnOpen(Duration.ofHours(9)))
        // No limit at all: nothing to report.
        assertFalse(CloseWatch.worthAnnouncingOnOpen(null))
    }

    @Test
    fun `everything Walcott says about time engages at the same threshold`() {
        // One rule for the child to meet, not several: the horizon the timed warnings watch and
        // the one the opening banner uses are the same number by construction.
        assertEquals(CloseWatch.WARN_MINUTES.max().toLong(), CloseWatch.WARN_FROM.toMinutes())
        assertEquals(30, CloseWatch.thresholdFor(CloseWatch.WARN_FROM))
        assertTrue(CloseWatch.worthAnnouncingOnOpen(CloseWatch.WARN_FROM))
    }

    @Test
    fun `the horizon still reaches the largest threshold`() {
        // Adding a smaller rung must not shrink how far ahead nextClose looks: the 30-minute
        // warning is only reachable while the horizon covers it.
        assertEquals(30, CloseWatch.WARN_MINUTES.max())
    }
}
