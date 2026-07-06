package dev.walcott.rules

import java.time.Duration

/**
 * A rule that turns time spent in one category into extra budget for another. E.g. "every
 * 10 minutes of Education earns 5 minutes of Games, up to 30 minutes a day".
 */
data class EarnRule(
    val sourceCategoryId: String,
    val targetCategoryId: String,
    /** Block size in the source category that yields a reward. Must be > 0. */
    val sourceMinutesPerReward: Int,
    /** Minutes granted to the target per completed source block. */
    val rewardMinutes: Int,
    /** Maximum minutes this rule can grant in a single day. */
    val dailyCapMinutes: Int,
)

/**
 * Computes earned extra time from today's usage. Pure and deterministic: the enforcement
 * layer adds this on top of manually granted extra when evaluating budgets.
 */
object EarnEngine {

    fun computeEarned(
        rules: List<EarnRule>,
        usageBySource: Map<String, Duration>,
    ): Map<String, Duration> {
        val earnedMinutes = mutableMapOf<String, Long>()
        for (rule in rules) {
            if (rule.sourceMinutesPerReward <= 0 || rule.rewardMinutes <= 0) continue
            val usedMinutes = (usageBySource[rule.sourceCategoryId] ?: Duration.ZERO).toMinutes()
            val blocks = usedMinutes / rule.sourceMinutesPerReward
            val granted = (blocks * rule.rewardMinutes).coerceAtMost(rule.dailyCapMinutes.toLong())
            if (granted > 0) {
                earnedMinutes[rule.targetCategoryId] = (earnedMinutes[rule.targetCategoryId] ?: 0L) + granted
            }
        }
        return earnedMinutes.mapValues { Duration.ofMinutes(it.value) }
    }
}
