package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which new unlock PINs this app is willing to send.
 *
 * The platform accepts far more shapes than this, and none of the extra ones are what somebody can
 * be told over the telephone by a relative who has just changed it for them — which is the entire
 * situation this feature is built for. Refusing them here, before anything is sent, means the
 * parent gets "no" while they are still looking at the dialog rather than an ack from a phone whose
 * lock has already changed.
 */
class LockScreenPinTest {

    @Test
    fun `four to eight digits is the shape that can be read aloud`() {
        assertTrue(LockScreen.isValidPin("4291"))
        assertTrue(LockScreen.isValidPin("12345678"))
    }

    @Test
    fun `too short and too long are both refused`() {
        assertFalse(LockScreen.isValidPin("123"), "three digits is below every OEM minimum")
        assertFalse(LockScreen.isValidPin("123456789"), "nine digits is not a number anybody repeats correctly")
        assertFalse(LockScreen.isValidPin(""))
    }

    @Test
    fun `anything that is not digits is refused`() {
        // A pattern, a password, a stray space from a paste: all things the platform might accept
        // and nobody can dictate down a phone line.
        assertFalse(LockScreen.isValidPin("12 34"))
        assertFalse(LockScreen.isValidPin("abcd"))
        assertFalse(LockScreen.isValidPin("12a4"))
        assertFalse(LockScreen.isValidPin("1234\n"))
    }
}
