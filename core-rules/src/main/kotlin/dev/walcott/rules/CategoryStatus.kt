package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime

enum class CategoryState { ALLOWED, BUDGETED, BLOCKED }

/** Snapshot of a category's state at an instant, for rendering the child screen. */
data class CategoryStatus(
    val categoryId: String,
    val state: CategoryState,
    val used: Duration,
    /** Base budget for the day, or null if the category has no limit today. */
    val budget: Duration?,
    /** Time left (budget + extra − used); only in the BUDGETED state. */
    val remaining: Duration?,
    /** Reason when state == BLOCKED. */
    val blockReason: BlockReason?,
)

/**
 * Per-category view of the same logic as [RuleEngine.evaluate], to summarize the situation
 * without referring to a specific package. Same precedence:
 * bedtime > blocked window > budget. (Essential and unclassified are package concepts, not
 * category concepts, so they don't apply here.)
 */
fun RuleEngine.categoryStatus(
    config: FamilyConfig,
    categoryId: String,
    now: LocalDateTime,
    usageToday: Map<String, Duration> = emptyMap(),
    extraTime: Map<String, Duration> = emptyMap(),
): CategoryStatus {
    val dayType = config.calendar.dayTypeOf(now.toLocalDate())
    val time = now.toLocalTime()
    val used = usageToday[categoryId] ?: Duration.ZERO
    val policy = config.policies[categoryId]
    val budget = policy?.dailyBudget?.get(dayType)

    config.bedtime[dayType]?.let { window ->
        if (time in window) {
            return CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BEDTIME)
        }
    }
    if (policy != null && policy.blockedWindows[dayType].orEmpty().any { time in it }) {
        return CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BLOCKED_WINDOW)
    }
    if (budget == null) {
        return CategoryStatus(categoryId, CategoryState.ALLOWED, used, null, null, null)
    }
    val remaining = budget + (extraTime[categoryId] ?: Duration.ZERO) - used
    return if (remaining > Duration.ZERO) {
        CategoryStatus(categoryId, CategoryState.BUDGETED, used, budget, remaining, null)
    } else {
        CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BUDGET_EXHAUSTED)
    }
}
