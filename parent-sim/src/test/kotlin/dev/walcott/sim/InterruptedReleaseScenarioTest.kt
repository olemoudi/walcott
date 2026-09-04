package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A release that dies halfway — the process killed after the handback, with Device Owner still
 * held. That used to be terminal: the device was no longer a child, so no screen offered the
 * release again, and a phone owned by an app that manages nothing needs a factory reset.
 *
 * `am force-stop` cannot produce this on a Device Owner (the system brings the process straight
 * back), so the death is induced from inside by a debug hook, at the one point that matters:
 * everything given back, everything wiped, Device Owner not yet dropped. What is then proven is
 * the resume — the app's next start finishing what was interrupted (see
 * `PanicRelease.finishIfInterrupted`) — and, again, the OS letting go.
 */
@Tag("e2e")
@Tag("destructive")
class InterruptedReleaseScenarioTest : DeviceScenario() {

    @Test
    fun `a release killed before it could give up Device Owner is finished on the next start`() {
        val pkg = installFixtureApp()
        parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                restrictions = setOf("datetime"),
                dailyMinutes = mapOf(pkg to 0),
            ),
        )
        awaitDevice("the app suspended by its rules", timeoutMs = 60_000) { device.isSuspended(pkg) }
        val pidBefore = device.walcottPid()
        assertTrue(pidBefore.isNotBlank(), "the app should be running before the release")

        device.clearLogcat()
        device.dieBeforeClearingDeviceOwner()
        val ack = parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.RELEASE_DEVICE))
        assertTrue(ack.ok, "the release should be accepted: ${ack.detail}")

        // The death, on the app's own word — and the app given back before it, which is the
        // handback having run. Device Owner is asserted AFTER the resume rather than here: the
        // window between the kill and whatever starts the process next is not one to race.
        awaitDevice("the process dying on purpose", timeoutMs = 60_000) {
            device.walcottLog().any { "dying on purpose before clearing device owner" in it }
        }
        awaitDevice("the process gone", timeoutMs = 30_000) { device.walcottPid() != pidBefore }
        assertFalse(device.isSuspended(pkg), "the handback should have run before the death")

        // The next start — the person opening the app, or the phone booting — finishes it.
        device.startWalcott()
        awaitDevice("the interrupted release recognised", timeoutMs = 60_000) {
            device.walcottLog().any { "finishing the interrupted release" in it }
        }
        awaitDevice("management given up on the next start", timeoutMs = 60_000) { !device.isDeviceOwner() }
        assertHandedBack()
    }

    @AfterEach
    fun cleanUpAndReprovision() {
        runCatching { device.ensureRemoved(Fixture.FIRST.pkg) }
        device.reprovisionDeviceOwner()
    }
}
