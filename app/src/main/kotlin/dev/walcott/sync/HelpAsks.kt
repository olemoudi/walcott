package dev.walcott.sync

import kotlinx.serialization.Serializable

/**
 * The help button's message, from both ends: whether the asking phone may say it went, and when the
 * family's phone says it again (see [ChildRequest.KIND_HELP]).
 *
 * Every other ask can wait for somebody to open the app. This one is a person who is stuck, and it
 * had two ways of being lost that the others do not matter enough to have: a phone that said
 * "sent" while it was offline, and a single notification a sleeve can swipe away.
 */
object HelpAsks {

    /** How long a help ask waits between announcements on the family's phone. */
    const val REMINDER_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * Reminders after the first notification. A few, not until answered: an ask lives two days,
     * and a phone that repeats itself every quarter of an hour for two days is one whose family
     * silences the channel — and with it the next call for help. After these the notification
     * stays in the shade and the card at the top of the home.
     */
    const val MAX_REMINDERS = 3

    /** How often one help ask has been announced on this phone, and when last. */
    @Serializable
    data class Reminded(
        /** Reminders already posted, not counting the first notification. */
        val reminders: Int = 0,
        /** When this phone last announced it, on this phone's own clock. */
        val lastAtMs: Long = 0,
    ) {
        fun next(nowMs: Long): Reminded = Reminded(reminders + 1, nowMs)
    }

    /**
     * Whether [reminded] is due another announcement at [nowMs]. A clock that has gone backwards
     * waits for it to catch up rather than firing at once.
     */
    fun reminderDue(reminded: Reminded, nowMs: Long): Boolean =
        reminded.reminders < MAX_REMINDERS && nowMs - reminded.lastAtMs >= REMINDER_INTERVAL_MS

    /**
     * The help asks in [asks] the relay has not yet confirmed taking ([receipts]). Only help asks:
     * a request for an app says "waiting" either way, and receipting every publish for it would
     * spend the relay's patience on a question nobody is standing over.
     */
    fun unconfirmed(asks: List<ChildRequest>, receipts: Set<String>): Set<String> =
        asks.filter { it.kind == ChildRequest.KIND_HELP && it.requestId !in receipts }
            .map { it.requestId }
            .toSet()
}
