package dev.walcott.data

import dev.walcott.rules.DayType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Who the family editors have to warn about, and who they must not. */
class RuleOverridesTest {

    private fun family(vararg members: ChildEntry) = PolicySettings(children = members.toList())

    private fun member(name: String, overrides: ChildOverrides = ChildOverrides()) =
        ChildEntry(childId = name.lowercase(), name = name, overrides = overrides)

    @Test
    fun `a member who inherits a rule is not reported against it`() {
        val settings = family(member("Ana"), member("Leo"))
        FamilyRule.entries.forEach { rule ->
            assertEquals(emptyList<String>(), RuleOverrides.namesOverriding(settings, rule), "$rule")
        }
    }

    @Test
    fun `only the members who took the rule are reported, in registry order`() {
        val settings = family(
            member("Ana", ChildOverrides(bedtime = mapOf(DayType.SCHOOL.name to WindowDto(23 * 60, 6 * 60)))),
            member("Leo"),
            member("Mar", ChildOverrides(bedtime = emptyMap())),
        )
        assertEquals(listOf("Ana", "Mar"), RuleOverrides.namesOverriding(settings, FamilyRule.BEDTIME))
    }

    @Test
    fun `each rule reads its own override field and no other`() {
        // A member who customized their bedtime has NOT customized their web filter. Reporting
        // one rule's override against another editor would send a parent to change something
        // that was never the reason their edit had no effect.
        val settings = family(member("Ana", ChildOverrides(bedtime = emptyMap())))
        assertEquals(listOf("Ana"), RuleOverrides.namesOverriding(settings, FamilyRule.BEDTIME))
        FamilyRule.entries.filter { it != FamilyRule.BEDTIME }.forEach { rule ->
            assertEquals(emptyList<String>(), RuleOverrides.namesOverriding(settings, rule), "$rule")
        }
    }

    @Test
    fun `an override holding the family's own values still counts as taken`() {
        // The two are copies, not a link: from the switch onward, every family edit passes this
        // member by. Warning only on a CURRENT disagreement would go silent at exactly the
        // moment a parent is editing the family rule that is about to have no effect on them.
        val sameAsFamily = mapOf(DayType.SCHOOL.name to WindowDto(22 * 60, 6 * 60))
        val settings = PolicySettings(
            bedtime = sameAsFamily,
            children = listOf(member("Ana", ChildOverrides(bedtime = sameAsFamily))),
        )
        assertEquals(listOf("Ana"), RuleOverrides.namesOverriding(settings, FamilyRule.BEDTIME))
    }

    @Test
    fun `an empty override is a rule taken away, not a rule absent`() {
        // "No screen-free windows for this member at all" — the laxer-sibling case. It is the
        // override most worth warning about and the easiest to mistake for inheritance.
        val settings = family(member("Ana", ChildOverrides(allAppsBlockedWindows = emptyMap())))
        assertTrue(FamilyRule.SCREEN_FREE.isTakenOverBy(settings.children.first().overrides))
        assertEquals(listOf("Ana"), RuleOverrides.namesOverriding(settings, FamilyRule.SCREEN_FREE))
    }

    @Test
    fun `a member registered with no name is counted but never rendered blank`() {
        val settings = family(member("Ana", ChildOverrides(bedtime = emptyMap())), ChildEntry("x", "  ", ChildOverrides(bedtime = emptyMap())))
        assertEquals(2, RuleOverrides.membersOverriding(settings, FamilyRule.BEDTIME).size)
        assertEquals(listOf("Ana"), RuleOverrides.namesOverriding(settings, FamilyRule.BEDTIME))
    }

    @Test
    fun `the members offered as a way to their own rule are exactly the ones that can be named`() {
        // The note draws a button per member and labels it with their name, so a nameless entry
        // would be an unlabelled button that navigates somewhere. Same filter as the sentence,
        // from the same place, or the two would disagree about who is on the card.
        val settings = family(
            member("Ana", ChildOverrides(bedtime = emptyMap())),
            ChildEntry("x", "   ", ChildOverrides(bedtime = emptyMap())),
            member("Leo", ChildOverrides(bedtime = emptyMap())),
        )
        val offered = RuleOverrides.namedMembersOverriding(settings, FamilyRule.BEDTIME)
        assertEquals(listOf("ana", "leo"), offered.map { it.childId })
        assertEquals(RuleOverrides.namesOverriding(settings, FamilyRule.BEDTIME), offered.map { it.name })
    }

    @Test
    fun `every rule the enum names is one a member can actually take`() {
        // Guards against a rule being added here with no override behind it, which would render
        // a warning that can never appear — and against the reverse, silently.
        val everything = ChildOverrides(
            bedtime = emptyMap(),
            allAppsBlockedWindows = emptyMap(),
            defaultAppBudget = emptyMap(),
            appPolicies = emptyMap(),
            blockedDomains = emptySet(),
            deviceRestrictions = emptySet(),
            trackingIntervalMinutes = 0,
            locationHistoryEnabled = false,
            updateWifiOnly = false,
        )
        FamilyRule.entries.forEach { rule ->
            assertTrue(rule.isTakenOverBy(everything), "$rule is not reachable from ChildOverrides")
            assertFalse(rule.isTakenOverBy(ChildOverrides()), "$rule fires on a member who inherits")
        }
    }
}
