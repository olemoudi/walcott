package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
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

    /** Day type at noon on [date] — the hour only matters for the weekend-edge tests below. */
    private fun SchoolCalendar.dayTypeOn(date: LocalDate) = dayTypeOf(date.atTime(12, 0))

    @Test
    fun `weekday is a school day`() {
        assertEquals(DayType.SCHOOL, calendar.dayTypeOn(LocalDate.of(2026, 3, 2))) // Monday
    }

    @Test
    fun `saturday and sunday are weekend`() {
        assertEquals(DayType.WEEKEND, calendar.dayTypeOn(LocalDate.of(2026, 3, 14)))
        assertEquals(DayType.WEEKEND, calendar.dayTypeOn(LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun `one-off holiday is HOLIDAY even on a Monday`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `holiday takes precedence over weekend`() {
        // 2026-03-07 is a Saturday that is also declared a holiday.
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2026, 3, 7)))
    }

    @Test
    fun `vacation range is HOLIDAY, endpoints included`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2026, 6, 22)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2026, 7, 15)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2026, 9, 9)))
        assertEquals(DayType.SCHOOL, calendar.dayTypeOn(LocalDate.of(2026, 9, 10))) // Thursday
    }

    @Test
    fun `second vacation range crossing new year is HOLIDAY`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2026, 12, 31)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOn(LocalDate.of(2027, 1, 7)))
        assertEquals(DayType.SCHOOL, calendar.dayTypeOn(LocalDate.of(2027, 1, 8)))
    }

    @Test
    fun `with no edges set the whole Friday is school and the whole Sunday is weekend`() {
        val friday = LocalDate.of(2026, 3, 13)
        val sunday = LocalDate.of(2026, 3, 15)
        assertEquals(DayType.SCHOOL, calendar.dayTypeOf(friday.atTime(23, 59)))
        assertEquals(DayType.WEEKEND, calendar.dayTypeOf(sunday.atTime(23, 59)))
    }

    @Test
    fun `weekend starting Friday at 14 flips that afternoon and nothing else`() {
        val early = calendar.copy(weekendStartsFriday = LocalTime.of(14, 0))
        val friday = LocalDate.of(2026, 3, 13)
        assertEquals(DayType.SCHOOL, early.dayTypeOf(friday.atTime(13, 59)))
        assertEquals(DayType.WEEKEND, early.dayTypeOf(friday.atTime(14, 0))) // the edge itself is weekend
        assertEquals(DayType.WEEKEND, early.dayTypeOf(friday.atTime(21, 0)))
        // Thursday is untouched, and so is the following Monday.
        assertEquals(DayType.SCHOOL, early.dayTypeOf(friday.minusDays(1).atTime(20, 0)))
        assertEquals(DayType.SCHOOL, early.dayTypeOf(friday.plusDays(3).atTime(9, 0)))
    }

    @Test
    fun `weekend ending Sunday at 20 gives the school rules back that evening`() {
        val short = calendar.copy(weekendEndsSunday = LocalTime.of(20, 0))
        val sunday = LocalDate.of(2026, 3, 15)
        assertEquals(DayType.WEEKEND, short.dayTypeOf(sunday.atTime(19, 59)))
        assertEquals(DayType.SCHOOL, short.dayTypeOf(sunday.atTime(20, 0)))
        assertEquals(DayType.WEEKEND, short.dayTypeOf(sunday.minusDays(1).atTime(22, 0))) // Saturday night
    }

    @Test
    fun `a special day beats both edges - it is a holiday all day long`() {
        val edged = calendar.copy(
            weekendStartsFriday = LocalTime.of(14, 0),
            weekendEndsSunday = LocalTime.of(20, 0),
        )
        // Inside the summer vacation range: a Friday morning and a Sunday night.
        assertEquals(DayType.HOLIDAY, edged.dayTypeOf(LocalDate.of(2026, 7, 3).atTime(9, 0)))
        assertEquals(DayType.HOLIDAY, edged.dayTypeOf(LocalDate.of(2026, 7, 5).atTime(22, 0)))
    }

    @Test
    fun `an edge at midnight swallows the whole day`() {
        val allFriday = calendar.copy(weekendStartsFriday = LocalTime.MIDNIGHT)
        assertEquals(DayType.WEEKEND, allFriday.dayTypeOf(LocalDate.of(2026, 3, 13).atTime(0, 0)))
        val noSunday = calendar.copy(weekendEndsSunday = LocalTime.MIDNIGHT)
        assertEquals(DayType.SCHOOL, noSunday.dayTypeOf(LocalDate.of(2026, 3, 15).atTime(0, 0)))
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

    // --- Day filters (2026-03-09 is a Monday, so +N days walks the week) ---

    private val monday: LocalDate = LocalDate.of(2026, 3, 9)
    private val homework = TimeWindow(
        LocalTime.of(17, 0),
        LocalTime.of(19, 0),
        days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
    )

    @Test
    fun `no days set means every day, which is what old windows decode to`() {
        val everyDay = TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0))
        repeat(7) { offset ->
            assertTrue(everyDay.appliesAt(monday.plusDays(offset.toLong()).atTime(18, 0)))
        }
    }

    @Test
    fun `a window restricted to two weekdays fires only on those`() {
        assertTrue(homework.appliesAt(monday.plusDays(1).atTime(18, 0))) // Tuesday
        assertTrue(homework.appliesAt(monday.plusDays(3).atTime(18, 0))) // Thursday
        assertFalse(homework.appliesAt(monday.atTime(18, 0)))
        assertFalse(homework.appliesAt(monday.plusDays(2).atTime(18, 0)))
        assertFalse(homework.appliesAt(monday.plusDays(5).atTime(18, 0)))
    }

    @Test
    fun `the day filter never widens the time range`() {
        assertFalse(homework.appliesAt(monday.plusDays(1).atTime(16, 59)))
        assertTrue(homework.appliesAt(monday.plusDays(1).atTime(17, 0)))
        assertFalse(homework.appliesAt(monday.plusDays(1).atTime(19, 0)))
    }

    @Test
    fun `a window crossing midnight belongs to the day it started on`() {
        val fridayNight = TimeWindow(
            LocalTime.of(21, 30),
            LocalTime.of(7, 30),
            days = setOf(DayOfWeek.FRIDAY),
        )
        val friday = monday.plusDays(4)
        assertTrue(fridayNight.appliesAt(friday.atTime(23, 0)))
        // 01:00 on Saturday is still inside Friday's window...
        assertTrue(fridayNight.appliesAt(friday.plusDays(1).atTime(1, 0)))
        // ...but Saturday night is not, because Saturday isn't selected.
        assertFalse(fridayNight.appliesAt(friday.plusDays(1).atTime(23, 0)))
    }

    @Test
    fun `skipping special days stands the window down, and only when asked`() {
        val skipping = homework.copy(skipSpecialDays = true)
        val tuesday = monday.plusDays(1).atTime(18, 0)
        assertTrue(skipping.appliesAt(tuesday, specialDay = false))
        assertFalse(skipping.appliesAt(tuesday, specialDay = true))
        // The default is to keep blocking: a window nobody marked must not quietly opt out.
        assertTrue(homework.appliesAt(tuesday, specialDay = true))
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

    @Test
    fun `a config with budgets requires usage counting`() {
        // Revoking usage access must fail closed here: budgets can't count down without it.
        assertEquals(true, RuleEngine.requiresUsageCounting(config))
    }

    @Test
    fun `a config with only time-based rules does not require usage counting`() {
        // Bedtime and blocked windows read the clock, not the usage counter.
        val timeOnly = config.copy(
            policies = mapOf(
                "games" to CategoryPolicy(
                    blockedWindows = mapOf(
                        DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(8, 30), LocalTime.of(14, 30))),
                    ),
                ),
            ),
        )
        assertEquals(false, RuleEngine.requiresUsageCounting(timeOnly))
    }

    @Test
    fun `an empty config does not require usage counting`() {
        assertEquals(false, RuleEngine.requiresUsageCounting(config.copy(policies = emptyMap())))
    }

    // --- Weekend edges: the same Friday carries two sets of rules ---

    private val earlyWeekend = config.copy(
        calendar = SchoolCalendar(weekendStartsFriday = LocalTime.of(14, 0)),
        bedtime = config.bedtime + (DayType.WEEKEND to TimeWindow(LocalTime.of(23, 30), LocalTime.of(9, 0))),
    )
    private val fridayMorning = LocalDateTime.of(2026, 3, 13, 10, 0)
    private val fridayAfternoon = LocalDateTime.of(2026, 3, 13, 15, 0)

    @Test
    fun `Friday morning still runs the school rules`() {
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW), // school-hours window on "games"
            RuleEngine.evaluate(earlyWeekend, "com.game.fortnite", fridayMorning),
        )
    }

    @Test
    fun `after the Friday edge the weekend budget applies, minus what the morning already spent`() {
        // The usage counter is per calendar day and does NOT restart at the edge: 30 min used
        // before lunch come off the 2h weekend budget the child moves into.
        val verdict = RuleEngine.evaluate(
            earlyWeekend, "com.game.fortnite", fridayAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(90)), verdict)
    }

    @Test
    fun `Friday night gets the weekend bedtime`() {
        val fridayNight = LocalDateTime.of(2026, 3, 13, 22, 0)
        // 22:00 is inside the school bedtime (21:30) but outside the weekend one (23:30).
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(earlyWeekend, "org.duolingo", fridayNight))
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(config, "org.duolingo", fridayNight), // no edge: still a school night
        )
    }

    @Test
    fun `a family window restricted to weekdays leaves the weekend alone`() {
        // "No screens at dinner, school nights only" — the shape the editor now writes.
        val dinner = config.copy(
            blockedWindows = mapOf(
                DayType.SCHOOL to listOf(
                    TimeWindow(
                        LocalTime.of(21, 0),
                        LocalTime.of(21, 30),
                        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                    ),
                ),
            ),
        )
        val monday = LocalDateTime.of(2026, 3, 9, 21, 15)
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(dinner, "org.duolingo", monday), // an otherwise unrestricted app
        )
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(dinner, "org.duolingo", monday.plusDays(1)))
    }

    @Test
    fun `a per-app window can stand down on a special day`() {
        val calendar = SchoolCalendar(holidays = setOf(LocalDate.of(2026, 3, 10))) // a Tuesday
        val tuesday = LocalDateTime.of(2026, 3, 10, 18, 0)
        val window = TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0), days = setOf(DayOfWeek.TUESDAY))
        // The policy has to answer on the HOLIDAY slot: that's what a special day resolves to.
        fun configWith(w: TimeWindow) = config.copy(
            calendar = calendar,
            perAppPolicies = mapOf("com.game.fortnite" to CategoryPolicy(blockedWindows = mapOf(DayType.HOLIDAY to listOf(w)))),
            policies = emptyMap(),
        )
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(configWith(window), "com.game.fortnite", tuesday),
        )
        assertEquals(
            Verdict.Allowed,
            RuleEngine.evaluate(configWith(window.copy(skipSpecialDays = true)), "com.game.fortnite", tuesday),
        )
    }

    @Test
    fun `Sunday evening edge brings the school bedtime back`() {
        val schoolNightBack = config.copy(calendar = SchoolCalendar(weekendEndsSunday = LocalTime.of(20, 0)))
        val sundayNight = LocalDateTime.of(2026, 3, 15, 22, 0)
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(schoolNightBack, "org.duolingo", sundayNight),
        )
        // Without the edge, Sunday has no weekend bedtime configured at all: nothing blocks.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "org.duolingo", sundayNight))
    }
}
