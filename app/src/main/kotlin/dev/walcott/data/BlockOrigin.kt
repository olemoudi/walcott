package dev.walcott.data

import dev.walcott.rules.ActiveBlock

/** Whose rule is doing this: one written for this child, or the family's. */
enum class RuleOwner { CHILD, FAMILY }

/**
 * Which rule a live block came from, and therefore where it is edited.
 *
 * A blocked phone is read wrong without this. The rules a child answers to are two sets that
 * look identical once resolved — the family's, and whatever was customized for that child — so
 * "Bedtime" on the parent's screen was a sentence with the subject missing: a parent editing the
 * family's bedtime to lift it can be editing a rule this child does not use, and never finding
 * out except by watching nothing happen.
 *
 * The mapping is per rule rather than per child: an override is one field, so a child can carry
 * its own bedtime and still inherit the family's screen-free windows. That is also why the
 * budget kinds ask which budget produced them ([ActiveBlock.fromDefaultBudget]) — the default
 * limit and an app's own limit are different fields with different overrides, and they live on
 * different screens.
 */
object BlockOrigin {

    fun of(block: ActiveBlock, overrides: ChildOverrides): RuleOwner {
        val own = when (block.kind) {
            ActiveBlock.Kind.BEDTIME -> overrides.bedtime != null
            ActiveBlock.Kind.SCREEN_FREE -> overrides.allAppsBlockedWindows != null
            ActiveBlock.Kind.APP_WINDOW -> overrides.appPolicies != null
            ActiveBlock.Kind.BUDGET, ActiveBlock.Kind.APP_BLOCKED ->
                if (block.fromDefaultBudget) overrides.defaultAppBudget != null else overrides.appPolicies != null
        }
        return if (own) RuleOwner.CHILD else RuleOwner.FAMILY
    }
}
