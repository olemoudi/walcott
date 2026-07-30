package dev.walcott.policy

import dev.walcott.data.ChildEntry
import dev.walcott.data.ChildOverrides
import dev.walcott.data.DomainAppRuleDto
import dev.walcott.data.PolicySettings
import dev.walcott.data.WindowDto
import dev.walcott.rules.BlockReason
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * The seams a generated corpus can only sample: settings that compose into one verdict, and the
 * few decisions the policy makes ABOUT the policy (which child a device is, whether the DNS
 * filter needs to run at all). Each of these was reachable in the product and unasserted.
 */
class PolicyCompositionTest {

    private val game = "com.game"
    private val monday = LocalDate.of(2026, 3, 2)
    private val friday = monday.plusDays(4)

    private fun config(settings: PolicySettings, childId: String?) = PolicyFuzz.configFor(settings, childId)

    // --- Which child is this device? ---

    @Test
    fun `a device whose child id is not in the registry inherits the family policy`() {
        // Legacy and anonymous children exist by design (resolveForChild says so). If an
        // unknown id resolved to "no overrides found, therefore no rules", such a device would
        // silently stop being limited.
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 0)),
            children = listOf(ChildEntry("known", "Ana", ChildOverrides(budgets = emptyMap()))),
        )
        val family = config(settings, childId = null)
        for (id in listOf(null, "", "not-a-child")) {
            assertEquals(
                RuleEngine.evaluate(family, game, monday.atTime(18, 0)),
                RuleEngine.evaluate(config(settings, id), game, monday.atTime(18, 0)),
                "a device identifying as ${id ?: "nobody"} did not inherit the family rules",
            )
        }
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), RuleEngine.evaluate(family, game, monday.atTime(18, 0)))
    }

    @Test
    fun `two children under one family policy get their own verdicts`() {
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 60)),
            children = listOf(
                ChildEntry("strict", "Ana", ChildOverrides(budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 0)))),
                ChildEntry("loose", "Luis", ChildOverrides()),
            ),
        )
        val now = monday.atTime(18, 0)
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(config(settings, "strict"), game, now),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofHours(1)),
            RuleEngine.evaluate(config(settings, "loose"), game, now),
        )
    }

    @Test
    fun `a child override replaces a whole field, so an empty override means no limit`() {
        // The documented shape: null inherits, non-null replaces wholesale. An empty map is a
        // deliberate "this child has no budgets", not a mistake to be filled in from the family.
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 0)),
            children = listOf(ChildEntry("free", "Ana", ChildOverrides(budgets = emptyMap()))),
        )
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config(settings, "free"), game, monday.atTime(18, 0)))
    }

    @Test
    fun `a child's own blocked windows replace the family's`() {
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            blockedWindows = mapOf("games" to mapOf(DayType.SCHOOL.name to listOf(WindowDto(9 * 60, 12 * 60)))),
            children = listOf(
                ChildEntry(
                    "afternoon", "Ana",
                    ChildOverrides(
                        blockedWindows = mapOf("games" to mapOf(DayType.SCHOOL.name to listOf(WindowDto(17 * 60, 19 * 60)))),
                    ),
                ),
            ),
        )
        val child = config(settings, "afternoon")
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(child, game, monday.atTime(10, 0)))
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(child, game, monday.atTime(18, 0)))
    }

    // --- Settings that compose ---

    @Test
    fun `the weekend edge, the holiday mirror and a special-day column compose in that order`() {
        // A Friday afternoon is already the weekend; a Friday that is ALSO a marked special day
        // is a special day, and which budget that means depends on whether the family claimed
        // the column. All three settings meet on one instant.
        val base = PolicySettings(
            assignments = mapOf(game to "games"),
            budgets = mapOf(
                "games" to mapOf(DayType.SCHOOL.name to 30, DayType.WEEKEND.name to 120, DayType.HOLIDAY.name to 240),
            ),
            weekendStartsFridayAtMinute = 14 * 60,
            holidays = setOf(friday.toEpochDay()),
        )
        val at15 = friday.atTime(15, 0)

        // Mirrored: the special day gets the weekend's 2h, and the 4h a parent typed is discarded.
        val mirrored = config(base, null)
        assertEquals(Verdict.AllowedWithBudget(Duration.ofHours(2)), RuleEngine.evaluate(mirrored, game, at15))

        // Claimed: the special-day column stands, and it outranks the weekend edge.
        val claimed = config(base.copy(specialDaysOwnBudget = true), null)
        assertEquals(Verdict.AllowedWithBudget(Duration.ofHours(4)), RuleEngine.evaluate(claimed, game, at15))
    }

    @Test
    fun `a window whose ends are equal never blocks anything`() {
        // The time pickers happily produce From 17:00 To 17:00. Half-open means it covers no
        // instant at all — which must read as "no window", not "all day".
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            allAppsBlockedWindows = DayType.entries.associate { it.name to listOf(WindowDto(17 * 60, 17 * 60)) },
        )
        val config = config(settings, null)
        for (hour in 0..23) {
            assertEquals(
                Verdict.Allowed,
                RuleEngine.evaluate(config, game, monday.atTime(hour, 0)),
                "an empty window blocked at $hour:00",
            )
        }
    }

    @Test
    fun `crossing midnight moves the day type before the window ends`() {
        // Friday's late window runs into Saturday, and Saturday is a different day type. The
        // window has to keep applying from the slot it started in, or the rule dies at midnight.
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            allAppsBlockedWindows = DayType.entries.associate {
                it.name to listOf(WindowDto(23 * 60, 2 * 60, days = listOf(5))) // Friday only
            },
        )
        val config = config(settings, null)
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(config, game, friday.atTime(23, 30)))
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(config, game, friday.plusDays(1).atTime(1, 0)),
            "the window stopped at midnight instead of finishing the night it started",
        )
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, game, friday.plusDays(1).atTime(23, 30)))
    }

    @Test
    fun `usage resets with the calendar day, not with the rules`() {
        // The counter is keyed by day: yesterday's exhausted budget must not follow the child
        // into today, and today's must not be forgiven by a day-type flip at midnight.
        val settings = PolicySettings(
            assignments = mapOf(game to "games"),
            budgets = mapOf("games" to mapOf(DayType.SCHOOL.name to 60, DayType.WEEKEND.name to 60)),
        )
        val config = config(settings, null)
        val spent = mapOf("games" to Duration.ofHours(1))
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(config, game, friday.atTime(23, 59), spent),
        )
        // Same counter, one minute later and a new day type: the engine is stateless, so the
        // reset is the caller's job — and the day-keyed counter is what performs it.
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofHours(1)),
            RuleEngine.evaluate(config, game, friday.plusDays(1).atTime(0, 0), usageToday = emptyMap()),
        )
    }

    // --- Decisions the policy makes about itself ---

    @Test
    fun `the DNS filter runs exactly when there is something to filter`() {
        // hasWebFilter is the switch for the VPN service. False when there is nothing to block
        // (no idle tunnel), true for either kind of rule — a per-app rule alone counts, which is
        // the case a domains-only check would have missed.
        assertFalse(PolicySettings().hasWebFilter())
        assertTrue(PolicySettings(blockedDomains = setOf("tiktok.com")).hasWebFilter())
        assertTrue(
            PolicySettings(domainAppRules = listOf(DomainAppRuleDto("youtube.com", game, allowOnlyFromApp = true)))
                .hasWebFilter(),
        )
        assertTrue(
            PolicySettings(
                blockedDomains = setOf("tiktok.com"),
                domainAppRules = listOf(DomainAppRuleDto("youtube.com", game, allowOnlyFromApp = false)),
            ).hasWebFilter(),
        )
    }

    @Test
    fun `a child's blocked domains replace the family's for that child only`() {
        val settings = PolicySettings(
            blockedDomains = setOf("family.example"),
            children = listOf(ChildEntry("c1", "Ana", ChildOverrides(blockedDomains = setOf("ana.example")))),
        )
        assertEquals(setOf("ana.example"), settings.resolveForChild("c1").blockedDomains)
        assertEquals(setOf("family.example"), settings.resolveForChild(null).blockedDomains)
        assertTrue(settings.resolveForChild("c1").hasWebFilter())
    }
}
