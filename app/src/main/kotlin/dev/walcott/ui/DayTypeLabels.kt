package dev.walcott.ui

import androidx.annotation.StringRes
import dev.walcott.R
import dev.walcott.rules.DayType

/**
 * Ordered day types shown in the parent UI. Deliberately only two: SCHOOL is presented as
 * "weekdays" and HOLIDAY is hidden — the calendar's special days follow the weekend rules
 * (the policy writer mirrors WEEKEND into the HOLIDAY slot; see
 * [dev.walcott.data.withHolidayMirroringWeekend]), so there is no third policy to edit.
 */
val DAY_TYPES: List<DayType> = listOf(DayType.SCHOOL, DayType.WEEKEND)

/**
 * The rows a daily-limit editor shows. The special-day column appears only once the family has
 * claimed it ([dev.walcott.data.PolicySettings.specialDaysOwnBudget]); until then its budget is
 * a mirror of the weekend's and a third row would be a lie you could edit.
 */
fun budgetDayTypes(specialDaysOwnBudget: Boolean): List<DayType> =
    if (specialDaysOwnBudget) DAY_TYPES + DayType.HOLIDAY else DAY_TYPES

@StringRes
fun DayType.labelRes(): Int = when (this) {
    DayType.SCHOOL -> R.string.daytype_school
    DayType.WEEKEND -> R.string.daytype_weekend
    DayType.HOLIDAY -> R.string.daytype_holiday
}
