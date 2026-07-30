package dev.walcott.ui

import androidx.annotation.StringRes
import dev.walcott.R
import dev.walcott.rules.DayType

/**
 * Ordered day types shown in the parent UI. SCHOOL is presented as "weekdays"; HOLIDAY is the
 * calendar's special days.
 */
val DAY_TYPES: List<DayType> = listOf(DayType.SCHOOL, DayType.WEEKEND)

/**
 * The rows every time-based editor shows: weekdays, weekend, and special days.
 *
 * The special-day row is always present, in every editor. Whether it can be *edited* is the
 * family's single [dev.walcott.data.PolicySettings.specialDaysOwnRules] switch — when that is off
 * the row is disabled and displays the weekend values it mirrors, which is true rather than
 * absent. Hiding it was the older behaviour and it read as "special days aren't supported here",
 * differently on each screen depending on where the switch happened to live.
 */
val RULE_DAY_TYPES: List<DayType> = DAY_TYPES + DayType.HOLIDAY

/** Whether [dayType]'s row accepts edits, given the family's special-days switch. */
fun DayType.editableUnder(specialDaysOwnRules: Boolean): Boolean =
    this != DayType.HOLIDAY || specialDaysOwnRules

@StringRes
fun DayType.labelRes(): Int = when (this) {
    DayType.SCHOOL -> R.string.daytype_school
    DayType.WEEKEND -> R.string.daytype_weekend
    DayType.HOLIDAY -> R.string.daytype_holiday
}
