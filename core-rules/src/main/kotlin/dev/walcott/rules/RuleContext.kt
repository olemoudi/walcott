package dev.walcott.rules

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Where a child stands in the rules right now — including, deliberately, the rules that are NOT
 * running.
 *
 * Everything this app shows a parent is about something happening: an app being shut, a window
 * biting, a limit spent. So the ordinary state of a phone — the hours when nothing is stopping
 * anybody — was the one state nothing described, and it is the state a parent is most often
 * looking at. "Is it bedtime yet?", "does the weekend already count?", "is today a special day?"
 * could each only be answered by opening the editor, reading the rule and doing the arithmetic
 * against the clock, which is exactly the work this screen exists to have already done.
 *
 * The negative is not the absence of information; it is information: "no bedtime until 21:30"
 * says both that the phone is free and when that stops being true.
 */
data class RuleContext(
    /** Which day the rules think it is — not the calendar's weekday (see [SchoolCalendar.dayTypeOf]). */
    val dayType: DayType,
    /** Whether today's own date is one the family marked special. */
    val specialDay: Boolean,
    /** When the day type changes next, and into what. Null when nothing changes within the week. */
    val nextDayType: DayTypeChange? = null,
    val bedtime: WindowStatus = WindowStatus.None(configuredToday = false),
    val screenFree: WindowStatus = WindowStatus.None(configuredToday = false),
)

/** The next moment the rules start treating the day as a different kind of day. */
data class DayTypeChange(val at: LocalDateTime, val to: DayType)

/** One kind of device-wide window, as it stands at a given instant. */
sealed interface WindowStatus {

    /** Running now, and letting go at [until]. */
    data class Running(val until: LocalTime) : WindowStatus

    /** Not running; the next one today runs [from]–[to]. */
    data class Later(val from: LocalTime, val to: LocalTime) : WindowStatus

    /**
     * Nothing more today. [configuredToday] separates "this day has none of these rules at all"
     * from "they have all been and gone", which are different answers to "is one coming?".
     */
    data class None(val configuredToday: Boolean) : WindowStatus
}

/**
 * The whole picture at [now]: the day type, whether the date is special, when the day type
 * changes next, and the state of the two device-wide windows.
 */
fun RuleEngine.ruleContext(config: FamilyConfig, now: LocalDateTime): RuleContext {
    val dayType = config.calendar.dayTypeOf(now)
    val date = now.toLocalDate()
    val specialDay = date in config.calendar.holidays || config.calendar.vacations.any { date in it }
    return RuleContext(
        dayType = dayType,
        specialDay = specialDay,
        nextDayType = nextDayTypeChange(config.calendar, now),
        // Tonight's bedtime, not the rule's: a night the parent moved or lifted has to read as
        // moved or lifted here too, since this card is where they check that it took.
        bedtime = statusOf(listOfNotNull(config.bedtimeAt(now)), now, specialDay, dayFiltered = false),
        screenFree = statusOf(config.blockedWindows[dayType].orEmpty(), now, specialDay, dayFiltered = true),
    )
}

/**
 * The next instant at which [calendar] starts calling the day something else.
 *
 * Walked rather than reasoned about: the boundaries are midnights and the two weekend edges, so
 * the honest way to find the next one is to ask the calendar itself at each of them. A week of
 * candidates is enough for every rule this calendar can express, and a family whose every day is
 * a holiday for longer than that correctly gets "nothing changes".
 */
private fun nextDayTypeChange(calendar: SchoolCalendar, now: LocalDateTime): DayTypeChange? {
    val current = calendar.dayTypeOf(now)
    val candidates = sortedSetOf<LocalDateTime>()
    for (offset in 0L..8L) {
        val date = now.toLocalDate().plusDays(offset)
        candidates += date.atStartOfDay()
        if (date.dayOfWeek == DayOfWeek.FRIDAY) calendar.weekendStartsFriday?.let { candidates += date.atTime(it) }
        if (date.dayOfWeek == DayOfWeek.SUNDAY) calendar.weekendEndsSunday?.let { candidates += date.atTime(it) }
    }
    val at = candidates.firstOrNull { it.isAfter(now) && calendar.dayTypeOf(it) != current } ?: return null
    return DayTypeChange(at, calendar.dayTypeOf(at))
}

/**
 * How a set of windows stands at [now]: one running, else the next one due today, else nothing.
 *
 * [dayFiltered] is false for bedtime, which is chosen by day TYPE and carries no weekday filter
 * of its own — asking a bedtime whether it applies today would be asking the wrong question.
 */
private fun statusOf(
    windows: List<TimeWindow>,
    now: LocalDateTime,
    specialDay: Boolean,
    dayFiltered: Boolean,
): WindowStatus {
    val time = now.toLocalTime()
    fun appliesToday(window: TimeWindow): Boolean =
        !dayFiltered || window.appliesAt(now.toLocalDate().atTime(window.start), specialDay)

    val today = windows.filter { appliesToday(it) }
    today.firstOrNull { if (dayFiltered) it.appliesAt(now, specialDay) else time in it }
        ?.let { return WindowStatus.Running(it.end) }
    // The next one to open today. A window whose start has passed has either run (and its own
    // end is behind us) or wraps past midnight, in which case it is tomorrow's business.
    val next = today.filter { it.start > time }.minByOrNull { it.start }
    return if (next != null) WindowStatus.Later(next.start, next.end) else WindowStatus.None(today.isNotEmpty())
}
