package dev.walcott.rules

import java.time.LocalTime

/**
 * "Idle time earns app time" with token-window caps, à la Claude Code plan limits.
 *
 * The child banks idle time (time NOT spent on managed/restricted apps) and it converts into
 * extra minutes for a target category — but bounded two ways, like a token budget:
 *  - a **rolling window**: at most [windowCapMinutes] earned in any [windowHours] stretch, so it
 *    can't be binged;
 *  - a **weekly ceiling**: at most [weeklyCapMinutes] earned per 7 days.
 * Earning also only accrues during [earnWindows] (per day type) — empty means all day — so a
 * child can't rack up tokens while at school, where being idle isn't a choice.
 *
 * Pure and stateless: it reads the grant [ledger] and returns how much may be granted *now*.
 * The caller persists the ledger and applies the grant.
 */
data class IdleEarnConfig(
    val targetCategoryId: String,
    /** Minutes of banked idle needed for one reward. Must be > 0. */
    val minutesIdlePerReward: Int,
    /** Minutes granted per reward block. Must be > 0. */
    val rewardMinutes: Int,
    /** Rolling-window length in hours. Must be > 0. */
    val windowHours: Int,
    /** Max minutes earnable within any [windowHours] window. */
    val windowCapMinutes: Int,
    /** Max minutes earnable per rolling 7 days. */
    val weeklyCapMinutes: Int,
    /** Per-day-type windows during which idle earns; empty = earns all day. */
    val earnWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
)

/** One conversion of idle into extra minutes, timestamped for the rolling caps. */
data class EarnGrant(val epochMs: Long, val minutes: Int)

object IdleEarnEngine {

    private const val HOUR_MS = 3_600_000L
    private const val WEEK_MS = 7 * 24 * HOUR_MS

    /** Whether idle should accrue at [dayType]/[time] given the config's earn windows. */
    fun isEarningTime(config: IdleEarnConfig, dayType: DayType, time: LocalTime): Boolean {
        val windows = config.earnWindows[dayType] ?: return true // no restriction for this day
        if (windows.isEmpty()) return true
        return windows.any { time in it }
    }

    /**
     * Minutes that may be granted right now from [idleBankMinutes], honoring both caps against
     * [ledger]. Returns 0 when the config is invalid, nothing is bankable, or a cap is already
     * hit. Never negative.
     */
    fun grantableMinutes(
        config: IdleEarnConfig,
        ledger: List<EarnGrant>,
        idleBankMinutes: Long,
        nowMs: Long,
    ): Int {
        if (config.minutesIdlePerReward <= 0 || config.rewardMinutes <= 0) return 0
        if (idleBankMinutes < config.minutesIdlePerReward) return 0

        val earnedInWindow = ledger.filter { nowMs - it.epochMs < config.windowHours * HOUR_MS }.sumOf { it.minutes }
        val earnedInWeek = ledger.filter { nowMs - it.epochMs < WEEK_MS }.sumOf { it.minutes }
        val windowRoom = (config.windowCapMinutes - earnedInWindow).coerceAtLeast(0)
        val weekRoom = (config.weeklyCapMinutes - earnedInWeek).coerceAtLeast(0)

        // Grant only WHOLE reward blocks. Flooring the caps to whole blocks keeps the grant a
        // multiple of rewardMinutes, so [idleConsumedFor] debits the bank exactly and the idle
        // that didn't fit under a cap stays banked for a later window (no leak, fair carryover).
        val idleBlocks = idleBankMinutes / config.minutesIdlePerReward
        val windowBlocks = (windowRoom / config.rewardMinutes).toLong()
        val weekBlocks = (weekRoom / config.rewardMinutes).toLong()
        val blocks = minOf(idleBlocks, windowBlocks, weekBlocks).coerceAtLeast(0)
        return (blocks * config.rewardMinutes).toInt()
    }

    /** Idle minutes consumed to produce [grantedMinutes], so the caller can debit the bank. */
    fun idleConsumedFor(config: IdleEarnConfig, grantedMinutes: Int): Long {
        if (config.rewardMinutes <= 0 || grantedMinutes <= 0) return 0
        val blocks = grantedMinutes / config.rewardMinutes
        return blocks.toLong() * config.minutesIdlePerReward
    }

    /** Drops grants older than a week; call before persisting so the ledger stays bounded. */
    fun prune(ledger: List<EarnGrant>, nowMs: Long): List<EarnGrant> =
        ledger.filter { nowMs - it.epochMs < WEEK_MS }

    /** Total earned in the last 7 days (for display). */
    fun earnedThisWeek(ledger: List<EarnGrant>, nowMs: Long): Int =
        ledger.filter { nowMs - it.epochMs < WEEK_MS }.sumOf { it.minutes }

    /** Total earned so far today (local day boundary), for the child's "earned today" line. */
    fun earnedOnDay(ledger: List<EarnGrant>, dayStartMs: Long, dayEndMs: Long): Int =
        ledger.filter { it.epochMs in dayStartMs until dayEndMs }.sumOf { it.minutes }
}
