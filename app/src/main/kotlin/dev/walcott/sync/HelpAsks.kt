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

    /**
     * How long a help ask waits before its owner may send it again.
     *
     * The button hides itself while an ask is unanswered, which is right for the first minute and
     * wrong for the rest: nothing closes a help ask except the family pressing "I've helped", and
     * the family's own answer to it happens on the telephone. Somebody who rings, sorts it out and
     * never opens the app again used to leave a dead button behind for the two days the ask lives
     * ([SyncEngine.REQUEST_TTL_MS]) — on the one screen a person who is stuck has.
     *
     * Ten minutes because that is the floor of the parent's own catch-up ([ParentCadence.FAST_MS]):
     * before it, asking again cannot have reached anybody the first ask did not, so the honest
     * answer is still "it is on its way".
     */
    const val REASK_AFTER_MS = 10 * 60 * 1000L

    /**
     * Whether a help ask made at [createdAtMs] may be replaced by a fresh one at [nowMs].
     *
     * A clock that has gone backwards says no, like [reminderDue]: the alternative is a button
     * that re-arms itself the moment the phone's time drifts.
     */
    fun reaskAllowed(createdAtMs: Long, nowMs: Long): Boolean =
        nowMs - createdAtMs >= REASK_AFTER_MS

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
