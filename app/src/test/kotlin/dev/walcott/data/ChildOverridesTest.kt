package dev.walcott.data

import dev.walcott.rules.DayType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class ChildOverridesTest {

    private val family = PolicySettings(
        version = 7,
        defaultAppBudget = mapOf("SCHOOL" to 30),
        bedtime = mapOf("SCHOOL" to WindowDto(21 * 60, 7 * 60)),
        blockedDomains = setOf("youtube.com"),
        deviceRestrictions = setOf("vpn", "datetime"),
        pinHash = "hash",
        pinSalt = "salt",
        familyName = "Moudis",
        children = listOf(
            ChildEntry("child-a", "Ana", ChildOverrides(defaultAppBudget = mapOf("SCHOOL" to 60))),
            ChildEntry("child-b", "Bea"),
        ),
    )

    @Test
    fun `blank or unknown childId returns the family policy unchanged`() {
        assertSame(family, family.resolveForChild(""))
        assertSame(family, family.resolveForChild(null))
        assertSame(family, family.resolveForChild("nope"))
    }

    @Test
    fun `an overridden field replaces the family value wholesale`() {
        val resolved = family.resolveForChild("child-a")
        assertEquals(mapOf("SCHOOL" to 60), resolved.defaultAppBudget)
    }

    @Test
    fun `null override fields inherit the family value`() {
        val resolved = family.resolveForChild("child-a")
        assertEquals(family.bedtime, resolved.bedtime)
        assertEquals(family.blockedDomains, resolved.blockedDomains)
        assertEquals(family.deviceRestrictions, resolved.deviceRestrictions)
    }

    @Test
    fun `device restrictions can be overridden per child`() {
        val loosened = family.copy(
            children = listOf(ChildEntry("child-a", "Ana", ChildOverrides(deviceRestrictions = setOf("vpn")))),
        )
        assertEquals(setOf("vpn"), loosened.resolveForChild("child-a").deviceRestrictions)
        assertEquals(setOf("vpn", "datetime"), loosened.resolveForChild("unknown").deviceRestrictions)
    }

    @Test
    fun `a child without overrides gets the family policy`() {
        assertEquals(family.defaultAppBudget, family.resolveForChild("child-b").defaultAppBudget)
    }

    @Test
    fun `resolution never touches pin, family name, version or the registry`() {
        val resolved = family.resolveForChild("child-a")
        assertEquals("hash", resolved.pinHash)
        assertEquals("salt", resolved.pinSalt)
        assertEquals("Moudis", resolved.familyName)
        assertEquals(7, resolved.version)
        assertEquals(family.children, resolved.children)
    }

    @Test
    fun `resolved settings flow through toFamilyConfig`() {
        val config = family.resolveForChild("child-a").toFamilyConfig(essentials = emptySet())
        assertEquals(Duration.ofMinutes(60), config.defaultAppBudget[DayType.SCHOOL])
    }

    @Test
    fun `empty overrides report isEmpty`() {
        assertTrue(ChildOverrides().isEmpty)
        assertTrue(!ChildOverrides(bedtime = emptyMap()).isEmpty)
        assertTrue(!ChildOverrides(trackingIntervalMinutes = 0).isEmpty)
        assertTrue(!ChildOverrides(locationHistoryEnabled = false).isEmpty)
        assertTrue(!ChildOverrides(updateWifiOnly = false).isEmpty)
        assertTrue(!ChildOverrides(appPolicies = emptyMap()).isEmpty)
        assertTrue(!ChildOverrides(allAppsBlockedWindows = emptyMap()).isEmpty)
    }

    @Test
    fun `family screen-free windows resolve per-child, empty map opting out entirely`() {
        val window = WindowDto(startMinute = 21 * 60, endMinute = 21 * 60 + 30)
        val fam = family.copy(
            allAppsBlockedWindows = mapOf(DayType.SCHOOL.name to listOf(window)),
            children = listOf(
                // The laxer sibling: no screen-free windows at all.
                ChildEntry("w1", "Ana", ChildOverrides(allAppsBlockedWindows = emptyMap())),
                ChildEntry("w2", "Bea"),
            ),
        )
        val ana = fam.resolveForChild("w1").toFamilyConfig(emptySet())
        val bea = fam.resolveForChild("w2").toFamilyConfig(emptySet())
        assertTrue(ana.blockedWindows[DayType.SCHOOL].orEmpty().isEmpty())
        assertEquals(1, bea.blockedWindows[DayType.SCHOOL].orEmpty().size)
    }

    @Test
    fun `per-app policies resolve per-child override over the family map`() {
        val fam = family.copy(
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 60))),
            children = listOf(
                // This child gets a tighter per-app cap; the override replaces the whole map.
                ChildEntry(
                    "p1", "Ana",
                    ChildOverrides(
                        appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 15))),
                    ),
                ),
                ChildEntry("p2", "Bea"),
            ),
        )
        val ana = fam.resolveForChild("p1").toFamilyConfig(emptySet())
        val bea = fam.resolveForChild("p2").toFamilyConfig(emptySet())
        assertEquals(
            Duration.ofMinutes(15),
            ana.perAppPolicies.getValue("com.game").dailyBudget[DayType.SCHOOL],
        )
        assertEquals(
            Duration.ofMinutes(60),
            bea.perAppPolicies.getValue("com.game").dailyBudget[DayType.SCHOOL],
        )
    }

    @Test
    fun `update-wifi-only resolves per-child override over the family default`() {
        val fam = family.copy(
            updateWifiOnly = true, // family: Wi-Fi only
            children = listOf(
                // This child may use mobile data (e.g. a teen who is often out).
                ChildEntry("w1", "Ana", ChildOverrides(updateWifiOnly = false)),
                ChildEntry("w2", "Bea"),
            ),
        )
        assertEquals(false, fam.resolveForChild("w1").updateWifiOnly) // override wins
        assertEquals(true, fam.resolveForChild("w2").updateWifiOnly) // inherits the family default
    }

    @Test
    fun `update-wifi-only defaults off and a child can be stricter than the family`() {
        assertEquals(false, PolicySettings().updateWifiOnly)
        val fam = family.copy(
            updateWifiOnly = false,
            children = listOf(ChildEntry("w1", "Ana", ChildOverrides(updateWifiOnly = true))),
        )
        assertEquals(true, fam.resolveForChild("w1").updateWifiOnly)
    }

    @Test
    fun `location history resolves per-child override over the family default`() {
        val fam = family.copy(
            locationHistoryEnabled = true,
            children = listOf(
                // Explicitly opted out, even though the family keeps history.
                ChildEntry("h1", "Ana", ChildOverrides(locationHistoryEnabled = false)),
                ChildEntry("h2", "Bea"),
            ),
        )
        assertEquals(false, fam.resolveForChild("h1").locationHistoryEnabled)
        assertEquals(true, fam.resolveForChild("h2").locationHistoryEnabled)
    }

    @Test
    fun `location history is off unless someone turns it on`() {
        // History is opt-in: a family that never touched the setting must not collect a trail.
        assertEquals(false, PolicySettings().locationHistoryEnabled)
        assertEquals(false, family.resolveForChild("child-b").locationHistoryEnabled)
    }

    @Test
    fun `a child can keep history while the family default is off`() {
        val fam = family.copy(
            locationHistoryEnabled = false,
            children = listOf(ChildEntry("h1", "Ana", ChildOverrides(locationHistoryEnabled = true))),
        )
        assertEquals(true, fam.resolveForChild("h1").locationHistoryEnabled)
    }

    @Test
    fun `tracking interval resolves per-child override over the family default`() {
        val fam = family.copy(
            trackingIntervalMinutes = 15,
            children = listOf(
                ChildEntry("t1", "Ana", ChildOverrides(trackingIntervalMinutes = 5)),
                ChildEntry("t2", "Bea"),
            ),
        )
        assertEquals(5, fam.resolveForChild("t1").trackingIntervalMinutes) // per-child override
        assertEquals(15, fam.resolveForChild("t2").trackingIntervalMinutes) // inherits family default
    }

    @Test
    fun `the customized count sees this child's own daily limit`() {
        // The count used to be built from `budgets`, the pre-0.35 category map that the
        // migration blanks and no install has written since — so the one rule most likely to
        // be customized read as inherited, and the fold said "nothing customized" over a child
        // with their own limit.
        assertEquals(1, ChildOverrides(defaultAppBudget = mapOf("SCHOOL" to 60)).customRuleCount)
        assertEquals(0, ChildOverrides(budgets = mapOf("games" to mapOf("SCHOOL" to 60))).customRuleCount)
    }

    @Test
    fun `it counts the six rules that section owns, and nothing else`() {
        val all = ChildOverrides(
            bedtime = emptyMap(), allAppsBlockedWindows = emptyMap(), defaultAppBudget = emptyMap(),
            appPolicies = emptyMap(), blockedDomains = emptySet(), deviceRestrictions = emptySet(),
        )
        assertEquals(6, all.customRuleCount)
        // Location and updates have their own rows elsewhere on that screen: counting them
        // there would report a number the section cannot explain.
        assertEquals(
            0,
            ChildOverrides(trackingIntervalMinutes = 15, locationHistoryEnabled = true, updateWifiOnly = true)
                .customRuleCount,
        )
    }

    @Test
    fun `the support switches are per member, and inherit the family value until set`() {
        // These two are questions about ONE phone ("is this phone reachable", "does this phone keep
        // a log"), so a member with no answer of their own must take the family's rather than a
        // hardcoded off — otherwise a family-wide setting would apply to nobody.
        val fam = PolicySettings(
            version = 1,
            keepRingerAudible = true,
            notificationLogEnabled = false,
            children = listOf(
                ChildEntry("t1", "One", ChildOverrides(keepRingerAudible = false, notificationLogEnabled = true)),
                ChildEntry("t2", "Two"),
            ),
        )
        val own = fam.resolveForChild("t1")
        assertEquals(false, own.keepRingerAudible, "this member said no; the family's yes must not win")
        assertEquals(true, own.notificationLogEnabled)
        val inherited = fam.resolveForChild("t2")
        assertEquals(true, inherited.keepRingerAudible)
        assertEquals(false, inherited.notificationLogEnabled)
    }

    @Test
    fun `the support switches are not counted as customized rules`() {
        // They have their own cards, in their own section. Counting them in "N rules customized"
        // would report a number the rules fold cannot explain — the same mistake location and
        // updates are already kept out of.
        val overrides = ChildOverrides(keepRingerAudible = true, notificationLogEnabled = true)
        assertEquals(0, overrides.customRuleCount)
        // But they are still SOMETHING, so a member with only these has not "inherited everything"
        // — otherwise the "use the family's rules for everything" button would silently skip them.
        assertTrue(!overrides.isEmpty)
    }

    @Test
    fun `an override set to empty still counts as this child's own`() {
        // Empty is a real answer — "no bedtime for this one" — and the whole reason the fields
        // are nullable. A count that treated it as inherited would hide the laxer sibling.
        assertEquals(1, ChildOverrides(bedtime = emptyMap()).customRuleCount)
        assertTrue(!ChildOverrides(bedtime = emptyMap()).isEmpty)
    }
}
