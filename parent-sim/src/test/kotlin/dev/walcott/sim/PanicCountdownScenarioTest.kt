package dev.walcott.sim

import dev.walcott.sync.PanicProtocol
import dev.walcott.sync.RemoteAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The whole emergency release, end to end, on a clock where an hour is ten seconds.
 *
 * Every rule in [PanicProtocol] is unit-tested, and none of that says whether the alarms fire,
 * whether the relay's receipts come back, whether the counter really refuses to move without one,
 * or whether the phone that comes out the other end is healthy. Those are the parts that only
 * exist on a device, and they are also the parts with the most consequential ending in the
 * product: a phone that lets itself go.
 *
 * Twelve hours of real time is why nobody had ever watched it happen. Ten seconds an hour puts
 * the twelve notices, the final pause and the teardown inside two minutes — on the real alarms,
 * the real retry ladder, the real receipts and the real handback.
 *
 * Destructive, and it has to be: a completed release gives up Device Owner, which is the one
 * piece of state every other scenario needs. Hence its own tag, its own Gradle task
 * (`:parent-sim:e2eReleaseTest`) and the re-provisioning below.
 */
@Tag("e2e")
@Tag("destructive")
class PanicCountdownScenarioTest : DeviceScenario() {

    /** An "hour" of the countdown, in real seconds. Twelve of them plus the pause ≈ two minutes. */
    private val hourSeconds = 10L

    /**
     * Both scenarios here assert on what the OS did with its Device Owner, so a device that is
     * not one has nothing to say about either — and would fail with a message about the product
     * rather than about the device. Checked out loud, because a skipped assumption carries no
     * message through Gradle's report.
     */
    @BeforeEach
    fun onlyOnAManagedDevice() {
        if (!device.isDeviceOwner()) {
            println("SKIPPING: the device is not Device Owner — re-provision it before this suite")
        }
        assumeTrue(device.isDeviceOwner(), "the device is Device Owner")
        // From an empty device, and paid for rather than hoped for. The app this scenario has
        // the guard quarantine has to ARRIVE: a leftover copy makes the install an update, which
        // is deliberately not an arrival (see EnforcementService's package receiver), so nothing
        // is quarantined and the failure names the release rather than the fixture.
        device.ensureRemoved(Fixture.FIRST.pkg)
        device.allowInstallsFor(0)
        device.reconcileInstalls()
    }

    private fun startCompressedRequest() {
        device.panicHourSeconds(hourSeconds)
        device.panicReady()
        device.startPanic()
    }

    @Test
    fun `twelve delivered notices, a last pause, and a phone that is healthy afterwards`() {
        // A phone in a state a family would recognise: an app the device is holding shut, installs
        // locked down, the clock and the filter protected. All of it has to be gone at the end —
        // this is the half of a release that no acknowledgement can prove, and the half that
        // decides whether what the family is handed back is a working phone.
        //
        // The app is shut by a screen-free window rather than by a limit of its own, because a
        // window is the case that shuts EVERY managed app at once: what has to come back is not
        // one package the policy happens to name but everything the device was holding.
        val pkg = installFixtureApp()
        val now = java.time.LocalTime.now()
        val openWindow = now.minusMinutes(60).let { it.hour * 60 + it.minute } to
            now.plusMinutes(60).let { it.hour * 60 + it.minute }
        // Four restrictions and deliberately not the install block: arming that one AFTER an app
        // has landed puts the guard through a quarantine-and-clear of its own, which unsuspends
        // the very app this scenario is watching. The install block coming off is proven by the
        // parent's own release scenario; what this one is about is everything else.
        parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                restrictions = setOf("datetime", "vpn", "apps_control", "unknown_sources"),
                screenFree = listOf(openWindow),
            ),
        )
        awaitDevice("the app shut by the window", timeoutMs = 60_000) { device.isSuspended(pkg) }
        awaitDevice("force-stop greyed out") { device.hasRestriction("no_control_apps") }

        startCompressedRequest()
        val opened = parent.awaitChild { it.panic != null }.panic
        requireNotNull(opened)
        assertEquals(0, opened.checkpoints, "a request has delivered nothing before its first notice")
        assertTrue(opened.startedAtSec > 0, "the request is anchored on the relay's receipt")

        // The counter climbing IS the feature: each step is a notice the relay took, and nothing
        // else moves it. Twelve of them at ten seconds is two minutes, so this waits four.
        val full = parent.awaitChild(timeoutMs = 4 * 60_000) {
            (it.panic?.checkpoints ?: 0) >= PanicProtocol.REQUIRED_CHECKPOINTS
        }
        assertEquals(PanicProtocol.REQUIRED_CHECKPOINTS, full.panic?.checkpoints)

        // And then, after the final pause, the phone genuinely hands itself back — asked of the
        // OS every time, never of the app that is supposed to be tearing itself down.
        awaitDevice("the app given back", timeoutMs = 90_000) { !device.isSuspended(pkg) }
        awaitDevice("force-stop available again") { !device.hasRestriction("no_control_apps") }
        awaitDevice("sideloading unlocked") { !device.hasRestriction("no_install_unknown_sources_globally") }
        awaitDevice("the date and time unlocked") { !device.hasRestriction("no_config_date_time") }
        awaitDevice("VPN settings unlocked") { !device.hasRestriction("no_config_vpn") }
        awaitDevice("private DNS unlocked") { !device.hasRestriction("no_config_private_dns") }
        awaitDevice("management given up", timeoutMs = 90_000) { !device.isDeviceOwner() }
    }

    @Test
    fun `the parent can still refuse inside the last three minutes`() {
        // The pause exists for exactly this, and it is the one window that never existed before:
        // the twelfth notice used to be the last thing that happened before the device let go.
        startCompressedRequest()
        val request = requireNotNull(parent.awaitChild { it.panic != null }.panic)
        val full = parent.awaitChild(timeoutMs = 4 * 60_000) {
            (it.panic?.checkpoints ?: 0) >= PanicProtocol.REQUIRED_CHECKPOINTS
        }
        assertEquals(PanicProtocol.REQUIRED_CHECKPOINTS, full.panic?.checkpoints)

        val ack = parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.DENY_PANIC, arg = request.id))
        assertTrue(ack.ok, "a refusal in the final pause should be accepted: ${ack.detail}")

        val after = parent.awaitChild { it.panic == null }
        assertNull(after.panic, "the refusal should have killed the request")
        // The point of the whole exercise: the phone is still managed.
        assertTrue(device.isDeviceOwner(), "a refused request released the device anyway")
    }

    /**
     * Puts Device Owner back so the rest of the suite has a device to run against, and takes the
     * fixture away so the next scenario starts where this one found things.
     *
     * The re-provisioning is a no-op for the scenarios here that leave Device Owner alone; loud
     * when it is needed and cannot be done, because a device that is no longer managed makes
     * every later scenario SKIP — and a suite that skips everything goes green having tested
     * nothing.
     */
    @AfterEach
    fun cleanUpAndReprovision() {
        runCatching { device.allowInstallsFor(0) }
        runCatching { device.ensureRemoved(Fixture.FIRST.pkg) }
        reprovision()
    }

    private fun reprovision() {
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
