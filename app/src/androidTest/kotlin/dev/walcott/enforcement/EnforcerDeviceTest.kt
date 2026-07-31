package dev.walcott.enforcement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walcott.data.AppInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of enforcement no JVM test can reach: whether the operating system actually does
 * what the rules ask.
 *
 * Every pure test in this repo proves which packages *should* be suspended. Only a device can
 * answer whether asking made it so, whether the system reports it back honestly, and what
 * happens when it refuses — which is precisely where a family's rules would fail silently
 * while every unit test stayed green.
 *
 * Needs Walcott to be Device Owner (the walcott-spike emulator is); skips cleanly otherwise
 * rather than failing on a phone that can't answer.
 */
@RunWith(AndroidJUnit4::class)
class EnforcerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val enforcer = Enforcer(context)
    private lateinit var target: String

    @Before
    fun pickASuspendableApp() {
        assumeTrue("not Device Owner on this device", enforcer.isDeviceOwner())
        // Anything launchable that isn't us. Suspending our own package would be both refused
        // and self-defeating, and the launcher/IME are left alone by picking from the same list
        // the enforcement loop manages.
        val candidates = AppInventory(context).launchableApps()
            .map { it.packageName }
            .filter { it != context.packageName }
        assumeTrue("no other launchable app on this device", candidates.isNotEmpty())
        target = candidates.firstOrNull { it == "com.google.android.youtube" } ?: candidates.first()
        enforcer.apply(managed = setOf(target), blocked = emptySet())
    }

    @After
    fun leaveTheDeviceAsItWas() {
        if (::target.isInitialized) enforcer.apply(managed = setOf(target), blocked = emptySet())
    }

    /**
     * Whether the SYSTEM says the package is suspended right now — read through the same call
     * the heartbeat self-test uses, so these tests can't pass against a probe the product
     * doesn't rely on.
     */
    private fun systemSaysSuspended(pkg: String): Boolean = enforcer.unenforced(setOf(pkg)).isEmpty()

    @Test
    fun asking_the_system_to_block_an_app_actually_suspends_it() {
        enforcer.apply(managed = setOf(target), blocked = setOf(target))
        assertTrue("$target was not suspended by the system", systemSaysSuspended(target))

        enforcer.apply(managed = setOf(target), blocked = emptySet())
        assertFalse("$target stayed suspended after the rules allowed it again", systemSaysSuspended(target))
    }

    @Test
    fun the_self_test_sees_the_gap_between_what_the_rules_say_and_what_the_system_did() {
        // Nothing suspended, but the rules say it should be: exactly the failure the heartbeat
        // self-test exists to notice ("looks healthy, isn't blocking").
        assertEquals(listOf(target), enforcer.unenforced(setOf(target)))

        enforcer.apply(managed = setOf(target), blocked = setOf(target))
        assertTrue("the self-test still reported a gap after a real suspend", enforcer.unenforced(setOf(target)).isEmpty())
    }

    @Test
    fun applying_the_same_state_twice_asks_the_system_for_nothing() {
        // The loop runs every few seconds. Re-asserting a state that already holds must be a
        // no-op, or a device churns through device-policy calls forever.
        enforcer.apply(managed = setOf(target), blocked = setOf(target))
        val plan = Enforcer.plan(setOf(target), setOf(target)) { systemSaysSuspended(it) }
        assertTrue("re-asserting produced work: $plan", plan.isEmpty)
    }

    @Test
    fun a_package_that_is_not_installed_never_becomes_a_reported_gap() {
        // The OS refuses to suspend what isn't there, on every pass, forever. Recording that
        // as an enforcement gap pinned a dead package name to every future health report — and
        // the check that stops it is the one thing here that needs a real PackageManager.
        val ghost = "com.walcott.definitely.not.installed"
        enforcer.apply(managed = setOf(ghost), blocked = setOf(ghost))
        assertFalse(
            "an uninstalled package was reported as an enforcement gap: ${Enforcer.recentSuspendFailures}",
            ghost in Enforcer.recentSuspendFailures,
        )
        // And it is not counted as unenforced either: it can't be used, so it isn't a hole.
        assertTrue(enforcer.unenforced(setOf(ghost)).isEmpty())
    }

    @Test
    fun the_managed_set_the_loop_acts_on_never_contains_walcott_itself() {
        // If it did, the app would suspend the thing enforcing the rules.
        val inventory = AppInventory(context)
        assertFalse("Walcott put itself in the managed set", context.packageName in inventory.managedPackages())
    }

    @Test
    fun the_apps_that_reach_a_person_are_never_managed_and_never_limited() {
        // The phone and contacts apps are exempt from every rule (see WalcottRepository's
        // essentials). Asserted on a device because resolving WHO they are is the part no JVM
        // test can do — and getting it wrong means a child who cannot call.
        val inventory = AppInventory(context)
        val reachable = inventory.alwaysReachablePackages()
        assertFalse("no phone app resolved on this device", reachable.isEmpty())
        assertTrue(
            "an always-reachable app is in the managed set: ${'$'}reachable",
            inventory.managedPackages().none { it in reachable },
        )
    }
}
