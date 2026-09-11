package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A phone freed by the release must not come out of it locked with a PIN nobody was told.
 *
 * The parent can set the child's unlock PIN from a distance (see `RemoteAction.SET_LOCK_PIN`).
 * The release promises to take that lock off again while it still has the power to — after
 * Device Owner is dropped nothing on the phone can ever reset a credential, and a phone whose
 * only PIN lives in a lost parent's memory is the factory reset the whole feature exists to
 * avoid. That step was a guaranteed no-op for two releases: the handback cleared the reset
 * token one step before the lock was asked to use it, and the guard against re-arming during
 * a release refused to hand the token over at all. The happy-path suite never noticed because
 * nothing in it looked at the keyguard.
 */
@Tag("e2e")
@Tag("destructive")
class LockScreenReleaseScenarioTest : DeviceScenario() {

    private val pin = "2468"

    @Test
    fun `a lock Walcott set comes off with the release`() {
        val pkg = installBaselineFixture()
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(pkg to 0)))
        awaitDevice("the app suspended by its rules", timeoutMs = 60_000) { device.isSuspended(pkg) }
        device.setPin("4291")

        // The lock, set the way a parent sets it. Whether the platform lets it happen depends
        // on the reset token being active, which is Android's call (see AssistedScenarioTest):
        // without it there is no lock to take off and nothing here to prove, so say so and skip.
        val ack = parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.SET_LOCK_PIN, arg = pin))
        assumeTrue(ack.ok, "the platform would not set a lock (${ack.detail}); nothing to release")
        device.clearLogcat()

        device.releaseWithPin("4291")
        awaitDevice("management given up", timeoutMs = 90_000) { !device.isDeviceOwner() }
        assertHandedBack()
        val log = device.walcottLog()
        assertTrue(log.any { "lock screen removed" in it }, "the release never took the lock off")
        assertFalse(
            log.any { "could not remove the lock screen on release" in it },
            "the release reported the lock as still on",
        )
        // And the phone is one a person can get into: no credential stands in the way.
        device.nudgeAwake()
        device.dismissSwipeKeyguard()
        assertTrue(device.userUnlocked(), "the phone came out of the release still locked")
    }

    @AfterEach
    fun cleanUpAndReprovision() {
        // Belt and braces: whatever the scenario proved, the next class must not meet a PIN.
        runCatching { device.run("shell", "locksettings", "clear", "--old", pin) }
        runCatching { device.run("shell", "locksettings", "set-disabled", "true") }
        runCatching { device.allowInstallsFor(0) }
        runCatching { device.ensureRemoved(Fixture.FIRST.pkg) }
        device.reprovisionDeviceOwner()
    }
}
