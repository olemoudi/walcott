package dev.walcott.data

import dev.walcott.rules.ExtraTime
import dev.walcott.rules.FamilyConfig
import java.time.Duration
import java.time.LocalDate

/**
 * Parent-side dashboard math over a child's reported usage and its resolved policy. Pure so
 * it's unit-tested on the JVM.
 */
object ChildStats {

    /**
     * Screen time the child can still use today: the sum of every budgeted category's
     * remaining time (categories are independent caps, so the sum is the true maximum).
     * Deliberately ignores bedtime/blocked windows — this is "budget left for the day",
     * not "can they use it this second". Null when no category has a budget today.
     */
    fun remainingToday(
        config: FamilyConfig,
        today: LocalDate,
        usage: Map<String, Duration>,
        extra: Map<String, Duration>,
    ): Duration? {
        val dayType = config.calendar.dayTypeOf(today)
        var anyBudget = false
        var total = Duration.ZERO
        for ((categoryId, policy) in config.policies) {
            val budget = policy.dailyBudget[dayType] ?: continue
            anyBudget = true
            // Same arithmetic as RuleEngine.categoryStatus: base + global extra + own extra − used.
            val remaining = budget +
                (extra[ExtraTime.ALL_APPS] ?: Duration.ZERO) +
                (extra[categoryId] ?: Duration.ZERO) -
                (usage[categoryId] ?: Duration.ZERO)
            if (remaining > Duration.ZERO) total += remaining
        }
        return if (anyBudget) total else null
    }
}
