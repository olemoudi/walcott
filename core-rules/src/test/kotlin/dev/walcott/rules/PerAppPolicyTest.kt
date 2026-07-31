package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How an app's limit is decided, which is now the whole of the budget model: nothing set means
 * the family default, a budget of its own replaces it, and "unlimited" opts out of it. The three
 * states have to be distinguishable — collapsing "nothing set" and "unlimited" is what would
 * force a parent to choose between capping everything and capping nothing.
 */
class PerAppPolicyTest {

    private val schoolAfternoon = LocalDateTime.of(2026, 3, 3, 16, 0) // a Tuesday
    private val saturday = LocalDateTime.of(2026, 3, 7, 16, 0)
    private val app = "com.chat"

    private fun config(
        default: Map<DayType, Duration> = emptyMap(),
        own: AppPolicy? = null,
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = default,
        perAppPolicies = own?.let { mapOf(app to it) } ?: emptyMap(),
        essentialPackages = setOf("dev.walcott"),
    )

    @Test
    fun `nothing set anywhere means no limit`() {
        assertNull(config().budgetFor(app, DayType.SCHOOL))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config(), app, schoolAfternoon))
    }

    @Test
    fun `nothing set for this app means the family default`() {
        val cfg = config(default = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)))
        assertEquals(Duration.ofMinutes(30), cfg.budgetFor(app, DayType.SCHOOL))
        assertTrue(cfg.usesDefaultBudget(app))
    }

    @Test
    fun `a budget of its own replaces the default`() {
        val cfg = config(
            default = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)),
            own = AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(10))),
        )
        assertEquals(Duration.ofMinutes(10), cfg.budgetFor(app, DayType.SCHOOL))
        assertFalse(cfg.usesDefaultBudget(app))
    }

    @Test
    fun `unlimited beats the default, and beats a stale budget of its own`() {
        val free = config(
            default = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)),
            own = AppPolicy(unlimited = true),
        )
        assertNull(free.budgetFor(app, DayType.SCHOOL))
        assertFalse(free.usesDefaultBudget(app))
        // The editor may leave an old budget behind when the switch is flipped; the switch wins.
        val stale = config(
            default = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)),
            own = AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(5)), unlimited = true),
        )
        assertNull(stale.budgetFor(app, DayType.SCHOOL))
    }

    @Test
    fun `an app limited only on school days falls back to the default at the weekend`() {
        // The parent said something about school days; they said nothing about Saturday, so the
        // family's own answer for Saturday applies. (Both editors write every day type at once,
        // so this is the hand-made-policy case rather than the everyday one.)
        val cfg = config(
            default = mapOf(DayType.SCHOOL to Duration.ofHours(1), DayType.WEEKEND to Duration.ofHours(2)),
            own = AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(10))),
        )
        assertEquals(Duration.ofMinutes(10), cfg.budgetFor(app, DayType.SCHOOL))
        assertEquals(Duration.ofHours(2), cfg.budgetFor(app, DayType.WEEKEND))
        assertEquals(Verdict.AllowedWithBudget(Duration.ofHours(2)), RuleEngine.evaluate(cfg, app, saturday))
    }

    @Test
    fun `a budget of zero blocks the app outright`() {
        // How "block this app" is expressed: a limit of nothing, which is an explicit act.
        val cfg = config(own = AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ZERO)))
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), RuleEngine.evaluate(cfg, app, schoolAfternoon))
    }

    @Test
    fun `per-app windows add to the family ones and stand alone`() {
        val cfg = config(
            own = AppPolicy(
                blockedWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(15, 0), LocalTime.of(17, 0)))),
            ),
        )
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, app, schoolAfternoon))
        // Another app is untouched by it.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.other", schoolAfternoon))
    }

    @Test
    fun `an app window blocks even when the app has time left`() {
        val cfg = config(
            own = AppPolicy(
                dailyBudget = mapOf(DayType.SCHOOL to Duration.ofHours(2)),
                blockedWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(15, 0), LocalTime.of(17, 0)))),
            ),
        )
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, app, schoolAfternoon))
    }
}
