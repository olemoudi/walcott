package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class IdleEarnEngineTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private val config = IdleEarnConfig(
        targetCategoryId = "games",
        minutesIdlePerReward = 10, // 10 min idle
        rewardMinutes = 5, //         -> 5 min games
        windowHours = 4,
        windowCapMinutes = 20, //    at most 20 min per 4h
        weeklyCapMinutes = 120, //   at most 2h per week
    )

    @Test
    fun `idle converts at the configured rate`() {
        // 30 min idle -> 3 blocks -> 15 min.
        assertEquals(15, IdleEarnEngine.grantableMinutes(config, emptyList(), 30, now))
    }

    @Test
    fun `not enough idle for a full block earns nothing`() {
        assertEquals(0, IdleEarnEngine.grantableMinutes(config, emptyList(), 9, now))
    }

    @Test
    fun `the rolling window caps earning to whole blocks`() {
        // Already earned 15 in this 4h window -> 5 of headroom = exactly one 5-min block.
        val ledger = listOf(EarnGrant(now - hour, 15))
        assertEquals(5, IdleEarnEngine.grantableMinutes(config, ledger, 100, now))
    }

    @Test
    fun `headroom below a full block grants nothing (idle stays banked)`() {
        // 18 earned -> 2 of room, less than one 5-min block -> grant 0, no bank leak.
        val ledger = listOf(EarnGrant(now - hour, 18))
        assertEquals(0, IdleEarnEngine.grantableMinutes(config, ledger, 100, now))
    }

    @Test
    fun `grants older than the window do not count against it`() {
        val ledger = listOf(EarnGrant(now - 5 * hour, 20)) // outside the 4h window
        assertEquals(20, IdleEarnEngine.grantableMinutes(config, ledger, 100, now)) // full window cap
    }

    @Test
    fun `the weekly ceiling caps earning even across windows`() {
        // 115 earned this week (all outside the 4h window) -> 5 left = one block, despite window room.
        val ledger = listOf(EarnGrant(now - 2 * 24 * hour, 60), EarnGrant(now - 24 * hour, 55))
        assertEquals(5, IdleEarnEngine.grantableMinutes(config, ledger, 100, now))
    }

    @Test
    fun `partial-block carryover keeps the leftover idle banked`() {
        // 40 min idle = 4 blocks possible, but only 2 blocks (10 min) of window room.
        val ledger = listOf(EarnGrant(now - hour, 10))
        val grant = IdleEarnEngine.grantableMinutes(config, ledger, 40, now)
        assertEquals(10, grant) // 2 blocks
        // Only the granted blocks' idle is consumed; the other 2 blocks stay banked.
        assertEquals(20L, IdleEarnEngine.idleConsumedFor(config, grant))
    }

    @Test
    fun `grants older than a week fall off the weekly ceiling`() {
        val ledger = listOf(EarnGrant(now - 8 * 24 * hour, 120))
        assertEquals(20, IdleEarnEngine.grantableMinutes(config, ledger, 100, now))
    }

    @Test
    fun `zero or negative config earns nothing`() {
        assertEquals(0, IdleEarnEngine.grantableMinutes(config.copy(minutesIdlePerReward = 0), emptyList(), 100, now))
        assertEquals(0, IdleEarnEngine.grantableMinutes(config.copy(rewardMinutes = 0), emptyList(), 100, now))
    }

    @Test
    fun `idle consumed reflects only the whole blocks granted`() {
        // Grant of 15 = 3 blocks of 5 -> consumed 3*10 = 30 idle minutes.
        assertEquals(30L, IdleEarnEngine.idleConsumedFor(config, 15))
    }

    @Test
    fun `earning is always on when no windows are configured`() {
        assertTrue(IdleEarnEngine.isEarningTime(config, DayType.SCHOOL, LocalTime.of(10, 0)))
    }

    @Test
    fun `earning respects per-day-type windows so class time never earns`() {
        val withWindows = config.copy(
            earnWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(16, 0), LocalTime.of(21, 0)))),
        )
        // 10:00 on a school day = class -> no earning; 17:00 = after school -> earning.
        assertFalse(IdleEarnEngine.isEarningTime(withWindows, DayType.SCHOOL, LocalTime.of(10, 0)))
        assertTrue(IdleEarnEngine.isEarningTime(withWindows, DayType.SCHOOL, LocalTime.of(17, 0)))
        // A day type with no window configured still earns all day.
        assertTrue(IdleEarnEngine.isEarningTime(withWindows, DayType.WEEKEND, LocalTime.of(10, 0)))
    }

    @Test
    fun `prune drops entries older than a week`() {
        val ledger = listOf(EarnGrant(now - 8 * 24 * hour, 30), EarnGrant(now - hour, 10))
        assertEquals(listOf(EarnGrant(now - hour, 10)), IdleEarnEngine.prune(ledger, now))
    }

    @Test
    fun `earnedThisWeek and earnedOnDay sum the right entries`() {
        val ledger = listOf(EarnGrant(now - 2 * 24 * hour, 30), EarnGrant(now - hour, 10))
        assertEquals(40, IdleEarnEngine.earnedThisWeek(ledger, now))
        assertEquals(10, IdleEarnEngine.earnedOnDay(ledger, now - 3 * hour, now + hour))
    }
}
