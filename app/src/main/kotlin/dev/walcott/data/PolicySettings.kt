package dev.walcott.data

import dev.walcott.rules.CategoryPolicy
import dev.walcott.rules.DayType
import dev.walcott.rules.DomainAppRule
import dev.walcott.rules.EarnRule
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

/** Persistable earn-time rule (see [EarnRule]). */
@Serializable
data class EarnRuleDto(
    val sourceCategoryId: String,
    val targetCategoryId: String,
    val sourceMinutesPerReward: Int,
    val rewardMinutes: Int,
    val dailyCapMinutes: Int,
) {
    fun toEarnRule() = EarnRule(sourceCategoryId, targetCategoryId, sourceMinutesPerReward, rewardMinutes, dailyCapMinutes)
}

/** Persistable vacation range (inclusive), as epoch days. */
@Serializable
data class VacationDto(val startEpochDay: Long, val endEpochDay: Long)

/** Persistable per-app domain rule (see [DomainAppRule]). */
@Serializable
data class DomainAppRuleDto(
    val domain: String,
    val packageName: String,
    val allowOnlyFromApp: Boolean,
) {
    fun toDomainAppRule() = DomainAppRule(domain, packageName, allowOnlyFromApp)
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
    /** Vacation ranges (inclusive). */
    val vacations: List<VacationDto> = emptyList(),
    /** Earn-time rules ("X min of A unlocks Y min of B"). */
    val earnRules: List<EarnRuleDto> = emptyList(),
    /** Domains blocked at DNS level (suffix match). */
    val blockedDomains: Set<String> = emptySet(),
    /** Advanced per-app domain rules. */
    val domainAppRules: List<DomainAppRuleDto> = emptyList(),
    val pinHash: String? = null,
    val pinSalt: String? = null,
) {
    fun toEarnRules(): List<EarnRule> = earnRules.map { it.toEarnRule() }

    fun toDomainAppRules(): List<DomainAppRule> = domainAppRules.map { it.toDomainAppRule() }

    /** True when any DNS filtering is configured (drives whether the VPN runs). */
    fun hasWebFilter(): Boolean = blockedDomains.isNotEmpty() || domainAppRules.isNotEmpty()

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
            calendar = SchoolCalendar(
                holidays = holidays.map(LocalDate::ofEpochDay).toSet(),
                vacations = vacations.map { LocalDate.ofEpochDay(it.startEpochDay)..LocalDate.ofEpochDay(it.endEpochDay) },
            ),
        )
    }
}
