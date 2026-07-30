package dev.walcott.enforcement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walcott.data.AppInventory
import dev.walcott.data.PolicySettings
import dev.walcott.data.WindowDto
import dev.walcott.data.withHolidayMirroringWeekend
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The whole chain on a real device: a policy a parent could have written, resolved into rules,
 * turned into a set of packages, and handed to the operating system — then read back from the
 * operating system.
 *
 * [EnforcerDeviceTest] proves the last hop in isolation and the JVM harness proves everything
 * before it. This is the join: it is the only test in the repo where a budget written as
 * minutes-per-day ends with an app the system will not open.
 */
@RunWith(AndroidJUnit4::class)
class PolicyEnforcementDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val enforcer = Enforcer(context)
    private lateinit var target: String

    /** A Monday, so the policy below resolves to its weekday rules. */
    private val monday = LocalDate.of(2026, 3, 2)

    @Before
    fun pickAnApp() {
        assumeTrue("not Device Owner on this device", enforcer.isDeviceOwner())
        val candidates = AppInventory(context).launchableApps()
            .map { it.packageName }
            .filter { it != context.packageName }
        assumeTrue("no other launchable app on this device", candidates.isNotEmpty())
        target = candidates.firstOrNull { it == "com.google.android.youtube" } ?: candidates.first()
    }

    @After
    fun leaveTheDeviceAsItWas() {
        if (::target.isInitialized) enforcer.apply(managed = setOf(target), blocked = emptySet())
    }

    /** The rules exactly as a child device derives them: parent write, then the child's slice. */
    private fun rulesFor(settings: PolicySettings) =
        settings.withHolidayMirroringWeekend().resolveForChild(null).toFamilyConfig(setOf(context.packageName))

    private fun systemSaysSuspended(pkg: String) = enforcer.unenforced(setOf(pkg)).isEmpty()

    /** Derives the blocked set for [at] and hands it to the OS, as the loop does every tick. */
    private fun enforce(settings: PolicySettings, at: java.time.LocalDateTime, usageMinutes: Long = 0) {
        val config = rulesFor(settings)
        val managed = setOf(target)
        val blocked = RuleEngine.blockedPackages(
            config,
            managed,
            at,
            usageToday = mapOf("games" to java.time.Duration.ofMinutes(usageMinutes)),
        )
        enforcer.apply(managed, blocked)
    }

    @Test
    fun a_bedtime_written_in_minutes_ends_with_an_app_the_system_will_not_open() {
        val settings = PolicySettings(
            assignments = mapOf(target to "games"),
            bedtime = DayType.entries.associate { it.name to WindowDto(21 * 60, 7 * 60) },
        )
        enforce(settings, monday.atTime(22, 30))
        assertTrue("bedtime did not reach the operating system", systemSaysSuspended(target))

        enforce(settings, monday.atTime(18, 0))
        assertFalse("the app stayed suspended outside bedtime", systemSaysSuspended(target))
    }

    @Test
    fun a_daily_budget_running_out_reaches_the_operating_system() {
        val settings = PolicySettings(
            assignments = mapOf(target to "games"),
            budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 60)),
        )
        enforce(settings, monday.atTime(18, 0), usageMinutes = 30)
        assertFalse("half a budget already blocked the app", systemSaysSuspended(target))

        enforce(settings, monday.atTime(18, 0), usageMinutes = 60)
        assertTrue("an exhausted budget did not reach the operating system", systemSaysSuspended(target))
    }

    @Test
    fun an_app_the_parent_never_classified_is_blocked_on_the_device_too() {
        // "Unclassified is blocked" is the rule everything else rests on; it has to hold at the
        // level of the OS, not just in the engine.
        enforce(PolicySettings(), monday.atTime(18, 0))
        assertTrue("an unclassified app was left usable", systemSaysSuspended(target))
    }

    @Test
    fun revoking_the_counter_locks_down_a_policy_that_needs_it() {
        // The fail-closed branch, end to end: with budgets configured and no way to count time,
        // every managed app goes — which is what makes revoking the permission pointless.
        val settings = PolicySettings(
            assignments = mapOf(target to "games"),
            budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 60)),
        )
        val config = rulesFor(settings)
        val blocked = RuleEngine.blockedPackages(
            config,
            setOf(target),
            monday.atTime(18, 0),
            usageCountingAvailable = false,
        )
        assertEquals(setOf(target), blocked)
        enforcer.apply(setOf(target), blocked)
        assertTrue("fail-closed did not reach the operating system", systemSaysSuspended(target))
    }
}
