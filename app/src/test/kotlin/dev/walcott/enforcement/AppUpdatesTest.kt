package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AppUpdatesTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, day, hour, minute)

    @Test
    fun `the window is open from its hour for exactly its length`() {
        assertNull(AppUpdates.windowEnd(at(10, 3, 59), hour = 4, minutes = 60), "a minute early is closed")
        assertEquals(at(10, 5), AppUpdates.windowEnd(at(10, 4), hour = 4, minutes = 60), "open on the hour")
        assertEquals(at(10, 5), AppUpdates.windowEnd(at(10, 4, 59), hour = 4, minutes = 60))
        assertNull(AppUpdates.windowEnd(at(10, 5), hour = 4, minutes = 60), "closed on its end, not after it")
    }

    @Test
    fun `a window that crosses midnight is still open on the other side of it`() {
        // The failure this prevents: asking only "did one start today" answers no at 00:30 while
        // a window opened at 23:30 is still running, and the block would re-arm mid-update.
        assertEquals(at(11, 1), AppUpdates.windowEnd(at(11, 0, 30), hour = 23, minutes = 120))
        assertEquals(at(11, 1), AppUpdates.windowEnd(at(10, 23, 30), hour = 23, minutes = 120))
        assertNull(AppUpdates.windowEnd(at(11, 2), hour = 23, minutes = 120))
    }

    @Test
    fun `a zero-length window is no window at all`() {
        assertNull(AppUpdates.windowEnd(at(10, 4), hour = 4, minutes = 0))
    }

    @Test
    fun `the next start is today's if it is still to come, and tomorrow's once it has passed`() {
        assertEquals(at(10, 4), AppUpdates.nextStart(at(10, 1), hour = 4, minutes = 60))
        assertEquals(at(11, 4), AppUpdates.nextStart(at(10, 9), hour = 4, minutes = 60))
    }

    @Test
    fun `a device that wakes up inside a window opens that one, not tomorrow's`() {
        // A reboot at 04:10 must not cost the phone its updates until the following night.
        assertEquals(at(10, 4), AppUpdates.nextStart(at(10, 4, 10), hour = 4, minutes = 60))
        assertEquals(at(10, 23), AppUpdates.nextStart(at(11, 0, 30), hour = 23, minutes = 120))
    }

    @Test
    fun `an unknown mode is read as the strict one`() {
        // A policy from a NEWER parent naming a mode this build has never heard of must fall
        // back to the protective answer, never to "no restriction at all".
        assertEquals(AppUpdates.MODE_STRICT, AppUpdates.modeOf(null))
        assertEquals(AppUpdates.MODE_STRICT, AppUpdates.modeOf(""))
        assertEquals(AppUpdates.MODE_STRICT, AppUpdates.modeOf("something_new"))
        assertEquals(AppUpdates.MODE_GUARDED, AppUpdates.modeOf(AppUpdates.MODE_GUARDED))
    }

    @Test
    fun `an hour outside the clock is clamped rather than thrown`() {
        assertEquals(at(10, 23, 30), AppUpdates.windowEnd(at(10, 23, 10), hour = 99, minutes = 30))
        assertEquals(at(10, 0, 30), AppUpdates.windowEnd(at(10, 0, 10), hour = -5, minutes = 30))
    }
}
