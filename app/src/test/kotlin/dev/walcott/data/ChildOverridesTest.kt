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
        val config = family.resolveForChild("child-a").toFamilyConfig(
            assignments = mapOf("com.game" to "games"),
            essentials = emptySet(),
        )
        assertEquals(Duration.ofMinutes(60), config.policies.getValue("games").dailyBudget[DayType.SCHOOL])
    }

    @Test
    fun `empty overrides report isEmpty`() {
        assertTrue(ChildOverrides().isEmpty)
        assertTrue(!ChildOverrides(bedtime = emptyMap()).isEmpty)
    }
}
