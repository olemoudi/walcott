package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime

/**
 * Deterministic, stateless evaluator: it reads no clock or storage — everything comes in
 * as parameters. The enforcement service calls it with real state; tests, with whatever
 * state they want to reproduce.
 *
 * Precedence: essential > bedtime > blocked window > budget. An app nobody has set a rule for
 * answers to the family's default daily budget, and to nothing at all when there isn't one —
 * a newly installed app is never restricted by a rule that predates it.
 */
object RuleEngine {

    fun evaluate(
        config: FamilyConfig,
        packageName: String,
        now: LocalDateTime,
        /** Time used today per app (package -> duration). */
        usageToday: Map<String, Duration> = emptyMap(),
        /** Extra time granted today, keyed by package or [ExtraTime.ALL_APPS]. */
        extraTime: Map<String, Duration> = emptyMap(),
    ): Verdict {
        if (packageName in config.essentialPackages) return Verdict.Allowed

        val dayType = config.calendar.dayTypeOf(now)
        val time = now.toLocalTime()
        // Blocked windows can be restricted to certain weekdays and can step aside on special
        // days; bedtime is per day type and reads the clock alone.
        val specialDay = dayType == DayType.HOLIDAY

        // Above every rule, because it is not one: a parent said "put it down, now", and no
        // standing rule and no granted minute is an answer to that.
        if (config.todayException.pausedAt(now)) return Verdict.Blocked(BlockReason.PAUSED)

        config.bedtimeAt(now)?.let { window ->
            if (time in window) return Verdict.Blocked(BlockReason.BEDTIME)
        }
        // Family-wide screen-free windows: like bedtime, a hard block on every non-essential
        // app — checked before any budget, so an app with no limit is inside them too. Extra
        // time never lifts a window.
        if (config.blockedWindows[dayType].orEmpty().any { it.appliesAt(now, specialDay) }) {
            return Verdict.Blocked(BlockReason.BLOCKED_WINDOW)
        }

        val appPolicy = config.perAppPolicies[packageName]
        if (appPolicy?.blockedWindows?.get(dayType).orEmpty().any { it.appliesAt(now, specialDay) }) {
            return Verdict.Blocked(BlockReason.BLOCKED_WINDOW)
        }

        // The budget plus the extra time that reaches this app: its own grant always, plus any
        // "all apps" grant — but only while the app is on the family default. A budget somebody
        // set for this app on purpose is not something a blanket "everyone gets 30 more minutes"
        // should blow past. Written once, in FamilyConfig, because everything that draws a
        // number for a child reads it and they have to agree (see appStatus, activeBlocks).
        val allowance = config.allowanceFor(packageName, dayType, extraTime) ?: return Verdict.Allowed

        val remaining = allowance - (usageToday[packageName] ?: Duration.ZERO)
        return if (remaining > Duration.ZERO) {
            Verdict.AllowedWithBudget(remaining)
        } else {
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED)
        }
    }

    /**
     * The block that applies to EVERY non-essential app right now (bedtime, or a family-wide
     * screen-free window), or null when nothing device-wide is in force.
     *
     * The same two checks [evaluate] runs before it looks at any app, pulled out so a caller can
     * ask the question once instead of per package: a device that has just entered bedtime has
     * one thing to say, not one thing per installed app.
     */
    fun deviceWideBlock(config: FamilyConfig, now: LocalDateTime): BlockReason? {
        val dayType = config.calendar.dayTypeOf(now)
        if (config.todayException.pausedAt(now)) return BlockReason.PAUSED
        config.bedtimeAt(now)?.let { if (now.toLocalTime() in it) return BlockReason.BEDTIME }
        val specialDay = dayType == DayType.HOLIDAY
        return if (config.blockedWindows[dayType].orEmpty().any { it.appliesAt(now, specialDay) }) {
            BlockReason.BLOCKED_WINDOW
        } else {
            null
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
        config.defaultAppBudget.isNotEmpty() ||
            // Per-app budgets count down off the same counter. Missing them here was a real
            // bypass: a family that caps only individual apps ("WhatsApp, 30 min") kept
            // enforcing "as usual" with the counter gone, which for a budget means forever.
            config.perAppPolicies.values.any { it.dailyBudget.isNotEmpty() }

    /**
     * Whether this config depends on the device clock being right. Every rule this engine
     * applies is a rule about *when*: bedtime, blocked windows, and budgets that reset at
     * midnight. A config with none of them can't be walked past by moving the clock.
     */
    fun requiresTrustedClock(config: FamilyConfig): Boolean =
        config.bedtime.isNotEmpty() ||
            config.blockedWindows.values.any { it.isNotEmpty() } ||
            config.defaultAppBudget.isNotEmpty() ||
            config.perAppPolicies.values.any { it.dailyBudget.isNotEmpty() || it.blockedWindows.isNotEmpty() }

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
        /**
         * False when the device clock is provably wrong (see [dev.walcott.sync.ClockGuard]).
         * Every rule here is a rule about *when*, so a clock moved forward walks straight past
         * bedtime and resets the day's budget — the same shape of bypass as revoking usage
         * access, and answered the same way: fail closed until the clock is right again, which
         * makes moving it self-defeating instead of profitable.
         */
        clockTrusted: Boolean = true,
    ): Set<String> {
        if (!usageCountingAvailable && requiresUsageCounting(config)) return managed.toSet()
        if (!clockTrusted && requiresTrustedClock(config)) return managed.toSet()
        return managed.filterTo(mutableSetOf()) {
            evaluate(config, it, now, usageToday, extraTime) is Verdict.Blocked
        }
    }
}
