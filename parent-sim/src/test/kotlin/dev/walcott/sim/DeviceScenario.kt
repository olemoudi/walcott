package dev.walcott.sim

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag

/**
 * Base for the scenarios that need a real child.
 *
 * Each test gets a brand new family: fresh keys, fresh topic, fresh relay port. That costs a
 * pairing per test and buys the thing that matters — no state carried between scenarios, so a
 * failure is about the case it names and not about what ran before it.
 *
 * Prerequisites, checked rather than assumed: an emulator attached, the debug build installed,
 * and Device Owner provisioned (the half of the product that suspends packages and blocks
 * installs does not exist without it). Missing any of them SKIPS rather than fails — a red
 * suite should mean the product is wrong, not that nobody plugged a phone in.
 */
@Tag("e2e")
abstract class DeviceScenario {

    protected lateinit var relay: MockRelay
    protected lateinit var parent: ParentSim
    protected val device = ChildDevice()

    /** The child's own device id, learned from its first check-in. */
    protected lateinit var deviceId: String

    @BeforeEach
    fun pairFreshFamily() {
        // Said out loud as well as assumed: a skipped assumption carries no message through
        // Gradle's report, so a suite that quietly skipped 33 scenarios looks like a suite that
        // passed. If the device is not ready, the run should say so where someone will read it.
        precondition("a device is attached", device.isAvailable())
        precondition("dev.walcott is installed", device.isWalcottInstalled())
        // Repaired rather than skipped: the emulated Wi-Fi interface vanishes under a long run,
        // and a suite that skips itself reports success while testing nothing.
        precondition("the device has a network", device.repairNetwork())

        relay = MockRelay().start()
        // Two addresses for one relay: this process reaches it on loopback, the device reaches
        // it through the emulator's host alias.
        parent = ParentSim(relay.localUrl, advertisedRelay = relay.emulatorUrl).start()

        // Leave the device the way a scenario expects to find it: no family, no restrictions.
        // The policy goes first — a leftover install block would refuse the next `adb install`
        // and, worse, would make an unrelated scenario fail for a reason it never mentions.
        device.seedPolicy(PolicyJson.minimal())
        // Wait for it to be real before going on: a seed broadcast returns when the receiver was
        // dispatched, not when the OS has acted, and a scenario that starts while the previous
        // one's install block is still armed fails on its first `adb install` for no reason of
        // its own.
        val deadline = System.currentTimeMillis() + 20_000
        while (device.installBlocked() && System.currentTimeMillis() < deadline) Thread.sleep(250)
        // Wipe and pair in one broadcast: as two, the wipe can land after the pairing and
        // leave a device that believes it is paired and never speaks.
        device.pairFresh(parent.pairingFor(childId = CHILD_ID, childName = CHILD_NAME))
        deviceId = awaitFirstCheckIn()
    }

    /**
     * The first check-in, nudged rather than waited out.
     *
     * A pairing publish can be lost to a socket that has not finished coming up — after a
     * network repair, most reliably. The product heals exactly that with its periodic re-emit,
     * fifteen minutes later; a scenario asks for the same thing now. Nothing is being papered
     * over: the scenarios still assert on real behaviour, this only gets them to the starting
     * line the way the product itself would.
     */
    private fun awaitFirstCheckIn(): String {
        repeat(CHECK_IN_ATTEMPTS) {
            val found = runCatching {
                parent.awaitChild(timeoutMs = 15_000) { it.childId == CHILD_ID }
            }.getOrNull()
            if (found != null) return found.deviceId
            device.publish()
        }
        throw AssertionError("the device never checked in after pairing")
    }

    private fun precondition(what: String, holds: Boolean) {
        if (!holds) println("SKIPPING: $what — not true right now")
        assumeTrue(holds, what)
    }

    @AfterEach
    fun releaseDevice() {
        // Restrictions are the one thing that outlives the app's own state and can break the
        // NEXT run before it starts, so they come off explicitly rather than by being forgotten.
        runCatching { device.seedPolicy(PolicyJson.minimal()) }
        runCatching { parent.stop() }
        runCatching { relay.stop() }
    }

    /** Waits for the child to report something, forcing a publish rather than idling for one. */
    protected fun childReports(
        timeoutMs: Long = ParentSim.DEFAULT_TIMEOUT_MS,
        predicate: (dev.walcott.sync.ChildSnapshot) -> Boolean,
    ): dev.walcott.sync.ChildSnapshot {
        device.publish()
        return parent.awaitChild(timeoutMs, predicate)
    }

    /**
     * Waits for something to become true ON THE DEVICE — the OS's answer, not the app's claim
     * about it. Asserting through `dumpsys` is the difference between "we asked for a package to
     * be suspended" and "it is suspended", and the whole reason a scenario runs on a device.
     */
    protected fun awaitDevice(what: String, timeoutMs: Long = 30_000, probe: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return
            Thread.sleep(1_000)
        }
        throw AssertionError("device never reached: $what (within ${timeoutMs}ms)")
    }

    /** Asserts the device does NOT reach [what] while the window lasts. */
    protected fun assertDeviceNever(what: String, windowMs: Long = 8_000, probe: () -> Boolean) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) throw AssertionError("device reached a state it must not: $what")
            Thread.sleep(1_000)
        }
    }

    companion object {
        const val CHILD_ID = "sim-child"
        const val CHILD_NAME = "Sim Child"

        /** Nudges before giving up on a first check-in (see [awaitFirstCheckIn]). */
        const val CHECK_IN_ATTEMPTS = 3
    }
}
