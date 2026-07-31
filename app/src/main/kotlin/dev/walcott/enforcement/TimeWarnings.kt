package dev.walcott.enforcement

import dev.walcott.rules.BlockReason
import dev.walcott.rules.CloseWatch
import dev.walcott.rules.ClosingSoon

/**
 * Which "this is about to close" warnings have already been said. The enforcement loop asks
 * several times a minute; the child hears each warning once.
 *
 * A countdown is recognised by its *deadline*, not by how much is left: while it runs down the
 * deadline stays put, and when something real moves it — the parent grants extra time, a new
 * day brings tonight's bedtime — it jumps, and that is a new countdown that earns its own
 * warnings. Deadlines are compared with slack because the loop samples, not ticks.
 */
class TimeWarnings {

    private val said = mutableMapOf<String, Deadline>()

    private data class Deadline(val signature: Long, val threshold: Int)

    /**
     * The threshold (in minutes) to announce for [closing] right now, or null to stay quiet.
     * [nowMinute] is the current time in minutes since the epoch — the frame the wall-clock
     * deadlines are measured in.
     */
    fun due(closing: ClosingSoon?, nowMinute: Long): Int? {
        if (closing == null) return null
        val threshold = CloseWatch.thresholdFor(closing.left) ?: return null
        val key = "${closing.reason}|${closing.packageName}"
        val signature = signatureOf(closing, nowMinute)
        val previous = said[key]
        val sameCountdown = previous != null && signature <= previous.signature + SLACK_MINUTES
        if (sameCountdown && previous!!.threshold <= threshold) return null
        said[key] = Deadline(signature, threshold)
        return threshold
    }

    /**
     * What identifies this countdown. An app's own time is spent, not elapsed: it only moves
     * when the app is in use, so the minutes left ARE the deadline. Bedtime and screen-free
     * windows arrive whatever the child does, so theirs is a moment on the clock.
     */
    private fun signatureOf(closing: ClosingSoon, nowMinute: Long): Long =
        if (closing.reason == BlockReason.BUDGET_EXHAUSTED) {
            closing.left.toMinutes()
        } else {
            nowMinute + closing.left.toMinutes()
        }

    companion object {
        /**
         * How far a deadline may drift and still count as the same countdown. The loop samples
         * every couple of seconds and rounds to minutes, and a child who puts an app down for a
         * moment shouldn't be told about the same 30 minutes twice.
         */
        const val SLACK_MINUTES = 5L
    }
}
