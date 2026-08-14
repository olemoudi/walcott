package dev.walcott.policy

import dev.walcott.rules.BlockReason
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.Verdict
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the harness itself.
 *
 * A property test is only worth its runtime if the corpus it runs on actually reaches the
 * situations the properties talk about. A generator that quietly degenerated — every policy
 * empty, every verdict "allowed" — would leave every invariant in [PolicyInvariantsTest]
 * passing while testing nothing at all. So the corpus has to prove its own reach, and this
 * test fails when it stops covering something a family can configure.
 */
class PolicyCorpusTest {

    private val cases = PolicyFuzz.cases(PolicyInvariantsTest.POLICIES)

    @Test
    fun `the corpus covers every kind of rule a parent can set`() {
        val seen = mutableMapOf<String, Int>()
        fun note(trait: String, present: Boolean) { if (present) seen.merge(trait, 1, Int::plus) }

        for (case in cases) {
            val config = case.config
            note("bedtime", config.bedtime.isNotEmpty())
            note("family windows", config.blockedWindows.values.any { it.isNotEmpty() })
            note("default budget", config.defaultAppBudget.isNotEmpty())
            note("per-app budget", config.perAppPolicies.values.any { it.dailyBudget.isNotEmpty() })
            note("per-app window", config.perAppPolicies.values.any { it.blockedWindows.isNotEmpty() })
            note("app set free", config.perAppPolicies.values.any { it.unlimited })
            note(
                "zero budget",
                config.perAppPolicies.values.any { p -> p.dailyBudget.values.any { it.isZero } },
            )
            note("holiday", config.calendar.holidays.isNotEmpty())
            note("vacation", config.calendar.vacations.isNotEmpty())
            note("weekend starts Friday", config.calendar.weekendStartsFriday != null)
            note("weekend ends Sunday", config.calendar.weekendEndsSunday != null)
            note("own special-day budget", case.settings.specialDaysOwnRules)
            note("child overrides", case.settings.children.any { !it.overrides.isEmpty })
            note("app with no rules", PolicyFuzz.MANAGED.any { it !in config.perAppPolicies })
            val allWindows = config.blockedWindows.values.flatten() +
                config.perAppPolicies.values.flatMap { it.blockedWindows.values.flatten() }
            note("day-of-week mask", allWindows.any { it.days.isNotEmpty() })
            note("skips special days", allWindows.any { it.specialDays == dev.walcott.rules.SpecialDays.NEVER })
            note("midnight-crossing window", allWindows.any { it.start > it.end })
        }

        val thin = seen.filterValues { it < cases.size / 20 }.keys +
            (EXPECTED_TRAITS - seen.keys)
        assertTrue(
            thin.isEmpty(),
            "the generator stopped producing (or barely produces) these: $thin — seen counts: ${seen.toSortedMap()}",
        )
    }

    @Test
    fun `the corpus reaches every day type and every reason a device can block for`() {
        val reasons = mutableSetOf<BlockReason>()
        val dayTypes = mutableSetOf<DayType>()
        var allowed = 0
        for (case in cases) {
            val config = case.config
            for (now in PolicyFuzz.INSTANTS) {
                dayTypes += config.calendar.dayTypeOf(now)
                for (usage in PolicyFuzz.usageProfiles(config, now)) {
                    for (pkg in PolicyFuzz.MANAGED) {
                        when (val verdict = RuleEngine.evaluate(config, pkg, now, usage)) {
                            is Verdict.Blocked -> reasons += verdict.reason
                            else -> allowed++
                        }
                    }
                }
            }
        }
        // FAIL_CLOSED is reached through blockedPackages, not evaluate, so it is not expected here.
        assertTrue(
            reasons.containsAll(
                setOf(
                    BlockReason.BEDTIME,
                    BlockReason.BLOCKED_WINDOW,
                    BlockReason.BUDGET_EXHAUSTED,
                ),
            ),
            "the corpus never reached some block reasons: $reasons",
        )
        assertTrue(dayTypes == DayType.entries.toSet(), "the corpus never reached some day types: $dayTypes")
        assertTrue(allowed > 0, "every single evaluation was blocked — the corpus is not exercising the allowed path")
    }

    private companion object {
        /** Every trait the corpus is expected to keep producing, so a silent drop fails loudly. */
        val EXPECTED_TRAITS = setOf(
            "bedtime", "family windows", "default budget",
            "per-app budget", "per-app window", "app set free", "zero budget", "holiday", "vacation",
            "weekend starts Friday", "weekend ends Sunday", "own special-day budget",
            "child overrides", "app with no rules", "day-of-week mask", "skips special days",
            "midnight-crossing window",
        )
    }
}
