package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "What is NOT stopping them, and when does that stop being true" — the state a parent is
 * looking at most of the time, and the one nothing described.
 */
class RuleContextTest {

    private val bedtime = TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30))
    private val homework = TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0))

    private fun config(
        bedtimes: Map<DayType, TimeWindow> = emptyMap(),
        screenFree: Map<DayType, List<TimeWindow>> = emptyMap(),
        calendar: SchoolCalendar = SchoolCalendar(),
    ) = FamilyConfig(version = 1, bedtime = bedtimes, blockedWindows = screenFree, calendar = calendar)

    /** A Monday afternoon, nothing running. */
    private val mondayAfternoon = LocalDateTime.of(2026, 3, 2, 15, 0)

    // --- the windows that are not running ---

    @Test
    fun `a quiet afternoon says when the quiet ends`() {
        val ctx = RuleEngine.ruleContext(
            config(
                bedtimes = mapOf(DayType.SCHOOL to bedtime),
                screenFree = mapOf(DayType.SCHOOL to listOf(homework)),
            ),
            mondayAfternoon,
        )
        assertEquals(WindowStatus.Later(LocalTime.of(21, 30), LocalTime.of(7, 30)), ctx.bedtime)
        assertEquals(WindowStatus.Later(LocalTime.of(17, 0), LocalTime.of(19, 0)), ctx.screenFree)
    }

    @Test
    fun `a running window says when it lets go`() {
        val at = LocalDateTime.of(2026, 3, 2, 18, 0)
        val ctx = RuleEngine.ruleContext(
            config(
                bedtimes = mapOf(DayType.SCHOOL to bedtime),
                screenFree = mapOf(DayType.SCHOOL to listOf(homework)),
            ),
            at,
        )
        assertEquals(WindowStatus.Running(LocalTime.of(19, 0)), ctx.screenFree)
        assertEquals(WindowStatus.Later(LocalTime.of(21, 30), LocalTime.of(7, 30)), ctx.bedtime)
    }

    @Test
    fun `bedtime running past midnight is reported as running, not as due later`() {
        val at = LocalDateTime.of(2026, 3, 3, 1, 0)
        val ctx = RuleEngine.ruleContext(config(bedtimes = mapOf(DayType.SCHOOL to bedtime)), at)
        assertEquals(WindowStatus.Running(LocalTime.of(7, 30)), ctx.bedtime)
    }

    @Test
    fun `a day with no rule of that kind is not the same as one whose rules are over`() {
        val none = RuleEngine.ruleContext(config(), mondayAfternoon)
        assertEquals(WindowStatus.None(configuredToday = false), none.screenFree)
        assertEquals(WindowStatus.None(configuredToday = false), none.bedtime)

        val spent = RuleEngine.ruleContext(
            config(screenFree = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 0))))),
            mondayAfternoon,
        )
        assertEquals(WindowStatus.None(configuredToday = true), spent.screenFree)
    }

    @Test
    fun `the next window today is the earliest one still to come`() {
        val ctx = RuleEngine.ruleContext(
            config(
                screenFree = mapOf(
                    DayType.SCHOOL to listOf(
                        TimeWindow(LocalTime.of(21, 0), LocalTime.of(22, 0)),
                        homework,
                        TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 0)),
                    ),
                ),
            ),
            mondayAfternoon,
        )
        assertEquals(WindowStatus.Later(LocalTime.of(17, 0), LocalTime.of(19, 0)), ctx.screenFree)
    }

    @Test
    fun `a window that does not apply on this weekday is not announced for today`() {
        val fridaysOnly = homework.copy(days = setOf(java.time.DayOfWeek.FRIDAY))
        val ctx = RuleEngine.ruleContext(
            config(screenFree = mapOf(DayType.SCHOOL to listOf(fridaysOnly))),
            mondayAfternoon,
        )
        assertEquals(WindowStatus.None(configuredToday = false), ctx.screenFree)
    }

    @Test
    fun `a window that stands down on special days says so on one`() {
        val notOnHolidays = homework.copy(specialDays = SpecialDays.NEVER)
        val holiday = LocalDate.of(2026, 3, 2)
        val ctx = RuleEngine.ruleContext(
            config(
                screenFree = mapOf(DayType.HOLIDAY to listOf(notOnHolidays)),
                calendar = SchoolCalendar(holidays = setOf(holiday)),
            ),
            mondayAfternoon,
        )
        assertTrue(ctx.specialDay)
        assertEquals(WindowStatus.None(configuredToday = false), ctx.screenFree)
    }

    // --- which day the rules think it is ---

    @Test
    fun `a plain Monday is a school day that turns into the weekend on Saturday`() {
        val ctx = RuleEngine.ruleContext(config(), mondayAfternoon)
        assertEquals(DayType.SCHOOL, ctx.dayType)
        assertTrue(!ctx.specialDay)
        assertEquals(DayType.WEEKEND, ctx.nextDayType?.to)
        assertEquals(LocalDateTime.of(2026, 3, 7, 0, 0), ctx.nextDayType?.at)
    }

    @Test
    fun `the Friday edge is where the weekend begins, and it is said before it happens`() {
        val fridayMorning = LocalDateTime.of(2026, 3, 6, 9, 0)
        val calendar = SchoolCalendar(weekendStartsFriday = LocalTime.of(14, 0))
        val ctx = RuleEngine.ruleContext(config(calendar = calendar), fridayMorning)
        assertEquals(DayType.SCHOOL, ctx.dayType)
        assertEquals(DayType.WEEKEND, ctx.nextDayType?.to)
        assertEquals(LocalDateTime.of(2026, 3, 6, 14, 0), ctx.nextDayType?.at)
    }

    @Test
    fun `the Sunday edge is where the weekday rules come back`() {
        val sundayAfternoon = LocalDateTime.of(2026, 3, 8, 15, 0)
        val calendar = SchoolCalendar(weekendEndsSunday = LocalTime.of(21, 0))
        val ctx = RuleEngine.ruleContext(config(calendar = calendar), sundayAfternoon)
        assertEquals(DayType.WEEKEND, ctx.dayType)
        assertEquals(DayType.SCHOOL, ctx.nextDayType?.to)
        assertEquals(LocalDateTime.of(2026, 3, 8, 21, 0), ctx.nextDayType?.at)
    }

    @Test
    fun `a special day is a special day all day, and says what comes after it`() {
        val holiday = LocalDate.of(2026, 3, 2)
        val ctx = RuleEngine.ruleContext(
            config(calendar = SchoolCalendar(holidays = setOf(holiday))),
            mondayAfternoon,
        )
        assertEquals(DayType.HOLIDAY, ctx.dayType)
        assertTrue(ctx.specialDay)
        assertEquals(DayType.SCHOOL, ctx.nextDayType?.to)
        assertEquals(LocalDateTime.of(2026, 3, 3, 0, 0), ctx.nextDayType?.at)
    }

    @Test
    fun `a week that never changes reports no change rather than inventing one`() {
        val vacation = LocalDate.of(2026, 3, 1)..LocalDate.of(2026, 4, 30)
        val ctx = RuleEngine.ruleContext(
            config(calendar = SchoolCalendar(vacations = listOf(vacation))),
            mondayAfternoon,
        )
        assertEquals(DayType.HOLIDAY, ctx.dayType)
        assertTrue(ctx.nextDayType == null)
    }
}
