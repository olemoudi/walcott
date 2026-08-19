package dev.walcott.sim

import dev.walcott.sync.LiveTracking
import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Close tracking: the bounded window in which a child reports where it is every minute.
 *
 * The properties worth proving on a real device are the ones about STOPPING, not about starting.
 * This is the only mode that holds the CPU awake, so a session that could be left running by
 * accident is a phone that dies in somebody's bag — and none of the ways it ends (the parent's
 * deadline, the parent's own tap, the battery floor, a restart) can be shown against anything
 * but a device that actually persists the session.
 */
class LiveTrackingScenarioTest : DeviceScenario() {

    @Test
    fun `a close-tracking request is acknowledged and the device says a session is running`() {
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        val commandId = parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "15")
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "close tracking should start: ${ack.detail}")
        assertEquals(LiveTracking.DETAIL_STARTED, ack.detail)

        // The acknowledgement says the command ran; this says the device is actually in the mode.
        // A child that acked and did nothing would look identical to the parent otherwise.
        val running = parent.awaitChild { it.liveTrackingUntilMs > System.currentTimeMillis() }
        val leftMs = running.liveTrackingUntilMs - System.currentTimeMillis()
        assertTrue(leftMs <= 15 * 60_000L) { "reported $leftMs ms left of a 15 minute session" }
    }

    @Test
    fun `the parent can stop a session before its deadline`() {
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "60")
        parent.awaitChild { it.liveTrackingUntilMs > System.currentTimeMillis() }

        val stopId = parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "0")
        val ack = parent.awaitAck(stopId)
        assertTrue(ack.ok, "a stop should be obeyed: ${ack.detail}")
        assertEquals(LiveTracking.DETAIL_STOPPED, ack.detail)
        parent.awaitChild { it.liveTrackingUntilMs == 0L }
    }

    @Test
    fun `extending a running session does not end it`() {
        // The bug this guards: the loop watching the old deadline is cancelled by the new one
        // being written, and its cleanup used to clear the session that had just been started —
        // so asking for more time read as a stop, from the same tap that asked for more.
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "15")
        parent.awaitChild { it.liveTrackingUntilMs > System.currentTimeMillis() }

        parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "120")
        // Comfortably longer than the 15 minutes the first request asked for, so this can only
        // be the second session.
        val extended = parent.awaitChild {
            it.liveTrackingUntilMs - System.currentTimeMillis() > 30 * 60_000L
        }
        assertTrue(extended.liveTrackingUntilMs > System.currentTimeMillis())

        assertDeviceNever("the extended session should not have been cleared") {
            childReports { true }.liveTrackingUntilMs == 0L
        }
        parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "0")
    }

    @Test
    fun `a stale request is refused rather than starting a session nobody is waiting for`() {
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        val commandId = parent.sendCommand(
            deviceId,
            RemoteAction.LIVE_TRACKING,
            arg = "60",
            // Issued well beyond the command's life: a phone that was in a tunnel should come
            // back and get on with things, not burn an hour of GPS for an audience that gave up.
            issuedAtMs = System.currentTimeMillis() - RemoteAction.LIVE_TRACKING_TTL_MS - 60_000L,
        )
        val ack = parent.awaitAck(commandId)
        assertFalse(ack.ok, "a stale close-tracking request should be refused")
        assertEquals(RemoteAction.DETAIL_EXPIRED, ack.detail)
        assertEquals(0L, childReports { true }.liveTrackingUntilMs)
    }
}
