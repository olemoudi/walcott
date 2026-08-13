package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Remote fixes: the parent reaching across and changing something on a phone in another
 * building, and the acknowledgement that says whether it worked.
 *
 * The runner's branches were unit-testable; what was not was the round trip — a command
 * arriving inside a signed snapshot, being applied once and only once, and the answer coming
 * back inside the child's next publish. Exactly-once in particular can only be shown against a
 * child that persists what it has already applied, which is device state.
 */
class RemoteCommandScenarioTest : DeviceScenario() {

    @Test
    fun `a health report comes back with what the device can actually see`() {
        val commandId = parent.sendCommand(deviceId, RemoteAction.DIAGNOSE)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "diagnose should succeed: ${ack.detail}")

        // The report travels as its own message kind, so this also proves that path works.
        val report = parent.awaitDiag()
        assertEquals(deviceId, report.deviceId)
        assertEquals(device.isDeviceOwner(), report.deviceOwner, "the report should not flatter the device")
        assertTrue(report.appVersionCode > 0, "a report should say what build it came from")
    }

    @Test
    fun `re-applying the policy is acknowledged`() {
        val commandId = parent.sendCommand(deviceId, RemoteAction.REAPPLY_POLICY)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "reapply should succeed: ${ack.detail}")
    }

    @Test
    fun `an action this build has never heard of is refused, not fatal`() {
        // Forward compatibility in the other direction: a NEWER parent naming a command an older
        // child does not implement. The child must say so and carry on being a working child.
        val commandId = parent.sendCommand(deviceId, "some_action_from_the_future")
        val ack = parent.awaitAck(commandId)
        assertFalse(ack.ok, "an unknown action should not claim success")
        assertEquals("unsupported", ack.detail)

        // Still alive afterwards.
        val next = parent.sendCommand(deviceId, RemoteAction.REAPPLY_POLICY)
        assertTrue(parent.awaitAck(next).ok)
    }

    @Test
    fun `a command republished in every snapshot is applied exactly once`() {
        // Commands live in the parent's snapshot and that snapshot is re-emitted; without the
        // applied-id ledger on the child, a "remove this app" would run again every fifteen
        // minutes for as long as the parent kept saying it.
        val commandId = parent.sendCommand(deviceId, RemoteAction.DIAGNOSE)
        parent.awaitAck(commandId)
        val reportsAfterFirst = parent.diagReports.size

        repeat(3) { parent.reEmit() }
        Thread.sleep(5_000)
        assertEquals(
            reportsAfterFirst,
            parent.diagReports.size,
            "the command ran again on a re-emit",
        )
    }

    @Test
    fun `a replayed snapshot does not re-run a command already applied`() {
        // Same guarantee, arrived at through the relay repeating itself rather than the parent.
        val before = relay.published(parent.topic).size
        val commandId = parent.sendCommand(deviceId, RemoteAction.DIAGNOSE)
        parent.awaitAck(commandId)
        val commandIndex = relay.published(parent.topic).drop(before).indices.first() + before
        val reportsAfterFirst = parent.diagReports.size

        relay.replay(parent.topic, commandIndex)
        Thread.sleep(5_000)
        assertEquals(reportsAfterFirst, parent.diagReports.size, "a replayed command ran twice")
    }

    @Test
    fun `asking for permissions is acknowledged rather than silently dropped`() {
        val commandId = parent.sendCommand(deviceId, RemoteAction.REQUEST_PERMISSIONS)
        val ack = parent.awaitAck(commandId)
        assertEquals(RemoteAction.REQUEST_PERMISSIONS, ack.action)
    }

    @Test
    fun `a command aimed at another device is left alone`() {
        // Every child on the family topic reads every snapshot. A command addressed to a sibling
        // must not be executed here — and the only way to see that is to watch this device fail
        // to answer for it.
        val commandId = parent.sendCommand("some-other-device", RemoteAction.DIAGNOSE)
        val reportsBefore = parent.diagReports.size
        Thread.sleep(6_000)
        device.publish()
        Thread.sleep(2_000)
        assertEquals(reportsBefore, parent.diagReports.size, "this device answered someone else's command")
        assertTrue(
            parent.childHistory.none { it.lastCommand?.id == commandId },
            "this device acknowledged a command addressed elsewhere",
        )
    }
}
