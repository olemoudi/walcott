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
        budgets = mapOf("games" to mapOf("SCHOOL" to 30)),
        bedtime = mapOf("SCHOOL" to WindowDto(21 * 60, 7 * 60)),
        earnRules = listOf(EarnRuleDto("education", "games", 30, 10, 60)),
        blockedDomains = setOf("youtube.com"),
        deviceRestrictions = setOf("vpn", "datetime"),
        pinHash = "hash",
        pinSalt = "salt",
        familyName = "Moudis",
        children = listOf(
            ChildEntry("child-a", "Ana", ChildOverrides(budgets = mapOf("games" to mapOf("SCHOOL" to 60)))),
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
        assertEquals(mapOf("games" to mapOf("SCHOOL" to 60)), resolved.budgets)
    }

    @Test
    fun `null override fields inherit the family value`() {
        val resolved = family.resolveForChild("child-a")
        assertEquals(family.bedtime, resolved.bedtime)
        assertEquals(family.earnRules, resolved.earnRules)
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
        assertEquals(family.budgets, family.resolveForChild("child-b").budgets)
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
        val config = family.copy(assignments = mapOf("com.game" to "games"))
            .resolveForChild("child-a")
            .toFamilyConfig(essentials = emptySet())
        assertEquals(Duration.ofMinutes(60), config.policies.getValue("games").dailyBudget[DayType.SCHOOL])
        // Assignments are family-wide: they survive the per-child resolve.
        assertEquals("games", config.assignments["com.game"])
    }

    @Test
    fun `empty overrides report isEmpty`() {
        assertTrue(ChildOverrides().isEmpty)
        assertTrue(!ChildOverrides(bedtime = emptyMap()).isEmpty)
        assertTrue(!ChildOverrides(trackingIntervalMinutes = 0).isEmpty)
        assertTrue(!ChildOverrides(locationHistoryEnabled = false).isEmpty)
        assertTrue(!ChildOverrides(updateWifiOnly = false).isEmpty)
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
}
