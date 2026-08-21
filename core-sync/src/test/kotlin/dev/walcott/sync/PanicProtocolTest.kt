package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The twelve hours, and what they are counted on.
 *
 * The counter advances for a notice the RELAY took and for nothing else, so almost everything
 * here is about the difference between the phone's clock and the relay's: one decides when to
 * try, the other decides whether trying counted.
 */
class PanicProtocolTest {

    private val start = 1_700_000_000L
    private val hour = PanicProtocol.CHECKPOINT_INTERVAL_SEC
    private val nowMs = 1_700_000_000_000L

    private fun request(
        checkpoints: Int = 0,
        lastCheckpointSec: Long = start,
        lastNoticeAtMs: Long = nowMs,
    ) = PanicRequest(
        "req",
        startedAtSec = start,
        lastCheckpointSec = lastCheckpointSec,
        checkpoints = checkpoints,
        lastNoticeAtMs = lastNoticeAtMs,
    )

    @Test
    fun `a notice the relay stamps an hour on counts`() {
        assertTrue(PanicProtocol.banks(request(), start + hour))
        assertTrue(PanicProtocol.banks(request(), start + hour + 5 * 60))
    }

    @Test
    fun `a notice the relay stamps early does not`() {
        // The whole anti-tamper story: a phone whose clock was moved forward wakes early, sends
        // early, and buys nothing at all. Twelve hours cannot be fiddled down to twelve minutes.
        assertFalse(PanicProtocol.banks(request(), start))
        assertFalse(PanicProtocol.banks(request(), start + hour / 2))
        assertFalse(PanicProtocol.banks(request(), start + hour - PanicProtocol.toleranceSec(hour) - 1))
    }

    @Test
    fun `a minute of slack per hour absorbs ordinary clock skew`() {
        // A phone and a relay disagreeing by seconds is normal; refusing a notice for that would
        // cost the child an hour every time it happened.
        assertEquals(60L, PanicProtocol.toleranceSec(hour))
        assertTrue(PanicProtocol.banks(request(), start + hour - 60))
    }

    @Test
    fun `the slack scales with a compressed hour instead of swallowing it`() {
        // An end-to-end run makes an hour ten seconds. A flat minute of slack there would wave
        // every notice through and quietly stop testing the spacing at all.
        assertEquals(1L, PanicProtocol.toleranceSec(10))
        assertFalse(PanicProtocol.banks(request(), start + 5, intervalSec = 10))
        assertTrue(PanicProtocol.banks(request(), start + 10, intervalSec = 10))
    }

    @Test
    fun `an early notice reschedules on what the relay says is left`() {
        // Not on the phone's clock, which has just been shown to be wrong.
        val soFar = request()
        assertEquals(hour * 1000 / 2, PanicProtocol.sendAgainInMs(soFar, start + hour / 2))
        // Never zero, so a wrong clock cannot spin this into a publish loop.
        assertEquals(1_000L, PanicProtocol.sendAgainInMs(soFar, start + hour))
        // And never more than one whole interval, whatever the clock claims.
        assertEquals(hour * 1000, PanicProtocol.sendAgainInMs(soFar, start - 10 * hour))
    }

    @Test
    fun `a landed notice re-anchors on the relay's stamp, not on a grid`() {
        val next = PanicProtocol.withCheckpoint(request(), start + hour + 90, sentAtMs = nowMs + 1_000)
        assertEquals(1, next.checkpoints)
        assertEquals(start + hour + 90, next.lastCheckpointSec)
        assertEquals(nowMs + 1_000, next.lastNoticeAtMs)
        // The next hour is counted from the notice that actually went out.
        assertTrue(PanicProtocol.banks(next, start + 2 * hour + 90))
        assertFalse(PanicProtocol.banks(next, start + 2 * hour))
    }

    @Test
    fun `twelve notices span twelve hours`() {
        var current = request()
        var serverNow = start
        repeat(PanicProtocol.REQUIRED_CHECKPOINTS) {
            serverNow += hour
            assertTrue(PanicProtocol.banks(current, serverNow), "notice ${it + 1} should have counted")
            current = PanicProtocol.withCheckpoint(current, serverNow, sentAtMs = nowMs)
        }
        assertTrue(PanicProtocol.earned(current))
        assertEquals(12 * 60 * 60L, serverNow - start)
    }

