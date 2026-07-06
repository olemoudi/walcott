package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SchoolCalendarTest {

    private val calendar = SchoolCalendar(
        holidays = setOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 3, 7)), // second one is a Saturday
        vacations = listOf(
            LocalDate.of(2026, 6, 22)..LocalDate.of(2026, 9, 9),
            LocalDate.of(2026, 12, 22)..LocalDate.of(2027, 1, 7),
        ),
    )

    @Test
    fun `weekday is a school day`() {
        assertEquals(DayType.SCHOOL, calendar.dayTypeOf(LocalDate.of(2026, 3, 2))) // Monday
    }

    @Test
    fun `saturday and sunday are weekend`() {
        assertEquals(DayType.WEEKEND, calendar.dayTypeOf(LocalDate.of(2026, 3, 14)))
        assertEquals(DayType.WEEKEND, calendar.dayTypeOf(LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun `one-off holiday is HOLIDAY even on a Monday`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `holiday takes precedence over weekend`() {
        // 2026-03-07 is a Saturday that is also declared a holiday.
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 3, 7)))
    }

    @Test
    fun `vacation range is HOLIDAY, endpoints included`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 6, 22)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 7, 15)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 9, 9)))
        assertEquals(DayType.SCHOOL, calendar.dayTypeOf(LocalDate.of(2026, 9, 10))) // Thursday
    }

    @Test
    fun `second vacation range crossing new year is HOLIDAY`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 12, 31)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2027, 1, 7)))
        assertEquals(DayType.SCHOOL, calendar.dayTypeOf(LocalDate.of(2027, 1, 8)))
    }
}

class TimeWindowTest {

    @Test
    fun `normal window - start inclusive, end exclusive`() {
        val school = TimeWindow(LocalTime.of(8, 30), LocalTime.of(14, 30))
        assertTrue(LocalTime.of(8, 30) in school)
        assertTrue(LocalTime.of(12, 0) in school)
        assertFalse(LocalTime.of(14, 30) in school)
        assertFalse(LocalTime.of(20, 0) in school)
    }

    @Test
    fun `window crossing midnight`() {
        val bedtime = TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30))
        assertTrue(LocalTime.of(23, 0) in bedtime)
        assertTrue(LocalTime.of(3, 0) in bedtime)
        assertTrue(LocalTime.of(21, 30) in bedtime)
        assertFalse(LocalTime.of(7, 30) in bedtime)
        assertFalse(LocalTime.of(12, 0) in bedtime)
    }
}

class RuleEngineTest {

    private val config = FamilyConfig(
        version = 1,
        assignments = mapOf(
            "com.game.fortnite" to "games",
            "org.duolingo" to "edu",
            "com.whatsapp" to "social",
        ),
        policies = mapOf(
            "games" to CategoryPolicy(
                dailyBudget = mapOf(
                    DayType.SCHOOL to Duration.ofMinutes(30),
                    DayType.WEEKEND to Duration.ofHours(2),
                ),
                blockedWindows = mapOf(
                    DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(8, 30), LocalTime.of(14, 30))),
                ),
            ),
            // "edu" has no policy: unrestricted
            "social" to CategoryPolicy(
                dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(45)),
            ),
        ),
        bedtime = mapOf(
            DayType.SCHOOL to TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30)),
        ),
        essentialPackages = setOf("com.android.dialer", "dev.walcott"),
    )

    // Monday (school) and Saturday, outside conflicting windows unless stated.
    private val schoolAfternoon = LocalDateTime.of(2026, 3, 2, 17, 0)
    private val schoolMorning = LocalDateTime.of(2026, 3, 2, 10, 0)
    private val schoolNight = LocalDateTime.of(2026, 3, 2, 22, 0)
    private val saturdayMorning = LocalDateTime.of(2026, 3, 7, 10, 0)

    @Test
    fun `essential app is always allowed, even during bedtime`() {
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "com.android.dialer", schoolNight))
    }

    @Test
    fun `bedtime blocks non-essential, even unrestricted apps`() {
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(config, "org.duolingo", schoolNight),
        )
    }

    @Test
    fun `bedtime takes precedence over the unclassified rule`() {
        // An unclassified app at night reports BEDTIME, not UNCLASSIFIED.
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(config, "com.unknown.app", schoolNight),
        )
    }

    @Test
    fun `unclassified app is blocked by default`() {
        assertEquals(
            Verdict.Blocked(BlockReason.UNCLASSIFIED),
            RuleEngine.evaluate(config, "com.random.newapp", schoolAfternoon),
        )
    }

    @Test
    fun `category without a policy is unrestricted`() {
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "org.duolingo", schoolAfternoon))
    }

    @Test
    fun `blocked window applies on a school day but not on the weekend`() {
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(config, "com.game.fortnite", schoolMorning),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofHours(2)),
            RuleEngine.evaluate(config, "com.game.fortnite", saturdayMorning),
        )
    }

    @Test
    fun `budget subtracts today's usage`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(10)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(20)), verdict)
    }

    @Test
    fun `exhausted budget blocks`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `usage exactly at budget blocks (no negative remaining)`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(45)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `approved extra time widens the budget`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
            extraTime = mapOf("games" to Duration.ofMinutes(15)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(15)), verdict)
    }

    @Test
    fun `a day without a budget entry is unlimited for that category`() {
        // "social" only defines a budget for school days.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "com.whatsapp", saturdayMorning))
    }

    @Test
    fun `one category's usage does not affect another`() {
        val verdict = RuleEngine.evaluate(
            config, "com.whatsapp", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofHours(5)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(45)), verdict)
    }
}
