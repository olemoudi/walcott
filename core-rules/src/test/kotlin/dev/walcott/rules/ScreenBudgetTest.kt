package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * The phone's own limit for the day (see [FamilyConfig.dailyScreenBudget]).
 *
 * The rule every family assumes is already there. Limits are per app and each spends its own
 * clock, so ten apps with an hour each is a ten-hour day — and the parent who set those ten
 * hours believed they had set one. These tests are about the arithmetic that makes the ceiling
 * real, and about the three things it must NOT do: close the phone, close an app the family said
 * to never limit, or disagree with the number the child is shown.
 */
class ScreenBudgetTest {

    private val monday = LocalDateTime.of(2026, 3, 2, 17, 0)
    private val game = "com.game"
    private val chat = "com.chat"

    private fun config(
        total: Duration? = Duration.ofHours(2),
        perApp: Map<String, AppPolicy> = emptyMap(),
        default: Duration? = null,
    ) = FamilyConfig(
        version = 1,
        dailyScreenBudget = total?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        defaultAppBudget = default?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        perAppPolicies = perApp,
        essentialPackages = setOf("com.android.dialer"),
    )

    private fun used(vararg entries: Pair<String, Duration>) = entries.toMap()

    @Test
    fun `an app with no limit of its own still answers to the day's total`() {
        // The whole point: nothing was ever set for this app, and the phone still shuts it once
        // the day is spent. Without this the ceiling would only apply to apps that already had
        // limits, which is to say to the apps that did not need one.
        val config = config(total = Duration.ofHours(2))
        assertEquals(
            Verdict.Blocked(BlockReason.SCREEN_BUDGET),
            RuleEngine.evaluate(config, game, monday, used(game to Duration.ofHours(2))),
        )
    }

    @Test
    fun `every app is spent by every app, whichever one is asked about`() {
        // Two hours of one app closes the other, which is the difference between a total and a
        // per-app limit and the reason this exists.
        val config = config(total = Duration.ofHours(2))
        assertEquals(
            Verdict.Blocked(BlockReason.SCREEN_BUDGET),
            RuleEngine.evaluate(config, chat, monday, used(game to Duration.ofHours(2))),
        )
    }

