package dev.walcott.enforcement

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How a family that does not want unapproved apps still lets the ones it has update themselves.
 *
 * The problem is Android's, not this app's: `DISALLOW_INSTALL_APPS` blocks INSTALLS, and to the
 * platform an update is an install. There is no "new apps no, updates yes" restriction, and a
 * phone whose apps cannot update is a phone that stops getting security fixes — which is not
 * what any parent meant by "don't install things".
 *
 * Walcott's own updates are unaffected either way: a Device Owner installs through
 * [android.content.pm.PackageInstaller] and the restriction does not apply to it. Play has no
 * such privilege, which is why this exists.
 *
 * Two answers, because families differ on which risk they mind:
 *
 *  - [MODE_GUARDED], which is what a new family is set up with: the restriction is never set.
 *    Play works normally all day — so updates simply happen, on Play's own schedule, with no
 *    window to hit — sideloading is still blocked by its own restriction, and anything new that
 *    appears is suspended and reported within seconds. The app exists on the phone for those
 *    seconds; that is the whole of the trade, and it is the honest one to default to: the
 *    alternative's promise ("nothing installs, ever") came with a phone whose apps went stale.
 *  - [MODE_STRICT] keeps the restriction and opens a window in which it is lifted. New apps are
 *    impossible outside it, and even inside it the install guard keeps watching (see
 *    `InstallGuard`) — an UPDATE does not change the set of installed packages, so it is
 *    invisible to the guard, while a new package is caught exactly as it would be at noon.
 *
 * The window defaults to the family's own sleeping hours rather than a token hour, because
 * nobody can make Play update inside a window of our choosing: Play runs its own daily pass
 * when it finds the phone charging, idle and on Wi-Fi, and an hour that misses it costs the
 * phone a night. A window the length of a night is the only lever this side has (see
 * [dev.walcott.enforcement.AppUpdateWindowAlarm]) — and it is a cheap one, because during those
 * hours the child's bedtime rules are already closing Play's own screen.
 *
 * Pure so the arithmetic — which decides when a phone's protection is briefly down — is
 * unit-tested rather than reasoned about.
 */
object AppUpdates {

    /** The restriction stays on, with a window at night for updates. */
    const val MODE_STRICT = "strict"

    /** No restriction; the guard catches and suspends whatever turns up. The default. */
    const val MODE_GUARDED = "guarded"

    /** Where the manual window starts when the family has no sleeping hours to follow. */
    const val DEFAULT_HOUR = 1

    /** And how long it runs: 01:00–06:00, the floor under any night. */
    const val DEFAULT_MINUTES = 300

    /** What the parent can pick for a manual window, in minutes. */
    val MINUTE_CHOICES = listOf(60, 120, 300)

    /** And when it may start. Late enough that nobody is shopping for apps, early enough to end by morning. */
    val HOUR_CHOICES = listOf(0, 1, 2, 3, 4)

    /**
     * The longest window this build will honour, whatever it is asked for.
     *
     * Twelve hours: no window that means "while the house is asleep" is longer than half a day,
     * and the length travels in the family policy — so it arrives from another phone and another
     * build, where a field that decodes wrong (or one a future version repurposes) would
     * otherwise be read here as "the block is down for the next eleven weeks".
     */
    const val MAX_MINUTES = 12 * 60

    /** Minutes in a day, for the clock arithmetic below. */
    private const val DAY_MINUTES = 24 * 60

    /**
     * A window on the clock: where it starts, in minutes past midnight, and how long it runs.
     *
     * Minutes rather than a whole hour because the window follows the family's bedtime by
     * default, and bedtimes are set at 21:30 as often as at 22:00.
     */
    data class Window(val startMinute: Int, val lengthMinutes: Int) {
        /** Where it starts on the clock, whatever arrived in the field. */
        val start: LocalTime get() = LocalTime.MIDNIGHT.plusMinutes(startMinute.coerceIn(0, DAY_MINUTES - 1).toLong())

        /** And where it ends — the following morning, for every window that follows a bedtime. */
        val end: LocalTime get() = start.plusMinutes(length.toLong())

        /** The length this build will actually run for; see [MAX_MINUTES]. */
        val length: Int get() = lengthMinutes.coerceIn(0, MAX_MINUTES)
    }

    /** Whether [mode] is one this build understands; anything else is read as [MODE_STRICT]. */
    fun modeOf(mode: String?): String = if (mode == MODE_GUARDED) MODE_GUARDED else MODE_STRICT

    /**
     * The window in force tonight: the family's sleeping hours when they asked to follow them and
     * there are any, otherwise the hour they picked by hand.
     *
     * [bedtime] is null both when the switch is off and when the family has no bedtime at all —
     * the fallback is deliberately the same, because "follow their sleeping hours" said of a
     * family that has none has to mean something, and [DEFAULT_HOUR] for [DEFAULT_MINUTES] is
     * the night everybody has.
     */
    fun window(bedtime: Window?, hour: Int, minutes: Int): Window =
        bedtime?.takeIf { it.length > 0 } ?: Window(hour.coerceIn(0, 23) * 60, minutes)

    /**
     * When the window containing [now] ends, or null when [now] is outside one.
     *
     * Yesterday's window is checked as well as today's, because a window that follows a bedtime
     * nearly always crosses midnight (21:30 for ten hours) and "did one start today" would
     * answer no at 00:30 while one was running.
     */
    fun windowEnd(now: LocalDateTime, window: Window): LocalDateTime? {
        if (window.length <= 0) return null
        val startToday = startOn(now, window)
        return listOf(startToday, startToday.minusDays(1))
            .map { it to it.plusMinutes(window.length.toLong()) }
            .firstOrNull { (start, end) -> !now.isBefore(start) && now.isBefore(end) }
            ?.second
    }

    /**
     * The next time a window opens STRICTLY AFTER [now] — the one the alarm is set for.
     *
     * Never an instant in the past, however deep inside a window [now] is. This used to answer
     * the current window's start while inside one, so that a device waking up mid-window opened
     * it instead of waiting for tomorrow — but the alarm this arms is the same alarm whose
     * firing re-arms it, and an alarm set for a past instant is delivered at once: every firing
     * scheduled another immediate firing, round and round for the whole window, each lap costing
     * a policy write, twenty restriction calls and a publish. Doze's ~9-minute quota was the only
     * brake, and Doze does not apply to a charging phone — which is the phone this was designed
     * for.
     *
     * Opening a window that is ALREADY running is a separate question with its own answer:
     * [windowEnd] (see [dev.walcott.enforcement.AppUpdateWindowAlarm.sync]).
     */
    fun nextStart(now: LocalDateTime, window: Window): LocalDateTime {
        val startToday = startOn(now, window)
        return if (now.isBefore(startToday)) startToday else startToday.plusDays(1)
    }

    /** [now]'s own day at the window's start. */
    private fun startOn(now: LocalDateTime, window: Window): LocalDateTime =
        now.toLocalDate().atTime(window.start)
}
