package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParentCadenceTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `a child that checked in recently keeps the parent on the fast cadence`() {
        assertEquals(ParentCadence.FAST_MS, ParentCadence.nextIntervalMs(now, now))
        assertEquals(ParentCadence.FAST_MS, ParentCadence.nextIntervalMs(now - 29 * 60_000L, now))
    }

    @Test
    fun `two missed check-ins mean nobody can be asking, so the parent slows down`() {
        val quiet = now - ParentCadence.QUIET_AFTER_MS
        assertEquals(ParentCadence.SLOW_MS, ParentCadence.nextIntervalMs(quiet, now))
        assertEquals(ParentCadence.FAST_MS, ParentCadence.nextIntervalMs(quiet + 1, now))
    }

    @Test
    fun `a parent with no children yet does not wake up for them`() {
        assertEquals(ParentCadence.SLOW_MS, ParentCadence.nextIntervalMs(null, now))
    }

    @Test
    fun `a check-in stamped in the future reads as recent, not as ancient silence`() {
        // Skew is the one input that could flip this the dangerous way: a naive subtraction
        // would go negative, and a careless comparison would call that "quiet for ever".
        assertEquals(ParentCadence.FAST_MS, ParentCadence.nextIntervalMs(now + 60 * 60_000L, now))
    }

    @Test
    fun `the slow cadence is never worse than the fixed interval it replaced`() {
        // 30 min was unconditional before ParentCadence existed; the adaptive version must not
        // be able to make any case slower than that.
        assertTrue(ParentCadence.SLOW_MS <= 30 * 60 * 1000L)
        assertTrue(ParentCadence.FAST_MS < ParentCadence.SLOW_MS)
        // Below the ~9-minute floor the OS enforces on setAndAllowWhileIdle, asking for less
        // just gets deferred — so the fast cadence must not pretend to be faster than that.
        assertTrue(ParentCadence.FAST_MS >= 9 * 60 * 1000L)
    }
}
