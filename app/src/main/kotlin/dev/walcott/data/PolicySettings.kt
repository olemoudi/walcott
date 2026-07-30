package dev.walcott.data

import dev.walcott.rules.CategoryPolicy
import dev.walcott.rules.DayType
import dev.walcott.rules.DomainAppRule
import dev.walcott.rules.EarnRule
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.IdleEarnConfig
import dev.walcott.rules.SchoolCalendar
import dev.walcott.rules.TimeWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Day-typed map with its string keys resolved to [DayType], SKIPPING any key this build
 * doesn't know. The rules arrive from the parent, which may be running a newer version: a
 * strict `DayType.valueOf` throws there, and it throws inside the enforcement loop — an old
 * child would crash-restart every few seconds, forever, enforcing nothing. Ignoring the
 * unknown slot degrades one rule instead of the whole device, and lets the wire format grow.
 */
internal fun <V> Map<String, V>.byDayType(): Map<DayType, V> =
    mapNotNull { (key, value) -> runCatching { DayType.valueOf(key) }.getOrNull()?.let { it to value } }.toMap()

/**
 * Minute-of-day to a [LocalTime], dropping anything out of range. Same reasoning as
 * [byDayType]: the rules arrive from another device, and a bad value here would throw inside
 * the enforcement loop rather than at a parse boundary.
 */
internal fun Int?.toTimeOfDayOrNull(): LocalTime? =
    this?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.ofSecondOfDay(it * 60L) }

