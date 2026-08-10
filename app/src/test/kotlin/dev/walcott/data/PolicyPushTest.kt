package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PolicyPushTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `the hold grows five seconds per edit and then stops`() {
        assertEquals(15_000L, PolicyPush.holdMs(1))
        assertEquals(20_000L, PolicyPush.holdMs(2))
        assertEquals(25_000L, PolicyPush.holdMs(3))
        assertEquals(30_000L, PolicyPush.holdMs(4))
        // Past the ceiling every further edit simply restarts the maximum.
        assertEquals(30_000L, PolicyPush.holdMs(5))
        assertEquals(PolicyPush.MAX_HOLD_MS, PolicyPush.holdMs(50))
    }

    @Test
    fun `a first edit with no others is sent after the first hold`() {
        assertEquals(t0 + 15_000L, PolicyPush.dueAtMs(t0, 1))
    }

    @Test
    fun `the wait is measured from the LAST edit, not the first`() {
        // Two edits ten seconds apart: the deadline is 20 s after the second, i.e. 30 s after
        // the first — not 20 s after the burst began.
        val second = t0 + 10_000
        assertEquals(second + 20_000L, PolicyPush.dueAtMs(second, 2))
    }

    @Test
    fun `at the ceiling each new edit restarts the full thirty seconds`() {
        val fifth = t0 + 60_000
        assertEquals(fifth + PolicyPush.MAX_HOLD_MS, PolicyPush.dueAtMs(fifth, 5))
        val sixth = fifth + 29_000
        assertEquals(sixth + PolicyPush.MAX_HOLD_MS, PolicyPush.dueAtMs(sixth, 6))
    }

    @Test
    fun `remaining counts down and never goes negative`() {
        assertEquals(15_000L, PolicyPush.remainingMs(t0, 1, t0))
        assertEquals(5_000L, PolicyPush.remainingMs(t0, 1, t0 + 10_000))
        assertEquals(0L, PolicyPush.remainingMs(t0, 1, t0 + 15_000))
        assertEquals(0L, PolicyPush.remainingMs(t0, 1, t0 + 999_000))
    }

    @Test
    fun `a clock that jumped cannot park an edit in the future or make it look ancient`() {
        // Backwards: still bounded by the hold rather than by how far the clock moved.
        assertEquals(PolicyPush.holdMs(1), PolicyPush.remainingMs(t0, 1, t0 - 86_400_000))
        // Forwards: overdue, which reads as "send it now".
        assertEquals(0L, PolicyPush.remainingMs(t0, 1, t0 + 86_400_000))
    }
}
