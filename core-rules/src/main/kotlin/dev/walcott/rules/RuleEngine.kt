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
        // Family-wide screen-free windows: like bedtime, a hard block on every non-essential
        // app (before classification, so unclassified apps are inside too); extra time never
        // lifts a window.
        if (config.blockedWindows[dayType].orEmpty().any { time in it }) {
            return Verdict.Blocked(BlockReason.BLOCKED_WINDOW)
        }

        val categoryId = config.assignments[packageName]
            ?: return Verdict.Blocked(BlockReason.UNCLASSIFIED)
        val policy = config.policies[categoryId]
        val appPolicy = config.perAppPolicies[packageName]
        if (policy == null && appPolicy == null) return Verdict.Allowed

        // Blocked windows: category OR per-app (the per-app ones only add restrictions).
        val inCategoryWindow = policy?.blockedWindows?.get(dayType).orEmpty().any { time in it }
        val inAppWindow = appPolicy?.blockedWindows?.get(dayType).orEmpty().any { time in it }
        if (inCategoryWindow || inAppWindow) return Verdict.Blocked(BlockReason.BLOCKED_WINDOW)

        // Extra time applying to this package: everyone's grant + its category's + its own.
        val globalExtra = extraTime[ExtraTime.ALL_APPS] ?: Duration.ZERO
        val appExtra = extraTime[packageName] ?: Duration.ZERO

        // Budgets: the category budget (widened by global + category + this-app extra) and the
        // per-app sub-cap (widened only by a grant to THIS app — a blanket "all apps" grant must
        // not blow through a deliberately tight per-app cap). Whichever bites first blocks.
        val categoryRemaining = policy?.dailyBudget?.get(dayType)?.let { budget ->
            budget + globalExtra + (extraTime[categoryId] ?: Duration.ZERO) + appExtra -
                (usageToday[categoryId] ?: Duration.ZERO)
        }
        val appRemaining = appPolicy?.dailyBudget?.get(dayType)?.let { budget ->
            budget + appExtra - (usageToday[packageName] ?: Duration.ZERO)
        }
        val remaining = listOfNotNull(categoryRemaining, appRemaining).minOrNull()
            ?: return Verdict.Allowed // neither has a budget for this day type
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

    /**
     * The set of [managed] packages that must be suspended right now — the single decision the
     * enforcement loop acts on. Fails CLOSED: when the usage counter is unavailable
     * ([usageCountingAvailable] = false) and the config relies on budgets, every managed app is
     * blocked, so revoking usage access can never buy unlimited time. Pure, so this whole
     * control (including the fail-closed branch) is unit-tested rather than only exercised live.
     */
    fun blockedPackages(
        config: FamilyConfig,
        managed: Set<String>,
        now: LocalDateTime,
        usageToday: Map<String, Duration> = emptyMap(),
        extraTime: Map<String, Duration> = emptyMap(),
        usageCountingAvailable: Boolean = true,
    ): Set<String> {
        if (!usageCountingAvailable && requiresUsageCounting(config)) return managed.toSet()
        return managed.filterTo(mutableSetOf()) {
            evaluate(config, it, now, usageToday, extraTime) is Verdict.Blocked
        }
    }
}
