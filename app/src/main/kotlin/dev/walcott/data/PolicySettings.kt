package dev.walcott.data

import dev.walcott.rules.AppPolicy
import dev.walcott.rules.DayType
import dev.walcott.rules.DomainAppRule
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

/**
 * Pre-0.35 earn-time rule ("X min of category A unlocks Y min of category B"). Kept only so an
 * old stored policy still decodes; [migratedFromCategories] drops them, and nothing reads one.
 */
@Serializable
data class EarnRuleDto(
    val sourceCategoryId: String = "",
    val targetCategoryId: String = "",
    val sourceMinutesPerReward: Int = 0,
    val rewardMinutes: Int = 0,
    val dailyCapMinutes: Int = 0,
)

/** Persistable vacation range (inclusive), as epoch days. */
@Serializable
data class VacationDto(val startEpochDay: Long, val endEpochDay: Long)

/**
 * Idle-earn configuration (see [dev.walcott.rules.IdleEarnConfig]): banking idle time into
 * extra minutes for every app, with a rolling-window and a weekly cap, earning only inside
 * [earnWindows] (dayType name -> windows; empty = all day). Null = feature off.
 */
@Serializable
data class IdleEarnDto(
    /** Pre-0.35: which category the minutes went to. Ignored — they now go to every app. */
    val targetCategoryId: String = "",
    val minutesIdlePerReward: Int,
    val rewardMinutes: Int,
    val windowHours: Int,
    val windowCapMinutes: Int,
    val weeklyCapMinutes: Int,
    val earnWindows: Map<String, List<WindowDto>> = emptyMap(),
) {
    fun toConfig() = IdleEarnConfig(
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
 * What was set for one app: its own daily budget, its own blocked windows, or an explicit
 * exemption from the family default. Day-type keys are [DayType] names; budgets are minutes.
 */
@Serializable
data class AppPolicyDto(
    val budgets: Map<String, Int> = emptyMap(),
    val blockedWindows: Map<String, List<WindowDto>> = emptyMap(),
    /**
     * This app answers to no daily limit, not even the family default. Distinct from an empty
     * [budgets] map, which means "nothing set here, use the family default" — the two have to
     * stay distinguishable or a parent could only cap everything or nothing.
     */
    val unlimited: Boolean = false,
) {
    val isEmpty: Boolean get() = budgets.isEmpty() && blockedWindows.isEmpty() && !unlimited
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
 * A day-typed budget map with [dayTypeName] set to [minutes]; null clears that day. Shared by
 * the family editor and the per-child override editor.
 */
fun Map<String, Int>.withBudget(dayTypeName: String, minutes: Int?): Map<String, Int> =
    if (minutes == null) this - dayTypeName else this + (dayTypeName to minutes)

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
        defaultAppBudget = defaultAppBudget.mirrorHoliday(mirror),
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
                    defaultAppBudget = child.overrides.defaultAppBudget?.mirrorHoliday(mirror),
                    bedtime = child.overrides.bedtime?.mirrorHoliday(mirror),
                    appPolicies = child.overrides.appPolicies
                        ?.mapValues { (_, dto) ->
                            dto.copy(
                                budgets = dto.budgets.mirrorHoliday(mirror),
                                blockedWindows = dto.blockedWindows.mirrorHoliday(mirror),
                            )
                        }
                        ?.filterValues { !it.isEmpty },
                    allAppsBlockedWindows = child.overrides.allAppsBlockedWindows?.mirrorHoliday(mirror),
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

/** The category every unassigned app used to fall into, before categories were removed. */
private const val LEGACY_GENERAL_CATEGORY = "other"

/**
 * A policy written when limits were per category, expressed in the per-app model that replaced
 * them. Idempotent, and a no-op for anything written since — it keys off the legacy fields being
 * non-empty, and it empties them.
 *
 * The conversion, chosen with the parent in the room rather than for tidiness:
 *  - the General budget — the one every unclassified app already shared — becomes the default
 *    every app gets when nothing was set for it;
 *  - every other category's budget and windows become the rules of each app that was IN it, so
 *    a family that capped "games" keeps a cap on each of their games rather than losing it;
 *  - earn rules (category to category) are dropped: they cannot be said any more.
 *
 * This deliberately LOOSENS one thing, and it is worth saying out loud: four games that shared
 * 45 minutes end up with 45 minutes each. The alternative — dividing the budget between them —
 * would invent a rule the parent never wrote. Splitting it would also be silently stricter for a
 * family that only ever used one app in a category, which is the common case.
 *
 * Runs at the store's read path, so every screen and the engine see the new shape from the first
 * launch after the update, whether or not anything has been written since.
 */
fun PolicySettings.migratedFromCategories(): PolicySettings {
    val familyLegacy = budgets.isNotEmpty() || blockedWindows.isNotEmpty() ||
        assignments.isNotEmpty() || earnRules.isNotEmpty()
    val childLegacy = children.any {
        it.overrides.budgets != null || it.overrides.blockedWindows != null || it.overrides.earnRules != null
    }
    if (!familyLegacy && !childLegacy) return this

    fun convert(
        legacyBudgets: Map<String, Map<String, Int>>,
        legacyWindows: Map<String, Map<String, List<WindowDto>>>,
        currentDefault: Map<String, Int>,
        currentApps: Map<String, AppPolicyDto>,
    ): Pair<Map<String, Int>, Map<String, AppPolicyDto>> {
        // Anything already written in the new model wins: the migration only fills gaps, so
        // running it twice (or after the parent has edited something) can never overwrite.
        val default = currentDefault.ifEmpty { legacyBudgets[LEGACY_GENERAL_CATEGORY].orEmpty() }
        val apps = currentApps.toMutableMap()
        for ((pkg, categoryId) in assignments) {
            if (categoryId == LEGACY_GENERAL_CATEGORY) continue
            val budget = legacyBudgets[categoryId].orEmpty()
            val windows = legacyWindows[categoryId].orEmpty()
            if (budget.isEmpty() && windows.isEmpty()) continue
            val existing = apps[pkg] ?: AppPolicyDto()
            apps[pkg] = existing.copy(
                budgets = existing.budgets.ifEmpty { budget },
                blockedWindows = existing.blockedWindows.ifEmpty { windows },
            )
        }
        return default to apps.filterValues { !it.isEmpty }
    }

    val (familyDefault, familyApps) = convert(budgets, blockedWindows, defaultAppBudget, appPolicies)
    return copy(
        defaultAppBudget = familyDefault,
        appPolicies = familyApps,
        budgets = emptyMap(),
        blockedWindows = emptyMap(),
        assignments = emptyMap(),
        earnRules = emptyList(),
        children = children.map { child ->
            val overrides = child.overrides
            if (overrides.budgets == null && overrides.blockedWindows == null && overrides.earnRules == null) {
                child
            } else {
                // A child who overrode the family budgets keeps overriding, in the new shape:
                // their General budget is their default, their category caps become their apps.
                val (childDefault, childApps) = convert(
                    overrides.budgets.orEmpty(),
                    overrides.blockedWindows.orEmpty(),
                    overrides.defaultAppBudget.orEmpty(),
                    overrides.appPolicies.orEmpty(),
                )
                child.copy(
                    overrides = overrides.copy(
                        defaultAppBudget = overrides.defaultAppBudget
                            ?: childDefault.takeIf { overrides.budgets != null },
                        appPolicies = overrides.appPolicies ?: childApps.takeIf { overrides.budgets != null },
                        budgets = null,
                        blockedWindows = null,
                        earnRules = null,
                    ),
                )
            }
        },
    )
}

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
    /** This child's own default per-app budget (dayType -> minutes); empty map = no default. */
    val defaultAppBudget: Map<String, Int>? = null,
    /** Pre-0.35 per-child category budgets. Migration input only (see [migratedFromCategories]). */
    val budgets: Map<String, Map<String, Int>>? = null,
    /** Pre-0.35 per-child category windows. Migration input only. */
    val blockedWindows: Map<String, Map<String, List<WindowDto>>>? = null,
    /** Pre-0.35 per-child earn rules. Dropped by the migration. */
    val earnRules: List<EarnRuleDto>? = null,
    val bedtime: Map<String, WindowDto>? = null,
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
    /** Per-app limits (package -> policy) for this child alone. Null inherits the family map. */
    val appPolicies: Map<String, AppPolicyDto>? = null,
    /**
     * Family-wide screen-free windows (dayType -> windows) for this child alone — empty map =
     * none at all, the laxer-sibling case. Null inherits the family's.
     */
    val allAppsBlockedWindows: Map<String, List<WindowDto>>? = null,
) {
    val isEmpty: Boolean
        get() = defaultAppBudget == null && bedtime == null &&
            blockedDomains == null && domainAppRules == null &&
            deviceRestrictions == null &&
            trackingIntervalMinutes == null && locationHistoryEnabled == null &&
            updateWifiOnly == null && appPolicies == null && allAppsBlockedWindows == null
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
    /**
     * The daily budget (dayType -> minutes) an app gets when nothing was set for it. Empty —
     * the default — means an app nobody has touched has no limit at all, so a newly installed
     * app never arrives already restricted. Each app spends it on its own counter.
     */
    val defaultAppBudget: Map<String, Int> = emptyMap(),
    /**
     * Pre-0.35 category budgets (categoryId -> dayType -> minutes). Read once by
     * [migratedFromCategories] and blanked; nothing else looks at them. Kept only so a policy
     * written before categories were removed can still be converted — delete once no install
     * that old can reach this code.
     */
    val budgets: Map<String, Map<String, Int>> = emptyMap(),
    /** Pre-0.35 category blocked windows. Migration input only, like [budgets]. */
    val blockedWindows: Map<String, Map<String, List<WindowDto>>> = emptyMap(),
    /** dayType -> bedtime window. */
    val bedtime: Map<String, WindowDto> = emptyMap(),
    /** dayType -> family-wide screen-free windows (block ALL apps, like bedtime). */
    val allAppsBlockedWindows: Map<String, List<WindowDto>> = emptyMap(),
    /** One-off special days that apply to every child (epochDay). */
    val holidays: Set<Long> = emptySet(),
    /** Periods (inclusive) that apply to every child. */
    val vacations: List<VacationDto> = emptyList(),
    /**
     * Special days for one child only — a birthday is theirs, not the family's. childId -> days.
     *
     * A separate map rather than a scope field on [holidays], because that set is `Set<Long>` on
     * the wire: changing its shape would make every not-yet-updated child fail to parse the policy
     * and quietly stop adopting rules altogether. Additive, so an old child simply sees the
     * family-wide days — fewer special days, which is the strict direction, never the lax one.
     */
    val childHolidays: Map<String, Set<Long>> = emptyMap(),
    /** Periods for one child only (a camp, a trip). childId -> ranges. Same reasoning as above. */
    val childVacations: Map<String, List<VacationDto>> = emptyMap(),
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
    /**
     * Pre-0.35 earn-time rules ("X min of category A unlocks Y min of category B"). They had no
     * meaning left once categories went, so the migration drops them; the field stays only to
     * decode an old policy without losing everything beside it.
     */
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
    /** Pre-0.35 app -> categoryId assignments. Migration input only, like [budgets]. */
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
    /** package -> what was set for that app (budget, windows, or exempt). Family-wide. */
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
            defaultAppBudget = overrides.defaultAppBudget ?: defaultAppBudget,
            bedtime = overrides.bedtime ?: bedtime,
            blockedDomains = overrides.blockedDomains ?: blockedDomains,
            domainAppRules = overrides.domainAppRules ?: domainAppRules,
            deviceRestrictions = overrides.deviceRestrictions ?: deviceRestrictions,
            trackingIntervalMinutes = overrides.trackingIntervalMinutes ?: trackingIntervalMinutes,
            locationHistoryEnabled = overrides.locationHistoryEnabled ?: locationHistoryEnabled,
            updateWifiOnly = overrides.updateWifiOnly ?: updateWifiOnly,
            appPolicies = overrides.appPolicies ?: appPolicies,
            allAppsBlockedWindows = overrides.allAppsBlockedWindows ?: allAppsBlockedWindows,
            // This child's own special days on top of the family's; the others' never travel here.
            holidays = holidays + childHolidays[childId].orEmpty(),
            vacations = vacations + childVacations[childId].orEmpty(),
        )
    }

    /** Every special day this policy knows about, family-wide and per child, for the parent's UI. */
    fun allHolidays(): Set<Long> = holidays + childHolidays.values.flatten()

    /** Same for periods. */
    fun allVacations(): List<VacationDto> = vacations + childVacations.values.flatten()

    /** Which children [day] applies to; empty = the whole family. */
    fun holidayScope(day: Long): Set<String> =
        if (day in holidays) emptySet() else childHolidays.filterValues { day in it }.keys

    /** Which children [period] applies to; empty = the whole family. */
    fun vacationScope(period: VacationDto): Set<String> =
        if (period in vacations) emptySet() else childVacations.filterValues { period in it }.keys

    /**
     * [day] scoped to [childIds] — empty meaning the whole family. Written as "remove it everywhere,
     * then add it where it belongs" so a day can be moved between scopes without ever existing in
     * two places at once.
     */
    fun withHolidayScope(day: Long, childIds: Set<String>): PolicySettings {
        val cleared = copy(
            holidays = holidays - day,
            childHolidays = childHolidays.mapValues { it.value - day }.filterValues { it.isNotEmpty() },
        )
        if (childIds.isEmpty()) return cleared.copy(holidays = cleared.holidays + day)
        return cleared.copy(
            childHolidays = childIds.fold(cleared.childHolidays) { acc, id ->
                acc + (id to acc[id].orEmpty() + day)
            },
        )
    }

    /** Same for a period. */
    fun withVacationScope(period: VacationDto, childIds: Set<String>): PolicySettings {
        val cleared = copy(
            vacations = vacations - period,
            childVacations = childVacations.mapValues { it.value - period }.filterValues { it.isNotEmpty() },
        )
        if (childIds.isEmpty()) return cleared.copy(vacations = cleared.vacations + period)
        return cleared.copy(
            childVacations = childIds.fold(cleared.childVacations) { acc, id ->
                acc + (id to acc[id].orEmpty() + period)
            },
        )
    }

    /** [day] gone, whoever it belonged to. */
    fun withoutHoliday(day: Long): PolicySettings = copy(
        holidays = holidays - day,
        childHolidays = childHolidays.mapValues { it.value - day }.filterValues { it.isNotEmpty() },
    )

    /** [period] gone, whoever it belonged to. */
    fun withoutVacation(period: VacationDto): PolicySettings = copy(
        vacations = vacations - period,
        childVacations = childVacations.mapValues { it.value - period }.filterValues { it.isNotEmpty() },
    )

    fun toDomainAppRules(): List<DomainAppRule> = domainAppRules.map { it.toDomainAppRule() }

    /** True when any DNS filtering is configured (drives whether the VPN runs). */
    fun hasWebFilter(): Boolean = blockedDomains.isNotEmpty() || domainAppRules.isNotEmpty()

    /** Builds the engine's [FamilyConfig] from these rules. */
    fun toFamilyConfig(essentials: Set<String>): FamilyConfig {
        val perApp = appPolicies
            .mapValues { (_, dto) ->
                AppPolicy(
                    dailyBudget = dto.budgets
                        .byDayType()
                        .mapValues { Duration.ofMinutes(it.value.toLong()) },
                    blockedWindows = dto.blockedWindows
                        .byDayType()
                        .mapValues { entry -> entry.value.mapNotNull { it.toTimeWindowOrNull() } },
                    unlimited = dto.unlimited,
                )
            }
        return FamilyConfig(
            version = version,
            defaultAppBudget = defaultAppBudget.byDayType().mapValues { Duration.ofMinutes(it.value.toLong()) },
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
