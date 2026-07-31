package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(5, CloseWatch.thresholdFor(Duration.ZERO))
    }
}
