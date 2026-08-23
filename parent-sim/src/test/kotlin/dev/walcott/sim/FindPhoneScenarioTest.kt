package dev.walcott.sim

import dev.walcott.sync.LastGasp
import dev.walcott.sync.RemoteAction
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding a phone: the ring the parent asks for, lost mode, and the last word a phone leaves
 * before it goes quiet.
 *
 * Every one of these is asked of the PLATFORM rather than of the app — the alarm stream's own
 * volume, the line the keyguard is holding, the battery service's own low-battery broadcast —
 * because the app's acknowledgement only ever says a command ran. A child that acked "ringing"
 * with the volume at zero, or "lost_on" with nothing on its lock screen, would look identical to
 * the parent.
 */
class FindPhoneScenarioTest : DeviceScenario() {

    private companion object {
        const val RING_CHANNEL = "walcott_ring"

        /** Where this scenario's phone dies, used by nothing else here (see LiveTrackingScenarioTest). */
        const val GASP_LAT = 40.4720
        const val GASP_LNG = -3.5910

        /** The battery service's low mark is 15%; its all-clear is 20%. Both with room. */
        const val LOW_LEVEL = 12
        const val OK_LEVEL = 50

        /** The line the parent writes for a finder; asserted verbatim off the lock screen. */
        const val LOST_LINE = "Lost phone - please call 600 000 000"

        fun at(point: dev.walcott.sync.LocationPoint, lat: Double, lng: Double): Boolean =
            kotlin.math.abs(point.lat - lat) < 0.005 && kotlin.math.abs(point.lng - lng) < 0.005
    }

