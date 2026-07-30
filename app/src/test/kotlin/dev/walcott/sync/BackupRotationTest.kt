package dev.walcott.sync

import dev.walcott.sync.BackupRotation.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BackupRotationTest {

    private val wed = LocalDate.of(2026, 7, 29)

    @Test
    fun `the first run fills every slot`() {
        assertEquals(setOf(Slot.DAILY, Slot.WEEKLY, Slot.MONTHLY), BackupRotation.due(wed, emptyMap()))
    }

    @Test
    fun `an ordinary night only rewrites the daily copy`() {
        val last = mapOf(Slot.DAILY to wed.minusDays(1), Slot.WEEKLY to wed.minusDays(2), Slot.MONTHLY to wed.minusDays(5))
        assertEquals(setOf(Slot.DAILY), BackupRotation.due(wed, last))
    }

    @Test
    fun `nothing is rewritten twice on the same day`() {
        val last = mapOf(Slot.DAILY to wed, Slot.WEEKLY to wed, Slot.MONTHLY to wed)
        assertEquals(emptySet<Slot>(), BackupRotation.due(wed, last))
    }

    @Test
    fun `the weekly copy rolls when the calendar week changes, not seven days on`() {
        // Written Sunday 26 Jul; Monday 27 starts a new ISO week, so it is due after one day.
        val monday = LocalDate.of(2026, 7, 27)
        val last = mapOf(Slot.DAILY to monday, Slot.WEEKLY to LocalDate.of(2026, 7, 26), Slot.MONTHLY to monday)
        assertEquals(setOf(Slot.WEEKLY), BackupRotation.due(monday, last))
        // ...and not again for the rest of that week.
        val saturday = LocalDate.of(2026, 8, 1)
        val after = mapOf(Slot.DAILY to saturday, Slot.WEEKLY to monday, Slot.MONTHLY to saturday)
        assertEquals(emptySet<Slot>(), BackupRotation.due(saturday, after))
    }

    @Test
    fun `the weekly copy does not collide across the new year`() {
        // 31 Dec 2026 and 1 Jan 2027 are the same ISO week; a plain week number would roll here.
        val newYear = LocalDate.of(2027, 1, 1)
        val last = mapOf(Slot.DAILY to newYear, Slot.WEEKLY to LocalDate.of(2026, 12, 31), Slot.MONTHLY to newYear)
        assertEquals(emptySet<Slot>(), BackupRotation.due(newYear, last))
    }

    @Test
    fun `the monthly copy rolls on the first of the month`() {
        val first = LocalDate.of(2026, 8, 1)
        val last = mapOf(Slot.DAILY to first, Slot.WEEKLY to first, Slot.MONTHLY to LocalDate.of(2026, 7, 31))
        assertEquals(setOf(Slot.MONTHLY), BackupRotation.due(first, last))
    }

    @Test
    fun `a month later is due even landing on the same day number`() {
        val last = mapOf(Slot.MONTHLY to LocalDate.of(2025, 7, 29))
        assert(Slot.MONTHLY in BackupRotation.due(wed, last)) { "same month, a year earlier, is still a different month" }
    }

    @Test
    fun `a phone that was off for a fortnight catches up in one night, not fourteen`() {
        // The gap crosses a month end, so all three are behind and all three roll once.
        val back = LocalDate.of(2026, 8, 5)
        val last = mapOf(Slot.DAILY to back.minusDays(14), Slot.WEEKLY to back.minusDays(14), Slot.MONTHLY to back.minusDays(14))
        assertEquals(setOf(Slot.DAILY, Slot.WEEKLY, Slot.MONTHLY), BackupRotation.due(back, last))
    }

    @Test
    fun `a gap inside one month leaves the monthly copy alone`() {
        // Its whole value is being older than the others; rewriting it on any long gap would
        // quietly collapse the three copies into three identical ones.
        val last = mapOf(Slot.DAILY to wed.minusDays(14), Slot.WEEKLY to wed.minusDays(14), Slot.MONTHLY to wed.minusDays(14))
        assertEquals(setOf(Slot.DAILY, Slot.WEEKLY), BackupRotation.due(wed, last))
    }

    @Test
    fun `a clock that jumped backwards does not freeze a slot`() {
        // Timezone travel or a bad clock can date the last write in the future. Refusing to write
        // until real time catches up would silently stop backing up for days.
        val last = mapOf(Slot.DAILY to wed.plusDays(3), Slot.WEEKLY to wed.plusMonths(2), Slot.MONTHLY to wed.plusYears(1))
        assertEquals(setOf(Slot.DAILY, Slot.WEEKLY, Slot.MONTHLY), BackupRotation.due(wed, last))
    }
}
