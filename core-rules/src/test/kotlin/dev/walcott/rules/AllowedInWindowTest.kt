package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A window that closes everything EXCEPT a few apps (see [TimeWindow.allowedPackages]).
 *
 * The rule every family has and could not write down. "Homework, five to seven" is almost never
 * "nothing at all": it is nothing except the dictionary, the calculator and whatever they are
 * listening to. Without this, the choice was a window that took the homework tools away with
 * everything else, or no window — and every family that met that choice picked the second.
 */
class AllowedInWindowTest {

    private val homework = LocalDateTime.of(2026, 3, 2, 18, 0)
    private val evening = LocalDateTime.of(2026, 3, 2, 20, 0)
    private val dictionary = "com.dictionary"
    private val game = "com.game"

    private fun config(
        allowed: Set<String> = emptySet(),
        bedtime: TimeWindow? = null,
        paused: LocalDateTime? = null,
    ) = FamilyConfig(
        version = 1,
        blockedWindows = mapOf(
            DayType.SCHOOL to listOf(
                TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0), allowedPackages = allowed),
            ),
        ),
        bedtime = bedtime?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        essentialPackages = setOf("com.android.dialer"),
        todayException = TodayException(pauseUntil = paused),
    )

    @Test
    fun `the window closes everything it was not told to leave open`() {
        val config = config(allowed = setOf(dictionary))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, dictionary, homework))
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(config, game, homework),
        )
        // And the child's own card agrees, from the other implementation.
        assertEquals(AppState.ALLOWED, RuleEngine.appStatus(config, dictionary, homework).state)
        assertEquals(AppState.BLOCKED, RuleEngine.appStatus(config, game, homework).state)
    }

    @Test
    fun `a window with no list is exactly what it always was`() {
        val config = config()
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(config, dictionary, homework),
        )
        assertEquals(emptySet<String>(), RuleEngine.windowExemptions(config, homework))
    }

    @Test
    fun `an allowed app is open only while that window is the one running`() {
        // The list belongs to the window, not to the app: outside its hours it says nothing, and
        // the app answers to whatever else the day has.
        val config = config(allowed = setOf(dictionary))
        assertEquals(emptySet<String>(), RuleEngine.windowExemptions(config, evening))
        assertEquals(setOf(dictionary), RuleEngine.windowExemptions(config, homework))
    }

    @Test
    fun `two rules running at once mean two rules, so the exemption is the intersection`() {
        // A bedtime that says nothing about the dictionary still closes it, whatever the
        // homework window permits. Taking the union here would have let one rule quietly lift
        // another — the bug this test exists to keep out.
        val config = config(
            allowed = setOf(dictionary),
            bedtime = TimeWindow(LocalTime.of(16, 0), LocalTime.of(7, 0)),
        )
        assertEquals(emptySet<String>(), RuleEngine.windowExemptions(config, homework))
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(config, dictionary, homework),
        )
    }

    @Test
    fun `a pause allows nothing, whatever any window says`() {
        // A pause is a person saying "put it down, now". It is the one rule with no exceptions,
        // and a window's allow-list must not become a way around it.
        val config = config(allowed = setOf(dictionary), paused = homework.plusMinutes(30))
        assertEquals(emptySet<String>(), RuleEngine.windowExemptions(config, homework))
        assertEquals(
            Verdict.Blocked(BlockReason.PAUSED),
            RuleEngine.evaluate(config, dictionary, homework),
        )
    }

    @Test
    fun `the DNS filter spares what the window leaves open`() {
        // Half an allow-list is worse than none: a homework window that permits the dictionary
        // and then takes its DNS away has permitted an app that cannot look anything up.
        val config = config(allowed = setOf(dictionary))
        val standing = Curfew.standing(config, browsers = setOf(dictionary, game), now = homework)
        assertTrue(standing.windowOpen)
        assertEquals(setOf(game), standing.packages)
        // And it survives the other half of the curfew, the one the enforcement loop observes:
        // an app the family allowed cannot be cut off for having been used.
        assertEquals(setOf(game), standing.with(observed = setOf(dictionary, game)))
    }

    @Test
    fun `bedtime can name exceptions too, because it is the same kind of window`() {
        val config = FamilyConfig(
            version = 1,
            bedtime = mapOf(
                DayType.SCHOOL to TimeWindow(
                    LocalTime.of(21, 0), LocalTime.of(7, 0),
                    allowedPackages = setOf(dictionary),
                ),
            ),
        )
        val night = LocalDateTime.of(2026, 3, 2, 22, 0)
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, dictionary, night))
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), RuleEngine.evaluate(config, game, night))
    }

    @Test
    fun `the phone is still shut, which is what the whole-phone question means`() {
        // deviceWideBlock answers about the PHONE, and a window with three apps in it is still a
        // window: the child's screen has to say so, and the exemptions are asked for separately.
        val config = config(allowed = setOf(dictionary))
        assertEquals(BlockReason.BLOCKED_WINDOW, RuleEngine.deviceWideBlock(config, homework))
    }
}
