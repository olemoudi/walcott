package dev.walcott.data

import dev.walcott.rules.ActiveBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whose rule is blocking the phone. The screen states it and the button navigates by it, so a
 * wrong answer here sends a parent to edit a rule this child does not use.
 */
class BlockOriginTest {

    private fun block(kind: ActiveBlock.Kind, fromDefault: Boolean = false) =
        ActiveBlock(kind, packageName = "com.game", fromDefaultBudget = fromDefault)

    @Test
    fun `nothing customized means every rule is the family's`() {
        val overrides = ChildOverrides()
        // Every rule, that is. A pause is not one: it was started for this phone from its own
        // row a few minutes ago, so there is no family copy of it to inherit.
        for (kind in ActiveBlock.Kind.entries - ActiveBlock.Kind.PAUSED) {
            assertEquals(RuleOwner.FAMILY, BlockOrigin.of(block(kind), overrides), "$kind")
            assertEquals(RuleOwner.FAMILY, BlockOrigin.of(block(kind, fromDefault = true), overrides), "$kind")
        }
    }

    @Test
    fun `a pause always belongs to the phone it was started on`() {
        assertEquals(RuleOwner.CHILD, BlockOrigin.of(block(ActiveBlock.Kind.PAUSED), ChildOverrides()))
    }

    @Test
    fun `each override only claims its own rule`() {
        // The point of doing this per rule: a child with its own bedtime still inherits the
        // family's screen-free windows, and saying "customized" over both would be a lie in one.
        val bedtimeOnly = ChildOverrides(bedtime = mapOf("SCHOOL" to WindowDto(1290, 450)))
        assertEquals(RuleOwner.CHILD, BlockOrigin.of(block(ActiveBlock.Kind.BEDTIME), bedtimeOnly))
        assertEquals(RuleOwner.FAMILY, BlockOrigin.of(block(ActiveBlock.Kind.SCREEN_FREE), bedtimeOnly))
        assertEquals(RuleOwner.FAMILY, BlockOrigin.of(block(ActiveBlock.Kind.APP_WINDOW), bedtimeOnly))
    }

    @Test
    fun `screen-free windows follow their own override`() {
        val windows = ChildOverrides(allAppsBlockedWindows = mapOf("SCHOOL" to listOf(WindowDto(1020, 1080))))
        assertEquals(RuleOwner.CHILD, BlockOrigin.of(block(ActiveBlock.Kind.SCREEN_FREE), windows))
        assertEquals(RuleOwner.FAMILY, BlockOrigin.of(block(ActiveBlock.Kind.BEDTIME), windows))
    }

    @Test
    fun `an app's own limit and the default limit are different overrides`() {
        // This is the case that made the distinction necessary: a child can carry its own app
        // list while still running on the family's default limit, and the reverse.
        val appsOnly = ChildOverrides(appPolicies = mapOf("com.game" to AppPolicyDto()))
        assertEquals(RuleOwner.CHILD, BlockOrigin.of(block(ActiveBlock.Kind.APP_BLOCKED), appsOnly))
        assertEquals(
            RuleOwner.FAMILY,
            BlockOrigin.of(block(ActiveBlock.Kind.APP_BLOCKED, fromDefault = true), appsOnly),
        )

        val defaultOnly = ChildOverrides(defaultAppBudget = mapOf("SCHOOL" to 60))
        assertEquals(
            RuleOwner.CHILD,
            BlockOrigin.of(block(ActiveBlock.Kind.BUDGET, fromDefault = true), defaultOnly),
        )
        assertEquals(RuleOwner.FAMILY, BlockOrigin.of(block(ActiveBlock.Kind.BUDGET), defaultOnly))
    }
}
