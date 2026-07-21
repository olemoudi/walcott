package dev.walcott.data

import dev.walcott.rules.CategoryPolicy
import dev.walcott.rules.DayType
import dev.walcott.rules.ExtraTime
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.SchoolCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

class ChildStatsTest {

    private val monday: LocalDate = LocalDate.of(2026, 7, 20)
    private val saturday: LocalDate = LocalDate.of(2026, 7, 25)

    private fun config(policies: Map<String, CategoryPolicy>, calendar: SchoolCalendar = SchoolCalendar()) =
        FamilyConfig(version = 1, assignments = emptyMap(), policies = policies, calendar = calendar)

    private fun budget(vararg perDay: Pair<DayType, Long>) =
        CategoryPolicy(dailyBudget = perDay.associate { it.first to Duration.ofMinutes(it.second) })

    @Test
    fun `sums remaining across budgeted categories for today's day type`() {
        val config = config(
            mapOf(
                "games" to budget(DayType.SCHOOL to 30, DayType.WEEKEND to 120),
                "video" to budget(DayType.SCHOOL to 60),
            ),
        )
        val remaining = ChildStats.remainingToday(
            config, monday,
            usage = mapOf("games" to Duration.ofMinutes(10)),
            extra = emptyMap(),
        )
        assertEquals(Duration.ofMinutes(20 + 60), remaining)
    }

    @Test
    fun `weekends use the weekend slice`() {
        val config = config(mapOf("games" to budget(DayType.SCHOOL to 30, DayType.WEEKEND to 120)))
        assertEquals(Duration.ofMinutes(120), ChildStats.remainingToday(config, saturday, emptyMap(), emptyMap()))
    }

    @Test
    fun `extra time (own category and all-apps) extends the remaining`() {
        val config = config(mapOf("games" to budget(DayType.SCHOOL to 30)))
        val remaining = ChildStats.remainingToday(
            config, monday,
            usage = mapOf("games" to Duration.ofMinutes(30)),
            extra = mapOf("games" to Duration.ofMinutes(15), ExtraTime.ALL_APPS to Duration.ofMinutes(5)),
        )
        assertEquals(Duration.ofMinutes(20), remaining)
    }

    @Test
    fun `an exhausted category contributes zero, not a negative`() {
        val config = config(
            mapOf(
                "games" to budget(DayType.SCHOOL to 30),
                "video" to budget(DayType.SCHOOL to 60),
            ),
        )
        val remaining = ChildStats.remainingToday(
            config, monday,
            usage = mapOf("games" to Duration.ofMinutes(90)),
            extra = emptyMap(),
        )
        assertEquals(Duration.ofMinutes(60), remaining)
    }

    @Test
    fun `no budget today means null (no limit), not zero`() {
        val unlimitedToday = config(mapOf("games" to budget(DayType.WEEKEND to 120)))
        assertNull(ChildStats.remainingToday(unlimitedToday, monday, emptyMap(), emptyMap()))
        assertNull(ChildStats.remainingToday(config(emptyMap()), monday, emptyMap(), emptyMap()))
    }

    @Test
    fun `a calendar special day resolves to the holiday slice`() {
        val config = config(
            mapOf("games" to budget(DayType.SCHOOL to 30, DayType.WEEKEND to 120, DayType.HOLIDAY to 120)),
            calendar = SchoolCalendar(holidays = setOf(monday)),
        )
        assertEquals(Duration.ofMinutes(120), ChildStats.remainingToday(config, monday, emptyMap(), emptyMap()))
    }
}
