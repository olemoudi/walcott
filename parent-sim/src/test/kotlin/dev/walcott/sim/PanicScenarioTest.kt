package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The emergency release: the one door out of enforcement, and the parent's power to refuse it.
 *
 * This is the most consequential conversation the two devices have. A child who is genuinely in
 * trouble must be able to start it and have it seen; a parent must be able to refuse it and have
 * the refusal actually land. Both halves were unit-tested — [dev.walcott.sync.PanicProtocol] is
 * pure and thoroughly covered — and neither had ever been run across the wire, so the part that
 * had no test was precisely the part that carries it between two phones.
 */
class PanicScenarioTest : DeviceScenario() {

    private companion object {
        /** An "hour" of the countdown, in real seconds, for the scenarios that need it to pass. */
        const val COMPRESSED_HOUR_SECONDS = 10L

        /** Long enough for a notice to come due and its three retries to be exhausted. */
        const val NETWORK_OUTAGE_MS = 25_000L
    }

    @AfterEach
    fun clearAnyRequest() {
        // A refusal leaves a three-day cooldown on the device. Left behind, it would make the
        // next scenario's request fail for a reason that has nothing to do with it.
        runCatching { device.clearPanic() }
    }

    private fun startedRequest(): dev.walcott.sync.PanicRequest {
        device.panicReady()
        device.startPanic()
        val seen = parent.awaitChild { it.panic != null }
        return requireNotNull(seen.panic)
    }

    @Test
    fun `a child's request for release reaches the parent`() {
        val request = startedRequest()
        assertTrue(request.id.isNotBlank(), "a request needs an id the parent can refuse by")
        assertEquals(0, request.checkpoints, "a fresh request has proven nothing yet")
        assertTrue(request.startedAtSec > 0, "the request is anchored to the server's clock")
    }

    @Test
    fun `the parent refuses it, and it dies on the device`() {
        val request = startedRequest()
        val commandId = parent.sendCommand(deviceId, RemoteAction.DENY_PANIC, arg = request.id)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "a refusal should be accepted: ${ack.detail}")
        assertEquals("denied", ack.detail)

        val after = parent.awaitChild { it.panic == null }
        assertNull(after.panic, "the request should be gone from the child")
    }

    @Test
    fun `a refusal aimed at a request the child no longer has is ignored`() {
        // The race this protects against: the child withdraws, asks again, and a refusal issued
        // for the FIRST request arrives afterwards. Punishing the second one would take away a
        // door the child had just re-opened, for a decision the parent never made about it.
        val first = startedRequest()
        device.cancelPanic()
        parent.awaitChild { it.panic == null }

        device.startPanic()
        val second = parent.awaitChild { it.panic != null }.panic
        assertNotNull(second)
        assertFalse(second!!.id == first.id, "the second request should be a new one")

        val commandId = parent.sendCommand(deviceId, RemoteAction.DENY_PANIC, arg = first.id)
        val ack = parent.awaitAck(commandId)
        assertFalse(ack.ok, "a stale refusal should not succeed")
        assertEquals("stale_request", ack.detail)

        val still = childReports { true }
        assertNotNull(still.panic, "the live request was killed by a refusal meant for another")
        assertEquals(second.id, still.panic!!.id)
    }

    @Test
    fun `refusing when there is nothing to refuse says so`() {
        val commandId = parent.sendCommand(deviceId, RemoteAction.DENY_PANIC, arg = "whatever")
        val ack = parent.awaitAck(commandId)
        assertFalse(ack.ok)
        assertEquals("no_request", ack.detail)
    }

    @Test
    fun `a child can withdraw its own request`() {
        startedRequest()
        device.cancelPanic()
        val after = parent.awaitChild { it.panic == null }
        assertNull(after.panic)
    }

    @Test
    fun `a notice that will not go out ends the request where it stands`() {
        // The deal this feature offers, stated exactly: twelve hours of a phone that can be
        // REACHED. A notice is retried three times and then, if it still will not leave the
        // phone, the whole request dies wherever it had got to — which is a thing that can only
        // be tested by taking the network away from a real device mid-countdown.
        device.panicHourSeconds(COMPRESSED_HOUR_SECONDS)
        device.panicReady()
        device.startPanic()
        parent.awaitChild { it.panic != null }
        // At least one notice through first, so what follows is a failure and not a device that
        // was never working.
        parent.awaitChild(timeoutMs = 60_000) { (it.panic?.checkpoints ?: 0) >= 1 }

        // Nothing this phone sends will go out from here on. Told, not silently dropped: being
        // told is the whole basis of the counter, and it is what the retry ladder answers.
        relay.refusePublishes()
        Thread.sleep(NETWORK_OUTAGE_MS)
        relay.acceptPublishes()

        val after = childReports(timeoutMs = 60_000) { it.panic == null }
        assertNull(after.panic, "a request whose notice never went out should be over")
    }

    @Test
    fun `a refused child cannot immediately ask again`() {
        // The cooldown is what stops a refusal being a formality the child can simply re-issue.
        val request = startedRequest()
        parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.DENY_PANIC, arg = request.id))
        parent.awaitChild { it.panic == null }

        device.startPanic()
        parent.assertNoChild(windowMs = 6_000) { it.panic != null }
    }
}
