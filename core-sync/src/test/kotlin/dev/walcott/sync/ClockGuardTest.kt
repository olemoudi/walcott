package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockGuardTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `skew is local minus server, in ms`() {
        assertEquals(5_000L, ClockGuard.skewMs(localNowMs = 1_005_000, serverTimeSec = 1_000))
        assertEquals(-5_000L, ClockGuard.skewMs(localNowMs = 995_000, serverTimeSec = 1_000))
    }

    @Test
    fun `an own fresh echo proves skew in both directions`() {
        assertEquals(2 * hour, ClockGuard.measuredSkew(2 * hour, ownFreshEcho = true))
        assertEquals(-2 * hour, ClockGuard.measuredSkew(-2 * hour, ownFreshEcho = true))
        assertEquals(0L, ClockGuard.measuredSkew(0L, ownFreshEcho = true))
    }

    @Test
    fun `a positive skew on a possibly-replayed message proves nothing`() {
        // A replayed message's server timestamp is old, so local-ahead is expected — not tamper.
        assertNull(ClockGuard.measuredSkew(2 * hour, ownFreshEcho = false))
        assertNull(ClockGuard.measuredSkew(0L, ownFreshEcho = false))
    }

    @Test
    fun `a large negative skew is replay-proof tampering on any message`() {
        // The server already saw a later time than the local clock shows: clock moved back.
        assertEquals(-2 * hour, ClockGuard.measuredSkew(-2 * hour, ownFreshEcho = false))
    }

    @Test
    fun `a small negative skew on a possibly-replayed message is ignored`() {
        assertNull(ClockGuard.measuredSkew(-ClockGuard.TAMPER_THRESHOLD_MS + 1, ownFreshEcho = false))
    }

    @Test
    fun `tampered beyond the threshold in either direction`() {
        assertTrue(ClockGuard.isTampered(ClockGuard.TAMPER_THRESHOLD_MS))
        assertTrue(ClockGuard.isTampered(-ClockGuard.TAMPER_THRESHOLD_MS))
        assertFalse(ClockGuard.isTampered(ClockGuard.TAMPER_THRESHOLD_MS - 1))
        assertFalse(ClockGuard.isTampered(0))
    }

    @Test
    fun `alert is one-shot per outage`() {
        assertTrue(ClockGuard.shouldAlert(2 * hour, alreadyAlerted = false))
        assertFalse(ClockGuard.shouldAlert(2 * hour, alreadyAlerted = true))
        assertFalse(ClockGuard.shouldAlert(0, alreadyAlerted = false))
    }

    @Test
    fun `hysteresis - a skew between the clear and tamper thresholds neither alerts nor clears`() {
        val between = (ClockGuard.CLEAR_THRESHOLD_MS + ClockGuard.TAMPER_THRESHOLD_MS) / 2
        assertFalse(ClockGuard.shouldAlert(between, alreadyAlerted = false))
        assertFalse(ClockGuard.clears(between))
        assertFalse(ClockGuard.clears(-between))
    }

    @Test
    fun `clears once back under the clear threshold`() {
        assertTrue(ClockGuard.clears(0))
        assertTrue(ClockGuard.clears(ClockGuard.CLEAR_THRESHOLD_MS))
        assertTrue(ClockGuard.clears(-ClockGuard.CLEAR_THRESHOLD_MS))
    }
}
