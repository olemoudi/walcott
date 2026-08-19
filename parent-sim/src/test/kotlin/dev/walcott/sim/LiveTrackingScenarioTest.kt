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
 * Two kinds of property, and both need a real device. The ones about STOPPING, because this is
 * the only mode that holds the CPU awake and a session left running by accident is a phone that
 * dies in somebody's bag — and none of the ways it ends (the parent's deadline, the parent's own
 * tap, the battery floor, a restart) can be shown against anything but a device that persists
 * the session. And the one about REPORTING, because everything else here would pass on a child
 * that acknowledged the command and then published the same fix for an hour.
 */
class LiveTrackingScenarioTest : DeviceScenario() {

    private companion object {
        /**
         * How long a moved phone gets to reach the parent.
         *
         * A session samples every minute and publishes every two (see [LiveTracking]), so a move
         * lands within about three. Five leaves room for a fix the emulator is slow to hand over,
         * and none at all for a mode that has stopped reporting — which is what this is for.
         */
        private const val REPORTS_WITHIN_MS = 5 * 60 * 1000L

        /** Where this scenario's phone starts, used by nothing else here. */
        private const val START_LAT = 40.3900
        private const val START_LNG = -3.6600

        /** Where it goes: ~5 km, far past any accuracy blur. */
        private const val MOVED_LAT = 40.4350
        private const val MOVED_LNG = -3.7250

        /** Whether a reported point is the position the device was moved to. */
        private fun at(point: dev.walcott.sync.LocationPoint, lat: Double, lng: Double): Boolean =
            kotlin.math.abs(point.lat - lat) < 0.005 && kotlin.math.abs(point.lng - lng) < 0.005
    }

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
    fun `a running session keeps reporting as the phone moves`() {
        // The gap every other scenario here leaves, and the one a family actually notices. The
        // rest prove the SESSION — acknowledged, running, stopped, extended — and a child that
        // acked the command, took one fix and then went quiet for an hour would pass all of them.
        // That is exactly what "nothing moves" looks like from the other phone.
        //
        // Its own coordinates, unlike every other test here, and that is load-bearing: the
        // device carries a trail from whatever ran before it, so a position any earlier scenario
        // also uses would be satisfied by a point that predates this session entirely.
        device.setLocation(latitude = START_LAT, longitude = START_LNG)
        parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "15")
        parent.awaitChild(timeoutMs = REPORTS_WITHIN_MS) { snapshot ->
            snapshot.liveTrackingUntilMs > System.currentTimeMillis() &&
                snapshot.locations.any { at(it, START_LAT, START_LNG) }
        }

        // Two kilometres away, and then nothing else at all: no locate request, no re-emit, no
        // nudge, no second command. Only the session's own loop can carry this — and it has to
        // come round again to do it, which is the whole point. The first fix proves a session
        // started; this one proves it is still running.
        device.setLocation(latitude = MOVED_LAT, longitude = MOVED_LNG)
        val moved = parent.awaitChild(timeoutMs = REPORTS_WITHIN_MS) { snapshot ->
            snapshot.locations.any { at(it, MOVED_LAT, MOVED_LNG) }
        }
        val fix = moved.locations.last { at(it, MOVED_LAT, MOVED_LNG) }
        assertTrue(fix.epochMs > 0, "a point with no timestamp cannot be placed on a timeline")

        parent.sendCommand(deviceId, RemoteAction.LIVE_TRACKING, arg = "0")
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
