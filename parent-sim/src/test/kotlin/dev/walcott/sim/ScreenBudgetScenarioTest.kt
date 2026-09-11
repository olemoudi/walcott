package dev.walcott.sim

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The phone's own limit for the day, on a real phone.
 *
 * Limits are per app and each spends its own clock, so ten apps with an hour each is a ten-hour
 * day — and the parent who set those ten hours believed they had set one. This is the ceiling.
 * What only a device can settle is that it reaches the operating system: the arithmetic is
 * unit-tested to death, and none of that says whether an app the family never limited is
 * actually shut when the day runs out.
 *
 * Every number here is a DELTA on what the device has already counted. Screen time lives in Room
 * and outlives a re-pairing, so a scenario written against absolute totals would be a scenario
 * about what ran before it.
 */
class ScreenBudgetScenarioTest : DeviceScenario() {

    private lateinit var app: String

    @AfterEach
    fun removeFixture() {
        if (::app.isInitialized) runCatching { device.ensureRemoved(app) }
    }

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Everything this phone has counted today, as it reports it. */
    private fun screenSecondsNow(): Long = childReports { true }.usage.sumOf { it.seconds }

    /**
     * A total this phone still has [HEADROOM_MINUTES] of, whatever it has already spent today.
     *
     * Absolute numbers are no use here: screen time lives in Room and survives a re-pairing, so
     * a fixed "two hours" is two hours on a fresh emulator and long spent on one the suite has
     * been running on all morning.
     */
    private fun budgetWithHeadroom(): Int = (screenSecondsNow() / 60).toInt() + HEADROOM_MINUTES

    /**
     * Spends the rest of the phone's day, on [pkg], and says so out loud.
     *
     * The extra time is the part that has to be seeded past, and forgetting it is why the first
     * draft of this class reported a product that had stopped enforcing: EVERY grant widens the
     * DAY as well as an app's budget (see FamilyConfig.screenAllowanceAt — since 0.107 a grant
     * to one app does too), those grants live in Room until midnight, and every earlier scenario
     * that hands out bonus minutes leaves some behind. Worse, it made the "never limit" test pass
     * while the day was never spent at all — an assertion about nothing, which is the shape of
     * lie this suite has been bitten by before.
     */
    private fun spendTheDay(pkg: String) {
        val extra = childReports { true }.extra.sumOf { it.seconds }
        device.addUsage(pkg to (HEADROOM_MINUTES * 60L + extra + SLACK_SECONDS))
    }

    @Test
    fun `an app with no limit of its own is shut when the phone's day runs out`() {
        app = installFixtureApp(Fixture.STARTABLE)
        // Nothing about this app anywhere in the policy: no budget, no window. Under the old
        // model it could be used all day, and that is the hole this closes.
        parent.pushPolicy(PolicyJson.build(version = 2, screenBudgetMinutes = budgetWithHeadroom()))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app open while the day still has time in it") { !device.isSuspended(app) }

        // Spend the rest of the day, on the fixture — but it is the TOTAL that is spent, and the
        // app has no limit to run out of.
        spendTheDay(app)
        awaitDevice("the phone shut once its day was spent", timeoutMs = APPLY_TIMEOUT_MS) {
            device.isSuspended(app)
        }

        // And more minutes reopen it, which is the only thing that ends this one: a ceiling with
        // no way past it is a phone a parent cannot give back for an evening.
        parent.grantBonus(deviceId, ALL_APPS, minutes = 20, epochDay = today())
        awaitDevice("the phone open again after the parent gave it more time", timeoutMs = APPLY_TIMEOUT_MS) {
            !device.isSuspended(app)
        }
    }

    @Test
    fun `a spent day is spent by every app, not only the one that spent it`() {
        // The difference between a total and a per-app limit, asked of the OS: time went into
        // one app and it is a DIFFERENT app that will not open.
        app = installFixtureApp(Fixture.STARTABLE)
        val other = installFixtureApp(Fixture.FIRST)
        try {
            parent.pushPolicy(PolicyJson.build(version = 2, screenBudgetMinutes = budgetWithHeadroom()))
            childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
            spendTheDay(app)
            awaitDevice("the OTHER app shut by a day spent somewhere else", timeoutMs = APPLY_TIMEOUT_MS) {
                device.isSuspended(other)
            }
        } finally {
            runCatching { device.ensureRemoved(other) }
        }
    }

