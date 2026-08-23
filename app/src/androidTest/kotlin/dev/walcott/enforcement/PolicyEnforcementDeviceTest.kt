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
            // Counted under the package: every limit is that app's own now.
            usageToday = mapOf(target to java.time.Duration.ofMinutes(usageMinutes)),
        )
        enforcer.apply(managed, blocked)
    }

    @Test
    fun a_preinstalled_app_joins_the_managed_set_only_when_the_family_asks_for_it_by_name() {
        // The half of the opt-in that no JVM test can reach: which apps THIS phone says it came
        // with, asked of its own PackageManager.
        val inventory = AppInventory(context)
        val preinstalled = inventory.systemLaunchablePackages() - inventory.criticalPackages()
        assumeTrue("no preinstalled app on this device that could be limited", preinstalled.isNotEmpty())
        val candidate = preinstalled.firstOrNull { it == "com.android.chrome" } ?: preinstalled.first()

        assertFalse(
            "a preinstalled app was managed without anybody asking: $candidate",
            candidate in inventory.managedPackages(),
        )
        assertTrue(
            "the family asked for $candidate and the phone still would not manage it",
            candidate in inventory.managedPackages(setOf(candidate)),
        )
    }

    @Test
    fun the_apps_a_phone_cannot_do_without_are_never_managed_however_they_are_asked_for() {
        // Asking for every one of them at once must change nothing at all. The platform refuses
        // most of these itself — the default home, the dialer — but "most" is not a promise that
        // holds on every OEM, and the failure mode is a phone with no keyboard to type its own
        // unlock PIN into.
        val inventory = AppInventory(context)
        val critical = inventory.criticalPackages()
        assumeTrue("this device names nothing critical", critical.isNotEmpty())
        assertEquals(
            "asking to manage the phone's own essentials changed the managed set",
            inventory.managedPackages(),
            inventory.managedPackages(critical),
        )
    }

    @Test
    fun the_phone_says_who_is_limiting_it_wherever_android_names_an_administrator() {
        // Android tells a child "Blocked by work policy — for more info, contact your IT admin"
        // on every restriction this app sets. That sentence is true and useless: the person
        // reading it is holding the phone, and what they need is which app to open.
        //
        // Only the message being SET is asserted here — where it appears is Android's business
        // and was measured by hand on the emulator (Settings ▸ Date & time ▸ Set time
        // automatically, with the date-time restriction on, shows it in place of the IT-admin
        // line). Note it does NOT reach the dialog for a SUSPENDED app: that suspension is
        // attributed to the platform itself (`suspendingPackage=<0>android`), and a device owner
        // cannot put its own words on it at all.
        DeviceRestrictions.apply(context, emptySet())
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = dev.walcott.WalcottAdminReceiver.componentName(context)
        val short = dpm.getShortSupportMessage(admin)?.toString().orEmpty()
        val long = dpm.getLongSupportMessage(admin)?.toString().orEmpty()
        assertTrue("no short support message: the child is told to contact an IT admin", "Walcott" in short)
        assertTrue("no long support message on the admin's own settings page", "Walcott" in long)
    }

    @Test
    fun a_bedtime_written_in_minutes_ends_with_an_app_the_system_will_not_open() {
        val settings = PolicySettings(
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
            appPolicies = mapOf(target to dev.walcott.data.AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 60))),
        )
        enforce(settings, monday.atTime(18, 0), usageMinutes = 30)
        assertFalse("half a budget already blocked the app", systemSaysSuspended(target))

        enforce(settings, monday.atTime(18, 0), usageMinutes = 60)
        assertTrue("an exhausted budget did not reach the operating system", systemSaysSuspended(target))
    }

    @Test
    fun an_app_nobody_set_a_rule_for_is_left_alone_on_the_device_too() {
        // The promise since limits became per app: a policy that says nothing about an app
        // leaves it usable. It has to hold at the level of the OS, not just in the engine —
        // this is the assertion that would catch "empty policy suspends the world".
        enforce(PolicySettings(), monday.atTime(18, 0))
        assertFalse("an app with no rules was suspended", systemSaysSuspended(target))
    }

    @Test
    fun the_family_default_limit_reaches_the_operating_system_too() {
        // The other half of the budget model: the app has no limit of its own, and answers to
        // the one every app gets.
        val settings = PolicySettings(defaultAppBudget = mapOf(DayType.SCHOOL.name to 60))
        enforce(settings, monday.atTime(18, 0), usageMinutes = 30)
        assertFalse("half the default already blocked the app", systemSaysSuspended(target))

        enforce(settings, monday.atTime(18, 0), usageMinutes = 60)
        assertTrue("an exhausted default did not reach the operating system", systemSaysSuspended(target))
    }

    @Test
    fun revoking_the_counter_locks_down_a_policy_that_needs_it() {
        // The fail-closed branch, end to end: with budgets configured and no way to count time,
        // every managed app goes — which is what makes revoking the permission pointless.
        val settings = PolicySettings(
            appPolicies = mapOf(target to dev.walcott.data.AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 60))),
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
