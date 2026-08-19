package dev.walcott.enforcement

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How a family that does not want new apps still lets the ones it has update themselves.
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
 *  - [MODE_STRICT] keeps the restriction and opens a nightly window in which it is lifted. New
 *    apps are impossible for all but that hour, and even in it the install guard keeps watching
 *    (see `InstallGuard`) — an UPDATE does not change the set of installed packages, so it is
 *    invisible to the guard, while a new package is caught exactly as it would be at noon.
 *  - [MODE_GUARDED] never sets the restriction. Play works normally all day, sideloading is
 *    still blocked by its own restriction, and anything new that appears is suspended and
 *    reported within seconds. The app exists on the phone for those seconds; that is the whole
 *    of the trade.
 *
 * Pure so the arithmetic — which decides when a phone's protection is briefly down — is
 * unit-tested rather than reasoned about.
 */
object AppUpdates {

    /** The restriction stays on, with a nightly window for updates. */
    const val MODE_STRICT = "strict"

    /** No restriction; the guard catches and suspends whatever turns up. */
    const val MODE_GUARDED = "guarded"

    /** Small hours: the phone is charging, nobody is shopping for apps, and Play likes it too. */
    const val DEFAULT_HOUR = 4

    /** Long enough for a queue of updates on a slow connection, short enough to be a window. */
    const val DEFAULT_MINUTES = 60

    /** What the parent can pick, in minutes. */
    val MINUTE_CHOICES = listOf(30, 60, 120)

    /** Whether [mode] is one this build understands; anything else is read as [MODE_STRICT]. */
    fun modeOf(mode: String?): String = if (mode == MODE_GUARDED) MODE_GUARDED else MODE_STRICT

    /**
     * When the window containing [now] ends, or null when [now] is outside one.
     *
     * Yesterday's window is checked as well as today's, because a window is allowed to cross
     * midnight (23:30 for two hours) and "did one start today" would answer no at 00:30 while
     * one was running.
     */
    fun windowEnd(now: LocalDateTime, hour: Int, minutes: Int): LocalDateTime? {
        if (minutes <= 0) return null
        val startToday = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
        return listOf(startToday, startToday.minusDays(1))
            .map { it to it.plusMinutes(minutes.toLong()) }
            .firstOrNull { (start, end) -> !now.isBefore(start) && now.isBefore(end) }
            ?.second
    }

    /**
     * The next time a window opens after [now] — the one the alarm is set for.
     *
     * Answers the CURRENT window's start while inside it, so a device that wakes up mid-window
     * (a reboot at 04:10) still opens it instead of waiting for tomorrow.
     */
    fun nextStart(now: LocalDateTime, hour: Int, minutes: Int): LocalDateTime {
        val startToday = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
        val startYesterday = startToday.minusDays(1)
        return when {
            windowEnd(now, hour, minutes) != null ->
                if (!now.isBefore(startToday)) startToday else startYesterday
            now.isBefore(startToday) -> startToday
            else -> startToday.plusDays(1)
        }
    }
}
