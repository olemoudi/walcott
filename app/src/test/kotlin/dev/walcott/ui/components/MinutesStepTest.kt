package dev.walcott.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The +/- stepping behind the custom-minutes dialog. The interesting case is a value that
 * isn't a multiple of the step (an old 45-minute limit, say): the buttons must snap onto the
 * 30-minute grid instead of carrying the offset forever (45 → 60 → 90, not 75 → 105).
 */
class MinutesStepTest {

    @Test
    fun `stepping up from a multiple adds one step`() {
        assertEquals(60, nextStep(30))
        assertEquals(90, nextStep(60))
        assertEquals(30, nextStep(0))
    }

    @Test
    fun `stepping down from a multiple removes one step`() {
        assertEquals(30, previousStep(60))
        assertEquals(0, previousStep(30))
    }

    @Test
    fun `an off-grid value snaps to the surrounding multiples`() {
        assertEquals(60, nextStep(45)) // up snaps to the next multiple
        assertEquals(30, previousStep(45)) // down snaps to the previous one
        assertEquals(30, nextStep(15))
        assertEquals(0, previousStep(15))
    }

    @Test
    fun `stepping near the daily maximum still lands on the grid`() {
        assertEquals(1440, nextStep(1410)) // 23h30m -> 24h
        assertEquals(1410, previousStep(1440))
    }

    @Test
    fun `a custom step size is honoured`() {
        assertEquals(20, nextStep(10, step = 20))
        assertEquals(0, previousStep(10, step = 20))
    }
}
