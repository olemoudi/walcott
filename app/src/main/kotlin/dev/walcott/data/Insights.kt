package dev.walcott.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * One thing worth telling the child about their own screen time.
 *
 * Data, not sentences: the phrasing lives in resources (several per kind, so the same fact does
 * not arrive in the same words twice), and the numbers are decided here where they can be tested.
 * Nothing here advises anybody — the awareness is meant to come from the comparison itself, which
 * is the difference between a line a teenager reads and a line a teenager learns to skip.
 */
sealed interface Insight {

    /** The app that took the most of the last seven days. */
    data class TopAppWeek(val packageName: String, val time: Duration) : Insight

    /**
     * A month in one app, measured in something you can picture. "Eleven hours" is a number;
     * "as long as you sleep in a week" is a fact about your life.
     */
    data class MonthYardstick(
        val packageName: String,
        val time: Duration,
        val unit: Yardstick,
        val count: Int,
    ) : Insight

    /** This week against the one before it, stated and not judged. */
    data class WeekDelta(val difference: Duration, val down: Boolean) : Insight

    /** The longest day of the last seven. */
    data class BusiestDay(val day: DayOfWeek, val time: Duration) : Insight

    /** How much of the week went to a single app. */
    data class OneAppShare(val packageName: String, val percent: Int) : Insight
}

/** Something a stretch of screen time can be measured against. */
enum class Yardstick(val minutes: Int) {
    NIGHT_OF_SLEEP(8 * 60),
    FILM(110),
    FOOTBALL_MATCH(90),
    ALBUM(40),
}

/**
 * Picks the one thing to say today.
 *
 * Deterministic by day: the line changes each morning and then stays put, because a message that
 * reshuffles every time the screen is opened is a slot machine, not a fact. Only insights whose
 * data actually qualifies are offered, so nothing is ever padded out with something dull —
 * an empty answer is better than "you used your phone today".
 */
object Insights {

    /** Below this a day's usage is noise (the phone unlocking, a notification tapped). */
    val FLOOR: Duration = Duration.ofMinutes(20)

    /**
     * How much of a week one app has to take before saying so is news. Half is not it: with two
     * apps on the phone, "50% went to one app" is what having two apps looks like.
     */
    private const val DOMINANT_SHARE = 60

    /** A yardstick only reads well when the count is small enough to picture. */
    private const val MIN_COUNT = 2
    private const val MAX_COUNT = 15

    /**
     * @param today usage per package for today
     * @param week usage per package per day for the last seven days, today included
     * @param month the same for the last thirty
     * @param previousWeek the seven days before [week], for the comparison
     * @param rotation changes once a day (day of the year); which qualifying insight is shown
     */
    fun forToday(
        today: Map<String, Duration>,
        week: Map<Long, Map<String, Duration>>,
        month: Map<Long, Map<String, Duration>>,
        previousWeek: Map<Long, Map<String, Duration>>,
        rotation: Int,
    ): Insight? {
        val candidates = candidates(today, week, month, previousWeek)
        if (candidates.isEmpty()) return null
        val index = ((rotation % candidates.size) + candidates.size) % candidates.size
        return candidates[index]
    }

    /** Everything true and worth saying right now, in a stable order. */
    fun candidates(
        today: Map<String, Duration>,
        week: Map<Long, Map<String, Duration>>,
        month: Map<Long, Map<String, Duration>>,
        previousWeek: Map<Long, Map<String, Duration>>,
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val weekByApp = totals(week)
        val weekTotal = weekByApp.values.fold(Duration.ZERO, Duration::plus)
        val topWeek = weekByApp.maxByOrNull { it.value }

        if (topWeek != null && topWeek.value >= FLOOR) {
            out += Insight.TopAppWeek(topWeek.key, topWeek.value)
            // Only when one app really dominates: "31% went to one app" is arithmetic, not news.
            val share = (topWeek.value.seconds * 100 / weekTotal.seconds.coerceAtLeast(1)).toInt()
            if (share >= DOMINANT_SHARE) out += Insight.OneAppShare(topWeek.key, share)
        }

        val topMonth = totals(month).maxByOrNull { it.value }
        if (topMonth != null && topMonth.value >= FLOOR) {
            yardstickFor(topMonth.value)?.let { (unit, count) ->
                out += Insight.MonthYardstick(topMonth.key, topMonth.value, unit, count)
            }
        }

        val previousTotal = totals(previousWeek).values.fold(Duration.ZERO, Duration::plus)
        // Both weeks have to be real, or the "difference" is just the ledger starting.
        if (previousTotal >= FLOOR && weekTotal >= FLOOR) {
            val difference = weekTotal - previousTotal
            if (difference.abs() >= Duration.ofMinutes(30)) {
                out += Insight.WeekDelta(difference.abs(), down = difference.isNegative)
            }
        }

        val busiest = week.mapValues { (_, byApp) -> byApp.values.fold(Duration.ZERO, Duration::plus) }
            .maxByOrNull { it.value }
        if (busiest != null && busiest.value >= FLOOR) {
            out += Insight.BusiestDay(LocalDate.ofEpochDay(busiest.key).dayOfWeek, busiest.value)
        }
        return out
    }

    /** Sums a day-keyed usage map per package. */
    private fun totals(byDay: Map<Long, Map<String, Duration>>): Map<String, Duration> {
        val totals = mutableMapOf<String, Duration>()
        byDay.values.forEach { byApp ->
            byApp.forEach { (pkg, time) -> totals[pkg] = (totals[pkg] ?: Duration.ZERO) + time }
        }
        return totals
    }

    /**
     * The largest unit that [time] is worth between [MIN_COUNT] and [MAX_COUNT] of, or null when
     * nothing fits — "1 film" says less than the hours did, and "40 albums" is a number again.
     */
    fun yardstickFor(time: Duration): Pair<Yardstick, Int>? {
        val minutes = time.toMinutes()
        return Yardstick.entries
            .sortedByDescending { it.minutes }
            .firstNotNullOfOrNull { unit ->
                val count = (minutes / unit.minutes).toInt()
                (unit to count).takeIf { count in MIN_COUNT..MAX_COUNT }
            }
    }
}
