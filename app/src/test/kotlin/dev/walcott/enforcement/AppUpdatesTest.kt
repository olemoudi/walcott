package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

class AppUpdatesTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, day, hour, minute)

    private fun window(hour: Int, minutes: Int, startMinute: Int = 0) =
        AppUpdates.Window(hour * 60 + startMinute, minutes)

    @Test
    fun `the window is open from its start for exactly its length`() {
        val fourToFive = window(hour = 4, minutes = 60)
        assertNull(AppUpdates.windowEnd(at(10, 3, 59), fourToFive), "a minute early is closed")
        assertEquals(at(10, 5), AppUpdates.windowEnd(at(10, 4), fourToFive), "open on the hour")
        assertEquals(at(10, 5), AppUpdates.windowEnd(at(10, 4, 59), fourToFive))
        assertNull(AppUpdates.windowEnd(at(10, 5), fourToFive), "closed on its end, not after it")
    }

    @Test
    fun `a window that crosses midnight is still open on the other side of it`() {
        // The failure this prevents: asking only "did one start today" answers no at 00:30 while
        // a window opened at 21:30 is still running, and the block would re-arm mid-update. Every
        // window that follows a bedtime crosses midnight, so this is the ordinary case now.
        val bedtime = window(hour = 21, minutes = 600, startMinute = 30)
        assertEquals(at(11, 7, 30), AppUpdates.windowEnd(at(11, 0, 30), bedtime))
        assertEquals(at(11, 7, 30), AppUpdates.windowEnd(at(10, 21, 30), bedtime))
        assertNull(AppUpdates.windowEnd(at(11, 8), bedtime))
    }

    @Test
    fun `a zero-length window is no window at all`() {
        assertNull(AppUpdates.windowEnd(at(10, 4), window(hour = 4, minutes = 0)))
    }

    @Test
    fun `the next start is today's if it is still to come, and tomorrow's once it has passed`() {
        assertEquals(at(10, 4), AppUpdates.nextStart(at(10, 1), window(hour = 4, minutes = 60)))
        assertEquals(at(11, 4), AppUpdates.nextStart(at(10, 9), window(hour = 4, minutes = 60)))
    }

    @Test
    fun `the next start is never in the past, not even from inside a window`() {
        // The failure this prevents is the expensive one: the alarm this arms is the same alarm
        // whose firing re-arms it, and an alarm set for a past instant fires at once. Answering
        // 04:00 at 04:10 spun the receiver — open window, re-schedule, fire, open window — for
        // the whole hour, on the charging phone that never enters Doze to be throttled by it.
        assertEquals(at(11, 4), AppUpdates.nextStart(at(10, 4, 10), window(hour = 4, minutes = 60)))
        assertEquals(
            at(11, 4),
            AppUpdates.nextStart(at(10, 4), window(hour = 4, minutes = 60)),
            "on the hour counts as passed",
        )
        assertEquals(
            at(11, 21, 30),
            AppUpdates.nextStart(at(11, 0, 30), window(hour = 21, minutes = 600, startMinute = 30)),
        )
    }

    @Test
    fun `a device that wakes up inside a window can still tell that it is inside one`() {
        // The catch-up a reboot at 04:10 needs: not a past alarm, but this question, asked by
        // AppUpdateWindowAlarm.sync() on every start.
        assertEquals(at(10, 5), AppUpdates.windowEnd(at(10, 4, 10), window(hour = 4, minutes = 60)))
    }

    @Test
    fun `a window longer than this build honours is cut down to it`() {
        // The length arrives in a policy from another phone: a field that decodes wrong must not
        // read as "the block is down for the next eleven weeks".
        val absurd = window(hour = 1, minutes = 100_000)
        assertEquals(at(10, 13), AppUpdates.windowEnd(at(10, 7), absurd), "open for the maximum, from its own hour")
        assertNull(AppUpdates.windowEnd(at(10, 14), absurd), "shut twelve hours in, not weeks")
        assertEquals(0, AppUpdates.Window(0, -30).length)
        assertEquals(AppUpdates.MAX_MINUTES, AppUpdates.Window(0, 100_000).length)
    }

    @Test
    fun `the window is the family's sleeping hours when they have any, and the fallback when not`() {
        // Why the default follows bedtime at all: nobody outside Google can make Play update
        // inside an hour of our choosing, so the only lever this side has is how much of the
        // night it is allowed to pick.
        val bedtime = AppUpdates.Window(22 * 60 + 15, 9 * 60)
        assertEquals(bedtime, AppUpdates.window(bedtime, hour = 1, minutes = 300))
        assertEquals(
            AppUpdates.Window(60, 300),
            AppUpdates.window(null, hour = 1, minutes = 300),
            "a family with no bedtime still gets a night",
        )
        assertEquals(
            AppUpdates.Window(60, 300),
            AppUpdates.window(AppUpdates.Window(22 * 60, 0), hour = 1, minutes = 300),
            "and so does one whose bedtime is empty",
        )
    }

    @Test
    fun `an unknown mode is read as the strict one`() {
        // A policy from a NEWER parent naming a mode this build has never heard of must fall
        // back to the protective answer, never to "no restriction at all".
        assertEquals(AppUpdates.MODE_STRICT, AppUpdates.modeOf(null))
        assertEquals(AppUpdates.MODE_STRICT, AppUpdates.modeOf(""))
        assertEquals(AppUpdates.MODE_STRICT, AppUpdates.modeOf("something_new"))
        assertEquals(AppUpdates.MODE_GUARDED, AppUpdates.modeOf(AppUpdates.MODE_GUARDED))
    }

    @Test
    fun `a start outside the clock is clamped rather than thrown`() {
        assertEquals(LocalTime.of(23, 59), AppUpdates.Window(99 * 60, 30).start)
        assertEquals(LocalTime.MIDNIGHT, AppUpdates.Window(-300, 30).start)
        assertEquals(at(10, 0, 30), AppUpdates.windowEnd(at(10, 0, 10), AppUpdates.Window(-300, 30)))
    }
}
