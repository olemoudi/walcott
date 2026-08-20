package dev.walcott.sync

import dev.walcott.sync.PanicProtocol.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanicProtocolTest {

    private val start = 1_700_000_000L
    private val twoHours = PanicProtocol.CHECKPOINT_INTERVAL_SEC
    private val grace = PanicProtocol.CHECKPOINT_GRACE_SEC

    private fun request(checkpoints: Int = 0, lastCheckpointSec: Long = start) =
        PanicRequest("req", startedAtSec = start, lastCheckpointSec = lastCheckpointSec, checkpoints = checkpoints)

    @Test
    fun `nothing is due before the two-hour mark`() {
        assertEquals(Step.WAIT, PanicProtocol.evaluate(request(), start))
        assertEquals(Step.WAIT, PanicProtocol.evaluate(request(), start + twoHours - 1))
    }

    @Test
    fun `a notice is due at two hours`() {
        assertEquals(Step.CHECKPOINT, PanicProtocol.evaluate(request(), start + twoHours))
    }

    @Test
    fun `a late-but-within-grace notice still counts`() {
        assertEquals(Step.CHECKPOINT, PanicProtocol.evaluate(request(), start + twoHours + grace))
    }

    @Test
    fun `a notice past the grace window voids the request`() {
        assertEquals(Step.EXPIRED, PanicProtocol.evaluate(request(), start + twoHours + grace + 1))
    }

    @Test
    fun `the request expires even when the twelfth notice is the late one`() {
        // Reaching the end doesn't excuse a connectivity failure: the last notice must land too.
        val last = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS - 1)
        assertEquals(Step.EXPIRED, PanicProtocol.evaluate(last, start + twoHours + grace + 1))
    }

    @Test
    fun `the twelfth checkpoint releases the device`() {
        val eleven = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS - 1)
        assertEquals(Step.RELEASE, PanicProtocol.evaluate(eleven, start + twoHours))
    }

    @Test
    fun `twelve checkpoints span twenty-four hours of proven connectivity`() {
        var current = request()
        var now = start
        var steps = 0
        while (true) {
            now += twoHours
            val step = PanicProtocol.evaluate(current, now)
            if (step == Step.RELEASE) break
            assertEquals(Step.CHECKPOINT, step)
            current = PanicProtocol.withCheckpoint(current, now)
            steps++
        }
        assertEquals(PanicProtocol.REQUIRED_CHECKPOINTS - 1, steps)
        assertEquals(24 * 60 * 60L, now - start)
    }

    @Test
    fun `a checkpoint advances the clock and the count`() {
        val next = PanicProtocol.withCheckpoint(request(), start + twoHours)
        assertEquals(1, next.checkpoints)
        assertEquals(start + twoHours, next.lastCheckpointSec)
        // The countdown re-anchors on the notice that was actually sent, not on a fixed grid.
        assertEquals(start + 2 * twoHours, PanicProtocol.dueSec(next))
    }

    @Test
    fun `going silent past a due notice voids the request on the local clock too`() {
        assertFalse(PanicProtocol.expiredOffline((twoHours + grace) * 1000))
        assertTrue(PanicProtocol.expiredOffline((twoHours + grace) * 1000 + 1))
    }

    @Test
    fun `only a live message proves the channel`() {
        val nowMs = 1_700_000_000_000L
        // The device's own echo, seconds old: proof.
        assertTrue(PanicProtocol.provesChannel(nowMs, nowMs / 1000))
        assertTrue(PanicProtocol.provesChannel(nowMs, nowMs / 1000 - 60))
        // A message replayed after a reconnect carries an old server timestamp: it says nothing
        // about connectivity now, and must never pay for a notice the device failed to send.
        assertFalse(PanicProtocol.provesChannel(nowMs, nowMs / 1000 - 3 * 60 * 60))
        // Nor a timestamp from a server that is somehow ahead of us, or a missing one.
        assertFalse(PanicProtocol.provesChannel(nowMs, nowMs / 1000 + 3 * 60 * 60))
        assertFalse(PanicProtocol.provesChannel(nowMs, 0))
    }

    @Test
    fun `an offline stretch cannot be paid for by the replay that follows it`() {
        // Six hours offline: the replayed backlog is stale, so nothing advances; the first live
        // message (the child's own publish on reconnect) lands past the deadline and voids it.
        val request = request()
        val reconnectSec = start + 6 * 60 * 60
        assertFalse(PanicProtocol.provesChannel(reconnectSec * 1000, start + 60 * 60))
        assertEquals(Step.EXPIRED, PanicProtocol.evaluate(request, reconnectSec))
    }

    @Test
    fun `a request that already served its 24 hours is a release, not an expiry`() {
        // What an interrupted release leaves behind: the twelfth notice is banked and published,
        // and the teardown then failed or was killed. However long the device is then away, the
        // countdown it finished must not have to be served again.
        val done = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS)
        assertTrue(PanicProtocol.earned(done))
        assertEquals(Step.RELEASE, PanicProtocol.evaluate(done, start))
        assertEquals(Step.RELEASE, PanicProtocol.evaluate(done, start + twoHours + grace + 1))
        assertEquals(Step.RELEASE, PanicProtocol.evaluate(done, start + 30 * 24 * 60 * 60))
    }

    @Test
    fun `a request one notice short of the end is not earned yet`() {
        // The boundary the case above turns on: eleven banked notices still expire normally.
        val nearly = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS - 1)
        assertFalse(PanicProtocol.earned(nearly))
        assertEquals(Step.EXPIRED, PanicProtocol.evaluate(nearly, start + twoHours + grace + 1))
    }

    @Test
    fun `a denial blocks new requests for three days`() {
        val until = PanicProtocol.cooldownUntilSec(start)
        assertEquals(3 * 24 * 60 * 60L, until - start)
        assertFalse(PanicProtocol.cooldownPassed(until, start))
        assertFalse(PanicProtocol.cooldownPassed(until, until - 1))
        assertTrue(PanicProtocol.cooldownPassed(until, until))
    }

    @Test
    fun `no standing denial means the child may ask`() {
        assertTrue(PanicProtocol.cooldownPassed(blockedUntilSec = 0, serverNowSec = start))
    }

    @Test
    fun `starting needs a channel that proved itself within the last half hour`() {
        assertTrue(PanicProtocol.channelProven(0))
        assertTrue(PanicProtocol.channelProven(PanicProtocol.START_CHANNEL_FRESH_MS))
        // One heartbeat missed is already too stale: the request's server-time anchor comes from
        // that last message, and a stale anchor makes the first notice due in the past.
        assertFalse(PanicProtocol.channelProven(PanicProtocol.START_CHANNEL_FRESH_MS + 1))
    }

    @Test
    fun `every condition is required to start a request`() {
        fun gate(
            hasActiveRequest: Boolean = false,
            parentSupported: Boolean = true,
            msSinceChannelOk: Long = 0,
            blockedUntilSec: Long = 0,
        ) = PanicProtocol.mayStart(
            hasActiveRequest, parentSupported, msSinceChannelOk, blockedUntilSec, serverNowSec = start,
        )

        assertTrue(gate())
        // One request at a time: starting again would restart the 24 hours, not shorten them.
        assertFalse(gate(hasActiveRequest = true))
        // A parent build that can't display the request would make this a silent escape hatch.
        assertFalse(gate(parentSupported = false))
        // No connectivity, no request — the whole design is "keep proving you can be seen".
        assertFalse(gate(msSinceChannelOk = PanicProtocol.START_CHANNEL_FRESH_MS + 1))
        // A standing refusal.
        assertFalse(gate(blockedUntilSec = start + 1))
    }

    @Test
    fun `a device with no server clock yet may not start a request`() {
        // The cursor is zeroed when the family moves relay, and the last proof of the channel
        // predates the move by less than half an hour — so every OTHER condition still says yes.
        // A request anchored at zero has its deadline in January 1970 and is expired by the very
        // next message, which spends the child's one request on a countdown already over.
        assertFalse(
            PanicProtocol.mayStart(
                hasActiveRequest = false,
                parentSupported = true,
                msSinceChannelOk = 60_000,
                blockedUntilSec = 0,
                serverNowSec = 0,
            ),
            "a request with no server anchor must not be allowed to start",
        )
        assertTrue(PanicProtocol.anchored(1L))
        assertFalse(PanicProtocol.anchored(0L))
    }

    @Test
    fun `a request anchored at the epoch would be dead on arrival`() {
        // Why the gate above exists, stated as the behaviour it prevents.
        val stillborn = PanicRequest(id = "x", startedAtSec = 0, lastCheckpointSec = 0)
        assertEquals(PanicProtocol.Step.EXPIRED, PanicProtocol.evaluate(stillborn, start))
    }

    @Test
    fun `a cooldown anchored at the epoch would block nothing`() {
        // Why the caller must pass a server second it can vouch for rather than a bare cursor.
        assertTrue(
            PanicProtocol.cooldownPassed(PanicProtocol.cooldownUntilSec(0), start),
            "three days after the epoch is not a lockout",
        )
        assertFalse(PanicProtocol.cooldownPassed(PanicProtocol.cooldownUntilSec(start), start))
    }

    @Test
    fun `progress and remaining notices follow the proven checkpoints`() {
        assertEquals(0f, PanicProtocol.progress(request()))
        assertEquals(12, PanicProtocol.remainingCheckpoints(request()))
        assertEquals(0.5f, PanicProtocol.progress(request(checkpoints = 6)))
        assertEquals(6, PanicProtocol.remainingCheckpoints(request(checkpoints = 6)))
        assertEquals(1f, PanicProtocol.progress(request(checkpoints = 99)))
        assertEquals(0, PanicProtocol.remainingCheckpoints(request(checkpoints = 99)))
    }
}
