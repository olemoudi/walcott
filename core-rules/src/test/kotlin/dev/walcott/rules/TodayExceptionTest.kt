package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The two one-off changes a family makes constantly and the rules cannot say: "put it down, now"
 * and "you can stay up tonight".
 *
 * Both are dated, and that is most of what these tests are about: an exception must reach every
 * hour of the thing it changes (a bedtime is two halves of one night) and must not survive it.
 */
class TodayExceptionTest {

    private val game = "com.game"
    private val phone = "com.phone"

    /** A Monday evening, before any bedtime. */
    private val monday = LocalDateTime.of(2026, 3, 2, 20, 0)

    private fun config(
        bedtime: TimeWindow? = TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30)),
        exception: TodayException = TodayException(),
        default: Duration? = Duration.ofHours(2),
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = default?.let { DayType.entries.associateWith { _ -> it } }.orEmpty(),
        bedtime = bedtime?.let { w -> DayType.entries.associateWith { w } }.orEmpty(),
        essentialPackages = setOf(phone),
        todayException = exception,
    )

    // --- The pause ---

    @Test
    fun `a pause closes the phone until its moment, and then stops`() {
        val until = monday.plusMinutes(30)
        val cfg = config(exception = TodayException(pauseUntil = until))

        assertEquals(
            Verdict.Blocked(BlockReason.PAUSED),
            RuleEngine.evaluate(cfg, game, monday),
            "a paused phone is closed, whatever the rules would have said",
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofHours(2)),
            RuleEngine.evaluate(cfg, game, until.plusMinutes(1)),
            "and it opens again by itself, with nothing to undo",
        )
    }

    @Test
    fun `a pause never touches the essential apps`() {
        val cfg = config(exception = TodayException(pauseUntil = monday.plusHours(1)))
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, phone, monday), "the phone still calls home")
    }

    @Test
    fun `granted minutes are not an answer to a pause`() {
        val cfg = config(exception = TodayException(pauseUntil = monday.plusHours(1)))
        val verdict = RuleEngine.evaluate(
            cfg, game, monday,
            usageToday = emptyMap(),
            extraTime = mapOf(ExtraTime.ALL_APPS to Duration.ofHours(5)),
        )
        assertEquals(Verdict.Blocked(BlockReason.PAUSED), verdict)
    }

    @Test
    fun `a pause is the device-wide block, and the parent sees when it lets go`() {
        val until = monday.plusMinutes(20)
        val cfg = config(exception = TodayException(pauseUntil = until))

        assertEquals(BlockReason.PAUSED, RuleEngine.deviceWideBlock(cfg, monday))
        val blocks = RuleEngine.activeBlocks(cfg, listOf(game), monday)
        assertEquals(ActiveBlock.Kind.PAUSED, blocks.first().kind, "and it is the first thing said")
        assertEquals(until.toLocalTime(), blocks.first().until)
    }

    @Test
    fun `the child is told it is a pause, not a rule`() {
        val cfg = config(exception = TodayException(pauseUntil = monday.plusMinutes(5)))
        val status = RuleEngine.appStatus(cfg, game, monday)
        assertEquals(AppState.BLOCKED, status.state)
        assertEquals(BlockReason.PAUSED, status.blockReason)
    }

    @Test
    fun `a pause that has run out is not a block at all`() {
        val cfg = config(exception = TodayException(pauseUntil = monday.minusMinutes(1)))
        assertNull(RuleEngine.deviceWideBlock(cfg, monday))
        assertTrue(RuleEngine.activeBlocks(cfg, listOf(game), monday).isEmpty())
        assertFalse(cfg.todayException.pausedAt(monday))
    }

    // --- Tonight's bedtime ---

    @Test
    fun `a delayed bedtime starts later and still ends at the usual hour`() {
        val cfg = config(
            exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeDelayMinutes = 60),
        )
        val tonight = cfg.bedtimeAt(monday)
        assertEquals(LocalTime.of(22, 30), tonight?.start)
        assertEquals(LocalTime.of(7, 30), tonight?.end, "a late night is not a late morning")

        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofHours(2)),
            RuleEngine.evaluate(cfg, game, monday.withHour(22)),
            "22:00 is inside the old bedtime and outside tonight's",
        )
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(cfg, game, monday.withHour(23)),
            "the delay moves bedtime, it does not cancel it",
        )
    }

    @Test
    fun `the morning after a delayed bedtime is still that night`() {
        val cfg = config(
            exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeDelayMinutes = 60),
        )
        // 03:00 on the Tuesday: the tail of Monday night, so the exception has to reach it.
        val smallHours = LocalDateTime.of(2026, 3, 3, 3, 0)
        assertEquals(LocalTime.of(22, 30), cfg.bedtimeAt(smallHours)?.start)
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), RuleEngine.evaluate(cfg, game, smallHours))
    }

    @Test
    fun `a lifted bedtime leaves no bedtime that night`() {
        val cfg = config(exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeOff = true))
        assertNull(cfg.bedtimeAt(monday))
        assertEquals(Verdict.AllowedWithBudget(Duration.ofHours(2)), RuleEngine.evaluate(cfg, game, monday.withHour(23)))
        assertNull(RuleEngine.deviceWideBlock(cfg, monday.withHour(23)))
    }

    @Test
    fun `an exception does not outlive its night`() {
        val cfg = config(exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeOff = true))
        // The NEXT night: 23:00 on the Tuesday, which is nothing to do with Monday's exception.
        val tomorrow = LocalDateTime.of(2026, 3, 3, 23, 0)
        assertEquals(LocalTime.of(21, 30), cfg.bedtimeAt(tomorrow)?.start)
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), RuleEngine.evaluate(cfg, game, tomorrow))
    }

    @Test
    fun `a delay longer than the night lifts it rather than swallowing the next day`() {
        val cfg = config(
            bedtime = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)),
            exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeDelayMinutes = 10 * 60),
        )
        assertNull(cfg.bedtimeAt(monday), "eight hours of bedtime delayed by ten is no bedtime")
        assertNull(RuleEngine.deviceWideBlock(cfg, LocalDateTime.of(2026, 3, 3, 3, 0)))
    }

    @Test
    fun `tonight's change is what the parent's context card reads`() {
        val cfg = config(
            exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeDelayMinutes = 90),
        )
        val context = RuleEngine.ruleContext(cfg, monday)
        assertEquals(WindowStatus.Later(LocalTime.of(23, 0), LocalTime.of(7, 30)), context.bedtime)
    }

    @Test
    fun `a lifted bedtime still knows which night it is`() {
        // The bug this exists for: after midnight, a caller that asks bedtimeAt "which night is
        // this?" gets null on a night already lifted, falls back to today's date, and concludes
        // the exception it is holding belongs to some other night — so the parent is offered no
        // way to put back the bedtime they lifted a minute ago.
        val cfg = config(exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeOff = true))
        val smallHours = LocalDateTime.of(2026, 3, 3, 1, 5)

        assertNull(cfg.bedtimeAt(smallHours), "the night is lifted, so there is no bedtime left")
        assertEquals(
            LocalDate.of(2026, 3, 2),
            cfg.scheduledBedtimeAt(smallHours)?.nightOf(smallHours),
            "and the rule still says which night the phone is in",
        )
    }

    @Test
    fun `nothing set is nothing changed`() {
        val cfg = config()
        assertTrue(cfg.todayException.isEmpty)
        assertEquals(LocalTime.of(21, 30), cfg.bedtimeAt(monday)?.start)
        assertNull(RuleEngine.deviceWideBlock(cfg, monday))
    }

    @Test
    fun `a warning never announces a bedtime the parent has already moved`() {
        val cfg = config(
            exception = TodayException(bedtimeNight = LocalDate.of(2026, 3, 2), bedtimeDelayMinutes = 60),
            default = null,
        )
        // 21:10: twenty minutes from the configured bedtime, eighty from tonight's.
        val closing = CloseWatch.nextDeviceWideClose(cfg, monday.withHour(21).withMinute(10))
        assertNull(closing, "the old hour is not coming, and the new one is outside the horizon")
    }
}
