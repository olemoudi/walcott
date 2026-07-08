package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PinLockoutTest {

    @Test
    fun `no lockout before the first tranche completes`() {
        assertEquals(0L, PinLockout.lockoutMs(0))
        assertEquals(0L, PinLockout.lockoutMs(1))
        assertEquals(0L, PinLockout.lockoutMs(2))
    }

    @Test
    fun `lockout steps up by 5 minutes every 3 attempts and caps at 30`() {
        // First tranche (3rd attempt) earns 5 min; it holds through the tranche.
        assertEquals(5 * 60_000L, PinLockout.lockoutMs(3))
        assertEquals(5 * 60_000L, PinLockout.lockoutMs(4))
        assertEquals(5 * 60_000L, PinLockout.lockoutMs(5))
        assertEquals(10 * 60_000L, PinLockout.lockoutMs(6))
        assertEquals(15 * 60_000L, PinLockout.lockoutMs(9))
        assertEquals(20 * 60_000L, PinLockout.lockoutMs(12))
        assertEquals(25 * 60_000L, PinLockout.lockoutMs(15))
        // Caps at 30 min and never grows unbounded.
        assertEquals(30 * 60_000L, PinLockout.lockoutMs(18))
        assertEquals(30 * 60_000L, PinLockout.lockoutMs(100))
    }

    @Test
    fun `remaining clamps at zero`() {
        assertEquals(0L, PinLockout.remainingMs(lockedUntilMs = 1_000, nowMs = 5_000))
        assertEquals(4_000L, PinLockout.remainingMs(lockedUntilMs = 9_000, nowMs = 5_000))
    }
}
