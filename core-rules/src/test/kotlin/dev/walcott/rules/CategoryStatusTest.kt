package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class CategoryStatusTest {

    private val config = FamilyConfig(
        version = 1,
        assignments = mapOf("com.game" to "games"),
        policies = mapOf(
            "games" to CategoryPolicy(
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
        val status = RuleEngine.categoryStatus(
            config, "games", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(10)),
        )
        assertEquals(CategoryState.BUDGETED, status.state)
        assertEquals(Duration.ofMinutes(20), status.remaining)
        assertEquals(Duration.ofMinutes(30), status.budget)
        assertEquals(Duration.ofMinutes(10), status.used)
    }

    @Test
    fun `exhausted budget blocks with a reason`() {
        val status = RuleEngine.categoryStatus(
            config, "games", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
        )
        assertEquals(CategoryState.BLOCKED, status.state)
        assertEquals(BlockReason.BUDGET_EXHAUSTED, status.blockReason)
    }

    @Test
    fun `extra time increases remaining`() {
        val status = RuleEngine.categoryStatus(
            config, "games", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
            extraTime = mapOf("games" to Duration.ofMinutes(20)),
        )
        assertEquals(CategoryState.BUDGETED, status.state)
        assertEquals(Duration.ofMinutes(20), status.remaining)
    }

    @Test
    fun `blocked window reports BLOCKED_WINDOW`() {
        val status = RuleEngine.categoryStatus(config, "games", LocalDateTime.of(2026, 3, 2, 10, 0))
        assertEquals(CategoryState.BLOCKED, status.state)
        assertEquals(BlockReason.BLOCKED_WINDOW, status.blockReason)
    }

    @Test
    fun `bedtime takes precedence over available budget`() {
        val status = RuleEngine.categoryStatus(config, "games", LocalDateTime.of(2026, 3, 2, 22, 0))
        assertEquals(CategoryState.BLOCKED, status.state)
        assertEquals(BlockReason.BEDTIME, status.blockReason)
    }

    @Test
    fun `category with no budget today is ALLOWED`() {
        val status = RuleEngine.categoryStatus(config, "games", LocalDateTime.of(2026, 3, 7, 17, 0)) // Saturday
        assertEquals(CategoryState.ALLOWED, status.state)
    }
}
