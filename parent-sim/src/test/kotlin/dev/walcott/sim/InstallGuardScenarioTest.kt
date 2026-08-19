package dev.walcott.sim

import dev.walcott.sync.RemoteAction
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The install guard, across both devices.
 *
 * The guard's own decisions are pure and unit-tested; what could not be tested was everything
 * around them — that an app really appears, that the OS really suspends it, that the case
 * really reaches the parent, and that the parent's two answers really come back and take
 * effect. All of that needs a phone on one side and a parent on the other.
 *
 * The stuck-removal cases lean on the OS refusing an uninstall for real (`setUninstallBlocked`),
 * not on a mocked failure: on an emulator a Device Owner uninstall otherwise succeeds within a
 * second, which would make the retry loop, the honest "their phone couldn't remove it"
 * reporting, and the parent's "let it stay" answer all unreachable.
 */
class InstallGuardScenarioTest : DeviceScenario() {

    private val unapproved = "com.sneaky.notapproved"
    private val unapprovedLabel = "Sneaky Game"
    private val installBlock = setOf("installs")

    private fun fixture(name: String): String {
        val url = requireNotNull(javaClass.classLoader.getResource(name)) { "missing fixture $name" }
        return File(url.toURI()).absolutePath
    }

    @BeforeEach
    fun startFromAnEmptyDevice() {
        // Order-independence, deliberately paid for rather than hoped for. A leftover fixture app
        // — especially one a previous scenario made un-removable — turns the NEXT scenario into a
        // story about the last one, and the failure it produces names the wrong feature.
        device.ensureRemoved(unapproved)
        // The quarantine ledger outlives a re-pair (it is device state, not family state), so a
        // case left open would still be there. Removing the app closes it; this makes it happen
        // now rather than at some later reconciliation in the middle of an assertion.
        device.reconcileInstalls()
    }

    @AfterEach
    fun cleanUpTestApp() {
        // Every path out of here must leave the device able to install things again, or the next
        // scenario fails during setup for a reason that has nothing to do with it.
        runCatching { device.seedPolicy(PolicyJson.minimal()) }
        runCatching { device.ensureRemoved(unapproved) }
    }

    /** Arms the block and puts the device in the state where an install is possible but unapproved. */
    private fun armBlockAndOpenWindowFor(approvedPackage: String) {
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
        device.openInstallWindow(approvedPackage)
        awaitDevice("the window lifted the block") { !device.installBlocked() }
    }

    @Test
    fun `nothing installs while the block is armed, adb included`() {
        // The control every other case rests on. If this stopped holding, the guard would be
        // catching apps that the block should never have let near the phone.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
        val result = device.install(fixture("unapproved-app.apk"))
        assertTrue(
            result.contains("SecurityException") || result.contains("USER_RESTRICTED") ||
                result.contains("restriction"),
            "an install went through with the block armed: $result",
        )
        assertFalse(device.isInstalled(unapproved))
    }

    @Test
    fun `an app that lands unapproved is suspended, named to the parent, and removed`() {
        armBlockAndOpenWindowFor("com.approved.example")
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"), "the sneak-in should install")

        // The case has to reach the parent BY NAME. A package name would be useless on the
        // parent's screen, and the label can only be resolved on the device that has the app.
        val reported = parent.awaitChild { snapshot ->
            snapshot.unauthorized.any { it.pkg == unapproved }
        }
        val entry = reported.unauthorized.single { it.pkg == unapproved }
        assertEquals(unapprovedLabel, entry.label, "the app's human name should cross the wire")
        assertTrue(entry.suspended, "the parent is told it is blocked, so it had better be")

        awaitDevice("the unapproved app removed") { !device.isInstalled(unapproved) }
        // And the case closes rather than lingering as a permanent accusation.
        childReports { it.unauthorized.none { entry -> entry.pkg == unapproved } }
    }

