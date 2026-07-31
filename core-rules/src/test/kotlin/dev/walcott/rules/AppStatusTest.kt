package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class AppStatusTest {

    private val game = "com.game"

    private val config = FamilyConfig(
        version = 1,
        perAppPolicies = mapOf(
            game to AppPolicy(
                dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)),
                blockedWindows = mapOf(
                    DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(8, 30), LocalTime.of(14, 30))),
                ),
            ),
        ),
        bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30))),
    )

    private val schoolAfternoon = LocalDateTime.of(2026, 3, 2, 17, 0)

    @Test
    fun `budget with partial usage reports remaining`() {
        val status = RuleEngine.appStatus(
            config, game, schoolAfternoon,
            usageToday = mapOf(game to Duration.ofMinutes(10)),
        )
        assertEquals(AppState.BUDGETED, status.state)
        assertEquals(Duration.ofMinutes(20), status.remaining)
        assertEquals(Duration.ofMinutes(30), status.budget)
        assertEquals(Duration.ofMinutes(10), status.used)
    }

    @Test
    fun `exhausted budget blocks with a reason`() {
        val status = RuleEngine.appStatus(
            config, game, schoolAfternoon,
            usageToday = mapOf(game to Duration.ofMinutes(30)),
        )
        assertEquals(AppState.BLOCKED, status.state)
        assertEquals(BlockReason.BUDGET_EXHAUSTED, status.blockReason)
    }

    @Test
    fun `extra time increases remaining`() {
        val status = RuleEngine.appStatus(
            config, game, schoolAfternoon,
            usageToday = mapOf(game to Duration.ofMinutes(30)),
            extraTime = mapOf(game to Duration.ofMinutes(20)),
        )
        assertEquals(AppState.BUDGETED, status.state)
        assertEquals(Duration.ofMinutes(20), status.remaining)
    }

    @Test
    fun `blocked window reports BLOCKED_WINDOW`() {
        val status = RuleEngine.appStatus(config, game, LocalDateTime.of(2026, 3, 2, 10, 0))
        assertEquals(AppState.BLOCKED, status.state)
        assertEquals(BlockReason.BLOCKED_WINDOW, status.blockReason)
    }

    @Test
    fun `bedtime takes precedence over available budget`() {
        val status = RuleEngine.appStatus(config, game, LocalDateTime.of(2026, 3, 2, 22, 0))
        assertEquals(AppState.BLOCKED, status.state)
        assertEquals(BlockReason.BEDTIME, status.blockReason)
    }

    @Test
    fun `an app with no budget today is ALLOWED`() {
        val status = RuleEngine.appStatus(config, game, LocalDateTime.of(2026, 3, 7, 17, 0)) // Saturday
        assertEquals(AppState.ALLOWED, status.state)
    }

    @Test
    fun `an app on the family default reports that default`() {
        // The card the child sees for an app nobody singled out.
        val cfg = config.copy(defaultAppBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(45)))
        val status = RuleEngine.appStatus(
            cfg, "com.brand.new", schoolAfternoon,
            usageToday = mapOf("com.brand.new" to Duration.ofMinutes(15)),
        )
        assertEquals(AppState.BUDGETED, status.state)
        assertEquals(Duration.ofMinutes(45), status.budget)
        assertEquals(Duration.ofMinutes(30), status.remaining)
    }

    @Test
    fun `fail-closed says blocked whatever the budget says`() {
        // The screen must agree with the device: a card promising time over an app that won't
        // open is worse than a block, because the child can't tell what is broken.
        val status = RuleEngine.appStatus(config, game, schoolAfternoon, failClosed = true)
        assertEquals(AppState.BLOCKED, status.state)
        assertEquals(BlockReason.FAIL_CLOSED, status.blockReason)
    }
}
