package dev.walcott.sim

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Limiting an app that CAME WITH THE PHONE — the browser, the video app, the gallery.
 *
 * Until now this product managed only what the family had installed, so the app a day actually
 * disappears into was the one app it could not touch: a parent could open the browser's editor
 * from the weekly report, save an hour a day on it, and the phone would go on opening it for
 * ever with nothing anywhere saying so. The rule was saved, shown, and dead.
 *
 * Only a device can settle any of this. Whether a device owner is ALLOWED to suspend a
 * preinstalled package is not a fact about our code — it is a decision the platform makes, per
 * package, and one it makes differently for the browser (yes) and for Settings (no). So the
 * assertions here are asked of `dumpsys package`, and the scenario deliberately keeps a
 * user-installed app under the same rule throughout: without it, a phone that had simply
 * stopped enforcing would pass the first half of every test in this class.
 */
class SystemAppScenarioTest : DeviceScenario() {

    private lateinit var browser: String
    private lateinit var fixture: String

    @AfterEach
    fun handBackTheBrowser() {
        // Through a path this scenario does not depend on: the last thing these tests do is
        // withdraw the opt-in, and if THAT is what broke, the phone is left with its browser
        // shut and every later scenario inherits it. Seeding the rules directly is the one
        // route that does not go through the feature under test.
        if (!::browser.isInitialized) return
        runCatching { device.seedPolicy(PolicyJson.minimal()) }
        runCatching {
            awaitDevice("the browser handed back", timeoutMs = GIVE_BACK_TIMEOUT_MS) { !device.isSuspended(browser) }
        }
        if (::fixture.isInitialized) runCatching { device.ensureRemoved(fixture) }
    }

    @Test
    fun `a preinstalled app is closed only once the family asks for it by name`() {
        browser = device.defaultBrowser()
        assumeTrue(browser.isNotBlank(), "this phone has no browser to limit")
        fixture = installFixtureApp(Fixture.STARTABLE)
        openTheBrowser()

        // Blocked outright, both of them, and NOTHING opted in. This is the state the product
        // was in before this feature: the parent has said what they want about the browser and
        // the phone is about to ignore it.
        parent.pushPolicy(PolicyJson.build(version = 2, dailyMinutes = mapOf(browser to 0, fixture to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }

        // The control, and the reason the silence below means anything: the same rule, on an app
        // the family installed, really does shut it.
        awaitDevice("the installed app shut by its own limit", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(fixture)
        }
        assertDeviceNever("the browser shut without anybody asking for it") { device.isSuspended(browser) }

        // Now the switch, and nothing else about the rule changes.
        parent.pushPolicy(
            PolicyJson.build(
                version = 3,
                dailyMinutes = mapOf(browser to 0, fixture to 0),
                manageSystem = setOf(browser),
            ),
        )
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the browser shut once the family opted into it", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(browser)
        }

        // And withdrawing it hands the app back. This is the half that has no second chance: a
        // preinstalled app left suspended is one nothing else on the device would ever
        // unsuspend, on a phone whose owner has just said they no longer want it limited.
        parent.pushPolicy(PolicyJson.build(version = 4, dailyMinutes = mapOf(browser to 0, fixture to 0)))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the browser given back when the opt-in was withdrawn", timeoutMs = APPLY_TIMEOUT_MS) {
            !device.isSuspended(browser)
        }
        // The rule itself never changed, so the app the family installed is still shut. Without
        // this the test above would also pass on a phone that had given every app back.
        assertTrue(device.isSuspended(fixture), "withdrawing one opt-in released an unrelated app")
    }

    @Test
    fun `the phone says which of its apps it came with`() {
        browser = device.defaultBrowser()
        assumeTrue(browser.isNotBlank(), "this phone has no browser to report")
        fixture = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2))

        // The flag is what decides whether the parent is offered the switch at all, so a phone
        // that reported its apps without it would leave the whole feature unreachable.
        val snapshot = childEventuallyReports { snap -> snap.apps.any { it.packageName == fixture } }
        val reported = snapshot.apps.associate { it.packageName to it.system }
        assertEquals(false, reported[fixture], "an app the family installed was reported as preinstalled")
        assertEquals(true, reported[browser], "the browser was not reported as coming with the phone")
    }

    /**
     * Starts from a phone whose browser is OPEN, and says so out loud if it cannot get there.
     *
     * Without this the scenario's central assertion — that a preinstalled app nobody opted into
     * is left alone — is satisfied just as well by a browser somebody else had already shut,
     * which is not the same claim at all. It is the same shape of lie as asserting a keyguard is
     * up on a phone whose screen simply timed out: true, and about nothing. It happened on the
     * first run of this class, and a scenario that cannot survive whatever ran before it is a
     * scenario that will one day report a product bug that is not there.
     *
     * Cleared through the rules rather than over adb, because a device-owner suspension is not
     * something `pm unsuspend` can lift: only the phone that applied it gives it back.
     */
    private fun openTheBrowser() {
        if (!device.isSuspended(browser)) return
        device.seedPolicy(PolicyJson.minimal())
        awaitDevice("the browser open before any rule is written", timeoutMs = GIVE_BACK_TIMEOUT_MS) {
            !device.isSuspended(browser)
        }
    }

    private companion object {
        /**
         * How long a rule has to reach the OS, and why it is not the usual thirty seconds.
         *
         * The managed set is what may be suspended at all, and the loop re-reads it on package
         * changes or every `EnforcementService.INVENTORY_TTL_MILLIS` (60 s) — not when a policy
         * lands. So a policy that only changes WHICH apps are managed (which is exactly what
         * this switch does) waits out that TTL before anything happens, plus a loop tick and the
         * reconcile. The product's own documented worst case is therefore about a minute and a
         * half; anything under it would make this scenario a question about which path answered.
         */
        const val APPLY_TIMEOUT_MS = 150_000L

        /** The same wait, for the cleanup that must not leave a phone with its browser shut. */
        const val GIVE_BACK_TIMEOUT_MS = 150_000L
    }
}
