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
    /**
     * Mirrors [RuleEngine.blockedPackages]'s fail-closed branches (no usage counter, or a
     * clock we can't trust). The screen has to agree with what the device is actually doing:
     * a card reading "Available · 2h remaining" over an app that won't open is worse than a
     * block, because the child can't tell whether the phone is broken or the rules are.
     */
    failClosed: Boolean = false,
): CategoryStatus {
    val dayType = config.calendar.dayTypeOf(now)
    val time = now.toLocalTime()
    val used = usageToday[categoryId] ?: Duration.ZERO
    val policy = config.policies[categoryId]
    val budget = policy?.dailyBudget?.get(dayType)

    if (failClosed) {
        return CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.FAIL_CLOSED)
    }

    config.bedtime[dayType]?.let { window ->
        if (time in window) {
            return CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BEDTIME)
        }
    }
    // Family-wide screen-free windows block every category, same as in RuleEngine.evaluate
    // (weekday filters and the special-day opt-out included).
    val specialDay = dayType == DayType.HOLIDAY
    if (config.blockedWindows[dayType].orEmpty().any { it.appliesAt(now, specialDay) }) {
        return CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BLOCKED_WINDOW)
    }
    if (policy != null && policy.blockedWindows[dayType].orEmpty().any { it.appliesAt(now, specialDay) }) {
        return CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BLOCKED_WINDOW)
    }
    if (budget == null) {
        return CategoryStatus(categoryId, CategoryState.ALLOWED, used, null, null, null)
    }
    // Category view: the category's own grant plus any "all apps" grant (per-app grants are a
    // package concept and don't surface on the category card).
    val remaining = budget + (extraTime[ExtraTime.ALL_APPS] ?: Duration.ZERO) +
        (extraTime[categoryId] ?: Duration.ZERO) - used
    return if (remaining > Duration.ZERO) {
        CategoryStatus(categoryId, CategoryState.BUDGETED, used, budget, remaining, null)
    } else {
        CategoryStatus(categoryId, CategoryState.BLOCKED, used, budget, null, BlockReason.BUDGET_EXHAUSTED)
    }
}