    @Test
    fun `an app the family said never to limit keeps working after the day is spent`() {
        // The family's own escape hatch — the bus timetable, the chat with a parent. A ceiling
        // that closed it would be the rule closing the way out of itself, and there would be
        // nothing on the phone to say why.
        app = installFixtureApp(Fixture.STARTABLE)
        val other = installFixtureApp(Fixture.FIRST)
        try {
            parent.pushPolicy(
                PolicyJson.build(
                    version = 2,
                    screenBudgetMinutes = budgetWithHeadroom(),
                    unlimited = setOf(app),
                ),
            )
            childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
            spendTheDay(app)
            // The control, and the reason the silence below means anything: the day really IS
            // spent. Without it this passes just as well on a phone whose total never ran out,
            // which is an assertion about nothing.
            awaitDevice("the day actually spent", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(other) }
            assertDeviceNever("an app marked never-limit was shut by the day's total") {
                device.isSuspended(app)
            }
        } finally {
            runCatching { device.ensureRemoved(other) }
        }
    }

    @Test
    fun `a spent day never closes the app the child texts from, even when it is managed`() {
        // Reported from a real phone: once this app started managing preinstalled apps and
        // capping the whole day, the messaging app was inside both — so "two hours of phone"
        // quietly included the way a child says they are running late.
        //
        // Managed ON PURPOSE here, which is what makes this worth running on a device: an app
        // the phone cannot suspend proves nothing about an exemption. This one it can, and it
        // still must not.
        val messaging = device.messagingApp()
        assumeTrue(messaging.isNotBlank(), "this phone has no messaging app")
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                screenBudgetMinutes = budgetWithHeadroom(),
                manageSystem = setOf(messaging),
            ),
        )
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        spendTheDay(app)
        // The control: the day really is spent, and an ordinary app really is shut by it.
        awaitDevice("the day actually spent", timeoutMs = APPLY_TIMEOUT_MS) { device.isSuspended(app) }
        assertDeviceNever("the phone's own total closed the app the child texts from") {
            device.isSuspended(messaging)
        }
    }

    @Test
    fun `the phone says which of its apps reach a person`() {
        // The flag the parent's screens judge by: without it they would report the phone as out
        // of time under a family default, about a device that would never have blocked it.
        val messaging = device.messagingApp()
        assumeTrue(messaging.isNotBlank(), "this phone has no messaging app")
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2))
        val snapshot = childEventuallyReports { snap -> snap.apps.any { it.packageName == app } }
        val reported = snapshot.apps.associate { it.packageName to it.reachOut }
        assertTrue(reported[messaging] == true, "the messaging app was not reported as a way to reach a person")
        assertTrue(reported[app] == false, "an ordinary app was reported as a way to reach a person")
    }

    @Test
    fun `a total makes the phone fail closed without a counter, like every other budget`() {
        // A family whose ONLY rule is a daily total would otherwise have written the one rule
        // that revoking a permission turns off — the same bypass budgets are already closed to.
        app = installFixtureApp(Fixture.STARTABLE)
        parent.pushPolicy(PolicyJson.build(version = 2, screenBudgetMinutes = budgetWithHeadroom()))
        childEventuallyReports { it.appliedPolicyVersion >= parent.currentVersion() }
        awaitDevice("the app open before anything is revoked") { !device.isSuspended(app) }
        try {
            device.run("shell", "appops", "set", "dev.walcott", "GET_USAGE_STATS", "ignore")
            awaitDevice("everything shut once the counter went away", timeoutMs = APPLY_TIMEOUT_MS) {
                device.isSuspended(app)
            }
        } finally {
            device.ensureUsageAccess()
        }
        awaitDevice("and open again once it came back", timeoutMs = APPLY_TIMEOUT_MS) {
            !device.isSuspended(app)
        }
    }

    private companion object {
        const val ALL_APPS = "__all_apps__"

        /** Enough past the line that a second of sampling while this runs cannot undo it. */
        const val SLACK_SECONDS = 120L

        /** Room to prove the app is OPEN first, then spent in one seeded step. */
        const val HEADROOM_MINUTES = 40

        /**
         * The counters reach the loop by their own subscription, which is normally instant — but
         * the safety net behind it is `EnforcementService.COUNTER_RESYNC_MILLIS` (60 s), and when
         * the net is what answers, a shorter window would be under the product's own documented
         * worst case. Plus a loop tick and the reconcile.
         */
        const val APPLY_TIMEOUT_MS = 120_000L
    }
}
