package dev.walcott.ui

import androidx.annotation.StringRes
import dev.walcott.R
import dev.walcott.rules.DayType

/** Ordered day types shown in the parent UI. */
val DAY_TYPES: List<DayType> = listOf(DayType.SCHOOL, DayType.WEEKEND, DayType.HOLIDAY)

@StringRes
fun DayType.labelRes(): Int = when (this) {
    DayType.SCHOOL -> R.string.daytype_school
    DayType.WEEKEND -> R.string.daytype_weekend
    DayType.HOLIDAY -> R.string.daytype_holiday
}
