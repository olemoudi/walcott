package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parent letting a child's phone install anything for a while, from their own phone.
 *
 * For setting a phone up, when a child has a dozen apps to install. What only a device can show
 * is the part a family would notice: the OS actually lifts the block, an app installed in the
 * window is left alone by the install guard rather than quarantined the moment the window shuts,
 * and "close now" really puts the block back.
 */
class RemoteInstallWindowScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    @Test
    fun `installs opened from the parent's phone stay installed, and closing puts the block back`() {
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = setOf("installs")))
        awaitDevice("the install block armed") { device.installBlocked() }

        val openId = parent.sendCommand(deviceId, RemoteAction.ALLOW_INSTALLS, "30")
        val opened = parent.awaitAck(openId)
        assertTrue(opened.ok, "the window should open: ${opened.detail}")
        assertEquals(RemoteAction.DETAIL_INSTALLS_OPEN, opened.detail)
        awaitDevice("the install block lifted by the parent") { !device.installBlocked() }
        // The phone reports the window, which is what the parent's "close now" card reads.
        val reported = childEventuallyReports { it.installExemptionUntilMs > System.currentTimeMillis() }
        assertTrue(
            reported.installExemptionUntilMs <= System.currentTimeMillis() + 31 * 60_000L,
            "a thirty-minute window reported as ending later than that",
        )

        // Installed while the parent held the door open: it is the family's.
        app = installFixtureApp()
        assertDeviceNever("the app installed in the window quarantined", windowMs = 20_000) {
            device.isSuspended(app)
        }

        // "Close now" is REAPPLY_POLICY, which every build obeys.
        val closeId = parent.sendCommand(deviceId, RemoteAction.REAPPLY_POLICY)
        assertTrue(parent.awaitAck(closeId).ok, "closing should be acknowledged")
        awaitDevice("the install block back after closing") { device.installBlocked() }
        // And the app is still the family's once the window has shut.
        assertDeviceNever("the app quarantined once the window closed", windowMs = 20_000) {
            device.isSuspended(app)
        }
        assertTrue(device.isInstalled(app), "the app installed in the window was removed")
    }

    @Test
    fun `an allow that took longer to arrive than it asked for opens nothing`() {
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = setOf("installs")))
        awaitDevice("the install block armed") { device.installBlocked() }

        val staleId = parent.sendCommand(
            deviceId, RemoteAction.ALLOW_INSTALLS, "30",
            issuedAtMs = System.currentTimeMillis() - 40 * 60_000L,
        )
        val ack = parent.awaitAck(staleId)
        assertFalse(ack.ok, "a forty-minute-old half hour should not open anything: ${ack.detail}")
        assertDeviceNever("the block lifted by a stale command") { !device.installBlocked() }
    }
}
