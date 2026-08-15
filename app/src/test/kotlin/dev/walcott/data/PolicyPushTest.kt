package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a sitting's worth of rule edits actually goes on the wire.
 *
 * Two promises, and the tests exist to keep them apart: ten seconds after the parent stops, and
 * thirty from the first change whatever they do next.
 */
class PolicyPushTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `one edit and nothing else goes out ten seconds later`() {
        assertEquals(t0 + 10_000L, PolicyPush.dueAtMs(firstEditAtMs = t0, lastEditAtMs = t0))
        assertEquals(PolicyPush.IDLE_HOLD_MS, PolicyPush.remainingMs(t0, t0, t0))
    }

    @Test
    fun `each further edit restarts the ten seconds, measured from the last one`() {
        // Three taps four seconds apart: the burst is due ten seconds after the third, not
        // after the first — the point of coalescing at all.
        val third = t0 + 8_000
        assertEquals(third + 10_000L, PolicyPush.dueAtMs(firstEditAtMs = t0, lastEditAtMs = third))
    }

    @Test
    fun `a sitting that keeps going still ships its oldest change at thirty seconds`() {
        // The parent is still editing at t+28s. Ten more seconds would put this at t+38s;
        // the ceiling from the first edit cuts it at t+30s.
        val stillGoing = t0 + 28_000
        assertEquals(t0 + PolicyPush.MAX_HOLD_MS, PolicyPush.dueAtMs(t0, stillGoing))
        assertEquals(2_000L, PolicyPush.remainingMs(t0, stillGoing, stillGoing))
    }

    @Test
    fun `an edit made past the ceiling is due at once`() {
        // Half a minute of steady editing: whatever has been waiting goes now, and the next
        // edit starts a fresh burst with a clock of its own (see SyncManager.onPolicyEdited).
        val late = t0 + 45_000
        assertEquals(0L, PolicyPush.remainingMs(t0, late, late))
    }

    @Test
    fun `the ceiling is thirty seconds and the idle wait is ten`() {
        // The numbers themselves are the promise, so they are asserted rather than implied.
        assertEquals(10_000L, PolicyPush.IDLE_HOLD_MS)
        assertEquals(30_000L, PolicyPush.MAX_HOLD_MS)
        assertTrue(PolicyPush.IDLE_HOLD_MS < PolicyPush.MAX_HOLD_MS)
    }

    @Test
    fun `remaining counts down and never goes negative`() {
        assertEquals(10_000L, PolicyPush.remainingMs(t0, t0, t0))
        assertEquals(4_000L, PolicyPush.remainingMs(t0, t0, t0 + 6_000))
        assertEquals(0L, PolicyPush.remainingMs(t0, t0, t0 + 10_000))
        assertEquals(0L, PolicyPush.remainingMs(t0, t0, t0 + 999_000))
    }

    @Test
    fun `a clock that jumped cannot park an edit in the future or make it look ancient`() {
        // Backwards: still bounded by the idle hold rather than by how far the clock moved.
        assertEquals(PolicyPush.IDLE_HOLD_MS, PolicyPush.remainingMs(t0, t0, t0 - 86_400_000))
        // Forwards: overdue, which reads as "send it now".
        assertEquals(0L, PolicyPush.remainingMs(t0, t0, t0 + 86_400_000))
    }

    @Test
    fun `no wait can ever exceed the ceiling from the first edit`() {
        // The property the old shape did not have: whatever the pattern of edits, nothing
        // already changed is still sitting here thirty seconds later.
        var last = t0
        repeat(40) {
            last += 3_000
            val due = PolicyPush.dueAtMs(firstEditAtMs = t0, lastEditAtMs = last)
            assertTrue(
                due <= t0 + PolicyPush.MAX_HOLD_MS,
                "an edit from t0 was still being held at ${due - t0}ms",
            )
        }
    }
}
