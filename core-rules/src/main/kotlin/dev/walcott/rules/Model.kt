package dev.walcott.rules

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class DayType { SCHOOL, WEEKEND, HOLIDAY }

/**
 * Keys for the extra-time map. Extra time can be granted three ways, and the engine sums
 * whichever apply to a package: to one category (its id), to a single app (its package name,
 * which always contains a dot), or to every app at once ([ExtraTime.ALL_APPS], a sentinel that
 * is neither a category id nor a package). Categories are the optional power tool; "all apps"
 * and "this app" are the simple, everyday grants.
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
data class TimeWindow(
    val start: LocalTime,
    val end: LocalTime,
    /**
     * Days of the week this window applies on; empty — the default, and what every window
     * written before this field existed means — is every day.
     */
    val days: Set<DayOfWeek> = emptySet(),
    /**
     * When true the window stands down on calendar special days, so "no screens Mon–Fri
     * 17:00–19:00 for homework" doesn't fire on a bank-holiday Tuesday.
     */
    val skipSpecialDays: Boolean = false,
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
        if (skipSpecialDays && specialDay) return false
        if (days.isEmpty()) return true
        // A window that crosses midnight belongs to the day it STARTED on: at 01:00 inside a
        // 21:30–07:30 window, the day the parent picked is yesterday's.
        val startedYesterday = start > end && at.toLocalTime() < end
        return (if (startedYesterday) at.toLocalDate().minusDays(1).dayOfWeek else at.dayOfWeek) in days
    }
}

data class CategoryPolicy(
    /** Daily budget per day type; no entry = no time limit that day. */
    val dailyBudget: Map<DayType, Duration> = emptyMap(),
    /** Full-block windows per day type (e.g. school hours). */
    val blockedWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
)

data class FamilyConfig(
    /** Monotonic version of the writer; sync uses last-write-wins on it. */
    val version: Long,
    /** package -> categoryId. Packages not listed fall into [DEFAULT_CATEGORY]. */
    val assignments: Map<String, String>,
    /** categoryId -> policy. A category without a policy is unrestricted. */
    val policies: Map<String, CategoryPolicy>,
    /**
     * package -> per-app policy that ADDS restrictions on top of the app's category. A per-app
     * daily budget is a sub-cap (the app is blocked when it OR its category runs out); per-app
     * blocked windows are unioned with the category's. So per-app rules only ever tighten.
     */
    val perAppPolicies: Map<String, CategoryPolicy> = emptyMap(),
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
) {
    /** The category [packageName] is judged under: its assignment, or the default bucket. */
    fun categoryOf(packageName: String): String = assignments[packageName] ?: DEFAULT_CATEGORY

    companion object {
        /**
         * The category every unassigned app falls into ("General" in the UI). New installs are
         * usable under its budget rather than blocked outright — classifying is opt-in, and
         * blocking an app is an explicit act (its own budget of zero), never a default.
         */
        const val DEFAULT_CATEGORY = "other"
    }
}

sealed interface Verdict {
    /** Allowed with no applicable time limit right now. */
    data object Allowed : Verdict

    /** Allowed; its category has this much budget left today. */
    data class AllowedWithBudget(val remaining: Duration) : Verdict

    data class Blocked(val reason: BlockReason) : Verdict
}

enum class BlockReason {
    UNCLASSIFIED,
    BEDTIME,
    BLOCKED_WINDOW,
    BUDGET_EXHAUSTED,

    /**
     * Blocked because the device can't be trusted to apply the rules right now — the usage
     * counter is unavailable, or the clock is provably wrong. See
     * [RuleEngine.blockedPackages]'s fail-closed branches.
     */
    FAIL_CLOSED,
}
