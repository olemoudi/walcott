package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The apps a child reaches a person with, and the limits that must never catch them by accident.
 *
 * The phone and contacts answer to nothing at all ([FamilyConfig.essentialPackages]) and always
 * have. Messaging is the one that had to be named: it ships as a system app on nearly every
 * phone, so it was never managed and never limited by accident — until this app learnt to
 * manage preinstalled apps and to cap the whole day, at which point "every app gets an hour"
 * and "two hours of phone" both quietly included the way a child says they are running late.
 *
 * The rule these pin: a limit NOBODY WROTE about this app does not reach it; one written about
 * it by name does.
 */
class ReachOutAppsTest {

    private val monday = LocalDateTime.of(2026, 3, 2, 17, 0)
    private val sms = "com.android.messaging"
    private val game = "com.game"

    private fun config(
        default: Duration? = null,
        total: Duration? = null,
        own: AppPolicy? = null,
        bedtime: TimeWindow? = null,
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = default?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        dailyScreenBudget = total?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        perAppPolicies = own?.let { mapOf(sms to it) }.orEmpty(),
        bedtime = bedtime?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        essentialPackages = setOf("com.android.dialer"),
        reachOutPackages = setOf(sms, "com.android.dialer"),
    )

    @Test
    fun `the family default does not reach it`() {
        // "Every app gets an hour" is a statement about apps in general, and this is not one.
        val config = config(default = Duration.ofHours(1))
        assertNull(config.budgetFor(sms, DayType.SCHOOL))
        assertEquals(
            Verdict.Allowed,
            RuleEngine.evaluate(config, sms, monday, mapOf(sms to Duration.ofHours(3))),
        )
        // The same default still reaches an ordinary app, which is the point of it.
        assertEquals(Duration.ofHours(1), config.budgetFor(game, DayType.SCHOOL))
    }

    @Test
    fun `the day's total does not reach it either`() {
        val config = config(total = Duration.ofHours(2))
        assertEquals(
            Verdict.Allowed,
            RuleEngine.evaluate(config, sms, monday, mapOf(game to Duration.ofHours(3))),
        )
        // And the child's own card agrees, from the other implementation.
        assertEquals(
            AppState.ALLOWED,
            RuleEngine.appStatus(config, sms, monday, mapOf(game to Duration.ofHours(3))).state,
        )
        // The phone really is out of time, for everything else.
        assertEquals(
            Verdict.Blocked(BlockReason.SCREEN_BUDGET),
            RuleEngine.evaluate(config, game, monday, mapOf(game to Duration.ofHours(3))),
        )
    }

    @Test
    fun `a limit written about it BY NAME does reach it`() {
        // The other half of the promise, and the reason this is not the same as being essential:
        // a parent who decides to limit messaging can, from that app's own screen.
        val config = config(own = AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30))))
        assertEquals(Duration.ofMinutes(30), config.budgetFor(sms, DayType.SCHOOL))
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(config, sms, monday, mapOf(sms to Duration.ofMinutes(30))),
        )
    }

    @Test
    fun `a window written about it by name reaches it too`() {
        val config = config(
            own = AppPolicy(
                blockedWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(9, 0), LocalTime.of(18, 0)))),
            ),
        )
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(config, sms, monday),
        )
    }

    @Test
    fun `bedtime still closes it, because bedtime is a decision and not a default`() {
        // Deliberately NOT exempt. A parent setting bedtime is saying something about tonight on
        // this phone, not typing a number on a screen about apps in general — and the phone
        // itself still calls, which is what essentialPackages is for.
        val config = config(bedtime = TimeWindow(LocalTime.of(16, 0), LocalTime.of(7, 0)))
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(config, sms, monday),
        )
    }

    @Test
    fun `the phone itself answers to nothing at all, as it always has`() {
        val config = config(default = Duration.ofHours(1), total = Duration.ofHours(2))
        assertEquals(
            Verdict.Allowed,
            RuleEngine.evaluate(config, "com.android.dialer", monday, mapOf(game to Duration.ofHours(9))),
        )
    }
}
