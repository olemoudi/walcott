package dev.walcott.policy

import dev.walcott.data.PolicySettings
import dev.walcott.rules.BlockReason
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.Verdict
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Policies as they can actually arrive: written by a parent running a newer build, or damaged.
 *
 * A child decodes and applies the rules inside the enforcement loop, so anything that throws
 * there is the worst failure this app has — crash-restart every few seconds, apps frozen as
 * they were, and the device unable to report why. The rule the codebase already follows for
 * unknown day-type keys holds for every other malformed field: degrade the rule, never the
 * device. These tests are the ones that keep that true.
 */
class HostileWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val monday: LocalDateTime = LocalDateTime.of(2026, 3, 2, 18, 0)
    private val game = "com.game"

    private fun decode(policyJson: String): PolicySettings =
        json.decodeFromString(PolicySettings.serializer(), policyJson)

    /** Decodes, walks the real parent-write path, and evaluates — the whole child-side trip. */
    private fun enforce(policyJson: String): Verdict {
        val config = PolicyFuzz.configFor(decode(policyJson), childId = null)
        return RuleEngine.evaluate(config, game, monday)
    }

    @Test
    fun `a window with an impossible start minute degrades instead of taking the device down`() {
        // 1500 is not a minute of any day. LocalTime refuses it, and this is decoded inside
        // the loop, so an unguarded conversion is a crash-loop rather than a broken rule.
        val verdict = enforce(
            """
            {"version":9,"assignments":{"$game":"games"},
             "allAppsBlockedWindows":{"SCHOOL":[{"startMinute":1500,"endMinute":1600}],
                                      "WEEKEND":[],"HOLIDAY":[]}}
            """.trimIndent(),
        )
        assertTrue(verdict !is Verdict.Blocked || verdict.reason != BlockReason.FAIL_CLOSED, "unexpected: $verdict")
    }

    @Test
    fun `a negative minute is refused the same way`() {
        enforce(
            """
            {"version":9,"assignments":{"$game":"games"},
             "appPolicies":{"$game":{"blockedWindows":{"SCHOOL":[{"startMinute":-30,"endMinute":60}]}}}}
            """.trimIndent(),
        )
    }

    @Test
    fun `a bedtime with an impossible minute does not stop the rest of the policy working`() {
        // The malformed rule goes; the budget beside it must survive, or one bad field would
        // quietly disarm a whole policy.
        val config = PolicyFuzz.configFor(
            decode(
                """
                {"version":9,"assignments":{"$game":"games"},
                 "budgets":{"games":{"SCHOOL":0}},
                 "bedtime":{"SCHOOL":{"startMinute":99999,"endMinute":7}}}
                """.trimIndent(),
            ),
            childId = null,
        )
        val verdict = RuleEngine.evaluate(config, game, monday)
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict, "the zero budget beside it was lost")
    }

    @Test
    fun `an unknown day type from a newer parent leaves the known ones enforcing`() {
        // Already the codebase's rule (byDayType); asserted end to end, through enforcement.
        val config = PolicyFuzz.configFor(
            decode(
                """
                {"version":9,"assignments":{"$game":"games"},
                 "budgets":{"games":{"SCHOOL":0,"EXAM_WEEK":15}}}
                """.trimIndent(),
            ),
            childId = null,
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), RuleEngine.evaluate(config, game, monday))
    }

    @Test
    fun `nonsense day numbers in a window mask never crash and never widen it`() {
        // Junk day numbers are dropped; an empty mask means "every day", which over-blocks
        // rather than silently letting the window lapse.
        val config = PolicyFuzz.configFor(
            decode(
                """
                {"version":9,"assignments":{"$game":"games"},
                 "allAppsBlockedWindows":{"SCHOOL":[{"startMinute":0,"endMinute":1439,"days":[0,9,-2]}],
                                          "WEEKEND":[],"HOLIDAY":[]}}
                """.trimIndent(),
            ),
            childId = null,
        )
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(config, game, monday))
    }

    @Test
    fun `an out-of-range weekend edge is ignored rather than shifting the week`() {
        val config = PolicyFuzz.configFor(
            decode(
                """
                {"version":9,"assignments":{"$game":"games"},
                 "weekendStartsFridayAtMinute":5000,"weekendEndsSundayAtMinute":-1}
                """.trimIndent(),
            ),
            childId = null,
        )
        assertEquals(null, config.calendar.weekendStartsFriday)
        assertEquals(null, config.calendar.weekendEndsSunday)
    }

    @Test
    fun `a policy that is only unknown fields still enforces the safe default`() {
        // Everything a future build might add, and nothing this one understands: that decodes
        // to an empty policy — no limits, exactly like a fresh install. Unassigned apps live
        // under General now, so "no rules" reads as usable, never as everything-blocked.
        val config = PolicyFuzz.configFor(
            decode("""{"version":9,"somethingNew":true,"weekendMode":"strict"}"""),
            childId = null,
        )
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, game, monday))
    }
}
