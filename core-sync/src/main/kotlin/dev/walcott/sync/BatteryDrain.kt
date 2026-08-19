package dev.walcott.sync

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * What close tracking actually costs this phone, measured on this phone.
 *
 * The dialog used to say "this uses a lot of battery", which is true and useless: a parent
 * deciding whether to follow a child across town cannot price "a lot". So the child measures
 * both halves of the comparison on its own hardware — ordinary use, and use with a session
 * running — and the answer travels as four numbers the parent's screens can put in a sentence.
 *
 * TWO THINGS MAKE THIS HARDER THAN IT LOOKS, and both shape everything below.
 *
 * The first is quantisation. Android reports the level as a whole percent, so a single half-hour
 * window carries ±1% of error — which, against an idle drain of about 1% per half hour, is ±100%.
 * Nothing here averages the RATES of individual windows for that reason; it sums the minutes and
 * sums the drops and divides once, so the error is spread over everything measured rather than
 * multiplied by it.
 *
 * The second is that "ordinary use" has to mean something. A night asleep drains almost nothing
 * and would drag the baseline down until close tracking looked ten times worse than it is; a
 * phone on a charger drains negative. The caller excludes those (see [MIN_WINDOW_MINUTES] for
 * the rest of the shape); this file only does arithmetic, and does it in whole minutes and whole
 * percent because that is all the platform ever gives.
 *
 * Pure, so all of it is unit-tested without a battery.
 */
object BatteryDrain {

    /** The window everything is expressed in, because it is the one a parent can picture. */
    const val WINDOW_MINUTES = 30

    /** Days of ordinary use kept. Past this a phone's habits — and its battery — have moved on. */
    const val RETENTION_DAYS = 15

    /** Sessions kept. Ten is enough to average out a walk in the sun against one in a pocket. */
    const val KEEP_SESSIONS = 10

    /**
     * Shortest interval worth recording.
     *
     * Below this the whole-percent reporting dominates: a five-minute window either shows 0% or
     * 1%, which is either "this phone uses nothing" or "this phone dies in four hours".
     */
    const val MIN_WINDOW_MINUTES = 10

    /**
     * Longest interval that can be attributed to what the rules said at its ends.
     *
     * The check-in that measures this fires every half hour; a gap much longer than that means
     * the phone was off, rebooted, or Doze deferred everything, and whatever happened in the
     * middle is not something the endpoints can vouch for.
     */
    const val MAX_WINDOW_MINUTES = 90

    /** Ordinary use needed before a figure is worth showing, in minutes. */
    const val MIN_NORMAL_MINUTES = 4 * 60

    /** Close tracking needed before its figure is worth showing, in minutes. */
    const val MIN_LIVE_MINUTES = 10

    /** One day's worth of measured ordinary use. Whole minutes and whole percent, as measured. */
    @Serializable
    data class Day(val epochDay: Long, val minutes: Int = 0, val drop: Int = 0)

    /** One close-tracking session, start to finish. */
    @Serializable
    data class Session(val startedAtMs: Long, val minutes: Int = 0, val drop: Int = 0)

    /**
     * What travels to the parent: the two rates, what they rest on, and the last session.
     *
     * Rates are percent per [WINDOW_MINUTES], -1 when there is not enough measurement to say.
     * Sent rather than the histories themselves — a parent needs the sentence, not the ledger,
     * and every byte here competes with the rules for one relay message.
     */
    @Serializable
    data class Summary(
        val normalPct: Float = -1f,
        val normalMinutes: Int = 0,
        val livePct: Float = -1f,
        val liveSessions: Int = 0,
        /** The last session's own drop and length, for "last time it cost you this much". */
        val lastDrop: Int = 0,
        val lastMinutes: Int = 0,
    ) {
        val hasNormal: Boolean get() = normalPct >= 0f
        val hasLive: Boolean get() = livePct >= 0f

        /**
         * How much more battery a session costs, as a percentage of ordinary use: 100 means it
         * doubles the drain. Null unless both halves were actually measured — a comparison with
         * one side missing is a number a parent would read as if both were there.
         *
         * A baseline that rounds to zero has no ratio to give: dividing by it produces a figure
         * that is arithmetically enormous and practically meaningless.
         */
        val upliftPercent: Int?
            get() = if (hasNormal && hasLive && normalPct >= 0.05f) {
                ((livePct / normalPct - 1f) * 100f).roundToInt().coerceAtLeast(0)
            } else {
                null
            }
    }