    @Test
    fun `the approved app is not quarantined for landing in its own window`() {
        // The failure that would matter most: punishing the child who did exactly as told.
        armBlockAndOpenWindowFor(unapproved)
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))

        device.reconcileInstalls()
        parent.assertNoChild(windowMs = 8_000) { it.unauthorized.any { e -> e.pkg == unapproved } }
        assertTrue(device.isInstalled(unapproved), "the approved app was removed")
        assertFalse(device.isSuspended(unapproved), "the approved app was suspended")
    }

    @Test
    fun `a removal the OS refuses keeps the case open and keeps the app unusable`() {
        // The reason quarantine is a ledger and not a one-shot. Suspension is the promise that
        // survives a failed uninstall, and the case has to stay open so the retry has something
        // to retry.
        device.blockUninstall(unapproved)
        armBlockAndOpenWindowFor("com.approved.example")
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))

        val reported = parent.awaitChild { it.unauthorized.any { e -> e.pkg == unapproved } }
        assertTrue(reported.unauthorized.single { it.pkg == unapproved }.suspended)

        // It cannot be removed, so it must still be here — and still suspended, which is the
        // whole point: unusable is a promise that does not depend on the uninstall working.
        device.reconcileInstalls()
        Thread.sleep(2_000)
        assertTrue(device.isInstalled(unapproved), "the OS was supposed to refuse this removal")
        assertTrue(device.isSuspended(unapproved), "a stuck case must keep the app suspended")

        // And the retries are counted, so a permanently stuck case is visible rather than silent.
        val retried = childReports {
            it.unauthorized.any { e -> e.pkg == unapproved && e.removalAttempts > 1 }
        }
        assertTrue(retried.unauthorized.single { it.pkg == unapproved }.removalAttempts > 1)
    }

    @Test
    fun `the parent lets a quarantined app stay, and the device stops blocking it`() {
        device.blockUninstall(unapproved)
        armBlockAndOpenWindowFor("com.approved.example")
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))
        parent.awaitChild { it.unauthorized.any { e -> e.pkg == unapproved } }

        val commandId = parent.sendCommand(deviceId, RemoteAction.ALLOW_APP, arg = unapproved)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "allowing an app should succeed: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_ALLOWED, ack.detail)

        awaitDevice("the allowed app un-suspended") { !device.isSuspended(unapproved) }
        assertTrue(device.isInstalled(unapproved), "letting it stay should not remove it")
        childReports { it.unauthorized.none { e -> e.pkg == unapproved } }

        // And the decision sticks: the next reconciliation must not re-quarantine what the
        // parent just allowed, which is the bug that would make the button useless.
        device.reconcileInstalls()
        parent.assertNoChild(windowMs = 6_000) { it.unauthorized.any { e -> e.pkg == unapproved } }
        assertFalse(device.isSuspended(unapproved), "the app was quarantined again after being allowed")
    }

    @Test
    fun `the parent removes an app on demand`() {
        // Not a quarantined app: "get that off their phone" is the same request whether or not
        // the guard flagged it, and the command is documented to work on any user app.
        armBlockAndOpenWindowFor(unapproved)
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))
        device.reconcileInstalls()
        awaitDevice("the app settled as approved") { device.isInstalled(unapproved) }

        val commandId = parent.sendCommand(deviceId, RemoteAction.UNINSTALL_APP, arg = unapproved)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "the removal command should be accepted: ${ack.detail}")
        assertEquals(RemoteAction.DETAIL_REMOVING, ack.detail)
        awaitDevice("the app removed on the parent's command") { !device.isInstalled(unapproved) }
    }

    @Test
    fun `removing an app that is not there says so instead of pretending`() {
        val commandId = parent.sendCommand(deviceId, RemoteAction.UNINSTALL_APP, arg = "com.not.installed.anywhere")
        val ack = parent.awaitAck(commandId)
        assertEquals(RemoteAction.DETAIL_NOT_INSTALLED, ack.detail)
    }

    @Test
    fun `turning the install block off releases what it had quarantined`() {
        // A family that stops blocking installs has withdrawn the rule the case was made under.
        // Leaving the app suspended would enforce a setting they turned off — and nothing would
        // ever lift it, because the pass that closes cases is skipped once the guard stands down.
        device.blockUninstall(unapproved)
        armBlockAndOpenWindowFor("com.approved.example")
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))
        parent.awaitChild { it.unauthorized.any { e -> e.pkg == unapproved } }
        awaitDevice("the app suspended") { device.isSuspended(unapproved) }

        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = emptySet()))
        awaitDevice("the install block lifted") { !device.installBlocked() }
        device.reconcileInstalls()

        awaitDevice("the quarantined app released") { !device.isSuspended(unapproved) }
        assertTrue(device.isInstalled(unapproved), "releasing a case should not remove the app")
        childReports { it.unauthorized.none { e -> e.pkg == unapproved } }
    }

    @Test
    fun `an app quarantined once can still be approved and installed later`() {
        // The trap a stale case sets. The first half leaves a case that closes while the guard
        // is standing down; the second half is the parent properly approving that same app. If
        // the closed case lingers, the approved install is suspended and removed on arrival —
        // which to the family looks like "I approved it, it installed, and then it vanished".
        device.blockUninstall(unapproved)
        armBlockAndOpenWindowFor("com.approved.example")
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))
        parent.awaitChild { it.unauthorized.any { e -> e.pkg == unapproved } }

        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = emptySet()))
        awaitDevice("the install block lifted") { !device.installBlocked() }
        device.ensureRemoved(unapproved)
        device.reconcileInstalls()

        // Now the parent approves it for real.
        armBlockAndOpenWindowFor(unapproved)
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))
        device.reconcileInstalls()

        assertDeviceNever("the approved app removed by a stale case") { !device.isInstalled(unapproved) }
        assertFalse(device.isSuspended(unapproved), "the approved app was suspended by a stale case")
        parent.assertNoChild(windowMs = 4_000) { it.unauthorized.any { e -> e.pkg == unapproved } }
    }

    @Test
    fun `the nightly window lifts the block, still judges what lands, and gives it back`() {
        // The whole feature, measured where it actually happens. Play cannot update anything
        // while DISALLOW_INSTALL_APPS is set — to Android an update IS an install — so the
        // restriction has to really come off the platform for the hour.
        //
        // The window used here is the CURRENT hour, which the device is by definition inside:
        // this is also the catch-up path (a policy that arrives at 04:10 must open the window it
        // is already inside), and the one whose old implementation armed an alarm for a past
        // instant and spun the receiver for the rest of the hour.
        fun policy(version: Long, windowOn: Boolean) = PolicyJson.build(
            version = version,
            restrictions = installBlock,
            extra = mapOf(
                "installMode" to JsonPrimitive("strict"),
                "updateWindowEnabled" to JsonPrimitive(windowOn),
                // Not the family's sleeping hours, which is the default: this scenario needs a
                // window it can put around THIS minute, and the sim family has no bedtime.
                "updateWindowFollowsBedtime" to JsonPrimitive(false),
                "updateWindowHour" to JsonPrimitive(java.time.LocalTime.now().hour),
                "updateWindowMinutes" to JsonPrimitive(60),
            ),
        )
        parent.pushPolicy(policy(version = 2, windowOn = false))
        awaitDevice("the install block armed") { device.installBlocked() }

        parent.pushPolicy(policy(version = 3, windowOn = true))
        awaitDevice("the update window lifted the block") { !device.installBlocked() }

        // And nothing is forgiven inside it, which is the only reason an open hour is safe to
        // have: an update never changes the set of installed packages, so it is invisible to the
        // guard, while a package that is genuinely new is caught exactly as it would be at noon.
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"), "the sneak-in should install")
        parent.awaitChild { snapshot -> snapshot.unauthorized.any { it.pkg == unapproved } }
        awaitDevice("the unapproved app removed") { !device.isInstalled(unapproved) }

        // Withdrawn mid-hour: the block comes back now, not at the end of an hour the family has
        // already changed its mind about.
        parent.pushPolicy(policy(version = 4, windowOn = false))
        awaitDevice("the block back once the family withdraws the window") { device.installBlocked() }
    }

    @Test
    fun `a family that does not block installs is never guarded`() {
        // Installing is not a violation in that family; quarantining what the child installs
        // would be the worst possible bug here.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = emptySet()))
        awaitDevice("no install block") { !device.installBlocked() }
        assertTrue(device.install(fixture("unapproved-app.apk")).contains("Success"))

        device.reconcileInstalls()
        parent.assertNoChild(windowMs = 8_000) { it.unauthorized.any { e -> e.pkg == unapproved } }
        assertTrue(device.isInstalled(unapproved), "an app installed by a family that allows it was removed")
        assertFalse(device.isSuspended(unapproved))
    }
}
