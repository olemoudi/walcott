package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinLockoutTest {

    @Test
    fun `no lockout within the free attempts`() {
        for (attempts in 0..PinLockout.FREE_ATTEMPTS) {
            assertEquals(0L, PinLockout.lockoutMs(attempts))
        }
    }

    @Test
    fun `lockout escalates and caps`() {
        assertEquals(30_000L, PinLockout.lockoutMs(5))
        assertEquals(60_000L, PinLockout.lockoutMs(6))
        assertEquals(5 * 60_000L, PinLockout.lockoutMs(7))
        assertEquals(30 * 60_000L, PinLockout.lockoutMs(8))
        // Caps at the last step, never grows unbounded.
        assertEquals(30 * 60_000L, PinLockout.lockoutMs(20))
    }

    @Test
    fun `remaining clamps at zero`() {
        assertEquals(0L, PinLockout.remainingMs(lockedUntilMs = 1_000, nowMs = 5_000))
        assertEquals(4_000L, PinLockout.remainingMs(lockedUntilMs = 9_000, nowMs = 5_000))
    }
}
