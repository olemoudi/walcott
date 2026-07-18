package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime

/**
 * Deterministic, stateless evaluator: it reads no clock or storage — everything comes in
 * as parameters. The enforcement service calls it with real state; tests, with whatever
 * state they want to reproduce.
 *
 * Precedence: essential > bedtime > unclassified > blocked window > budget.
 */
object RuleEngine {

    fun evaluate(
        config: FamilyConfig,
        packageName: String,
        now: LocalDateTime,
        /** Time used today per category (categoryId -> duration). */
        usageToday: Map<String, Duration> = emptyMap(),
        /** Extra time granted today per category (approvals, spent ledger…). */
        extraTime: Map<String, Duration> = emptyMap(),
    ): Verdict {
        if (packageName in config.essentialPackages) return Verdict.Allowed

        val dayType = config.calendar.dayTypeOf(now.toLocalDate())
        val time = now.toLocalTime()

        config.bedtime[dayType]?.let { window ->
            if (time in window) return Verdict.Blocked(BlockReason.BEDTIME)
        }

        val categoryId = config.assignments[packageName]
            ?: return Verdict.Blocked(BlockReason.UNCLASSIFIED)
        val policy = config.policies[categoryId] ?: return Verdict.Allowed

        if (policy.blockedWindows[dayType].orEmpty().any { time in it }) {
            return Verdict.Blocked(BlockReason.BLOCKED_WINDOW)
        }

        val budget = policy.dailyBudget[dayType] ?: return Verdict.Allowed
        val allowedTotal = budget + (extraTime[categoryId] ?: Duration.ZERO)
        val remaining = allowedTotal - (usageToday[categoryId] ?: Duration.ZERO)
        return if (remaining > Duration.ZERO) {
            Verdict.AllowedWithBudget(remaining)
        } else {
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED)
        }
    }

    /**
     * Whether this config must fail CLOSED when screen-time counting is unavailable (usage
     * access revoked). Budgets depend on the counter: without it they never run out, so
     * revoking the permission would mean unlimited time — the opposite of what the parent
     * configured. Pure time rules (bedtime, blocked windows) don't need the counter, so a
     * config without budgets can safely keep enforcing as usual.
     */
    fun requiresUsageCounting(config: FamilyConfig): Boolean =
        config.policies.values.any { it.dailyBudget.isNotEmpty() }
}
