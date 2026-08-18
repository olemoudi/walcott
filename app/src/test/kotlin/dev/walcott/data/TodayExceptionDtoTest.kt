package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's one-off change on the wire: how it is written per member, how it reaches the engine,
 * and — the part that matters most — how it stops existing.
 *
 * Nothing here is ever undone by hand, so a bug in the expiry is a pause that never ends or a
 * bedtime that comes back an hour into the night somebody was told they could stay up.
 */
class TodayExceptionDtoTest {

    private val today = LocalDate.of(2026, 3, 2)
    private val todayEpochDay = today.toEpochDay()
    private val nowMs = today.atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `a pause that has not run out survives, one that has does not`() {
        val running = TodayExceptionDto(pauseUntilMs = nowMs + 60_000)
        assertEquals(running, running.prunedAt(nowMs, todayEpochDay))

        val spent = TodayExceptionDto(pauseUntilMs = nowMs - 1)
        assertTrue(spent.prunedAt(nowMs, todayEpochDay).isEmpty, "a pause that is over is nothing at all")
    }

    @Test
    fun `tonight's bedtime change survives into the small hours`() {
        // Written on the Monday evening, read on the Tuesday at 02:00: the tail of the same
        // night, and the hour a child would notice it being taken back.
        val tonight = TodayExceptionDto(bedtimeNightEpochDay = todayEpochDay, bedtimeOff = true)
        val pruned = tonight.prunedAt(nowMs + 5 * 3600_000, todayEpochDay + 1)
        assertEquals(todayEpochDay, pruned.bedtimeNightEpochDay)
        assertTrue(pruned.bedtimeOff)
    }

    @Test
    fun `a night two days ago is dropped`() {
        val old = TodayExceptionDto(
            bedtimeNightEpochDay = todayEpochDay - 2,
            bedtimeDelayMinutes = 60,
            bedtimeOff = true,
        )
        val pruned = old.prunedAt(nowMs, todayEpochDay)
        assertTrue(pruned.isEmpty)
        assertEquals(0, pruned.bedtimeDelayMinutes)
        assertFalse(pruned.bedtimeOff)
    }

    @Test
    fun `the engine reads the instant, not an hour on somebody's clock`() {
        val at = today.atTime(21, 30)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val domain = TodayExceptionDto(pauseUntilMs = ms).toDomain()
        assertEquals(at, domain.pauseUntil)
        assertTrue(domain.pausedAt(at.minusMinutes(1)))
        assertFalse(domain.pausedAt(at.plusMinutes(1)))
    }

    @Test
    fun `nothing set decodes to nothing`() {
        val domain = TodayExceptionDto().toDomain()
        assertNull(domain.pauseUntil)
        assertNull(domain.bedtimeNight)
        assertTrue(domain.isEmpty)
    }

    @Test
    fun `it is written per member and the family carries none`() {
        val settings = PolicySettings(
            children = listOf(
                ChildEntry(
                    childId = "c1",
                    name = "Ana",
                    overrides = ChildOverrides(todayException = TodayExceptionDto(pauseUntilMs = nowMs + 1000)),
                ),
                ChildEntry(childId = "c2", name = "Leo"),
            ),
        )
        assertEquals(nowMs + 1000, settings.resolveForChild("c1").todayException?.pauseUntilMs)
        assertNull(settings.resolveForChild("c2").todayException, "a sibling is not paused")
        assertNull(settings.todayException, "and neither is the family")
    }

    @Test
    fun `a pause is not a customized rule`() {
        // The member's rules section counts what has been pulled away from the family's. An
        // exception expires on its own, so counting it would tell a parent they had personalised
        // something an hour before it stopped existing.
        val overrides = ChildOverrides(todayException = TodayExceptionDto(pauseUntilMs = nowMs + 1000))
        assertEquals(0, overrides.customRuleCount)
        assertTrue(overrides.isEmpty)
    }
}
