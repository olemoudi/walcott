package dev.walcott.data

import dev.walcott.AppCategory
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.rules.DayType

/**
 * The guided-setup wizards' policy transforms, kept pure (PolicySettings in, PolicySettings
 * out) so the wizard UI stays thin and this logic is unit-testable on the JVM. The wizard
 * teaches; this object writes.
 */
object SetupPresets {

    /**
     * Categories a daily screen-time cap applies to. Education and creative apps are the
     * deliberate exceptions, and system apps (phone, contacts…) are never managed at all,
     * so calls keep working under any cap.
     */
    val LEISURE_CATEGORY_IDS: List<String> = listOf(
        AppCategory.GAMES.id, AppCategory.SOCIAL.id, AppCategory.VIDEO.id, AppCategory.OTHER.id,
    )

    /** The wizard's bedtime starting point: 21:30–07:30, every day type. */
    fun defaultBedtime(): Map<String, WindowDto> =
        DayType.entries.associate { it.name to WindowDto(21 * 60 + 30, 7 * 60 + 30) }

    /** Sets [minutes] as the daily budget of every leisure category for all day types (null clears). */
    fun withLeisureBudget(settings: PolicySettings, minutes: Int?): PolicySettings {
        var budgets = settings.budgets
        for (categoryId in LEISURE_CATEGORY_IDS) {
            for (day in DayType.entries) budgets = budgets.withBudget(categoryId, day.name, minutes)
        }
        return settings.copy(budgets = budgets)
    }

    /**
     * Turns on the recommended anti-tamper set and applies the parent's install-block choice.
     * Restrictions outside this preset's scope are preserved either way.
     */
    fun withProtection(settings: PolicySettings, blockInstalls: Boolean): PolicySettings {
        val base = settings.deviceRestrictions + DeviceRestrictions.RECOMMENDED_DEFAULTS
        return settings.copy(
            deviceRestrictions = if (blockInstalls) {
                base + DeviceRestrictions.KEY_INSTALLS
            } else {
                base - DeviceRestrictions.KEY_INSTALLS
            },
        )
    }

    /** Balanced earned-time starter: 10 min off the phone earn 5 min of games, capped. */
    fun defaultIdleEarn() = IdleEarnDto(
        targetCategoryId = AppCategory.GAMES.id,
        minutesIdlePerReward = 10,
        rewardMinutes = 5,
        windowHours = 4,
        windowCapMinutes = 20,
        weeklyCapMinutes = 120,
    )
}
