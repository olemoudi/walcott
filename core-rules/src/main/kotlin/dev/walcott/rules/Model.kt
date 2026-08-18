package dev.walcott.rules

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class DayType { SCHOOL, WEEKEND, HOLIDAY }

/**
 * Keys for the extra-time map. Extra time is granted to a single app (its package name) or to
 * every app at once ([ExtraTime.ALL_APPS], a sentinel that is not a package name).
 */
object ExtraTime {
    const val ALL_APPS = "__all_apps__"
}

/** Parent-editable holidays, vacations and weekend edges; decides the day type. */
data class SchoolCalendar(
    val holidays: Set<LocalDate> = emptySet(),
    val vacations: List<ClosedRange<LocalDate>> = emptyList(),
    /**
     * Friday time from which the weekend rules already apply (school is out). Null — the
     * default — keeps the weekend starting at Saturday 00:00.
     */
    val weekendStartsFriday: LocalTime? = null,
    /**
     * Sunday time from which the weekday rules apply again (school night). Null — the
     * default — keeps the weekend running to Monday 00:00.
     */
    val weekendEndsSunday: LocalTime? = null,
) {
    /**
     * The day type in force at [now]. Deliberately NOT a function of the date alone: with a
     * weekend edge set, one date carries two day types (Friday is SCHOOL in the morning and
     * WEEKEND after the cut). Special days still win over both edges — a holiday is a holiday
     * all day long.
     *
     * The usage counter behind budgets is per calendar day, so it does NOT restart at the cut:
     * time already spent on Friday counts against the weekend budget the child moves into.
     */
    fun dayTypeOf(now: LocalDateTime): DayType {
        val date = now.toLocalDate()
        if (date in holidays || vacations.any { date in it }) return DayType.HOLIDAY
        val time = now.toLocalTime()
        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.WEEKEND
            DayOfWeek.SUNDAY ->
                if (weekendEndsSunday != null && time >= weekendEndsSunday) DayType.SCHOOL else DayType.WEEKEND
            DayOfWeek.FRIDAY ->
                if (weekendStartsFriday != null && time >= weekendStartsFriday) DayType.WEEKEND else DayType.SCHOOL
            else -> DayType.SCHOOL
        }
    }
}

/** Time window [start, end); may cross midnight (e.g. 21:30–07:30). */
/**
 * What a time window does when the calendar calls the day special (a holiday, a vacation day).
 *
 * The day-of-week picker on a rule and the calendar's special days are different questions —
 * "which weekdays" and "except when the day is not a normal one" — so they are answered
 * separately, on the same rule, instead of by filing the rule into one of several lists.
 */
enum class SpecialDays {
    /** The weekday filter is the whole answer; a holiday changes nothing. */
    ALWAYS,

    /** Stands down on special days: "no screens Mon–Fri 17:00–19:00 for homework", not on a
     *  bank-holiday Tuesday. */
    NEVER,

    /** Applies ONLY on special days — the rule a family wants for holidays and no other day. */
    ONLY,
    ;

    /** Whether a window carrying this applies on a day the calendar calls [special]. */
    fun appliesOn(special: Boolean): Boolean = when (this) {
        ALWAYS -> true
        NEVER -> !special
        ONLY -> special
    }
}

data class TimeWindow(
    val start: LocalTime,
    val end: LocalTime,
    /**
     * Days of the week this window applies on; empty — the default, and what every window
     * written before this field existed means — is every day.
     */
    val days: Set<DayOfWeek> = emptySet(),
    /**
     * How this window treats the calendar's special days (holidays, vacations).
     *
     * Three states rather than the boolean it replaced, because the editor used to express the
     * third by putting a whole separate LIST of windows under a "special days" section — one
     * more axis on a screen that already had two. [SpecialDays.ONLY] says the same thing on the
     * axis the parent is already using, so the section could go.
     */
    val specialDays: SpecialDays = SpecialDays.ALWAYS,
) {
    operator fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end
        else time >= start || time < end

    /**
     * Whether this window is in force at [at]: the time range AND the day filters. Callers that
     * only care about the clock (bedtime, earn windows) keep using [contains].
     *
     * [specialDay] is whether [at]'s own date is a holiday or vacation. For the post-midnight
     * tail of a window that crossed over, that is the date of the morning being blocked — the
     * day the parent is thinking about when they say "not on holidays".
     */
    fun appliesAt(at: LocalDateTime, specialDay: Boolean = false): Boolean {
        if (at.toLocalTime() !in this) return false
        if (!specialDays.appliesOn(specialDay)) return false
        if (days.isEmpty()) return true
        // A window that crosses midnight belongs to the day it STARTED on: at 01:00 inside a
        // 21:30–07:30 window, the day the parent picked is yesterday's.
        val startedYesterday = start > end && at.toLocalTime() < end
        return (if (startedYesterday) at.toLocalDate().minusDays(1).dayOfWeek else at.dayOfWeek) in days
    }
}

