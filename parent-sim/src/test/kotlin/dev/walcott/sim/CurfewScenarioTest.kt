package dev.walcott.sim

import dev.walcott.sync.ChildEvent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * What the phone does about the apps it CANNOT suspend, while the whole phone is supposed to be
 * shut (see `Curfew`).
 *
 * Bedtime suspends the managed apps, and the managed set is the non-system ones — so on a normal
 * phone the browser has never been in it, and the evening carried on in the app that was already
 * installed. Anything holding a WebView is the same hole. What is taken away instead is the DNS,
 * which is a wildcard "block every destination" applied to one app.
 *
 * Only a device can show any of this. The decision itself is pure and unit-tested; what is not
 * is whether a browser is actually IDENTIFIED on a real phone, whether a real `VpnService`
 * establish happens on a real kernel for a family that blocked no domains at all, and whether it
 * all really goes away when the window does. Those three are what these scenarios assert, and
 * they are asked of the OS — the tun interface and the device-policy dump — rather than of the
 * app's opinion of itself.
 *
 * One property is asserted at one remove, and it is worth naming: that the filter works this out
 * for ITSELF, with no enforcement loop behind it, which is what stops a reboot being a way out.
 * A reboot cannot be scripted here — the phone comes back at a secure lock screen and nothing in
 * this suite can type a PIN (see the module README) — so what is asserted instead is that the
 * filter derives the window half by its own call, at the moment of asking. The reboot itself was
 * measured by hand: with the enforcement service not yet running, the filter still named the
 * browser.
 */
class CurfewScenarioTest : DeviceScenario() {

    /** A window [fromMinutes] before now to [toMinutes] after, as minutes since midnight. */
    private fun windowAround(fromMinutes: Long, toMinutes: Long): Pair<Int, Int> {
        val now = LocalTime.now()
        return now.plusMinutes(fromMinutes).let { it.hour * 60 + it.minute } to
            now.plusMinutes(toMinutes).let { it.hour * 60 + it.minute }
    }

    /** A screen-free window that this minute is comfortably inside. */
    private fun shutPhone(version: Long): String =
        PolicyJson.build(version = version, screenFree = listOf(windowAround(-60, 60)))

    @Test
    fun `the browser is found and cut off, on a family that blocks no domains at all`() {
        val browser = device.defaultBrowser()
        assumeTrue(browser.isNotBlank(), "this phone has no browser to cut off")

        // The baseline, and the thing that makes the rest of this mean something: no rules, no
        // filter, no tunnel. Anything that comes up after this comes up because of the window.
        parent.pushPolicy(PolicyJson.build(version = 2))
        awaitDevice("no tunnel on a family that filters nothing") { !device.tunnelUp() }
        device.clearLogcat()

        parent.pushPolicy(shutPhone(version = 3))

        // A real tun, on a real kernel, for a family with not one domain in its rules. There is
        // no other reason this device could want one — which is what makes the interface itself
        // the assertion: the curfew found something to cut off and needs a filter to do it.
        awaitDevice("the DNS tunnel up while the phone is shut", timeoutMs = 60_000) { device.tunnelUp() }
        assertEquals(
            ChildDevice.PACKAGE, device.alwaysOnVpnPackage(),
            "the filter came up without being pinned as always-on, so the child could turn it off",
        )
        // And it is the BROWSER it cut off, not merely something. A curfew that quietly found
        // nothing would pass every assertion above by having a tunnel up for no one.
        assertTrue(
            device.walcottLog().any { "curfew:" in it && browser in it },
            "the window is running and the phone never named $browser as cut off",
        )
        // Asked of the filter itself, by the call its packet loop makes. Everything above is the
        // enforcement loop's account of what it decided; this is what a DNS query would actually
        // meet — and it is derived from the rules and the clock at the moment of asking, which is
        // what makes it survive the loop not being there at all (a reboot restores the always-on
        // VPN long before it restores the enforcement service).
        assertTrue(
            browser in device.curfewNow(),
            "the filter would still resolve for $browser while the phone is supposed to be shut",
        )
        assertTrue(
            device.walcottLog().any { "window:" in it && browser in it },
            "the filter never derived the window half for itself; it was only ever told",
        )

        // Withdrawn: all of it goes, and goes on its own. There is no expiry to run here and
        // nothing to remember to undo — the window closing IS the lift.
        parent.pushPolicy(PolicyJson.build(version = 4))
        awaitDevice("the tunnel gone once the window is over", timeoutMs = 60_000) { !device.tunnelUp() }
        assertEquals(
            "", device.alwaysOnVpnPackage(),
            "the always-on VPN stayed pinned after the window that asked for it ended",
        )
        assertEquals(
            emptySet<String>(), device.curfewNow(),
            "the filter would still refuse to resolve for an app after the window that closed it",
        )
    }

    @Test
    fun `an app the phone cannot suspend, still going after two minutes, is cut off and named`() {
        // The other half, and the one nobody can enumerate in advance: whatever the OEM put on
        // the phone that holds a WebView. Settings stands in for it here for the reason that
        // makes it a good stand-in — it is a system package, so the suspension never touches it,
        // and it is on every Android phone there has ever been.
        assumeTrue(device.isInstalled(SYSTEM_APP), "this phone has no $SYSTEM_APP to keep open")

        parent.pushPolicy(shutPhone(version = 2))
        awaitDevice("the DNS tunnel up while the phone is shut", timeoutMs = 60_000) { device.tunnelUp() }

        // Now keep it on screen, the way a child would. Nudged awake throughout: the enforcement
        // loop parks while the screen is off, so a dozing emulator accrues nothing and the whole
        // scenario would time out on a rule that was never given a chance to run.
        device.launchApp(SYSTEM_APP)
        val reported = childEventuallyReports(timeoutMs = LINGER_TIMEOUT_MS) { snapshot ->
            device.nudgeAwake()
            if (device.foregroundPackage() != SYSTEM_APP) device.launchApp(SYSTEM_APP)
            snapshot.ruleEvents.any { it.kind == ChildEvent.KIND_CURFEW_CUT && it.pkg == SYSTEM_APP }
        }

        // The parent is told which app, by name — the package alone is no use to somebody
        // deciding whether this is a limit they want to set.
        val event = reported.ruleEvents.first { it.kind == ChildEvent.KIND_CURFEW_CUT }
        assertEquals(SYSTEM_APP, event.pkg)
        assertTrue(event.label.isNotBlank(), "the child named no app, so the parent's wall says nothing")
        assertTrue(event.atMs > 0, "the wall orders by when it happened")

        device.home()
        parent.pushPolicy(PolicyJson.build(version = 3))
        awaitDevice("the cut-off lifted with the window", timeoutMs = 60_000) { !device.tunnelUp() }
    }

    private companion object {
        /** On every Android phone, never suspended, and holding a WebView on plenty of them. */
        const val SYSTEM_APP = "com.android.settings"

        /**
         * Room for the two minutes the rule actually asks for, plus the loop's own pace and a
         * publish. Deliberately not shortened by a test hook: two minutes is the product's
         * number, and a scenario that proved a different one would prove nothing.
         */
        const val LINGER_TIMEOUT_MS = 4 * 60 * 1000L
    }
}
