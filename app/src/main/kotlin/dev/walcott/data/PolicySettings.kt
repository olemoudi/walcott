package dev.walcott.data

import dev.walcott.rules.CategoryPolicy
import dev.walcott.rules.DayType
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.SchoolCalendar
import dev.walcott.rules.TimeWindow
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Persistable time window: minutes since midnight. */
@Serializable
data class WindowDto(val startMinute: Int, val endMinute: Int) {
    fun toTimeWindow() = TimeWindow(
        LocalTime.ofSecondOfDay(startMinute * 60L),
        LocalTime.ofSecondOfDay(endMinute * 60L),
    )
}

/**
 * Parent-editable configuration, serialized as JSON in DataStore. Holds everything that is
 * NOT app assignments (those live in Room because there are many and they are reactive).
 * Day-type keys are [DayType] names; budgets are minutes.
 */
@Serializable
data class PolicySettings(
    val version: Long = 1,
    /** categoryId -> (dayType -> budget minutes). */
    val budgets: Map<String, Map<String, Int>> = emptyMap(),
    /** categoryId -> (dayType -> full-block windows). */
    val blockedWindows: Map<String, Map<String, List<WindowDto>>> = emptyMap(),
    /** dayType -> bedtime window. */
    val bedtime: Map<String, WindowDto> = emptyMap(),
    /** One-off holidays (epochDay). */
    val holidays: Set<Long> = emptySet(),
    val pinHash: String? = null,
    val pinSalt: String? = null,
) {
    /** Builds the engine's [FamilyConfig] by combining these rules with the assignments. */
    fun toFamilyConfig(assignments: Map<String, String>, essentials: Set<String>): FamilyConfig {
        val categoryIds = budgets.keys + blockedWindows.keys + assignments.values
        val policies = categoryIds.associateWith { categoryId ->
            CategoryPolicy(
                dailyBudget = budgets[categoryId].orEmpty()
                    .mapKeys { DayType.valueOf(it.key) }
                    .mapValues { Duration.ofMinutes(it.value.toLong()) },
                blockedWindows = blockedWindows[categoryId].orEmpty()
                    .mapKeys { DayType.valueOf(it.key) }
                    .mapValues { entry -> entry.value.map { it.toTimeWindow() } },
            )
        }
        return FamilyConfig(
            version = version,
            assignments = assignments,
            policies = policies,
            bedtime = bedtime.mapKeys { DayType.valueOf(it.key) }.mapValues { it.value.toTimeWindow() },
            essentialPackages = essentials,
            calendar = SchoolCalendar(holidays = holidays.map(LocalDate::ofEpochDay).toSet()),
        )
    }
}
