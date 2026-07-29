package dev.walcott.ui.parent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek

/**
 * The day chips' storage convention: an empty list means "every day", so toggling has to move
 * between that and an explicit set without ever leaving a window that fires on no day at all.
 */
class DayToggleTest {

    @Test
    fun `unselecting one day from an all-days window spells out the rest`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), emptyList<Int>().toggledDay(DayOfWeek.SUNDAY))
    }

    @Test
    fun `selecting the last missing day collapses back to every day`() {
        assertEquals(emptyList<Int>(), listOf(1, 2, 3, 4, 5, 6).toggledDay(DayOfWeek.SUNDAY))
    }

    @Test
    fun `toggling keeps the list sorted so the stored order is stable`() {
        assertEquals(listOf(2, 4), listOf(4).toggledDay(DayOfWeek.TUESDAY))
    }

    @Test
    fun `the last remaining day cannot be unselected`() {
        // A window applying on no day is a rule the parent can see but that never fires.
        assertEquals(listOf(3), listOf(3).toggledDay(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun `unselecting a day that is already off puts it back on`() {
        assertEquals(listOf(1, 5), listOf(1).toggledDay(DayOfWeek.FRIDAY))
    }
}
