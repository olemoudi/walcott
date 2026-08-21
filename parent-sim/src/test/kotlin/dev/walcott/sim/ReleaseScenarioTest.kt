package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The parent freeing a supervised phone from a distance — the answer to a member being removed,
 * a phone being handed on, or a family simply stopping.
 *
 * Everything else in this suite can run in any order any number of times. This one cannot: it ends
 * with the device no longer being Device Owner, which is the one piece of state every other
 * scenario needs and the one thing `dpm` cannot always give back. So it carries its own tag and
 * its own Gradle task (`:parent-sim:e2eReleaseTest`), re-provisions afterwards, and says plainly
 * in its failure message when it could not.
 *
 * What it proves is the promise the parent's screen makes when it offers to free a phone: not that
 * a command was acknowledged, but that the OS itself let go — apps unsuspended, restrictions
 * lifted, management gone.
 */
@Tag("e2e")
@Tag("destructive")
class ReleaseScenarioTest : DeviceScenario() {

    @Test
    fun `the parent frees a phone and the OS actually lets go of it`() {
        val pkg = installFixtureApp()
        // A phone in a state a family would recognise: an app blocked by its rules, installs
        // locked down, and the date/time protected against being moved.
        parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                restrictions = setOf("installs", "datetime"),
                dailyMinutes = mapOf(pkg to 0),
            ),
        )
        // Sixty seconds, not the default thirty. The rules land on the enforcement loop's tick,
        // and the loop's own self-heal re-assert is every thirty — so a thirty-second wait sits
        // exactly ON the documented worst case and fails for being slow rather than wrong.
        awaitDevice("the app suspended by its rules", timeoutMs = 60_000) { device.isSuspended(pkg) }
        awaitDevice("the install block armed") { device.installBlocked() }

        val commandId = parent.sendCommand(deviceId, RemoteAction.RELEASE_DEVICE)

        // The acknowledgement has to arrive BEFORE the teardown, or a parent could never tell a
        // freed phone from one that simply went quiet.
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "the release should be accepted: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_RELEASING, ack.detail)

        // And then the phone is genuinely handed back — asked of the OS, not of the app.
        awaitDevice("the app given back") { !device.isSuspended(pkg) }
        awaitDevice("the install block lifted") { !device.installBlocked() }
        awaitDevice("the date and time unlocked") { !device.hasRestriction("no_config_date_time") }
        awaitDevice("management given up", timeoutMs = 60_000) { !device.isDeviceOwner() }
    }

    /**
     * Puts Device Owner back so the rest of the suite has a device to run against.
     *
     * `dpm set-device-owner` only works on a device with no accounts and no other admin, which is
     * exactly what a freed emulator is — but if it ever fails, every later scenario would skip its
     * preconditions and the suite would go green having tested nothing. Hence the loud failure.
     */
    @AfterEach
    fun reprovision() {
        if (!device.isAvailable() || device.isDeviceOwner()) return
        val result = runCatching {
            device.run("shell", "dpm", "set-device-owner", "dev.walcott/.WalcottAdminReceiver")
        }.getOrElse { it.message.orEmpty() }
        check(device.isDeviceOwner()) {
            "the device could not be made Device Owner again ($result). Re-provision it before " +
                "running the rest of the suite, or every scenario will skip and pass."
        }
    }
}
