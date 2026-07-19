package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Extra time can be granted three ways — to all apps, to one app, or to a category — and the
 * engine sums whichever apply. These lock the new "all apps" and "single app" grants alongside
 * the existing category grant, including how they interact with the per-app hard cap.
 */
class ExtraTimeTargetsTest {

    private val afternoon = LocalDateTime.of(2026, 3, 3, 16, 0) // a Tuesday (SCHOOL)
    private val game = "com.game"
    private val chat = "com.chat"

    private fun config(
        gamesBudget: Duration? = null,
        socialBudget: Duration? = null,
        appBudget: Duration? = null,
    ) = FamilyConfig(
        version = 1,
        assignments = mapOf(game to "games", chat to "social"),
        policies = buildMap {
            gamesBudget?.let { put("games", CategoryPolicy(dailyBudget = mapOf(DayType.SCHOOL to it))) }
            socialBudget?.let { put("social", CategoryPolicy(dailyBudget = mapOf(DayType.SCHOOL to it))) }
        },
        perAppPolicies = appBudget?.let { mapOf(game to CategoryPolicy(dailyBudget = mapOf(DayType.SCHOOL to it))) }
            ?: emptyMap(),
    )

    @Test
    fun `an all-apps grant widens every category`() {
        val cfg = config(gamesBudget = Duration.ofMinutes(60), socialBudget = Duration.ofMinutes(60))
        val usage = mapOf("games" to Duration.ofMinutes(60), "social" to Duration.ofMinutes(60))
        val extra = mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30))
        // Both categories were exhausted; the "all apps" grant revives both.
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(30)),
            RuleEngine.evaluate(cfg, game, afternoon, usage, extra),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(30)),
            RuleEngine.evaluate(cfg, chat, afternoon, usage, extra),
        )
    }

    @Test
    fun `a single-app grant revives only that app, not its category siblings`() {
        // Two games apps sharing the Games budget; grant extra to com.game only.
        val cfg = FamilyConfig(
            version = 1,
            assignments = mapOf(game to "games", "com.game2" to "games"),
            policies = mapOf("games" to CategoryPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))),
        )
        val usage = mapOf("games" to Duration.ofMinutes(60))
        val extra = mapOf(game to Duration.ofMinutes(20))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(20)),
            RuleEngine.evaluate(cfg, game, afternoon, usage, extra),
        )
        // The sibling stays blocked — the grant was for this app only.
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(cfg, "com.game2", afternoon, usage, extra),
        )
    }

    @Test
    fun `a single-app grant lifts the app's own hard sub-cap`() {
        val cfg = config(gamesBudget = Duration.ofMinutes(120), appBudget = Duration.ofMinutes(20))
        val usage = mapOf("games" to Duration.ofMinutes(20), "com.game" to Duration.ofMinutes(20))
        // The per-app 20-min cap is spent; a grant to this app lifts it.
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(15)),
            RuleEngine.evaluate(cfg, game, afternoon, usage, mapOf(game to Duration.ofMinutes(15))),
        )
    }

    @Test
    fun `an all-apps grant does not blow through a deliberate per-app cap`() {
        val cfg = config(gamesBudget = Duration.ofMinutes(120), appBudget = Duration.ofMinutes(20))
        val usage = mapOf("games" to Duration.ofMinutes(20), "com.game" to Duration.ofMinutes(20))
        // "All apps +30" widens the category, but the app's own 20-min cap still bites.
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(cfg, game, afternoon, usage, mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30))),
        )
    }

    @Test
    fun `grants of all three kinds stack for the same app`() {
        val cfg = config(gamesBudget = Duration.ofMinutes(60))
        val usage = mapOf("games" to Duration.ofMinutes(60))
        val extra = mapOf(
            ExtraTime.ALL_APPS to Duration.ofMinutes(10),
            "games" to Duration.ofMinutes(10),
            game to Duration.ofMinutes(10),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(30)),
            RuleEngine.evaluate(cfg, game, afternoon, usage, extra),
        )
    }

    @Test
    fun `a single-app grant can override a per-app zero block`() {
        // v0.6.0 "block this app" = a 0-minute per-app cap; a deliberate grant to it wins.
        val cfg = config(gamesBudget = Duration.ofMinutes(120), appBudget = Duration.ZERO)
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(25)),
            RuleEngine.evaluate(cfg, game, afternoon, emptyMap(), mapOf(game to Duration.ofMinutes(25))),
        )
    }

    @Test
    fun `extra never overrides bedtime`() {
        val cfg = config(gamesBudget = Duration.ofMinutes(60)).copy(
            bedtime = mapOf(DayType.SCHOOL to TimeWindow(java.time.LocalTime.of(15, 0), java.time.LocalTime.of(17, 0))),
        )
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(cfg, game, afternoon, emptyMap(), mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(60))),
        )
    }
}