/** Persistable time window: minutes since midnight. */
@Serializable
data class WindowDto(
    val startMinute: Int,
    val endMinute: Int,
    /**
     * ISO day numbers (1 = Monday … 7 = Sunday) this window applies on; empty = every day.
     * Empty is also what a child running a build older than this field will see, so an old
     * device over-blocks (the window fires every day) rather than silently stopping.
     */
    val days: List<Int> = emptyList(),
    /** Whether the window stands down on calendar special days (see [TimeWindow.skipSpecialDays]). */
    val skipSpecialDays: Boolean = false,
) {
    /**
     * This window as the engine wants it, or null when either end isn't a minute of any day.
     * Nullable on purpose: this conversion happens inside the enforcement loop, where an
     * exception is not a broken rule but a device that crash-restarts every few seconds with
     * its apps frozen and no way to say why. A malformed window is dropped instead — the same
     * bargain [byDayType] strikes for a day-type key it doesn't know.
     */
    fun toTimeWindowOrNull(): TimeWindow? {
        val start = startMinute.toTimeOfDayOrNull() ?: return null
        val end = endMinute.toTimeOfDayOrNull() ?: return null
        return TimeWindow(
            start,
            end,
            // Unparseable day numbers are dropped rather than thrown — this runs inside the
            // enforcement loop, same reasoning as [byDayType].
            days = days.mapNotNullTo(mutableSetOf()) { runCatching { DayOfWeek.of(it) }.getOrNull() },
            skipSpecialDays = skipSpecialDays,
        )
    }
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

/**
 * Idle-earn configuration (see [dev.walcott.rules.IdleEarnConfig]): banking idle time into
 * extra minutes for [targetCategoryId], with a rolling-window and a weekly cap, earning only
 * inside [earnWindows] (dayType name -> windows; empty = all day). Null = feature off.
 */
@Serializable
data class IdleEarnDto(
    val targetCategoryId: String,
    val minutesIdlePerReward: Int,
    val rewardMinutes: Int,
    val windowHours: Int,
    val windowCapMinutes: Int,
    val weeklyCapMinutes: Int,
    val earnWindows: Map<String, List<WindowDto>> = emptyMap(),
) {
    fun toConfig() = IdleEarnConfig(
        targetCategoryId = targetCategoryId,
        minutesIdlePerReward = minutesIdlePerReward,
        rewardMinutes = rewardMinutes,
        windowHours = windowHours,
        windowCapMinutes = windowCapMinutes,
        weeklyCapMinutes = weeklyCapMinutes,
        earnWindows = earnWindows.byDayType()
            .mapValues { entry -> entry.value.mapNotNull { it.toTimeWindowOrNull() } },
    )
}

/**
 * Per-app policy overrides (budget + blocked windows) that ADD restrictions on top of the
 * app's category. Day-type keys are [DayType] names; budgets are minutes.
 */
@Serializable
data class AppPolicyDto(
    val budgets: Map<String, Int> = emptyMap(),
    val blockedWindows: Map<String, List<WindowDto>> = emptyMap(),
) {
    val isEmpty: Boolean get() = budgets.isEmpty() && blockedWindows.isEmpty()
}

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

/** This day-typed map with the HOLIDAY slot mirroring WEEKEND (copied, or removed when absent). */
private fun <V> Map<String, V>.mirrorHoliday(mirror: Boolean = true): Map<String, V> {
    if (!mirror) return this
    val weekend = this[DayType.WEEKEND.name]
    return if (weekend == null) this - DayType.HOLIDAY.name else this + (DayType.HOLIDAY.name to weekend)
}

/**
 * Collapses the school/holiday distinction the UI no longer offers: every day-typed map gets
 * its HOLIDAY slot rewritten to mirror WEEKEND. The HOLIDAY key itself must keep travelling —
 * already-deployed children resolve calendar special days to it (and their `toFamilyConfig`
 * throws on unknown day-type keys), so the wire format is frozen; only the meaning changes:
 * a special day now simply behaves like a weekend. Applied on every parent policy write.
 *
 * [PolicySettings.specialDaysOwnRules] lifts the mirror off **every** time-based rule at once,
 * not just budgets. One switch for the whole dimension is what keeps the editors honest: a
 * family that has claimed special days finds the extra row in the same place on every screen,
 * and one that hasn't sees it greyed out everywhere rather than present here and absent there.
 */
fun PolicySettings.withHolidayMirroringWeekend(): PolicySettings {
    val mirror = !specialDaysOwnRules
    return copy(
        budgets = budgets.mapValues { it.value.mirrorHoliday(mirror) }.filterValues { it.isNotEmpty() },
        blockedWindows = blockedWindows.mapValues { it.value.mirrorHoliday(mirror) }.filterValues { it.isNotEmpty() },
        bedtime = bedtime.mirrorHoliday(mirror),
        allAppsBlockedWindows = allAppsBlockedWindows.mirrorHoliday(mirror),
        appPolicies = appPolicies
            .mapValues { (_, dto) ->
                dto.copy(
                    budgets = dto.budgets.mirrorHoliday(mirror),
                    blockedWindows = dto.blockedWindows.mirrorHoliday(mirror),
                )
            }
            .filterValues { !it.isEmpty },
        idleEarn = idleEarn?.let { it.copy(earnWindows = it.earnWindows.mirrorHoliday(mirror)) },
        children = children.map { child ->
            child.copy(
                overrides = child.overrides.copy(
                    budgets = child.overrides.budgets
                        ?.mapValues { it.value.mirrorHoliday(mirror) }?.filterValues { it.isNotEmpty() },
                    blockedWindows = child.overrides.blockedWindows
                        ?.mapValues { it.value.mirrorHoliday(mirror) }?.filterValues { it.isNotEmpty() },
                    bedtime = child.overrides.bedtime?.mirrorHoliday(mirror),
                ),
            )
        },
    )
}

/**
 * Turns the special-day row on or off across every time-based rule.
 *
 * Switching ON seeds every HOLIDAY slot — budgets, bedtime, blocked windows, earn windows — from
 * the WEEKEND value it was mirroring, so the rules are byte-identical at the instant the parent
 * takes control and nothing changes until they move a number. Without that, dropping the mirror
 * would leave special days with no budget and no bedtime at all, which reads as unlimited: the one
 * direction a parental control must never move by accident. Switching OFF only clears the flag —
 * the mirror pass that runs on every write re-collapses the row by itself.
 */
fun PolicySettings.withSpecialDaysOwnRules(on: Boolean): PolicySettings =
    if (on) copy(specialDaysOwnRules = false).withHolidayMirroringWeekend().copy(specialDaysOwnRules = true)
    else copy(specialDaysOwnRules = false)

/**
 * The domains in [domains] added to the family's web filter.
 *
 * [scopeToApp] null blocks them for every app (the global blocklist); a package keeps them to the
 * app that resolved them, which is the precise answer the domain monitor makes possible.
 */
fun PolicySettings.withFamilyDomainRules(domains: List<String>, scopeToApp: String?): PolicySettings =
    if (scopeToApp == null) copy(blockedDomains = blockedDomains + domains)
    else copy(domainAppRules = domainAppRules.plusAppRules(domains, scopeToApp))

/**
 * The same rules for one child only.
 *
 * A per-child override replaces the family value wholesale, so it has to be seeded from what that
 * child currently lives under: starting from an empty set would turn "also block this for Ana"
 * into "block only this for Ana", quietly dropping every rule she inherited.
 *
 * An unknown [childId] (a legacy device with no registry entry, which can hold no overrides) falls
 * back to the family scope. It is the wider of the two, and for a parental control a block that
 * reaches too far is recoverable in a way that a button doing nothing at all is not.
 */
fun PolicySettings.withChildDomainRules(childId: String, domains: List<String>, scopeToApp: String?): PolicySettings {
    if (children.none { it.childId == childId }) return withFamilyDomainRules(domains, scopeToApp)
    return copy(
        children = children.map { child ->
            if (child.childId != childId) {
                child
            } else {
                child.copy(
                    overrides = if (scopeToApp == null) {
                        child.overrides.copy(
                            blockedDomains = (child.overrides.blockedDomains ?: blockedDomains) + domains,
                        )
                    } else {
                        child.overrides.copy(
                            domainAppRules = (child.overrides.domainAppRules ?: domainAppRules)
                                .plusAppRules(domains, scopeToApp),
                        )
                    },
                )
            }
        },
    )
}

/** These rules plus one per domain not already covered for [packageName] (adding twice is a no-op). */
private fun List<DomainAppRuleDto>.plusAppRules(domains: List<String>, packageName: String): List<DomainAppRuleDto> {
    val covered = filter { it.packageName == packageName }.map { it.domain }.toSet()
    return this + domains.filterNot { it in covered }
        .map { DomainAppRuleDto(domain = it, packageName = packageName, allowOnlyFromApp = false) }
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
    /** Per-app domain rules for this child alone. Null inherits the family list. */
    val domainAppRules: List<DomainAppRuleDto>? = null,
    val deviceRestrictions: Set<String>? = null,
    /** Periodic location-tracking interval in minutes (0 = off). Null inherits the family value. */
    val trackingIntervalMinutes: Int? = null,
    /** Whether this child reports a 48h trail rather than just its current position. */
    val locationHistoryEnabled: Boolean? = null,
    /** Restrict this child's self-update to Wi-Fi. Null inherits the family value. */
    val updateWifiOnly: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = budgets == null && blockedWindows == null && bedtime == null &&
            earnRules == null && blockedDomains == null && domainAppRules == null &&
            deviceRestrictions == null &&
            trackingIntervalMinutes == null && locationHistoryEnabled == null &&
            updateWifiOnly == null
}

/** A child the parent registered; the per-child enrollment QR enrolls a device as this child. */
@Serializable
data class ChildEntry(
    val childId: String,
    val name: String,
    val overrides: ChildOverrides = ChildOverrides(),
    /** When this child was registered (epoch ms); 0 for legacy entries. Used to alert on a child that never checked in. */
    val addedAtMs: Long = 0,
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
    /** dayType -> family-wide screen-free windows (block ALL apps, like bedtime). */
    val allAppsBlockedWindows: Map<String, List<WindowDto>> = emptyMap(),
    /** One-off holidays (epochDay). */
    val holidays: Set<Long> = emptySet(),
    /** Vacation ranges (inclusive). */
    val vacations: List<VacationDto> = emptyList(),
    /**
     * Whether the calendar's special days get their own time rules instead of following the
     * weekend's — budgets, bedtime, screen-free windows, per-app windows and earn windows alike.
     * Off — the default, and what every install before this field did — keeps every HOLIDAY slot
     * a mirror of WEEKEND (see [withHolidayMirroringWeekend]).
     *
     * Kept on the wire under its original name: it shipped governing budgets only, and renaming
     * the field would have every not-yet-updated device silently drop the family's opt-in and
     * collapse special days back onto the weekend. The meaning widened; the key cannot.
     */
    @SerialName("specialDaysOwnBudget")
    val specialDaysOwnRules: Boolean = false,
    /**
     * Friday minute-of-day from which the weekend rules already apply (e.g. 840 = 14:00).
     * Null — the default — starts the weekend at Saturday 00:00, which is what every install
     * predating this field does: children on an older build simply ignore the key.
     */
    val weekendStartsFridayAtMinute: Int? = null,
    /** Sunday minute-of-day from which the weekday rules return. Null runs the weekend to Monday 00:00. */
    val weekendEndsSundayAtMinute: Int? = null,
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
    /**
     * Family default for keeping a 48h location trail. Off means a child reports only its
     * current position, so history is something the parent opts into per family or per child.
     */
    val locationHistoryEnabled: Boolean = false,
    /** True once recommended anti-tamper defaults were seeded (so we only seed once). */
    val hardeningSeeded: Boolean = false,
    /** Restrict the child's self-update to unmetered (Wi-Fi) connections. */
    val updateWifiOnly: Boolean = false,
    /**
     * Notify the parent when a child installs a new app. Most useful when installs aren't
     * blocked (with the install block on, a new app can't appear without the parent's tap
     * anyway). Defaults on so a family that never opens the setting still gets warned.
     */
    val newAppAlerts: Boolean = true,
    /** package -> per-app policy (budget + windows) that tightens its category. Family-wide. */
    val appPolicies: Map<String, AppPolicyDto> = emptyMap(),
    /** Idle-earn config (token-window model). Null = children earn no extra time from idle. */
    val idleEarn: IdleEarnDto? = null,
) {
    /**
     * One-time seeding of recommended anti-tamper [defaults] into [deviceRestrictions]. Idempotent
     * and respects a parent later removing any of them (only runs while [hardeningSeeded] is false).
     */
    fun seedRestrictions(defaults: Set<String>): PolicySettings =
        if (hardeningSeeded) this
        else copy(deviceRestrictions = deviceRestrictions + defaults, hardeningSeeded = true)
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
            domainAppRules = overrides.domainAppRules ?: domainAppRules,
            deviceRestrictions = overrides.deviceRestrictions ?: deviceRestrictions,
            trackingIntervalMinutes = overrides.trackingIntervalMinutes ?: trackingIntervalMinutes,
            locationHistoryEnabled = overrides.locationHistoryEnabled ?: locationHistoryEnabled,
            updateWifiOnly = overrides.updateWifiOnly ?: updateWifiOnly,
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
                    .byDayType()
                    .mapValues { Duration.ofMinutes(it.value.toLong()) },
                blockedWindows = blockedWindows[categoryId].orEmpty()
                    .byDayType()
                    .mapValues { entry -> entry.value.mapNotNull { it.toTimeWindowOrNull() } },
            )
        }
        val perApp = appPolicies
            .filterKeys { it in assignments } // ignore rules for apps no longer classified
            .mapValues { (_, dto) ->
                CategoryPolicy(
                    dailyBudget = dto.budgets
                        .byDayType()
                        .mapValues { Duration.ofMinutes(it.value.toLong()) },
                    blockedWindows = dto.blockedWindows
                        .byDayType()
                        .mapValues { entry -> entry.value.mapNotNull { it.toTimeWindowOrNull() } },
                )
            }
        return FamilyConfig(
            version = version,
            assignments = assignments,
            policies = policies,
            perAppPolicies = perApp,
            bedtime = bedtime.byDayType()
                .mapNotNull { (dayType, window) -> window.toTimeWindowOrNull()?.let { dayType to it } }
                .toMap(),
            blockedWindows = allAppsBlockedWindows
                .byDayType()
                .mapValues { entry -> entry.value.mapNotNull { it.toTimeWindowOrNull() } },
            essentialPackages = essentials,
            calendar = SchoolCalendar(
                holidays = holidays.map(LocalDate::ofEpochDay).toSet(),
                vacations = vacations.map { LocalDate.ofEpochDay(it.startEpochDay)..LocalDate.ofEpochDay(it.endEpochDay) },
                weekendStartsFriday = weekendStartsFridayAtMinute.toTimeOfDayOrNull(),
                weekendEndsSunday = weekendEndsSundayAtMinute.toTimeOfDayOrNull(),
            ),
        )
    }
}
