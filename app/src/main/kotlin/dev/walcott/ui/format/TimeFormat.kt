package dev.walcott.ui.format

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** "1h 20m", "20m", "45s". Compact form for counters. */
fun Duration.humanize(): String {
    val totalSeconds = seconds.coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 -> "${m}m"
        else -> "${s}s"
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
