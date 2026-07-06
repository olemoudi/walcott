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
