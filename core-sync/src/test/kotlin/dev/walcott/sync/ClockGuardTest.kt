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
    fun `a positive skew on a message that is not our own echo proves nothing`() {
        // A replayed message's server timestamp is old, so local-ahead is expected — not tamper.
        assertNull(ClockGuard.measuredSkew(2 * hour))
        assertNull(ClockGuard.measuredSkew(0L))
    }

    @Test
    fun `a large negative skew is replay-proof tampering on any message`() {
        // The server already saw a later time than the local clock shows: clock moved back.
        assertEquals(-2 * hour, ClockGuard.measuredSkew(-2 * hour))
    }

    @Test
    fun `a small negative skew on a possibly-replayed message is ignored`() {
        assertNull(ClockGuard.measuredSkew(-ClockGuard.TAMPER_THRESHOLD_MS + 1))
    }

    // --- Reading the clock off an echo of our own publish ---

    @Test
    fun `the echo we are waiting for proves skew in both directions`() {
        // Measured against the clock as it read AT the publish, so delivery latency is not
        // mistaken for drift: published at 1_005_000 local, server stamped second 1_000.
        assertEquals(
            5_000L,
            ClockGuard.skewFromOwnEcho(
                awaitedNonce = 42, publishedAtLocalMs = 1_005_000, echoNonce = 42, serverTimeSec = 1_000,
            ),
        )
        assertEquals(
            -5_000L,
            ClockGuard.skewFromOwnEcho(
                awaitedNonce = 42, publishedAtLocalMs = 995_000, echoNonce = 42, serverTimeSec = 1_000,
            ),
        )
    }

    @Test
    fun `an OLDER publish of ours coming back after a reconnect proves nothing`() {
        // The bug this exists for: a device off the socket for 21 minutes keeps publishing over
        // HTTP, and on reconnect the server hands its own 21-minute-old message back. Same
        // device id, timestamp from before the outage — read as skew it says "21 minutes
        // ahead", which alerts the parent and fails the child's phone closed over a bad Wi-Fi.
        val twentyOneMinutes = 21 * 60 * 1000L
        val now = 5_000_000L
        assertNull(
            ClockGuard.skewFromOwnEcho(
                awaitedNonce = 99, // the publish we are actually waiting for
                publishedAtLocalMs = now,
                echoNonce = 42, // an older one of ours, replayed
                serverTimeSec = (now - twentyOneMinutes) / 1000,
            ),
        )
    }

    @Test
    fun `an echo from a build that does not stamp its publishes proves nothing`() {
        assertNull(
            ClockGuard.skewFromOwnEcho(
                awaitedNonce = 0, publishedAtLocalMs = 1_000_000, echoNonce = 0, serverTimeSec = 1_000,
            ),
        )
    }

    @Test
    fun `a real forward-set clock is still caught on the paired echo`() {
        // What the guard is for, and what the replay fix must not cost: the child winds the
        // clock two hours forward, publishes, and its own echo comes straight back.
        val skew = ClockGuard.skewFromOwnEcho(
            awaitedNonce = 7,
            publishedAtLocalMs = 10_000_000 + 2 * hour,
            echoNonce = 7,
            serverTimeSec = 10_000,
        )
        assertEquals(2 * hour, skew)
        assertTrue(ClockGuard.isTampered(skew!!))
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

    // --- the phone watching its own clock ---

    private val minute = 60_000L
    private fun anchor(verified: Boolean) = ClockGuard.Anchor(wallMs = 1_000_000L, elapsedMs = 5_000L, bootCount = 7, verified = verified)

    @Test
    fun `a clock moved forward offline shows as a jump`() {
        // An hour of monotonic time passed, and the wall clock says ten hours did.
        val jump = ClockGuard.localJumpMs(anchor(true), 1_000_000L + 600 * minute, 5_000L + 60 * minute, 7)
        assertEquals(540 * minute, jump)
    }

    @Test
    fun `a clock moved back shows as a negative jump`() {
        val jump = ClockGuard.localJumpMs(anchor(true), 1_000_000L - 30 * minute, 5_000L + minute, 7)
        assertEquals(-31 * minute, jump)
    }

    @Test
    fun `time passing is not a jump`() {
        assertEquals(0L, ClockGuard.localJumpMs(anchor(true), 1_000_000L + 90 * minute, 5_000L + 90 * minute, 7))
    }

    @Test
    fun `another boot has nothing to compare against`() {
        assertNull(ClockGuard.localJumpMs(anchor(true), 1_000_000L + 600 * minute, 1_000L, bootCount = 8))
        assertNull(ClockGuard.localJumpMs(null, 1L, 1L, 7))
        assertNull(ClockGuard.localJumpMs(anchor(true), 1L, 1L, bootCount = -1))
    }

    @Test
    fun `a jump from a verified clock is tampering whatever the time setting`() {
        assertTrue(ClockGuard.jumpIsTampering(20 * minute, anchorVerified = true, autoTimeOn = true))
        assertTrue(ClockGuard.jumpIsTampering(-20 * minute, anchorVerified = true, autoTimeOn = false))
    }

    @Test
    fun `network time correcting an unverified clock is not tampering`() {
        // The false positive this rule exists to avoid: every limited app closed because the
        // phone fixed its own clock.
        assertFalse(ClockGuard.jumpIsTampering(40 * minute, anchorVerified = false, autoTimeOn = true))
    }

    @Test
    fun `a hand on an unverified clock is tampering`() {
        assertTrue(ClockGuard.jumpIsTampering(40 * minute, anchorVerified = false, autoTimeOn = false))
    }

    @Test
    fun `small jumps are never tampering`() {
        assertFalse(ClockGuard.jumpIsTampering(14 * minute, anchorVerified = true, autoTimeOn = false))
    }

    @Test
    fun `the effective skew is the worse of the two, sign kept`() {
        assertEquals(-40 * minute, ClockGuard.effectiveSkew(relaySkewMs = 2 * minute, localDriftMs = -40 * minute))
        assertEquals(30 * minute, ClockGuard.effectiveSkew(relaySkewMs = 30 * minute, localDriftMs = 0))
    }
}
