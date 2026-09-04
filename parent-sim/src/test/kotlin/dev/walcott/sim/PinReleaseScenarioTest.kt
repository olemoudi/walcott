package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The door a family is most likely to use, and the one no test had ever opened: the parent PIN
 * typed on the child's own phone. It works with no network, no parent phone and no relay, which
 * is the whole reason it exists — and until now the only thing standing behind it was a Compose
 * dialog nobody could drive.
 *
 * The debug hook runs exactly what that dialog runs — the guarded verify, lockout and all, and
 * on success the whole release — so what is proven here is the door, not a shortcut past it:
 * a wrong PIN changes nothing and reaches the parent; the right one hands the phone back, filter
 * and all; and a second release arriving while the first runs is one release, not two.
 */
@Tag("e2e")
@Tag("destructive")
class PinReleaseScenarioTest : DeviceScenario() {

    /**
     * The tunnel takes its time to come up after the policy lands: the documented worst case is
     * `VpnStatus.GRACE_MS` plus `VPN_HEAL_MILLIS` (see CurfewScenarioTest), and a wait under it
     * fails for being slow rather than wrong.
     */
    private val tunnelUpTimeoutMs = 150_000L

    private fun aManagedPhone(pkg: String) = PolicyJson.build(
        version = 2,
        restrictions = setOf("installs", "datetime", "vpn"),
        dailyMinutes = mapOf(pkg to 0),
        // A domain to block is what brings the DNS filter — and with it the always-on VPN, the
        // one Device Owner knob nothing but this app could ever unpin — up on the phone.
        extra = mapOf("blockedDomains" to JsonArray(listOf(JsonPrimitive("example.invalid")))),
    )

    @Test
    fun `a wrong PIN changes nothing, and the right one hands the phone back`() {
        val pkg = installBaselineFixture()
        parent.pushPolicy(aManagedPhone(pkg))
        awaitDevice("the app suspended by its rules", timeoutMs = 60_000) { device.isSuspended(pkg) }
        awaitDevice("the install block armed") { device.installBlocked() }
        awaitDevice("the filter pinned as always-on VPN", timeoutMs = tunnelUpTimeoutMs) {
            device.alwaysOnVpnPackage() == ChildDevice.PACKAGE
        }
        device.setPin("4291")

        // Wrong: the phone stays exactly as it was, and the parent is told somebody tried.
        device.clearLogcat()
        device.releaseWithPin("0000")
        awaitDevice("the wrong PIN refused") {
            device.walcottLog().any { "release_with_pin: Wrong" in it }
        }
        parent.awaitChild(timeoutMs = 60_000) { it.lastWrongPinMs > 0 }
        assertDeviceNever("management given up on a wrong PIN") { !device.isDeviceOwner() }
        assertTrue(device.isSuspended(pkg), "a wrong PIN unsuspended the app")

        // Right: the whole release, on a phone with no parent involved at all.
        device.clearLogcat()
        device.releaseWithPin("4291")
        awaitDevice("the app given back", timeoutMs = 60_000) { !device.isSuspended(pkg) }
        awaitDevice("the install block lifted") { !device.installBlocked() }
        awaitDevice("the date and time unlocked") { !device.hasRestriction("no_config_date_time") }
        awaitDevice("management given up", timeoutMs = 60_000) { !device.isDeviceOwner() }
        assertHandedBack()
    }

    @Test
    fun `two releases arriving together are one release`() {
        // The parent taps "free this phone" in the same moment the child types the PIN — or the
        // panic countdown completes while either arrives. Whichever gets there first runs the
        // teardown; the other waits for it and finds nothing left to do. Two teardowns racing
        // each other over a few hundred packages is the state this serialisation exists to avoid.
        val pkg = installBaselineFixture()
        parent.pushPolicy(aManagedPhone(pkg))
        awaitDevice("the app suspended by its rules", timeoutMs = 60_000) { device.isSuspended(pkg) }
        device.setPin("4291")

        device.clearLogcat()
        parent.sendCommand(deviceId, RemoteAction.RELEASE_DEVICE)
        device.releaseWithPin("4291")

        awaitDevice("management given up", timeoutMs = 90_000) { !device.isDeviceOwner() }
        assertHandedBack()
        val completed = device.walcottLog().count { "emergency release complete" in it }
        assertEquals(1, completed, "the teardown ran more than once")
    }

    @AfterEach
    fun cleanUpAndReprovision() {
        runCatching { device.allowInstallsFor(0) }
        runCatching { device.ensureRemoved(Fixture.FIRST.pkg) }
        device.reprovisionDeviceOwner()
    }
}
