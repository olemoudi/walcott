package dev.walcott.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Choose a PIN, then type it again" — the gate that decides what becomes a family's PIN.
 *
 * It is worth a test of its own because of what is downstream of it: the PIN it settles on opens
 * parent mode, frees a child's phone from the phone itself, and is what the emergency release
 * checks against offline. A screen that accepted a PIN the person did not mean to type would be
 * discovered on the worst possible day.
 */
class PinSetupTest {

    private val min = 6
    private val max = 8

    private fun type(setup: PinSetup, digits: String) = setup.typed(digits, max)

    @Test
    fun `only digits, and never more than the maximum`() {
        assertEquals("123456", type(PinSetup(), "12 34-56").entry)
        assertEquals("12345678", type(PinSetup(), "1234567890").entry)
        assertEquals("", type(PinSetup(), "abc").entry)
    }

    @Test
    fun `a PIN shorter than the minimum cannot move on, however hard the screen asks`() {
        // The button is disabled at this length; this is the half that does not depend on it.
        val short = type(PinSetup(), "12345")
        assertFalse(short.canAdvance(min))
        assertEquals(short, short.advance(min))
        assertNull(short.advance(min).completed)
    }

    @Test
    fun `the first step keeps what was chosen and empties the box for the second`() {
        val confirming = type(PinSetup(), "135790").advance(min)
        assertTrue(confirming.confirming)
        assertEquals("135790", confirming.chosen)
        assertEquals("", confirming.entry, "the second step must be typed from memory, not read")
        assertNull(confirming.completed)
    }

    @Test
    fun `two halves that agree are the PIN`() {
        val done = type(type(PinSetup(), "135790").advance(min), "135790").advance(min)
        assertEquals("135790", done.completed)
    }

    @Test
    fun `a disagreement throws both halves away rather than asking again for the second`() {
        // Either of the two could have been the typo and nobody knows which, so keeping the
        // first and re-asking for the second would be confirming a PIN nobody has typed twice.
        val mismatched = type(type(PinSetup(), "135790").advance(min), "135791").advance(min)
        assertTrue(mismatched.mismatch)
        assertFalse(mismatched.confirming)
        assertEquals("", mismatched.chosen)
        assertEquals("", mismatched.entry)
        assertNull(mismatched.completed)
    }

    @Test
    fun `typing again clears the complaint`() {
        val mismatched = type(type(PinSetup(), "135790").advance(min), "135791").advance(min)
        assertFalse(type(mismatched, "1").mismatch)
    }

    @Test
    fun `a PIN longer than the minimum is allowed all the way through`() {
        val done = type(type(PinSetup(), "12345678").advance(min), "12345678").advance(min)
        assertEquals("12345678", done.completed)
    }
}
