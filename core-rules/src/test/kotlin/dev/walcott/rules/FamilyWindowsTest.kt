package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Family-wide screen-free windows: like bedtime, but any number of them per day. */
class FamilyWindowsTest {

    private val tuesday = LocalDateTime.of(2026, 3, 3, 0, 0).toLocalDate() // SCHOOL

    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(tuesday, LocalTime.of(hour, minute))

    private fun window(fromH: Int, fromM: Int, toH: Int, toM: Int) =
        TimeWindow(LocalTime.of(fromH, fromM), LocalTime.of(toH, toM))

    private fun config(windows: List<TimeWindow>) = FamilyConfig(
        version = 1,
        blockedWindows = mapOf(DayType.SCHOOL to windows),
        essentialPackages = setOf("com.android.dialer"),
    )

    @Test
    fun `multiple windows in one day all bite, gaps stay open`() {
        val cfg = config(listOf(window(14, 0, 15, 30), window(17, 0, 19, 0)))
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, "com.game", at(14, 30)))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.game", at(16, 0)))
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, "com.game", at(18, 0)))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.game", at(20, 0)))
    }

    @Test
    fun `blocks every non-essential app, even ones with no rules of their own`() {
        val cfg = config(listOf(window(17, 0, 19, 0)))
        // An app nobody has set a rule for is inside the family window too: the window is
        // checked before any budget, so "no limit" never means "not covered".
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, "com.mystery", at(18, 0)))
        // Essentials (dialer…) always stay out of any window.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.android.dialer", at(18, 0)))
    }

    @Test
    fun `extra time never lifts a family window`() {
        val cfg = config(listOf(window(17, 0, 19, 0)))
        val verdict = RuleEngine.evaluate(
            cfg, "com.game", at(18, 0),
            extraTime = mapOf(ExtraTime.ALL_APPS to Duration.ofHours(2), "com.game" to Duration.ofHours(2)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), verdict)
    }

    @Test
    fun `a midnight-crossing window blocks on both sides`() {
        val cfg = config(listOf(window(22, 0, 6, 0)))
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, "com.game", at(23, 0)))
        assertEquals(Verdict.Blocked(BlockReason.BLOCKED_WINDOW), RuleEngine.evaluate(cfg, "com.game", at(5, 0)))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.game", at(12, 0)))
    }

    @Test
    fun `windows only apply on their day type`() {
        val cfg = config(listOf(window(17, 0, 19, 0))) // SCHOOL only
        val saturday = LocalDateTime.of(2026, 3, 7, 18, 0)
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.game", saturday))
    }

    @Test
    fun `the app card on the child home reflects the family window`() {
        val cfg = config(listOf(window(17, 0, 19, 0)))
        val status = RuleEngine.appStatus(cfg, "com.game", at(18, 0))
        assertEquals(AppState.BLOCKED, status.state)
        assertEquals(BlockReason.BLOCKED_WINDOW, status.blockReason)
    }
}