    @Test
    fun `the number a child is shown is whichever runs out first`() {
        // An hour left in the game, ten minutes left in the day: the answer to "how long have I
        // got" is ten minutes, or the screen is telling them something the phone will contradict.
        val config = config(
            total = Duration.ofHours(2),
            perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofHours(3)))),
        )
        val verdict = RuleEngine.evaluate(
            config, game, monday, used(game to Duration.ofHours(1), chat to Duration.ofMinutes(50)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(10)), verdict)
        // And the child's card says the same thing, from the other implementation.
        assertEquals(
            Duration.ofMinutes(10),
            RuleEngine.appStatus(
                config, game, monday, used(game to Duration.ofHours(1), chat to Duration.ofMinutes(50)),
            ).remaining,
        )
    }

    @Test
    fun `an app nobody limits reports the day's total rather than nothing`() {
        val config = config(total = Duration.ofHours(2))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(30)),
            RuleEngine.evaluate(config, game, monday, used(chat to Duration.ofMinutes(90))),
        )
    }

    @Test
    fun `essential apps and the ones marked never-limit are outside it`() {
        // The phone still calls somebody when the day is spent, and so does the app the family
        // said must always work. That last one is the family's own escape hatch: a total that
        // closed it would be the rule closing the way out of itself.
        val config = config(
            total = Duration.ofHours(2),
            perApp = mapOf(chat to AppPolicy(unlimited = true)),
        )
        val spent = used(game to Duration.ofHours(3))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "com.android.dialer", monday, spent))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, chat, monday, spent))
        assertEquals(AppState.ALLOWED, RuleEngine.appStatus(config, chat, monday, spent).state)
    }

    @Test
    fun `time spent in a never-limited app still counts towards the day`() {
        // Time is time. The exemption is about what can be STOPPED by the total, not about what
        // spends it — an hour is an hour of the child's day wherever it went.
        val config = config(
            total = Duration.ofHours(2),
            perApp = mapOf(chat to AppPolicy(unlimited = true)),
        )
        assertEquals(
            Verdict.Blocked(BlockReason.SCREEN_BUDGET),
            RuleEngine.evaluate(config, game, monday, used(chat to Duration.ofHours(2))),
        )
    }

    @Test
    fun `a family with no total is exactly as it was`() {
        val config = config(total = null, default = Duration.ofHours(1))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(30)),
            RuleEngine.evaluate(config, game, monday, used(game to Duration.ofMinutes(30), chat to Duration.ofHours(9))),
        )
        assertNull(config.screenTimeLeftAt(DayType.SCHOOL, Duration.ofHours(9)))
    }

    @Test
    fun `extra time widens the day, whether it was given or earned`() {
        val config = config(total = Duration.ofHours(2))
        val spent = used(game to Duration.ofHours(2))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(20)),
            RuleEngine.evaluate(config, game, monday, spent, mapOf(ExtraTime.ALL_APPS to Duration.ofMinutes(20))),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(15)),
            RuleEngine.evaluate(config, game, monday, spent, mapOf(ExtraTime.EARNED to Duration.ofMinutes(15))),
        )
    }

    @Test
    fun `a total makes the counter and the clock load-bearing`() {
        // Both fail-closed branches. A family whose ONLY rule is a daily total would otherwise
        // have written the one rule that revoking a permission — or moving the clock — turns off.
        val config = config(total = Duration.ofHours(2))
        assertTrue(RuleEngine.requiresUsageCounting(config))
        assertTrue(RuleEngine.requiresTrustedClock(config))
        assertEquals(
            setOf(game, chat),
            RuleEngine.blockedPackages(config, setOf(game, chat), monday, usageCountingAvailable = false),
        )
    }

    @Test
    fun `the child is warned before the day runs out, once, for the phone and not per app`() {
        val config = config(total = Duration.ofHours(2))
        val spent = used(game to Duration.ofMinutes(115))
        val closing = CloseWatch.nextClose(config, game, monday, spent)
        assertEquals(BlockReason.SCREEN_BUDGET, closing?.reason)
        assertEquals("", closing?.packageName, "the day's total is one event for the whole phone")
        assertEquals(Duration.ofMinutes(5), closing?.left)
        // And a child in an app nobody limits hears about it too.
        assertEquals(
            Duration.ofMinutes(5),
            CloseWatch.nextDeviceWideClose(config, monday, spent)?.left,
        )
    }

    @Test
    fun `nothing is said while the day is still long`() {
        val config = config(total = Duration.ofHours(2))
        assertNull(CloseWatch.nextClose(config, game, monday, used(game to Duration.ofMinutes(10))))
        assertNull(CloseWatch.nextDeviceWideClose(config, monday, used(game to Duration.ofMinutes(10))))
    }

    @Test
    fun `the parent's list says the phone is out of time, once`() {
        val config = config(total = Duration.ofHours(2))
        val blocks = RuleEngine.activeBlocks(
            config, listOf(game, chat), monday, used(game to Duration.ofHours(2)),
        )
        val screen = blocks.filter { it.kind == ActiveBlock.Kind.SCREEN_BUDGET }
        assertEquals(1, screen.size, "one phone, one row: $blocks")
        assertEquals("", screen.first().packageName)
        assertEquals(Duration.ofHours(2), screen.first().allowance)
    }

    @Test
    fun `a total judged from counters that are not today's is not reported at all`() {
        // The parent's screen reads a snapshot that can be hours old. "Out of time" derived from
        // yesterday's numbers is a rule they would be told is biting when it is not.
        val config = config(total = Duration.ofHours(2))
        val blocks = RuleEngine.activeBlocks(
            config, listOf(game), monday, used(game to Duration.ofHours(2)), usageIsToday = false,
        )
        assertTrue(blocks.none { it.kind == ActiveBlock.Kind.SCREEN_BUDGET }, "$blocks")
    }

    @Test
    fun `minutes granted to one app widen the day by the same amount`() {
        // The request a family answers most: "fifteen more minutes of that game" on a day that
        // is already spent. Credited to the app alone, the minutes bought nothing — the day's
        // total shut the app before its own allowance was ever read.
        val config = config(
            total = Duration.ofHours(2),
            perApp = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofHours(1)))),
        )
        val spent = used(game to Duration.ofHours(1), chat to Duration.ofHours(1))
        assertEquals(
            Verdict.Blocked(BlockReason.SCREEN_BUDGET),
            RuleEngine.evaluate(config, game, monday, spent),
        )
        val granted = mapOf(game to Duration.ofMinutes(15))
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofMinutes(15)),
            RuleEngine.evaluate(config, game, monday, spent, granted),
        )
        assertEquals(Duration.ofMinutes(15), config.screenTimeLeftAt(DayType.SCHOOL, ScreenTime.of(spent), granted))
    }
}