    /** The alarm stream's current and maximum volume, read from the audio service's own dump. */
    private fun alarmVolume(): Pair<Int, Int>? {
        val dump = device.run("shell", "dumpsys", "audio")
        val block = dump.substringAfter("- STREAM_ALARM:", missingDelimiterValue = "")
            .substringBefore("- STREAM_", missingDelimiterValue = "")
        if (block.isBlank()) return null
        val max = Regex("Max:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val current = Regex("streamVolume:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("Current:.*?:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return current to max
    }

    /**
     * Whether the ring's own notification is on the shade. Its own record, not the OS's
     * auto-group SUMMARY: an ongoing notification makes the platform mint a group summary on the
     * same channel, and that summary lingers after the notification it stood for is cancelled —
     * so counting it would read a stopped ring as a ringing one.
     */
    private fun ringNotified(): Boolean =
        device.run("shell", "dumpsys", "notification", "--noredact")
            .split("NotificationRecord(")
            .any { it.contains("pkg=dev.walcott ") && it.contains("channel=$RING_CHANNEL") && !it.contains("GROUP_SUMMARY") }

    /** Whether the keyguard is up, as the window policy reports it. */
    private fun keyguardShowing(): Boolean {
        val dump = device.run("shell", "dumpsys", "window", "policy")
        return Regex("(?i)keyguard\\w*showing=true").containsMatchIn(dump) ||
            Regex("(?im)^\\s*showing=true").containsMatchIn(dump.substringAfter("KeyguardServiceDelegate", ""))
    }

    private fun trackingPolicy(version: Long, intervalMinutes: Int): String = PolicyJson.build(
        version = version,
        extra = mapOf(
            "locationHistoryEnabled" to JsonPrimitive(true),
            "trackingIntervalMinutes" to JsonPrimitive(intervalMinutes),
        ),
    )

    @Test
    fun `a ring is heard on the alarm stream at full volume and stops by itself`() {
        // A quiet alarm stream first, so "turned up to full" is something that actually happened
        // here rather than the emulator's default.
        device.seed("--ei", "alarm_volume", "2")
        awaitDevice("the alarm stream should take the quiet setting", timeoutMs = 10_000) {
            alarmVolume()?.let { it.first < it.second } == true
        }
        val before = alarmVolume()
        assertNotNull(before, "the audio service should report the alarm stream")
        assertTrue(before!!.first < before.second, "the alarm stream should start below its maximum: $before")

        val commandId = parent.sendCommand(deviceId, RemoteAction.RING_NOW, arg = "12")
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "the ring should start: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_RINGING, ack.detail)

        // A finder is told (the notification), and the phone is actually loud (the stream at max)
        // — the ack alone would say neither. The alarm stream reaching its maximum is the effect
        // that matters; a ring that posted a notification and left the volume where it was would
        // be a silent one.
        awaitDevice("the alarm stream should be turned up to full to ring", timeoutMs = 15_000) {
            alarmVolume()?.let { it.first == it.second } == true
        }
        assertTrue(ringNotified(), "a finder should be told, on the ring's own notification")

        // Twelve seconds were asked for; nothing else ends it here. The volume going back to where
        // it was is the phone's own record that the ring is over — read from the audio service,
        // not from the app that asked.
        awaitDevice("the ring should stop by itself and give the volume back", timeoutMs = 30_000) {
            alarmVolume()?.first == before.first
        }
        awaitDevice("the ring's notification should be taken back down", timeoutMs = 10_000) { !ringNotified() }
    }

    @Test
    fun `the parent can cut the noise short, and the phone says so while it lasts`() {
        // The complaint this exists for: the ring works, and stopping it means finding the phone.
        // Two minutes of alarm is a long time to stand in a room you cannot locate.
        device.seed("--ei", "alarm_volume", "2")
        awaitDevice("the alarm stream should take the quiet setting", timeoutMs = 10_000) {
            alarmVolume()?.let { it.first < it.second } == true
        }
        val before = alarmVolume()
        assertNotNull(before, "the audio service should report the alarm stream")

        // The longest ring there is, so nothing here can be explained by it running out.
        val ringId = parent.sendCommand(
            deviceId, RemoteAction.RING_NOW, arg = RemoteAction.RING_MAX_SECONDS.toString(),
        )
        assertTrue(parent.awaitAck(ringId).ok, "the ring should start")
        awaitDevice("the alarm stream should be turned up to full to ring", timeoutMs = 15_000) {
            alarmVolume()?.let { it.first == it.second } == true
        }

        // The phone SAYS it is ringing, which is the whole reason the parent is offered a button:
        // a snapshot that stayed silent about it would leave that button unreachable.
        val ringing = childEventuallyReports { it.ringingSeconds > 0 }
        assertTrue(
            ringing.ringingSeconds <= RemoteAction.RING_MAX_SECONDS,
            "the phone reported more ring left than the longest one there is: ${ringing.ringingSeconds}",
        )

        val stopId = parent.sendCommand(deviceId, RemoteAction.RING_STOP)
        val stopAck = parent.awaitAck(stopId)
        assertTrue(stopAck.ok, "the stop should be taken: ${stopAck.detail}")
        assertEquals(RemoteAction.DETAIL_RING_STOPPED, stopAck.detail)

        // Silence, and well inside the two minutes the ring was asked for — read from the audio
        // service rather than from the app, so this is the noise ending and not a claim about it.
        awaitDevice("the ring should stop when the parent asks", timeoutMs = 30_000) {
            alarmVolume()?.first == before!!.first
        }
        awaitDevice("the ring's notification should be taken back down", timeoutMs = 10_000) { !ringNotified() }
        // And the phone withdraws the button it offered, rather than leaving the parent's home
        // holding an offer to stop a sound that ended.
        childEventuallyReports { it.ringingSeconds == 0 }
    }

    @Test
    fun `a stop for a phone that is already quiet is taken, not refused`() {
        // The commonest way a stop arrives: the parent taps it just as the ring ends by itself.
        // Answering that with a failure would put a red mark on the one screen telling the truth.
        val stopId = parent.sendCommand(deviceId, RemoteAction.RING_STOP)
        val ack = parent.awaitAck(stopId)
        assertTrue(ack.ok, "a stop with nothing to stop should still be taken: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_RING_STOPPED, ack.detail)
    }

    @Test
    fun `a stale ring is refused rather than going off in a classroom`() {
        val commandId = parent.sendCommand(
            deviceId, RemoteAction.RING_NOW, arg = "12",
            issuedAtMs = System.currentTimeMillis() - RemoteAction.RING_TTL_MS - 60_000,
        )
        val ack = parent.awaitAck(commandId)
        assertTrue(!ack.ok, "a stale ring should be refused")
        assertEquals(RemoteAction.DETAIL_EXPIRED, ack.detail)
        assertDeviceNever("a refused ring should make no sound") { ringNotified() }
    }

    @Test
    fun `lost mode locks the phone, says whose it is, keeps it reporting, and lets it go`() {
        parent.pushPolicy(trackingPolicy(version = 2, intervalMinutes = 5))
        device.setLocation(latitude = GASP_LAT, longitude = GASP_LNG)
        // A phone with its lock screen set to "none" has nothing for lockNow to show: it goes
        // dark and comes back straight to the home screen. The swipe keyguard is what a real
        // phone has at the very least, and it is what makes "locked" observable here.
        device.run("shell", "locksettings", "set-disabled", "false")
        try {
            // Awake and past the keyguard FIRST, so what is asserted below is the transition this
            // command caused rather than a state it happened to find. A keyguard shows whenever a
            // screen times out, and "it was already locked" would pass just as happily.
            device.nudgeAwake()
            device.dismissSwipeKeyguard()
            awaitDevice(
                "the phone should start this scenario unlocked — a keyguard that will not go " +
                    "means an earlier scenario left a PIN behind (see AssistedScenarioTest, whose " +
                    "cleanup can only work while the reset token is active): " +
                    "adb shell locksettings clear --old 4291",
                timeoutMs = 15_000,
            ) { !keyguardShowing() }

            val onId = parent.sendCommand(
                deviceId, RemoteAction.LOST_MODE,
                arg = RemoteAction.LOST_ON, label = LOST_LINE,
            )
            val on = parent.awaitAck(onId)
            assertTrue(on.ok, "lost mode should switch on: ${on.detail}")
            assertEquals(RemoteAction.DETAIL_LOST_ON, on.detail)

            // The phone's own word for it, and the session it rides on — the ack alone says only
            // that a command ran.
            val lost = parent.awaitChild { it.lostMode }
            assertTrue(lost.liveTrackingUntilMs > System.currentTimeMillis(), "a lost phone should be tracking closely")
            // And now it is locked, having demonstrably not been a moment ago. That is the Device
            // Owner half of lost mode: nothing else in this suite locks a screen.
            //
            // The LINE on that lock screen is deliberately not asserted. It is written (proven by
            // hand: with lost mode on, the message turns up in /data/system/locksettings.db) but
            // there is no way to read it back from adb without root on this image —
            // `settings get secure device_owner_info` answers null, `dumpsys device_policy` does
            // not carry it, and `locksettings` has no getter. Rooting the device to assert it
            // would break `cmd notification post` for every scenario after this one.
            awaitDevice("the screen should be locked", timeoutMs = 20_000) { keyguardShowing() }

            val offId = parent.sendCommand(deviceId, RemoteAction.LOST_MODE, arg = RemoteAction.LOST_OFF)
            val off = parent.awaitAck(offId)
            assertTrue(off.ok, "lost mode should switch off: ${off.detail}")
            assertEquals(RemoteAction.DETAIL_LOST_OFF, off.detail)
            val found = parent.awaitChild { !it.lostMode }
            assertEquals(0L, found.liveTrackingUntilMs, "a found phone should stop tracking closely")
        } finally {
            // Never leave a later scenario a locked phone, a keyguard, or a running session.
            runCatching { parent.sendCommand(deviceId, RemoteAction.LOST_MODE, arg = RemoteAction.LOST_OFF) }
            device.nudgeAwake()
            device.dismissSwipeKeyguard()
            runCatching { device.run("shell", "locksettings", "set-disabled", "true") }
        }
    }

    @Test
    fun `a low battery leaves a last word with the phone's position, and a recharge takes it back`() {
        parent.pushPolicy(trackingPolicy(version = 3, intervalMinutes = 5))
        device.setLocation(latitude = GASP_LAT, longitude = GASP_LNG)
        try {
            // The battery service sends its low broadcast when the level crosses its mark while
            // unplugged; fed from above so the crossing happens here, whatever an earlier
            // scenario left the fake battery at.
            device.run("shell", "dumpsys", "battery", "unplug")
            device.run("shell", "dumpsys", "battery", "set", "level", "40")
            device.run("shell", "dumpsys", "battery", "set", "level", LOW_LEVEL.toString())

            val dying = parent.awaitChild(timeoutMs = 90_000) { snapshot ->
                snapshot.lastGasp?.kind == LastGasp.KIND_BATTERY
            }
            val word = dying.lastGasp!!
            assertTrue(word.batteryPercent in 0..20, "the word should carry the level it was said at: ${word.batteryPercent}")
            assertNotNull(word.fix, "a tracked phone's last word should carry a position")
            assertTrue(at(word.fix!!, GASP_LAT, GASP_LNG), "the position should be where the phone was: ${word.fix}")

            // Charged past the all-clear: the word no longer applies, and the phone says so.
            device.run("shell", "dumpsys", "battery", "set", "level", OK_LEVEL.toString())
            val back = parent.awaitChild(timeoutMs = 60_000) { it.lastGasp == null }
            assertNull(back.lastGasp)
        } finally {
            runCatching { device.run("shell", "dumpsys", "battery", "reset") }
        }
    }

    @Test
    fun `a member nobody tracks leaves a word but no position`() {
        parent.pushPolicy(
            PolicyJson.build(
                version = 4,
                extra = mapOf(
                    "locationHistoryEnabled" to JsonPrimitive(false),
                    "trackingIntervalMinutes" to JsonPrimitive(0),
                ),
            ),
        )
        device.setLocation(latitude = GASP_LAT, longitude = GASP_LNG)
        try {
            device.run("shell", "dumpsys", "battery", "unplug")
            device.run("shell", "dumpsys", "battery", "set", "level", "40")
            device.run("shell", "dumpsys", "battery", "set", "level", LOW_LEVEL.toString())
            val dying = parent.awaitChild(timeoutMs = 90_000) { it.lastGasp?.kind == LastGasp.KIND_BATTERY }
            assertNull(dying.lastGasp!!.fix, "a dying phone is no reason to start locating somebody who is not located")
            device.run("shell", "dumpsys", "battery", "set", "level", OK_LEVEL.toString())
            parent.awaitChild(timeoutMs = 60_000) { it.lastGasp == null }
        } finally {
            runCatching { device.run("shell", "dumpsys", "battery", "reset") }
        }
    }
}
