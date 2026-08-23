package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Where minutes EARNED by leaving the phone alone actually land (see [ExtraTime.EARNED]).
 *
 * The promise is in the README: time off the phone converts into extra screen time for every
 * app. Filed with the blanket grants it kept that promise only for apps running on the family
 * default — a family with per-app limits was shown "you earned 20 minutes" that bought nothing,
 * anywhere, which is worse than not offering the feature.
 */
class EarnedTimeTest {

    private val monday = LocalDateTime.of(2026, 3, 2, 17, 0)
    private val game = "com.game"

    private fun config(own: Duration? = null, default: Duration? = null, total: Duration? = null) = FamilyConfig(
        version = 1,
        defaultAppBudget = default?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        dailyScreenBudget = total?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        perAppPolicies = own?.let { mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to it))) }.orEmpty(),
    )

    @Test
    fun `earned minutes reach a limit set for one app on purpose`() {
        // The bug, in one line: an hour set for this app, twenty minutes earned, and the child
        // may play for an hour and twenty.
        val config = config(own = Duration.ofHours(1))
        assertEquals(
            Duration.ofMinutes(80),
            config.allowanceFor(game, DayType.SCHOOL, mapOf(ExtraTime.EARNED to Duration.ofMinutes(20))),
        )
    }

    @Test
    fun `a blanket grant still does not, which is the whole reason they are different keys`() {
        // "Everybody gets thirty minutes" is an answer about the family's evening, not about a
        // limit somebody chose for one app. That rule is deliberate and stays.
        val config = config(own = Duration.ofHours(1))
        assertEquals(
            Duration.ofHours(1),
            config.allowanceFor(game, DayType.SCHOOL, mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30))),
        )
    }

    @Test
    fun `both kinds reach an app running on the family default`() {
        val config = config(default = Duration.ofHours(1))
        assertEquals(
            Duration.ofMinutes(105),
            config.allowanceFor(
                game, DayType.SCHOOL,
                mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(30), ExtraTime.EARNED to Duration.ofMinutes(15)),
            ),
        )
    }

    @Test
    fun `earned minutes lengthen the phone's day as well`() {
        // Otherwise the ceiling would quietly cancel the reward: a child who earned twenty
        // minutes and whose day is spent has earned twenty minutes of nothing.
        val config = config(total = Duration.ofHours(2))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(20)),
            RuleEngine.evaluate(
                config, game, monday,
                mapOf(game to Duration.ofHours(2)),
                mapOf(ExtraTime.EARNED to Duration.ofMinutes(20)),
            ),
        )
    }

    @Test
    fun `an app the family never limits is unaffected either way`() {
        val config = FamilyConfig(
            version = 1,
            perAppPolicies = mapOf(game to AppPolicy(unlimited = true)),
        )
        assertEquals(
            null,
            config.allowanceFor(game, DayType.SCHOOL, mapOf(ExtraTime.EARNED to Duration.ofMinutes(20))),
        )
    }
}
