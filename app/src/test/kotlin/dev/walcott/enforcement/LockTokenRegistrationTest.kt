package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the lock-screen reset token is registered again (see [LockScreen.needsRegistration]).
 *
 * The bug this pins: registering replaces the token, and on a phone with a PIN the replacement is
 * inactive until that PIN is typed again. The token used to be re-registered on every read — every
 * service start, every rule change, and immediately before the remote PIN change checked it — so
 * a phone that already had a PIN answered "not armed" every time a family tried to rescue it.
 */
class LockTokenRegistrationTest {

    @Test
    fun `an active token this device holds is left alone`() {
        assertFalse(LockScreen.needsRegistration(hasOwnToken = true, activeNow = true))
    }

    @Test
    fun `a token the system no longer holds active is registered again`() {
        assertTrue(LockScreen.needsRegistration(hasOwnToken = true, activeNow = false))
    }

    @Test
    fun `a device with no token of its own registers one whatever the system says`() {
        // An active token this device cannot present is somebody else's, or one it lost the bytes
        // of; either way it cannot be used from here.
        assertTrue(LockScreen.needsRegistration(hasOwnToken = false, activeNow = true))
        assertTrue(LockScreen.needsRegistration(hasOwnToken = false, activeNow = false))
    }
}
