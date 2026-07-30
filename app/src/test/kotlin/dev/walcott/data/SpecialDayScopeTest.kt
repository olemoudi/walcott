package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Special days that belong to one child rather than the family — a birthday is theirs.
 *
 * The rule that has to hold: a child's resolved policy carries the family's days plus their own,
 * and never a sibling's. Getting that wrong is silent, because the wrong answer is simply a
 * different day type on a Tuesday, and nobody reads a day type.
 */
class SpecialDayScopeTest {

    private val ana = ChildEntry("c1", "Ana")
    private val leo = ChildEntry("c2", "Leo")
    private val base = PolicySettings(children = listOf(ana, leo))
    private val summer = VacationDto(20_100, 20_130)
    private val camp = VacationDto(20_200, 20_210)

    @Test
    fun `a family-wide day reaches every child`() {
        val out = base.withHolidayScope(20_000, emptySet())
        assertEquals(setOf(20_000L), out.holidays)
        assertTrue(out.childHolidays.isEmpty())
        assertTrue(20_000L in out.resolveForChild("c1").holidays)
        assertTrue(20_000L in out.resolveForChild("c2").holidays)
    }

    @Test
    fun `a birthday reaches only its child`() {
        val out = base.withHolidayScope(20_050, setOf("c1"))
        assertTrue(out.holidays.isEmpty()) { "it is not the family's day" }
        assertEquals(setOf(20_050L), out.childHolidays["c1"])
        assertTrue(20_050L in out.resolveForChild("c1").holidays)
        assertTrue(20_050L !in out.resolveForChild("c2").holidays) { "a sibling's day must not travel" }
    }

    @Test
    fun `a child sees the family's days and their own together`() {
        val out = base.withHolidayScope(20_000, emptySet()).withHolidayScope(20_050, setOf("c1"))
        assertEquals(setOf(20_000L, 20_050L), out.resolveForChild("c1").holidays)
        assertEquals(setOf(20_000L), out.resolveForChild("c2").holidays)
    }

    @Test
    fun `a day can be shared by some children but not all`() {
        val out = base.withHolidayScope(20_070, setOf("c1", "c2"))
        assertTrue(20_070L in out.resolveForChild("c1").holidays)
        assertTrue(20_070L in out.resolveForChild("c2").holidays)
        assertTrue(out.holidays.isEmpty()) { "still not a family day — it just happens to cover both" }
    }

    @Test
    fun `re-scoping moves a day instead of leaving a copy behind`() {
        // The bug this guards: a day that ends up in two scopes at once, so removing it from the
        // row the parent is looking at leaves it quietly in force somewhere else.
        val family = base.withHolidayScope(20_000, emptySet())
        val toAna = family.withHolidayScope(20_000, setOf("c1"))
        assertTrue(toAna.holidays.isEmpty())
        assertEquals(setOf(20_000L), toAna.childHolidays["c1"])

        val backToFamily = toAna.withHolidayScope(20_000, emptySet())
        assertEquals(setOf(20_000L), backToFamily.holidays)
        assertTrue(backToFamily.childHolidays.isEmpty()) { "the per-child copy has to go" }

        val toLeo = toAna.withHolidayScope(20_000, setOf("c2"))
        assertEquals(setOf(20_000L), toLeo.childHolidays["c2"])
        assertTrue("c1" !in toLeo.childHolidays)
    }

    @Test
    fun `removing a day removes it whoever it belonged to`() {
        val out = base.withHolidayScope(20_000, emptySet()).withHolidayScope(20_050, setOf("c1"))
        assertTrue(out.withoutHoliday(20_000).allHolidays() == setOf(20_050L))
        assertTrue(out.withoutHoliday(20_050).allHolidays() == setOf(20_000L))
        assertTrue(out.withoutHoliday(20_050).childHolidays.isEmpty()) { "an emptied child key is dropped" }
    }

    @Test
    fun `the parent's list shows every day once, whoever owns it`() {
        val out = base.withHolidayScope(20_000, emptySet())
            .withHolidayScope(20_050, setOf("c1"))
            .withHolidayScope(20_060, setOf("c2"))
        assertEquals(setOf(20_000L, 20_050L, 20_060L), out.allHolidays())
        assertEquals(emptySet<String>(), out.holidayScope(20_000))
        assertEquals(setOf("c1"), out.holidayScope(20_050))
    }

    @Test
    fun `periods scope the same way as single days`() {
        val out = base.withVacationScope(summer, emptySet()).withVacationScope(camp, setOf("c2"))
        assertEquals(listOf(summer), out.vacations)
        assertEquals(listOf(camp), out.childVacations["c2"])
        assertEquals(listOf(summer), out.resolveForChild("c1").vacations)
        assertEquals(listOf(summer, camp), out.resolveForChild("c2").vacations)
        assertEquals(listOf(summer, camp), out.allVacations())
        assertEquals(setOf("c2"), out.vacationScope(camp))
        assertTrue(out.withoutVacation(camp).allVacations() == listOf(summer))
    }

    @Test
    fun `a legacy child with no registry entry still gets the family's days`() {
        // No childId means no per-child anything; it must not mean no calendar at all.
        val out = base.withHolidayScope(20_000, emptySet()).withHolidayScope(20_050, setOf("c1"))
        assertEquals(setOf(20_000L), out.resolveForChild("").holidays)
        assertEquals(setOf(20_000L), out.resolveForChild(null).holidays)
    }

    @Test
    fun `a child's own day becomes a special day for them and an ordinary one for their sibling`() {
        // The end of the chain the parent actually cares about: policy -> resolveForChild ->
        // FamilyConfig -> the day type the enforcement loop reads. A birthday that stops at the
        // policy and never reaches the calendar would look configured and do nothing.
        val birthday = java.time.LocalDate.of(2026, 3, 4)
        val settings = base
            .withHolidayScope(birthday.toEpochDay(), setOf("c1"))
            .withHolidayMirroringWeekend()
        val at = birthday.atTime(18, 0)

        val forAna = settings.resolveForChild("c1").toFamilyConfig(emptySet()).calendar
        val forLeo = settings.resolveForChild("c2").toFamilyConfig(emptySet()).calendar
        assertEquals(dev.walcott.rules.DayType.HOLIDAY, forAna.dayTypeOf(at))
        assertEquals(dev.walcott.rules.DayType.SCHOOL, forLeo.dayTypeOf(at)) { "4 Mar 2026 is a Wednesday" }
    }

    @Test
    fun `per-child days survive the holiday mirror pass untouched`() {
        // Every parent write runs through it; a calendar entry is not a day-typed rule and has no
        // business being collapsed by it.
        val out = base.withHolidayScope(20_050, setOf("c1")).withHolidayMirroringWeekend()
        assertEquals(setOf(20_050L), out.childHolidays["c1"])
    }
}
