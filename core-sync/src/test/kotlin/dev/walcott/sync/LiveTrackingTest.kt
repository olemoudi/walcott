package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveTrackingTest {

    @Test
    fun `durations snap to the quarter hour and stay inside the offered range`() {
        assertEquals(15, LiveTracking.clampMinutes(15))
        assertEquals(60, LiveTracking.clampMinutes(60))
        assertEquals(30, LiveTracking.clampMinutes(23)) // rounds to the nearest step
        assertEquals(15, LiveTracking.clampMinutes(22))
        assertEquals(15, LiveTracking.clampMinutes(1)) // never below one step
        assertEquals(LiveTracking.MAX_MINUTES, LiveTracking.clampMinutes(600))
    }

    @Test
    fun `zero and below mean stop, not a minimum session`() {
        // The wire sends "0" to end a session early; rounding that up to 15 minutes would make
        // the stop button start a new one.
        assertEquals(0, LiveTracking.clampMinutes(0))
        assertEquals(0, LiveTracking.clampMinutes(-30))
    }

    @Test
    fun `every preset is a valid duration`() {
        LiveTracking.PRESET_MINUTES.forEach {
            assertEquals(it, LiveTracking.clampMinutes(it)) { "$it is not a clean preset" }
            assertTrue(it <= LiveTracking.MAX_MINUTES)
        }
    }

    @Test
    fun `extending buys at least the half hour it offers, never less`() {
        // The trap this avoids: a session is SET, not added to, so extending asks for
        // "what is left plus thirty" — and clampMinutes rounds to the NEAREST step, so asking
        // for 37 would have handed back 30 and taken seven minutes off a running session.
        assertEquals(30, LiveTracking.extendedMinutes(0L))
        assertEquals(60, LiveTracking.extendedMinutes(30 * 60_000L))
        assertEquals(60, LiveTracking.extendedMinutes(20 * 60_000L)) // 50 rounds UP to 60
        assertEquals(45, LiveTracking.extendedMinutes(7 * 60_000L)) // 37 rounds UP to 45
        listOf(0L, 1L, 59_999L, 7 * 60_000L, 20 * 60_000L, 90 * 60_000L).forEach { left ->
            val asked = LiveTracking.extendedMinutes(left)
            val leftMinutes = (left + 59_999L) / 60_000L
            assertTrue(
                asked - leftMinutes >= LiveTracking.EXTEND_MINUTES,
                "extending by ${asked - leftMinutes} min is less than was offered",
            )
            // Whatever this answers travels through clampMinutes twice (the parent's request and
            // the child's own guard), so it has to survive both untouched.
            assertEquals(asked, LiveTracking.clampMinutes(asked))
        }
    }

    @Test
    fun `extending cannot outgrow the longest session on offer`() {
        assertEquals(LiveTracking.MAX_MINUTES, LiveTracking.extendedMinutes(LiveTracking.MAX_MINUTES * 60_000L))
        assertEquals(LiveTracking.MAX_MINUTES, LiveTracking.extendedMinutes(230 * 60_000L))
    }

    @Test
    fun `a session runs until its monotonic deadline and not a tick longer`() {
        val until = 100_000L
        assertTrue(LiveTracking.isRunning(until, nowElapsedMs = 99_999L))
        assertFalse(LiveTracking.isRunning(until, nowElapsedMs = 100_000L))
        assertFalse(LiveTracking.isRunning(until, nowElapsedMs = 200_000L))
        assertEquals(1L, LiveTracking.remainingMs(until, 99_999L))
        assertEquals(0L, LiveTracking.remainingMs(until, 200_000L))
    }

    @Test
    fun `no deadline means no session`() {
        assertFalse(LiveTracking.isRunning(0L, nowElapsedMs = 0L))
        assertFalse(LiveTracking.isRunning(0L, nowElapsedMs = 5_000L))
    }

    @Test
    fun `a flat battery ends a session, a charging one does not`() {
        assertTrue(LiveTracking.batteryTooLow(9, charging = false))
        assertFalse(LiveTracking.batteryTooLow(9, charging = true))
        assertFalse(LiveTracking.batteryTooLow(LiveTracking.BATTERY_FLOOR_PERCENT, charging = false))
        assertFalse(LiveTracking.batteryTooLow(80, charging = false))
    }

    @Test
    fun `an unknown battery level never stops a session`() {
        // Refusing to track because the platform would not say how full the battery is would be
        // its own failure, and -1 is exactly what a legacy or unusual device reports.
        assertFalse(LiveTracking.batteryTooLow(-1, charging = false))
    }

    @Test
    fun `children too old to understand the command are recognised as such`() {
        assertFalse(LiveTracking.isSupported(0)) // legacy child, no version reported
        assertFalse(LiveTracking.isSupported(LiveTracking.MIN_CHILD_VERSION - 1))
        assertTrue(LiveTracking.isSupported(LiveTracking.MIN_CHILD_VERSION))
    }

    @Test
    fun `a live session cannot outlast the parent's patience by more than the command TTL`() {
        // A session that starts long after everybody stopped looking is a phone burning its
        // battery for nobody, so the command has a life of its own.
        assertFalse(RemoteAction.expired(RemoteAction.LIVE_TRACKING, issuedAtMs = 0, nowMs = 60_000))
        assertTrue(
            RemoteAction.expired(
                RemoteAction.LIVE_TRACKING,
                issuedAtMs = 0,
                nowMs = RemoteAction.LIVE_TRACKING_TTL_MS + 1,
            ),
        )
    }
}

