package dev.walcott.ui.format

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The unit abbreviations every duration on every screen is printed with, read from the string
 * resources once per process and again when the locale changes (see `WalcottApplication`).
 *
 * Held here rather than passed in because [humanize] has no context to ask and sixty-odd call
 * sites — the permanent notification, every countdown, every budget row — none of which should
 * grow a parameter for what is one fact about the phone. It was the single most shown string in
 * the app and the only one not translated: "5m" is five metres to a Spanish reader.
 */
object DurationUnits {
    @Volatile var hour: String = "h"
    @Volatile var minute: String = "m"
    @Volatile var second: String = "s"

    fun load(context: android.content.Context) {
        hour = context.getString(dev.walcott.R.string.duration_unit_hour)
        minute = context.getString(dev.walcott.R.string.duration_unit_minute)
        second = context.getString(dev.walcott.R.string.duration_unit_second)
    }
}

/** "1h 20m", "20m", "45s" — in the device's language (see [DurationUnits]). Compact form for counters. */
fun Duration.humanize(): String {
    val totalSeconds = seconds.coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 && m > 0 -> "$h${DurationUnits.hour} $m${DurationUnits.minute}"
        h > 0 -> "$h${DurationUnits.hour}"
        m > 0 -> "$m${DurationUnits.minute}"
        else -> "$s${DurationUnits.second}"
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.hhmm(): String = format(hhmm)

/**
 * The reference instant to age [atMs] against, given a [nowMs] that may be a tick behind.
 *
 * The screens that print relative ages tick once a minute rather than continuously, so their
 * clock is up to a minute old — and anything that has just happened is therefore in its FUTURE.
 * `DateUtils.getRelativeTimeSpanString` says so out loud: an approval the parent had that second
 * tapped came back on the wall reading "In 0 minutes", and stayed in the future until the next
 * tick. Aging against this instead, the worst case is a fresh line reading "0 minutes ago",
 * which is both true and what the parent expects to see.
 */
fun ageReference(atMs: Long, nowMs: Long): Long = maxOf(atMs, nowMs)