/**
 * The date the bedtime window covering [now] starts on — "which night is this".
 *
 * A bedtime normally crosses midnight, so 23:00 on Friday and 06:00 on Saturday are the same
 * night and a one-off change to it has to reach both halves. Anything else (including a window
 * that does not cross) belongs to the day it is read on.
 */
fun TimeWindow.nightOf(now: LocalDateTime): LocalDate =
    if (start > end && now.toLocalTime() < end) now.toLocalDate().minusDays(1) else now.toLocalDate()

/** How long this window lasts, wrapping past midnight when it crosses. */
fun TimeWindow.lengthMinutes(): Long {
    val from = start.toSecondOfDay() / 60L
    val to = end.toSecondOfDay() / 60L
    return if (to >= from) to - from else 24 * 60L - from + to
}

/**
 * A one-off change to today, on top of the rules — the thing a family needs constantly and the
 * rules cannot express, because a rule is a statement about every day of its kind.
 *
 * Two shapes, and both exist because the alternative was editing a standing rule and remembering
 * to put it back: **a pause** ("dinner is ready", "put it down and come here") that closes the
 * phone until a moment, and **tonight's bedtime** moved back or lifted, for the birthday, the
 * film, the night the grandparents are staying.
 *
 * Everything here dies on its own — a pause when its moment passes, a bedtime change when its
 * night does — so nothing has to be undone by hand and no exception can be forgotten into a
 * permanent rule. Essential apps are untouched by both: a paused phone still calls its parents.
 *
 * A pause is measured against the device's own clock, which a child could move. That is
 * deliberately not defended here: any family with a rule of any kind already fails closed on a
 * clock the sync server disagrees with (see [RuleEngine.requiresTrustedClock]), and a family with
 * no rules at all is not one where minutes are being fought over.
 */
data class TodayException(
    /** Everything non-essential is closed until this moment; null = no pause running. */
    val pauseUntil: LocalDateTime? = null,
    /**
     * The night the bedtime change below applies to (see [nightOf]); null = tonight's bedtime is
     * whatever the rules say. Dated rather than a flag so an exception cannot outlive its night:
     * a phone that was off all evening reads yesterday's exception as spent, not as tonight's.
     */
    val bedtimeNight: LocalDate? = null,
    /** Minutes tonight's bedtime starts later than usual. */
    val bedtimeDelayMinutes: Int = 0,
    /** No bedtime at all on [bedtimeNight]. */
    val bedtimeOff: Boolean = false,
) {
    /** Whether the phone is closed by a pause at [now]. */
    fun pausedAt(now: LocalDateTime): Boolean = pauseUntil != null && now.isBefore(pauseUntil)

    /** Nothing set at all — the ordinary state of a day. */
    val isEmpty: Boolean get() = pauseUntil == null && bedtimeNight == null
}

/**
 * What one app is allowed, whether it was set for that app or inherited from the family's
 * default. Every limit in this engine is now per app: sorting apps into categories asked the
 * parent to do a filing job before they could set a single rule, and the rules they actually
 * want ("Roblox, 45 minutes") never needed it.
 */
data class AppPolicy(
    /** Daily budget per day type; no entry = no time limit that day. */
    val dailyBudget: Map<DayType, Duration> = emptyMap(),
    /** Full-block windows per day type (e.g. school hours). */
    val blockedWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
    /**
     * This app answers to no daily budget, not even the family default. The third state a
     * per-app entry needs: "nothing set" inherits the default, a budget overrides it, and this
     * opts out of it — the app the parent never wants to cut off (a bus timetable, a chat with
     * a parent) without having to turn the default off for everybody.
     */
    val unlimited: Boolean = false,
)

