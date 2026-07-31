package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Extra time is granted two ways — to all apps or to one app — and where each one reaches is a
 * promise to the parent: "everyone gets half an hour more" must not quietly undo the tight cap
 * they put on one app on purpose.
 */
class ExtraTimeTargetsTest {

    private val afternoon = LocalDateTime.of(2026, 3, 3, 16, 0) // a Tuesday (SCHOOL)
    private val game = "com.game"
    private val chat = "com.chat"

    private fun config(
        defaultBudget: Duration? = null,
        gameBudget: Duration? = null,
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = defaultBudget?.let { mapOf(DayType.SCHOOL to it) } ?: emptyMap(),
        perAppPolicies = gameBudget?.let { mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to it))) }
            ?: emptyMap(),
    )

    @Test
    fun `an all-apps grant revives every app running on the family default`() {
        val cfg = config(defaultBudget = Duration.ofMinutes(60))
        val usage = mapOf(game to Duration.ofMinutes(60), chat to Duration.ofMinutes(60))
        val extra = mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30))
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
    fun `an all-apps grant does not lift a cap set on one app on purpose`() {
        val cfg = config(defaultBudget = Duration.ofMinutes(60), gameBudget = Duration.ofMinutes(20))
        val usage = mapOf(game to Duration.ofMinutes(20), chat to Duration.ofMinutes(20))
        val extra = mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30))
        // The game is out of ITS time and stays out; the app on the default gets the grant.
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(cfg, game, afternoon, usage, extra),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(70)),
            RuleEngine.evaluate(cfg, chat, afternoon, usage, extra),
        )
    }

    @Test
    fun `a grant to one app reaches that app and nothing else`() {
        val cfg = config(defaultBudget = Duration.ofMinutes(60))
        val usage = mapOf(game to Duration.ofMinutes(60), chat to Duration.ofMinutes(60))
        val extra = mapOf(game to Duration.ofMinutes(15))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(15)),
            RuleEngine.evaluate(cfg, game, afternoon, usage, extra),
        )
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(cfg, chat, afternoon, usage, extra),
        )
    }

    @Test
    fun `a grant to one app lifts that app's own cap`() {
        // The everyday case for a per-app limit: "you can have 15 more minutes of the game".
        val cfg = config(gameBudget = Duration.ofMinutes(20))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(15)),
            RuleEngine.evaluate(
                cfg, game, afternoon,
                usageToday = mapOf(game to Duration.ofMinutes(20)),
                extraTime = mapOf(game to Duration.ofMinutes(15)),
            ),
        )
    }

    @Test
    fun `both grants stack on an app running on the default`() {
        val cfg = config(defaultBudget = Duration.ofMinutes(30))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(45)),
            RuleEngine.evaluate(
                cfg, chat, afternoon,
                usageToday = mapOf(chat to Duration.ofMinutes(30)),
                extraTime = mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30), chat to Duration.ofMinutes(15)),
            ),
        )
    }

    @Test
    fun `extra time cannot conjure a limit where there is none`() {
        // No default and no per-app budget: the app was already unlimited, and a grant must not
        // turn it into a budgeted one on the child's screen.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config(), chat, afternoon, emptyMap(), mapOf(chat to Duration.ofMinutes(15))))
    }
}
