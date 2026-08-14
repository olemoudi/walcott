package dev.walcott.data

import dev.walcott.rules.DayType
import dev.walcott.rules.SpecialDays
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Folding the screen-free schedule out of its day-type sections and onto each rule's own days.
 *
 * The whole risk of the change is here: a family that had different rules on weekdays and at the
 * weekend must not find them quietly rewritten. Every case below is "what did this family have
 * before, and is it still true after".
 */
class DayPickedWindowsMigrationTest {

    private fun window(start: Int, end: Int, days: List<Int> = emptyList(), skip: Boolean = false) =
        WindowDto(startMinute = start, endMinute = end, days = days, skipSpecialDays = skip)

    private fun settings(
        school: List<WindowDto> = emptyList(),
        weekend: List<WindowDto> = emptyList(),
        holiday: List<WindowDto> = emptyList(),
        ownRules: Boolean = false,
    ) = PolicySettings(
        allAppsBlockedWindows = mapOf(
            DayType.SCHOOL.name to school,
            DayType.WEEKEND.name to weekend,
            DayType.HOLIDAY.name to holiday,
        ),
        specialDaysOwnRules = ownRules,
    )

    /** The one list every day type now points at. */
    private fun folded(settings: PolicySettings): List<WindowDto> =
        settings.migratedToDayPickedWindows().allAppsBlockedWindows.getValue(DayType.SCHOOL.name)

    @Test
    fun `a section with no days of its own becomes the days that section meant`() {
        val out = folded(settings(school = listOf(window(1020, 1140))))
        assertEquals(listOf(1, 2, 3, 4, 5), out.single().days)
    }

    @Test
    fun `a weekend rule becomes Saturday and Sunday`() {
        val out = folded(settings(weekend = listOf(window(600, 720))))
        assertEquals(listOf(6, 7), out.single().days)
    }

    @Test
    fun `a rule that already named days keeps only the ones its section could reach`() {
        // "Weekdays, but only Monday and Wednesday" stays exactly that. The Saturday tick was
        // never doing anything in a weekday section and must not start now.
        val out = folded(settings(school = listOf(window(1020, 1140, days = listOf(1, 3, 6)))))
        assertEquals(listOf(1, 3), out.single().days)
    }

    @Test
    fun `a rule whose days its section could never reach is dropped, not widened`() {
        // The trap: an empty day list means EVERY day, so narrowing to nothing and keeping the
        // rule would turn one that never fired into one that fires all week.
        val out = folded(settings(school = listOf(window(1020, 1140, days = listOf(6, 7)))))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `weekday and weekend rules survive side by side`() {
        val out = folded(
            settings(
                school = listOf(window(1020, 1140)),
                weekend = listOf(window(600, 720)),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(listOf(1, 2, 3, 4, 5), out[0].days)
        assertEquals(listOf(6, 7), out[1].days)
    }

    @Test
    fun `special-day rules become ONLY when the family had opted into them`() {
        val out = folded(settings(holiday = listOf(window(720, 780)), ownRules = true))
        assertEquals(SpecialDays.ONLY, out.single().specialDays)
        assertTrue(out.single().onlySpecialDays)
    }

    @Test
    fun `the mirrored holiday list is not resurrected as holiday-only rules`() {
        // With the switch OFF the holiday slot is a COPY of the weekend's; re-adding it would
        // invent "only on holidays" rules the parent never wrote, on top of the weekend ones.
        val weekendRule = window(600, 720)
        val out = folded(settings(weekend = listOf(weekendRule), holiday = listOf(weekendRule), ownRules = false))
        assertEquals(1, out.size)
        assertEquals(SpecialDays.ALWAYS, out.single().specialDays)
    }

    @Test
    fun `an existing skip-special-days rule keeps standing down`() {
        val out = folded(settings(school = listOf(window(1020, 1140, skip = true))))
        assertEquals(SpecialDays.NEVER, out.single().specialDays)
    }

    @Test
    fun `the same window in both sections becomes one rule covering the whole week`() {
        // "22:00–23:00, every day" had to be written twice because the editor split the week in
        // two. It is one rule, and it comes back as one — not as a weekday rule sitting above an
        // identical weekend rule, which is what a naive fold would leave behind.
        val everyDay = window(1320, 1380)
        val out = folded(settings(school = listOf(everyDay), weekend = listOf(everyDay)))
        assertEquals(1, out.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), out.single().days)
    }

    @Test
    fun `merging respects the special-day state, which is part of what a rule IS`() {
        // Same hours, different answer on holidays: two rules, not one. Merging them would
        // silently pick one family's answer for the other's.
        val out = folded(
            settings(
                school = listOf(window(1020, 1140)),
                weekend = listOf(window(1020, 1140, skip = true)),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(SpecialDays.ALWAYS, out[0].specialDays)
        assertEquals(SpecialDays.NEVER, out[1].specialDays)
    }

    @Test
    fun `every day type ends up pointing at the same list`() {
        // The wire keeps its shape, so a child that has not updated still finds windows where it
        // looks for them; each rule's own days do the filtering the sections used to do.
        val migrated = settings(school = listOf(window(1020, 1140))).migratedToDayPickedWindows()
        val lists = migrated.allAppsBlockedWindows.values.distinct()
        assertEquals(1, lists.size)
        assertEquals(DayType.entries.size, migrated.allAppsBlockedWindows.size)
    }

    @Test
    fun `running it twice changes nothing`() {
        val once = settings(
            school = listOf(window(1020, 1140)),
            weekend = listOf(window(600, 720)),
        ).migratedToDayPickedWindows()
        assertEquals(once, once.migratedToDayPickedWindows())
    }

    @Test
    fun `a policy with no screen-free rules at all is left alone`() {
        val empty = PolicySettings()
        assertSame(empty, empty.migratedToDayPickedWindows())
    }

    @Test
    fun `per-app windows fold the same way`() {
        val settings = PolicySettings(
            appPolicies = mapOf(
                "com.game" to AppPolicyDto(
                    blockedWindows = mapOf(
                        DayType.SCHOOL.name to listOf(window(1020, 1140)),
                        DayType.WEEKEND.name to emptyList(),
                        DayType.HOLIDAY.name to emptyList(),
                    ),
                ),
            ),
        )
        val out = settings.migratedToDayPickedWindows()
            .appPolicies.getValue("com.game").blockedWindows.getValue(DayType.SCHOOL.name)
        assertEquals(listOf(1, 2, 3, 4, 5), out.single().days)
    }

    @Test
    fun `a child's own screen-free rules fold too`() {
        val settings = PolicySettings(
            children = listOf(
                ChildEntry(
                    childId = "c1",
                    name = "Ana",
                    overrides = ChildOverrides(
                        allAppsBlockedWindows = mapOf(
                            DayType.SCHOOL.name to listOf(window(1020, 1140)),
                            DayType.WEEKEND.name to emptyList(),
                            DayType.HOLIDAY.name to emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val out = settings.migratedToDayPickedWindows()
            .children.single().overrides.allAppsBlockedWindows!!.getValue(DayType.SCHOOL.name)
        assertEquals(listOf(1, 2, 3, 4, 5), out.single().days)
    }
}
