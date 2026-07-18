package dev.walcott.rules

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

enum class DayType { SCHOOL, WEEKEND, HOLIDAY }

/** Parent-editable holidays and vacations; decides the day type. */
data class SchoolCalendar(
    val holidays: Set<LocalDate> = emptySet(),
    val vacations: List<ClosedRange<LocalDate>> = emptyList(),
) {
    fun dayTypeOf(date: LocalDate): DayType = when {
        date in holidays || vacations.any { date in it } -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.WEEKEND
        else -> DayType.SCHOOL
    }
}

/** Time window [start, end); may cross midnight (e.g. 21:30–07:30). */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    operator fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end
        else time >= start || time < end
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
    /** package -> categoryId. Packages not listed are unclassified (blocked). */
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
    /** Never blocked: phone, contacts, the app itself… */
    val essentialPackages: Set<String> = emptySet(),
    val calendar: SchoolCalendar = SchoolCalendar(),
)

sealed interface Verdict {
    /** Allowed with no applicable time limit right now. */
    data object Allowed : Verdict

    /** Allowed; its category has this much budget left today. */
    data class AllowedWithBudget(val remaining: Duration) : Verdict

    data class Blocked(val reason: BlockReason) : Verdict
}

enum class BlockReason { UNCLASSIFIED, BEDTIME, BLOCKED_WINDOW, BUDGET_EXHAUSTED }
