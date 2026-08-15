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
    // The budget plus whatever extra time reaches this app, by the same widening rule the
    // engine uses — one implementation, in FamilyConfig, so the two cannot drift apart. Null
    // for exactly the apps that have no budget at all, which is what "no limit today" means.
    val allowance = config.allowanceFor(packageName, dayType, extraTime)
        ?: return AppStatus(packageName, AppState.ALLOWED, used, null, null, null)
    val remaining = allowance - used
    return if (remaining > Duration.ZERO) {
        AppStatus(packageName, AppState.BUDGETED, used, budget, remaining, null)
    } else {
        blocked(BlockReason.BUDGET_EXHAUSTED)
    }
}
