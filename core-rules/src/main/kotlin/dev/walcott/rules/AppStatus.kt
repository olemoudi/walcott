package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime

enum class AppState { ALLOWED, BUDGETED, BLOCKED }

/** Snapshot of one app's situation at an instant, for rendering the child screen. */
data class AppStatus(
    val packageName: String,
    val state: AppState,
    val used: Duration,
    /** Base budget for the day, or null when this app has no limit today. */
    val budget: Duration?,
    /** Time left (budget + extra − used); only in the BUDGETED state. */
    val remaining: Duration?,
    /** Reason when state == BLOCKED. */
    val blockReason: BlockReason?,
)

/**
 * The same logic as [RuleEngine.evaluate], but reporting how much has been used and how much is
 * left rather than a yes/no — what the child's screen needs to draw a card. Same precedence:
 * bedtime > blocked window > budget.
 */
fun RuleEngine.appStatus(
    config: FamilyConfig,
    packageName: String,
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
): AppStatus {
    val dayType = config.calendar.dayTypeOf(now)
    val time = now.toLocalTime()
    val used = usageToday[packageName] ?: Duration.ZERO
    val budget = config.budgetFor(packageName, dayType)

    fun blocked(reason: BlockReason) = AppStatus(packageName, AppState.BLOCKED, used, budget, null, reason)

    if (failClosed) return blocked(BlockReason.FAIL_CLOSED)

    config.bedtime[dayType]?.let { window -> if (time in window) return blocked(BlockReason.BEDTIME) }

    val specialDay = dayType == DayType.HOLIDAY
    if (config.blockedWindows[dayType].orEmpty().any { it.appliesAt(now, specialDay) }) {
        return blocked(BlockReason.BLOCKED_WINDOW)
    }
    val own = config.perAppPolicies[packageName]
    if (own?.blockedWindows?.get(dayType).orEmpty().any { it.appliesAt(now, specialDay) }) {
        return blocked(BlockReason.BLOCKED_WINDOW)
    }
    if (budget == null) return AppStatus(packageName, AppState.ALLOWED, used, null, null, null)

    // Same widening rule as the engine: a grant to this app always counts, an "all apps" grant
    // only reaches apps running on the family default (see RuleEngine.evaluate).
    val appExtra = extraTime[packageName] ?: Duration.ZERO
    val sharedExtra =
        if (config.usesDefaultBudget(packageName)) extraTime[ExtraTime.ALL_APPS] ?: Duration.ZERO
        else Duration.ZERO
    val remaining = budget + appExtra + sharedExtra - used
    return if (remaining > Duration.ZERO) {
        AppStatus(packageName, AppState.BUDGETED, used, budget, remaining, null)
    } else {
        blocked(BlockReason.BUDGET_EXHAUSTED)
    }
}