data class FamilyConfig(
    /** Monotonic version of the writer; sync uses last-write-wins on it. */
    val version: Long,
    /**
     * The daily budget an app gets when nothing was set for it, per day type. Empty — the
     * default — means an app nobody has touched has no time limit at all, which is the whole
     * point: a newly installed app must not silently arrive already restricted.
     *
     * Each app counts against this budget SEPARATELY: it is a per-app allowance, not a shared
     * pot, so an hour of one app does not eat another app's hour.
     */
    val defaultAppBudget: Map<DayType, Duration> = emptyMap(),
    /**
     * package -> the rules set for that app specifically. A budget here replaces
     * [defaultAppBudget] for that app (tighter or looser); [AppPolicy.unlimited] removes it;
     * blocked windows are added on top of the family-wide ones.
     */
    val perAppPolicies: Map<String, AppPolicy> = emptyMap(),
    /** Bedtime window per day type: blocks everything non-essential. */
    val bedtime: Map<DayType, TimeWindow> = emptyMap(),
    /**
     * Family-wide full-block windows per day type (homework, meals…): like bedtime they
     * block every non-essential app, but there can be any number of them per day.
     */
    val blockedWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
    /** Never blocked: phone, contacts, the app itself… */
    val essentialPackages: Set<String> = emptySet(),
    val calendar: SchoolCalendar = SchoolCalendar(),
    /** Today's one-off change to the two rules above, if the parent made one. */
    val todayException: TodayException = TodayException(),
) {
    /**
     * Tonight's bedtime at [now]: the configured window, after whatever [todayException] says
     * about this night. Null when there is no bedtime — configured or left.
     *
     * Every reader of a bedtime goes through here, for the reason [allowanceFor] exists: the
     * engine, the child's screen and the parent's "what is stopping them" list all draw the same
     * window, and a one-off change that reached only one of them would be a screen disagreeing
     * with the phone it describes.
     */
    /**
     * Tonight's bedtime as the RULES have it, before any exception — which is how you ask "which
     * night is this?" of a night whose bedtime has just been lifted.
     *
     * The distinction is not academic. [bedtimeAt] answers null for a lifted night, and a caller
     * deriving the night from that answer falls back to today's date — so at half past midnight
     * it looks at the night that has not started yet, decides the exception it is holding belongs
     * to some other night, and offers the parent no way to put back what they just lifted.
     */
    fun scheduledBedtimeAt(now: LocalDateTime): TimeWindow? = bedtime[calendar.dayTypeOf(now)]

    fun bedtimeAt(now: LocalDateTime): TimeWindow? {
        val window = scheduledBedtimeAt(now) ?: return null
        if (todayException.bedtimeNight != window.nightOf(now)) return window
        if (todayException.bedtimeOff) return null
        val delay = todayException.bedtimeDelayMinutes
        if (delay <= 0) return window
        // A delay longer than the night itself leaves no bedtime at all, rather than a window
        // that has crawled past its own end and blocks the whole of the next day.
        if (delay >= window.lengthMinutes()) return null
        return window.copy(start = window.start.plusMinutes(delay.toLong()))
    }

    /**
     * The budget [packageName] answers to on [dayType], or null when it has none: its own if it
     * was given one, otherwise the family default — unless it was explicitly set free.
     *
     * An essential app (the phone, Walcott itself) never has one. Stated here and not only in
     * [RuleEngine.evaluate] so that everything reading a budget agrees: a child's screen must
     * not draw "Phone · 20 min left" over an app that will keep working regardless.
     */
    fun budgetFor(packageName: String, dayType: DayType): Duration? {
        if (packageName in essentialPackages) return null
        val own = perAppPolicies[packageName]
        if (own?.unlimited == true) return null
        return own?.dailyBudget?.get(dayType) ?: defaultAppBudget[dayType]
    }

    /** Whether [packageName] is running on the family default rather than a budget of its own. */
    fun usesDefaultBudget(packageName: String): Boolean {
        val own = perAppPolicies[packageName] ?: return true
        return !own.unlimited && own.dailyBudget.isEmpty()
    }

    /**
     * Everything [packageName] may spend today: its budget plus whatever extra time reaches it.
     * Null when it has no budget at all, which is not the same as zero.
     *
     * The widening rule lives here so that every reader of an allowance agrees on it — a grant
     * to this app always counts, an "all apps" grant only reaches apps running on the family
     * default (see [RuleEngine.evaluate]). It was written out twice before, once in the engine
     * and once in the screen's [appStatus], which is exactly how a screen comes to disagree with
     * the phone it is describing.
     */
    fun allowanceFor(
        packageName: String,
        dayType: DayType,
        extraTime: Map<String, Duration> = emptyMap(),
    ): Duration? {
        val budget = budgetFor(packageName, dayType) ?: return null
        val own = extraTime[packageName] ?: Duration.ZERO
        val shared =
            if (usesDefaultBudget(packageName)) extraTime[ExtraTime.ALL_APPS] ?: Duration.ZERO
            else Duration.ZERO
        return budget + own + shared
    }
}

sealed interface Verdict {
    /** Allowed with no applicable time limit right now. */
    data object Allowed : Verdict

    /** Allowed; this app has this much time left today. */
    data class AllowedWithBudget(val remaining: Duration) : Verdict

    data class Blocked(val reason: BlockReason) : Verdict
}

enum class BlockReason {
    BEDTIME,
    BLOCKED_WINDOW,
    BUDGET_EXHAUSTED,

    /**
     * A parent paused this phone until a moment they picked (see [TodayException]).
     *
     * Its own reason rather than a screen-free window, because the child is owed the true
     * sentence: a window is a standing rule they can learn, and this is a person, just now,
     * asking for the phone to be put down. It also ends by itself, at a time worth printing.
     */
    PAUSED,

    /**
     * Blocked because the device can't be trusted to apply the rules right now — the usage
     * counter is unavailable, or the clock is provably wrong. See
     * [RuleEngine.blockedPackages]'s fail-closed branches.
     */
    FAIL_CLOSED,
}
