package dev.walcott.data

import dev.walcott.rules.ExtraTime
import dev.walcott.rules.FamilyConfig
import java.time.Duration
import java.time.LocalDateTime

/**
 * Parent-side dashboard math over a child's reported usage and its resolved policy. Pure so
 * it's unit-tested on the JVM.
 */
object ChildStats {

    /** Widest offset java.time will accept (±18h); anything else is a broken or hostile child. */
    private val OFFSET_MINUTES = -18 * 60..18 * 60

    /**
     * The clock to read a child by: its own when it reported a UTC offset, the parent's when it
     * didn't. Everything the child publishes — `epochDay`, the usage and extra counters, the day
     * type its budget depends on — is keyed to the child's calendar day, so dating it with the
     * parent's clock is only right while both share a timezone. When they don't (either one on a
     * plane), the parent's day and the child's differ and the usage reads as zero.
     *
     * [tzOffsetMinutes] comes off the wire, so an out-of-range value is treated as unreported
     * rather than thrown: a corrupt snapshot must degrade to the old behaviour, not crash the
     * parent's home screen.
     */
    fun localNow(tzOffsetMinutes: Int?, nowMs: Long, parentNow: LocalDateTime): LocalDateTime {
        if (tzOffsetMinutes == null || tzOffsetMinutes !in OFFSET_MINUTES) return parentNow
        return LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nowMs),
            java.time.ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60),
        )
    }

    /**
     * Whether [epochDay] — the day a child stamped its counters with — is still that child's
     * current day, and so whether those counters may be shown as "today". False for a device
     * that hasn't published since yesterday: its numbers are real but they are not today's.
     */
    fun reportsCurrentDay(
        epochDay: Long,
        tzOffsetMinutes: Int?,
        nowMs: Long,
        parentNow: LocalDateTime,
    ): Boolean = epochDay == localNow(tzOffsetMinutes, nowMs, parentNow).toLocalDate().toEpochDay()

    /**
     * The limit an app gets today unless something was set for it, or null when the family has
     * no default. What replaced "time left today": with a per-app allowance there is no single
     * number left to show — an unused app still has its full hour, so summing them would report
     * a day's worth of screen time per installed app. The number a parent can actually act on is
     * the allowance itself, and the child's own screen shows what is left app by app.
     */
    fun defaultBudgetToday(config: FamilyConfig, now: LocalDateTime): Duration? =
        config.defaultAppBudget[config.calendar.dayTypeOf(now)]
}
