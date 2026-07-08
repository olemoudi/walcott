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
 * Budgets map with [categoryId]/[dayTypeName] set to [minutes]. Null minutes clears the
 * entry; categories whose per-day map empties out are dropped. Shared by the family
 * editor and the per-child override editor.
 */
fun Map<String, Map<String, Int>>.withBudget(
    categoryId: String,
    dayTypeName: String,
    minutes: Int?,
): Map<String, Map<String, Int>> {
    val perDay = this[categoryId].orEmpty().toMutableMap()
    if (minutes == null) perDay.remove(dayTypeName) else perDay[dayTypeName] = minutes
    val budgets = toMutableMap()
    if (perDay.isEmpty()) budgets.remove(categoryId) else budgets[categoryId] = perDay
    return budgets
}

/**
 * Per-child policy overrides. A null field inherits the family value; a non-null field
 * replaces it wholesale (no deep merge, so "no limit for this child" is expressible).
 */
@Serializable
data class ChildOverrides(
    val budgets: Map<String, Map<String, Int>>? = null,
    val blockedWindows: Map<String, Map<String, List<WindowDto>>>? = null,
    val bedtime: Map<String, WindowDto>? = null,
    val earnRules: List<EarnRuleDto>? = null,
    val blockedDomains: Set<String>? = null,
    val deviceRestrictions: Set<String>? = null,
    /** Periodic location-tracking interval in minutes (0 = off). Null inherits the family value. */
    val trackingIntervalMinutes: Int? = null,
) {
    val isEmpty: Boolean
        get() = budgets == null && blockedWindows == null && bedtime == null &&
            earnRules == null && blockedDomains == null && deviceRestrictions == null &&
            trackingIntervalMinutes == null
}

/** A child the parent registered; the per-child enrollment QR enrolls a device as this child. */
@Serializable
data class ChildEntry(
    val childId: String,
    val name: String,
    val overrides: ChildOverrides = ChildOverrides(),
)

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
    /** Enabled device-protection features (keys from DeviceRestrictions; Device Owner only). */
    val deviceRestrictions: Set<String> = emptySet(),
    /** Family display name, shown on parent and enrolled child devices. */
    val familyName: String = "",
    /** Children registered by the parent, each with optional per-child overrides. */
    val children: List<ChildEntry> = emptyList(),
    /**
     * App -> categoryId assignments, family-wide. Part of the policy so they sync to children
     * (an app with no entry is blocked as "unclassified"). Was previously in Room.
     */
    val assignments: Map<String, String> = emptyMap(),
    /** Family-default periodic location-tracking interval in minutes (0 = off). */
    val trackingIntervalMinutes: Int = 0,
) {
    /**
     * Family policy with [childId]'s overrides applied (null override field = inherit).
     * Blank/unknown ids return the family policy unchanged, so legacy children degrade cleanly.
     */
    fun resolveForChild(childId: String?): PolicySettings {
        val overrides = children.firstOrNull { it.childId == childId }?.overrides ?: return this
        return copy(
            budgets = overrides.budgets ?: budgets,
            blockedWindows = overrides.blockedWindows ?: blockedWindows,
            bedtime = overrides.bedtime ?: bedtime,
            earnRules = overrides.earnRules ?: earnRules,
            blockedDomains = overrides.blockedDomains ?: blockedDomains,
            deviceRestrictions = overrides.deviceRestrictions ?: deviceRestrictions,
            trackingIntervalMinutes = overrides.trackingIntervalMinutes ?: trackingIntervalMinutes,
        )
    }

    /** One-time migration: adopt [legacy] Room assignments only if none are set yet. */
    fun withLegacyAssignments(legacy: Map<String, String>): PolicySettings =
        if (assignments.isEmpty() && legacy.isNotEmpty()) copy(assignments = legacy) else this

    fun toEarnRules(): List<EarnRule> = earnRules.map { it.toEarnRule() }

    fun toDomainAppRules(): List<DomainAppRule> = domainAppRules.map { it.toDomainAppRule() }

    /** True when any DNS filtering is configured (drives whether the VPN runs). */
    fun hasWebFilter(): Boolean = blockedDomains.isNotEmpty() || domainAppRules.isNotEmpty()

    /** Builds the engine's [FamilyConfig] from these rules and assignments. */
    fun toFamilyConfig(essentials: Set<String>): FamilyConfig {
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