    /** Whether an interval can be attributed at all, before asking what the rules said. */
    fun measurable(minutes: Int, fromPercent: Int, toPercent: Int, charging: Boolean): Boolean =
        !charging &&
            minutes in MIN_WINDOW_MINUTES..MAX_WINDOW_MINUTES &&
            fromPercent in 0..100 &&
            toPercent in 0..100 &&
            // A level that went UP means a charger was on at some point in the middle, whatever
            // the endpoints say about now.
            toPercent <= fromPercent

    /**
     * The same question for a whole close-tracking session, which is measured end to end.
     *
     * [MAX_WINDOW_MINUTES] does not apply here and must not: a four-hour session is four hours
     * the phone spent doing exactly one known thing, which is the most attributable interval
     * this app ever gets. The ceiling is the longest session that can be asked for, plus slack
     * for a device that came back late to close its own books.
     */
    fun measurableSession(minutes: Int, fromPercent: Int, toPercent: Int, charging: Boolean): Boolean =
        !charging &&
            minutes in MIN_LIVE_MINUTES..(LiveTracking.MAX_MINUTES + MAX_WINDOW_MINUTES) &&
            fromPercent in 0..100 &&
            toPercent in 0..100 &&
            toPercent <= fromPercent

    /** Percent per [WINDOW_MINUTES], or -1 when [minutes] is too little to divide by. */
    fun rate(minutes: Int, drop: Int): Float =
        if (minutes <= 0) -1f else drop.toFloat() * WINDOW_MINUTES / minutes

    /**
     * [days] with [minutes] and [drop] added to [epochDay], pruned to [RETENTION_DAYS].
     *
     * Kept by day rather than by window on purpose: fifteen days of half-hours is 720 rows to
     * hold, prune and re-serialise on a phone, and answers no question the fifteen totals cannot.
     */
    fun plusNormal(days: List<Day>, epochDay: Long, minutes: Int, drop: Int): List<Day> {
        val merged = days.filter { it.epochDay != epochDay } +
            (days.firstOrNull { it.epochDay == epochDay } ?: Day(epochDay)).let {
                it.copy(minutes = it.minutes + minutes, drop = it.drop + drop)
            }
        val oldest = epochDay - RETENTION_DAYS + 1
        // Bounded at both ends: a day in the FUTURE is a phone whose clock is wrong (this app
        // already knows that happens), and an unbounded future day never ages out.
        return merged.filter { it.epochDay in oldest..epochDay }.sortedBy { it.epochDay }
    }

    /** [sessions] with [session] added, keeping the newest [KEEP_SESSIONS]. */
    fun plusSession(sessions: List<Session>, session: Session): List<Session> =
        (sessions + session).sortedBy { it.startedAtMs }.takeLast(KEEP_SESSIONS)

    /** The two rates and the last session, from what has been measured so far. */
    fun summarize(days: List<Day>, sessions: List<Session>): Summary {
        val normalMinutes = days.sumOf { it.minutes }
        val normalDrop = days.sumOf { it.drop }
        val liveMinutes = sessions.sumOf { it.minutes }
        val liveDrop = sessions.sumOf { it.drop }
        val last = sessions.maxByOrNull { it.startedAtMs }
        return Summary(
            normalPct = if (normalMinutes >= MIN_NORMAL_MINUTES) rate(normalMinutes, normalDrop) else -1f,
            normalMinutes = normalMinutes,
            livePct = if (liveMinutes >= MIN_LIVE_MINUTES) rate(liveMinutes, liveDrop) else -1f,
            liveSessions = sessions.size,
            lastDrop = last?.drop ?: 0,
            lastMinutes = last?.minutes ?: 0,
        )
    }
}