    @Test
    fun `eleven notices are not twelve`() {
        assertFalse(PanicProtocol.earned(request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS - 1)))
        assertTrue(PanicProtocol.earned(request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS)))
    }

    @Test
    fun `the twelfth notice buys three minutes, not the release`() {
        // The loudest alert of the twelve used to be the last thing that happened before the
        // device let itself go: a parent reading it had already lost.
        val done = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS)
        assertFalse(PanicProtocol.releaseDue(done, nowMs))
        assertFalse(PanicProtocol.releaseDue(done, nowMs + PanicProtocol.FINAL_GRACE_MS - 1))
        assertTrue(PanicProtocol.releaseDue(done, nowMs + PanicProtocol.FINAL_GRACE_MS))
    }

    @Test
    fun `nothing releases before all twelve are in`() {
        val nearly = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS - 1)
        assertFalse(PanicProtocol.releaseDue(nearly, nowMs + 30 * 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `a phone that was switched off comes back to an overdue alarm, not a lost countdown`() {
        // Being off is not a connectivity failure — nothing was attempted, so nothing failed. The
        // wake-up is simply in the past, which fires at once and can only LENGTHEN the window.
        val current = request(checkpoints = 4, lastNoticeAtMs = nowMs)
        val wakeUp = PanicProtocol.nextWakeUpAtMs(current)
        assertEquals(nowMs + hour * 1000, wakeUp)
        assertTrue(wakeUp < nowMs + 6 * 60 * 60 * 1000L, "a five-hour outage leaves this overdue")
        // And the relay's clock then confirms the hour really passed, so it banks.
        assertTrue(PanicProtocol.banks(current, start + 5 * 60 * 60))
    }

    @Test
    fun `an earned request wakes up for its release, not for another notice`() {
        val done = request(checkpoints = PanicProtocol.REQUIRED_CHECKPOINTS)
        assertEquals(nowMs + PanicProtocol.FINAL_GRACE_MS, PanicProtocol.nextWakeUpAtMs(done))
    }

    @Test
    fun `the retry ladder is thirty seconds, a minute and three minutes`() {
        assertEquals(listOf(30_000L, 60_000L, 180_000L), PanicProtocol.retryDelaysMs(hour))
        // Compressed with the rest of the clock, and never collapsed into the same instant.
        assertEquals(listOf(1_000L, 1_000L, 1_000L), PanicProtocol.retryDelaysMs(10))
        assertEquals(3, PanicProtocol.retryDelaysMs(hour).size)
    }

    @Test
    fun `the final pause is compressed too, and never shrinks past being useful`() {
        assertEquals(PanicProtocol.FINAL_GRACE_MS, PanicProtocol.finalGraceMs(hour))
        // A refusal has to travel from a parent's tap to a phone that then has to notice. Scaled
        // naively, a ten-second hour would leave half a second for all of it — which is not a
        // smaller version of the pause, it is the absence of the only thing it is for.
        assertEquals(PanicProtocol.MIN_FINAL_GRACE_MS, PanicProtocol.finalGraceMs(10))
        assertTrue(PanicProtocol.MIN_FINAL_GRACE_MS < PanicProtocol.FINAL_GRACE_MS)
    }

    @Test
    fun `only a live message proves the channel`() {
        assertTrue(PanicProtocol.provesChannel(nowMs, nowMs / 1000))
        assertTrue(PanicProtocol.provesChannel(nowMs, nowMs / 1000 - 60))
        // A message replayed after a reconnect carries an old server timestamp: it says nothing
        // about connectivity now.
        assertFalse(PanicProtocol.provesChannel(nowMs, nowMs / 1000 - 3 * 60 * 60))
        assertFalse(PanicProtocol.provesChannel(nowMs, nowMs / 1000 + 3 * 60 * 60))
        assertFalse(PanicProtocol.provesChannel(nowMs, 0))
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
    fun `a cooldown anchored at the epoch would block nothing`() {
        // Why the caller must pass a server second it can vouch for rather than a bare cursor.
        assertTrue(
            PanicProtocol.cooldownPassed(PanicProtocol.cooldownUntilSec(0), start),
            "three days after the epoch is not a lockout",
        )
        assertFalse(PanicProtocol.cooldownPassed(PanicProtocol.cooldownUntilSec(start), start))
    }

    @Test
    fun `starting needs a channel that proved itself within the last half hour`() {
        assertTrue(PanicProtocol.channelProven(0))
        assertTrue(PanicProtocol.channelProven(PanicProtocol.START_CHANNEL_FRESH_MS))
        assertFalse(PanicProtocol.channelProven(PanicProtocol.START_CHANNEL_FRESH_MS + 1))
    }

    @Test
    fun `every condition is required to start a request`() {
        fun gate(
            hasActiveRequest: Boolean = false,
            parentSupported: Boolean = true,
            msSinceChannelOk: Long = 0,
            blockedUntilSec: Long = 0,
            serverNowSec: Long = start,
        ) = PanicProtocol.mayStart(
            hasActiveRequest, parentSupported, msSinceChannelOk, blockedUntilSec, serverNowSec,
        )

        assertTrue(gate())
        // One request at a time: starting again would restart the twelve hours, not shorten them.
        assertFalse(gate(hasActiveRequest = true))
        // A parent build that can't display the request would make this a silent escape hatch.
        assertFalse(gate(parentSupported = false))
        // No connectivity, no request — the whole design is "keep proving you can be seen".
        assertFalse(gate(msSinceChannelOk = PanicProtocol.START_CHANNEL_FRESH_MS + 1))
        // A standing refusal.
        assertFalse(gate(blockedUntilSec = start + 1))
    }

    @Test
    fun `a device with no server clock yet may still start a request`() {
        // It could not before, and that was a bug rather than a guard: the cursor is zeroed
        // whenever the family moves relay, and a request used to be anchored on it. The anchor
        // is now the relay's receipt for the opening notice, which cannot be stale — so having
        // heard nothing from the new relay YET is no longer a reason to refuse the one door out
        // of enforcement.
        assertTrue(
            PanicProtocol.mayStart(
                hasActiveRequest = false,
                parentSupported = true,
                msSinceChannelOk = 60_000,
                blockedUntilSec = 0,
                serverNowSec = 0,
            ),
        )
    }

    @Test
    fun `progress and remaining notices follow the delivered notices`() {
        assertEquals(0f, PanicProtocol.progress(request()))
        assertEquals(12, PanicProtocol.remainingCheckpoints(request()))
        assertEquals(0.5f, PanicProtocol.progress(request(checkpoints = 6)))
        assertEquals(6, PanicProtocol.remainingCheckpoints(request(checkpoints = 6)))
        assertEquals(1f, PanicProtocol.progress(request(checkpoints = 99)))
        assertEquals(0, PanicProtocol.remainingCheckpoints(request(checkpoints = 99)))
    }
}