/** The adaptive throttle: a session slows down as the battery falls rather than hitting a cliff. */
class LiveTrackingThrottleTest {

    @Test
    fun `a comfortable battery runs at full rate`() {
        assertEquals(LiveTracking.SAMPLE_INTERVAL_MS, LiveTracking.sampleIntervalMs(100, charging = false))
        assertEquals(LiveTracking.SAMPLE_INTERVAL_MS, LiveTracking.sampleIntervalMs(41, charging = false))
        assertEquals(
            LiveTracking.SAMPLE_INTERVAL_MS,
            LiveTracking.sampleIntervalMs(LiveTracking.THROTTLE_FROM_PERCENT, charging = false),
        )
    }

    @Test
    fun `charging never throttles, and neither does an unknown level`() {
        assertEquals(LiveTracking.SAMPLE_INTERVAL_MS, LiveTracking.sampleIntervalMs(5, charging = true))
        assertEquals(LiveTracking.SAMPLE_INTERVAL_MS, LiveTracking.sampleIntervalMs(-1, charging = false))
    }

    @Test
    fun `the interval stretches monotonically as the battery falls`() {
        val intervals = (LiveTracking.BATTERY_FLOOR_PERCENT..LiveTracking.THROTTLE_FROM_PERCENT)
            .map { LiveTracking.sampleIntervalMs(it, charging = false) }
        // Read from the floor upwards, each step must be no slower than the one below it.
        assertEquals(intervals.sortedDescending(), intervals)
        assertEquals(LiveTracking.MAX_SAMPLE_INTERVAL_MS, intervals.first())
        assertEquals(LiveTracking.SAMPLE_INTERVAL_MS, intervals.last())
    }

    @Test
    fun `halfway down the ramp is halfway between the two rates`() {
        val mid = (LiveTracking.THROTTLE_FROM_PERCENT + LiveTracking.BATTERY_FLOOR_PERCENT) / 2
        val expected = (LiveTracking.SAMPLE_INTERVAL_MS + LiveTracking.MAX_SAMPLE_INTERVAL_MS) / 2
        val actual = LiveTracking.sampleIntervalMs(mid, charging = false)
        assertTrue(kotlin.math.abs(actual - expected) < 15_000L) { "expected ~$expected, got $actual" }
    }

    @Test
    fun `the throttle never runs past the floor where the session stops`() {
        // Below the floor the session is over; the interval is still defined and still bounded.
        val below = LiveTracking.sampleIntervalMs(LiveTracking.BATTERY_FLOOR_PERCENT - 5, charging = false)
        assertEquals(LiveTracking.MAX_SAMPLE_INTERVAL_MS, below)
        assertTrue(LiveTracking.batteryTooLow(LiveTracking.BATTERY_FLOOR_PERCENT - 1, charging = false))
    }

    @Test
    fun `publishing always carries at least a couple of fixes`() {
        assertEquals(LiveTracking.PUBLISH_INTERVAL_MS, LiveTracking.publishIntervalMs(LiveTracking.SAMPLE_INTERVAL_MS))
        assertEquals(
            LiveTracking.MAX_SAMPLE_INTERVAL_MS * 2,
            LiveTracking.publishIntervalMs(LiveTracking.MAX_SAMPLE_INTERVAL_MS),
        )
    }
}
