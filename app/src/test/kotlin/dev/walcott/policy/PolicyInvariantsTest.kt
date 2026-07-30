package dev.walcott.policy

import dev.walcott.data.PolicySettings
import dev.walcott.data.withHolidayMirroringWeekend
import dev.walcott.enforcement.Enforcer
import dev.walcott.rules.BlockReason
import dev.walcott.rules.CategoryState
import dev.walcott.rules.DayType
import dev.walcott.rules.ExtraTime
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.Verdict
import dev.walcott.rules.categoryStatus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Properties that must hold for EVERY policy a parent can build, checked against a few hundred
 * generated ones (see [PolicyFuzz]) at every edge instant.
 *
 * Scenario tests answer "does this setup behave as intended". These answer the question that
 * actually keeps a parental control honest: "is there ANY combination of these settings where
 * the app contradicts itself, or quietly stops restricting". Each test is one invariant, so a
 * red name says which promise broke, and the message says on which policy and at what time.
 */
class PolicyInvariantsTest {

    internal companion object {
        /** Enough to cover the interesting combinations; the whole file runs in a second or two. */
        const val POLICIES = 200
        val GRANT: Map<String, Duration> = mapOf(
            ExtraTime.ALL_APPS to Duration.ofHours(10),
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private val cases = PolicyFuzz.cases(POLICIES)

    /** Runs [check] for every (policy, child, instant), reporting where it broke. */
    private fun forEachInstant(check: (PolicyFuzz.Case, FamilyConfig, LocalDateTime) -> Unit) {
        for (case in cases) {
            val config = case.config
            for (now in PolicyFuzz.INSTANTS) check(case, config, now)
        }
    }

    /** As [forEachInstant], and once per usage profile — fresh, half spent, over the cap. */
    private fun forEachUsage(check: (PolicyFuzz.Case, FamilyConfig, LocalDateTime, Map<String, Duration>) -> Unit) {
        for (case in cases) {
            val config = case.config
            for (now in PolicyFuzz.INSTANTS) {
                for (usage in PolicyFuzz.usageProfiles(config, now)) check(case, config, now, usage)
            }
        }
    }

    // --- The device must survive any policy at all ---

    @Test
    fun `no policy makes the enforcement loop throw`() {
        // The worst outcome this app has: an exception inside the loop crash-restarts the
        // service every few seconds, freezing apps as they were and silencing the child.
        forEachUsage { case, config, now, usage ->
            runCatching {
                RuleEngine.blockedPackages(config, PolicyFuzz.MANAGED, now, usage)
                PolicyFuzz.MANAGED.forEach { RuleEngine.evaluate(config, it, now, usage) }
                PolicyFuzz.CATEGORY_IDS.forEach { RuleEngine.categoryStatus(config, it, now, usage) }
            }.onFailure { error("$case at $now threw ${it::class.simpleName}: ${it.message}") }
        }
    }

    // --- Promises the code makes in prose, asserted ---

    @Test
    fun `an essential package is never blocked, whatever the policy says`() {
        // The phone, contacts, and Walcott itself: if a generated policy could block these,
        // a child could be locked out of calling anyone.
        forEachInstant { case, config, now ->
            assertEquals(
                Verdict.Allowed,
                RuleEngine.evaluate(config, PolicyFuzz.OWN_PACKAGE, now),
                "$case at $now blocked an essential package",
            )
            assertFalse(
                PolicyFuzz.OWN_PACKAGE in RuleEngine.blockedPackages(
                    config,
                    PolicyFuzz.MANAGED + PolicyFuzz.OWN_PACKAGE,
                    now,
                ),
                "$case at $now suspended an essential package",
            )
        }
    }

    @Test
    fun `the set the loop suspends is exactly the set the engine blocks`() {
        // blockedPackages is the only control the service acts on; evaluate is what every
        // screen explains. They must never disagree about a package.
        forEachUsage { case, config, now, usage ->
            val expected = PolicyFuzz.MANAGED.filterTo(mutableSetOf()) {
                RuleEngine.evaluate(config, it, now, usage) is Verdict.Blocked
            }
            assertEquals(
                expected,
                RuleEngine.blockedPackages(config, PolicyFuzz.MANAGED, now, usage),
                "$case at $now with usage $usage",
            )
        }
    }

    @Test
    fun `a policy with any budget anywhere cannot be enforced without the usage counter`() {
        // Structural, not by example: any budget at all — a category's or a single app's —
        // has to make the counter mandatory, or revoking usage access grants unlimited time.
        for (case in cases) {
            val config = case.config
            val hasBudget = config.policies.values.any { it.dailyBudget.isNotEmpty() } ||
                config.perAppPolicies.values.any { it.dailyBudget.isNotEmpty() }
            assertEquals(
                hasBudget,
                RuleEngine.requiresUsageCounting(config),
                "$case: budgets present=$hasBudget but requiresUsageCounting says otherwise",
            )
        }
    }

    @Test
    fun `a policy with any rule about time cannot be enforced with a clock we don't trust`() {
        for (case in cases) {
            val config = case.config
            val hasTimeRule = config.bedtime.isNotEmpty() ||
                config.blockedWindows.values.any { it.isNotEmpty() } ||
                (config.policies.values + config.perAppPolicies.values)
                    .any { it.dailyBudget.isNotEmpty() || it.blockedWindows.isNotEmpty() }
            assertEquals(
                hasTimeRule,
                RuleEngine.requiresTrustedClock(config),
                "$case: time rules present=$hasTimeRule but requiresTrustedClock says otherwise",
            )
        }
    }

    @Test
    fun `losing the counter or the clock blocks everything the policy relies on them for`() {
        forEachInstant { case, config, now ->
            if (RuleEngine.requiresUsageCounting(config)) {
                assertEquals(
                    PolicyFuzz.MANAGED,
                    RuleEngine.blockedPackages(config, PolicyFuzz.MANAGED, now, usageCountingAvailable = false),
                    "$case at $now did not fail closed with the counter gone",
                )
            }
            if (RuleEngine.requiresTrustedClock(config)) {
                assertEquals(
                    PolicyFuzz.MANAGED,
                    RuleEngine.blockedPackages(config, PolicyFuzz.MANAGED, now, clockTrusted = false),
                    "$case at $now did not fail closed with an untrusted clock",
                )
            }
        }
    }

    @Test
    fun `an app's own rules can only ever tighten its category`() {
        // The claim in FamilyConfig.perAppPolicies' own docs. If a per-app rule could ever
        // WIDEN what an app may do, it would be a hole a parent opened by accident.
        for (case in cases) {
            val withOwn = case.config
            val withoutOwn = PolicyFuzz.configFor(case.settings.copy(appPolicies = emptyMap()), case.childId)
            for (now in PolicyFuzz.INSTANTS) {
                for (pkg in PolicyFuzz.MANAGED) {
                    val allowedWithOwn = RuleEngine.evaluate(withOwn, pkg, now) !is Verdict.Blocked
                    val allowedWithout = RuleEngine.evaluate(withoutOwn, pkg, now) !is Verdict.Blocked
                    if (allowedWithOwn) {
                        assertTrue(allowedWithout, "$case at $now: $pkg is allowed only BECAUSE of its own rules")
                    }
                }
            }
        }
    }

    @Test
    fun `extra time never lifts bedtime or a blocked window`() {
        forEachUsage { case, config, now, usage ->
            for (pkg in PolicyFuzz.MANAGED) {
                val plain = RuleEngine.evaluate(config, pkg, now, usage)
                val reason = (plain as? Verdict.Blocked)?.reason
                if (reason == BlockReason.BEDTIME || reason == BlockReason.BLOCKED_WINDOW) {
                    val categoryId = config.assignments[pkg]
                    val everything = GRANT + buildMap {
                        put(pkg, Duration.ofHours(10))
                        if (categoryId != null) put(categoryId, Duration.ofHours(10))
                    }
                    assertEquals(
                        plain,
                        RuleEngine.evaluate(config, pkg, now, usage, extraTime = everything),
                        "$case at $now: a grant moved $pkg past a $reason",
                    )
                }
            }
        }
    }

    @Test
    fun `a blanket grant never blows through an app's own cap`() {
        // "Give everyone 10 more hours" must not defeat a deliberately tight per-app limit;
        // only a grant aimed at that app can.
        forEachInstant { case, config, now ->
            val dayType = config.calendar.dayTypeOf(now)
            for ((pkg, policy) in config.perAppPolicies) {
                val cap = policy.dailyBudget[dayType] ?: continue
                val spent = mapOf(pkg to cap, config.assignments.getValue(pkg) to Duration.ZERO)
                val verdict = RuleEngine.evaluate(config, pkg, now, usageToday = spent, extraTime = GRANT)
                assertTrue(
                    verdict is Verdict.Blocked,
                    "$case at $now: an all-apps grant revived $pkg past its own ${cap.toMinutes()}min cap",
                )
            }
        }
    }

    @Test
    fun `the child's category card never contradicts the enforcer`() {
        // A card reading "2h remaining" over an app that refuses to open is worse than a
        // block. For an app carrying no rules of its own, the card and the verdict have to
        // agree — same blocked-ness, same reason.
        forEachUsage { case, config, now, usage ->
            for ((pkg, categoryId) in config.assignments) {
                if (pkg in config.perAppPolicies) continue // its own rules may tighten past the card
                val card = RuleEngine.categoryStatus(config, categoryId, now, usage)
                val verdict = RuleEngine.evaluate(config, pkg, now, usage)
                assertEquals(
                    card.state == CategoryState.BLOCKED,
                    verdict is Verdict.Blocked,
                    "$case at $now (usage $usage): card says ${card.state} but $pkg is $verdict",
                )
                if (verdict is Verdict.Blocked) {
                    assertEquals(verdict.reason, card.blockReason, "$case at $now: reasons differ for $pkg")
                }
            }
        }
    }

    // --- The wire and the parent's write path ---

    @Test
    fun `a policy that has been through the wire enforces identically`() {
        // The rules reach a child as JSON. A field that fails to survive that trip changes
        // what a device does, silently and everywhere.
        for (case in cases) {
            val decoded = json.decodeFromString(
                PolicySettings.serializer(),
                json.encodeToString(PolicySettings.serializer(), case.settings),
            )
            val here = case.config
            val there = PolicyFuzz.configFor(decoded, case.childId)
            for (now in PolicyFuzz.INSTANTS) {
                assertEquals(
                    RuleEngine.blockedPackages(here, PolicyFuzz.MANAGED, now),
                    RuleEngine.blockedPackages(there, PolicyFuzz.MANAGED, now),
                    "$case at $now enforces differently after a JSON round trip",
                )
            }
        }
    }

    @Test
    fun `special days mirror the weekend unless the family claimed the column`() {
        for (case in cases) {
            val written = case.settings.withHolidayMirroringWeekend()
            val own = written.specialDaysOwnRules
            // Schedules mirror no matter what: a special day always has the weekend's bedtime
            // and windows, because nothing else can give it any.
            assertEquals(
                written.bedtime[DayType.WEEKEND.name],
                written.bedtime[DayType.HOLIDAY.name],
                "$case: special days lost their bedtime",
            )
            assertEquals(
                written.allAppsBlockedWindows[DayType.WEEKEND.name],
                written.allAppsBlockedWindows[DayType.HOLIDAY.name],
                "$case: special days lost the family's screen-free windows",
            )
            if (own) continue
            for (entry in written.budgets) {
                val categoryId = entry.key
                val perDay = entry.value
                assertEquals(
                    perDay[DayType.WEEKEND.name],
                    perDay[DayType.HOLIDAY.name],
                    "$case: $categoryId's special-day budget drifted from the weekend's",
                )
            }
        }
    }

    // --- What the device does with the answer ---

    @Test
    fun `suspending what the engine asks for settles in one pass`() {
        // The loop re-plans every few seconds. If applying a plan didn't converge, a device
        // would suspend and unsuspend the same app forever.
        forEachInstant { case, config, now ->
            val blocked = RuleEngine.blockedPackages(config, PolicyFuzz.MANAGED, now)
            // Start from a stale state on purpose: half the apps suspended, right or not.
            val suspended = PolicyFuzz.MANAGED.filterIndexedTo(mutableSetOf()) { i, _ -> i % 2 == 0 }
            val plan = Enforcer.plan(PolicyFuzz.MANAGED, blocked) { it in suspended }
            suspended.addAll(plan.toSuspend)
            suspended.removeAll(plan.toUnsuspend.toSet())
            assertEquals(blocked, suspended, "$case at $now: applying the plan left the wrong set suspended")
            assertTrue(
                Enforcer.plan(PolicyFuzz.MANAGED, blocked) { it in suspended }.isEmpty,
                "$case at $now: the plan did not converge in one pass",
            )
        }
    }
}
