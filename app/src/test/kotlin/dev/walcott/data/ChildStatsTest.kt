package dev.walcott.data

import dev.walcott.rules.DayType
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.SchoolCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class ChildStatsTest {

    private val monday: LocalDateTime = LocalDate.of(2026, 7, 20).atTime(18, 0)
    private val saturday: LocalDateTime = LocalDate.of(2026, 7, 25).atTime(18, 0)

    private fun config(vararg perDay: Pair<DayType, Long>, calendar: SchoolCalendar = SchoolCalendar()) =
        FamilyConfig(
            version = 1,
            defaultAppBudget = perDay.associate { it.first to Duration.ofMinutes(it.second) },
            calendar = calendar,
        )

    @Test
    fun `the default limit for today is what the dashboard reports`() {
        val config = config(DayType.SCHOOL to 30, DayType.WEEKEND to 120)
        assertEquals(Duration.ofMinutes(30), ChildStats.defaultBudgetToday(config, monday))
        assertEquals(Duration.ofMinutes(120), ChildStats.defaultBudgetToday(config, saturday))
    }

    @Test
    fun `no default today means null (no limit), not zero`() {
        assertNull(ChildStats.defaultBudgetToday(config(DayType.WEEKEND to 120), monday))
        assertNull(ChildStats.defaultBudgetToday(config(), monday))
    }

    @Test
    fun `a calendar special day resolves to the holiday slice`() {
        val config = config(
            DayType.SCHOOL to 30, DayType.WEEKEND to 120, DayType.HOLIDAY to 90,
            calendar = SchoolCalendar(holidays = setOf(monday.toLocalDate())),
        )
        assertEquals(Duration.ofMinutes(90), ChildStats.defaultBudgetToday(config, monday))
    }

    // --- Which clock the parent reads a child by (see ChildStats.localNow) ---

    /** 2026-07-20T21:00Z: evening in Madrid, next morning in Tokyo. */
    private val evening = LocalDate.of(2026, 7, 20).atTime(21, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val madrid = 120
    private val tokyo = 9 * 60
    private val mexico = -6 * 60

    @Test
    fun `a child in the parent's timezone is read exactly as before`() {
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        assertEquals(parentNow, ChildStats.localNow(madrid, evening, parentNow))
    }

    @Test
    fun `a child that reports no offset falls back to the parent's clock`() {
        // Legacy children never send one; the old behaviour has to survive untouched.
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        assertEquals(parentNow, ChildStats.localNow(null, evening, parentNow))
    }

    @Test
    fun `a child east of the parent is already on tomorrow`() {
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        val childNow = ChildStats.localNow(tokyo, evening, parentNow)
        assertEquals(LocalDate.of(2026, 7, 21).atTime(6, 0), childNow)
    }

    @Test
    fun `a child west of the parent is still on yesterday`() {
        // 2026-07-20T23:00Z — past midnight in Madrid, still afternoon in Mexico City.
        val instant = LocalDate.of(2026, 7, 20).atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val parentNow = LocalDate.of(2026, 7, 21).atTime(1, 0)
        assertEquals(LocalDate.of(2026, 7, 20).atTime(17, 0), ChildStats.localNow(mexico, instant, parentNow))
    }

    @Test
    fun `a travelling child's usage is no longer dated as another day`() {
        // The defect this exists for: the parent matched snapshot.epochDay against its OWN day,
        // so a child on the other side of the world reported "0 used today" for up to a day.
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        val childReported = LocalDate.of(2026, 7, 21).toEpochDay() // the child's own "today"
        assertEquals(childReported, ChildStats.localNow(tokyo, evening, parentNow).toLocalDate().toEpochDay())
        assertNotEquals(childReported, parentNow.toLocalDate().toEpochDay()) { "the parent's day differs — the bug" }
    }

    @Test
    fun `a child that stopped publishing yesterday is still not counted as today`() {
        // The guard this fix must not trade away: dating a child by its own clock has to keep
        // rejecting stale counters, or a silent device would show yesterday's usage as today's.
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        val yesterday = LocalDate.of(2026, 7, 19).toEpochDay()
        assertFalse(ChildStats.reportsCurrentDay(yesterday, null, evening, parentNow)) { "legacy child" }
        assertFalse(ChildStats.reportsCurrentDay(yesterday, tokyo, evening, parentNow)) { "child abroad" }
        assertFalse(ChildStats.reportsCurrentDay(yesterday, madrid, evening, parentNow)) { "same timezone" }
    }

    @Test
    fun `a child abroad reporting its own day is counted as today`() {
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        val tokyoToday = LocalDate.of(2026, 7, 21).toEpochDay()
        assertTrue(ChildStats.reportsCurrentDay(tokyoToday, tokyo, evening, parentNow))
        // Same snapshot read by the parent's clock — what the screens used to do, and the bug.
        assertFalse(ChildStats.reportsCurrentDay(tokyoToday, null, evening, parentNow))
    }

    @Test
    fun `an out-of-range offset degrades to the parent's clock instead of throwing`() {
        // It arrives over the wire, so it can be corrupt or hostile. ZoneOffset would throw on
        // anything past ±18h, and this runs inside the parent's home screen.
        val parentNow = LocalDate.of(2026, 7, 20).atTime(23, 0)
        assertEquals(parentNow, ChildStats.localNow(99_999, evening, parentNow))
        assertEquals(parentNow, ChildStats.localNow(-99_999, evening, parentNow))
        assertEquals(parentNow, ChildStats.localNow(Int.MIN_VALUE, evening, parentNow))
    }

    @Test
    fun `the child's own clock picks the day type the limit is on`() {
        // The consequence that reaches the screen: past the Friday cut in Tokyo, still before it
        // in Madrid. Read by the parent's clock the card understates the limit by 90 minutes.
        val config = config(
            DayType.SCHOOL to 30, DayType.WEEKEND to 120,
            calendar = SchoolCalendar(weekendStartsFriday = LocalTime.of(14, 0)),
        )
        val instant = LocalDate.of(2026, 7, 24).atTime(11, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val parentNow = LocalDate.of(2026, 7, 24).atTime(12, 0) // UTC+1, before the cut

        assertEquals(
            Duration.ofMinutes(30),
            ChildStats.defaultBudgetToday(config, ChildStats.localNow(null, instant, parentNow)),
        )
        assertEquals(
            Duration.ofMinutes(120),
            ChildStats.defaultBudgetToday(config, ChildStats.localNow(tokyo, instant, parentNow)),
        )
    }

    @Test
    fun `the Friday weekend edge switches the limit the dashboard reports`() {
        val config = config(
            DayType.SCHOOL to 30, DayType.WEEKEND to 120,
            calendar = SchoolCalendar(weekendStartsFriday = LocalTime.of(14, 0)),
        )
        val friday = LocalDate.of(2026, 7, 24)
        assertEquals(Duration.ofMinutes(30), ChildStats.defaultBudgetToday(config, friday.atTime(9, 0)))
        assertEquals(Duration.ofMinutes(120), ChildStats.defaultBudgetToday(config, friday.atTime(15, 0)))
    }
}
