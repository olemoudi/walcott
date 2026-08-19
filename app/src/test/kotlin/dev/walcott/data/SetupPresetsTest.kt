package dev.walcott.data

import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.rules.DayType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupPresetsTest {

    @Test
    fun `default bedtime covers every day type with the same window`() {
        val bedtime = SetupPresets.defaultBedtime()
        assertEquals(DayType.entries.map { it.name }.toSet(), bedtime.keys)
        bedtime.values.forEach {
            assertEquals(21 * 60 + 30, it.startMinute)
            assertEquals(7 * 60 + 30, it.endMinute)
        }
    }

    @Test
    fun `the default budget applies to every day type at once`() {
        val out = SetupPresets.withDefaultBudget(PolicySettings(), 90)
        for (day in DayType.entries) assertEquals(90, out.defaultAppBudget[day.name])
    }

    @Test
    fun `a null default budget clears what the wizard previously set`() {
        val set = SetupPresets.withDefaultBudget(PolicySettings(), 60)
        assertTrue(SetupPresets.withDefaultBudget(set, null).defaultAppBudget.isEmpty())
    }

    @Test
    fun `the wizard's budget never touches a limit set on one app`() {
        // The two instruments are independent: the wizard sets the fallback, the parent sets
        // an app's own limit, and neither may quietly rewrite the other.
        val manual = PolicySettings(
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 45))),
        )
        val out = SetupPresets.withDefaultBudget(SetupPresets.withDefaultBudget(manual, 60), null)
        assertEquals(45, out.appPolicies["com.game"]?.budgets?.get(DayType.SCHOOL.name))
    }

    // --- The weekend question the wizard asks ---

    @Test
    fun `the weekday cap leaves the weekend alone and vice versa`() {
        val split = SetupPresets.withWeekendDefaultBudget(
            SetupPresets.withWeekdayDefaultBudget(PolicySettings(), 60),
            180,
        )
        assertEquals(60, split.defaultAppBudget[DayType.SCHOOL.name])
        assertEquals(180, split.defaultAppBudget[DayType.WEEKEND.name])
        // HOLIDAY is left to the parent-write mirror, not written here.
        assertEquals(null, split.defaultAppBudget[DayType.HOLIDAY.name])
    }

    @Test
    fun `a policy only counts as weekend-aware once a cap or an edge differs`() {
        assertFalse(SetupPresets.hasWeekendDistinction(PolicySettings()))
        assertFalse(SetupPresets.hasWeekendDistinction(SetupPresets.withDefaultBudget(PolicySettings(), 90)))
        assertTrue(
            SetupPresets.hasWeekendDistinction(
                SetupPresets.withWeekendDefaultBudget(SetupPresets.withDefaultBudget(PolicySettings(), 90), 180),
            ),
        )
        // An edge on its own counts, even with identical caps.
        assertTrue(SetupPresets.hasWeekendDistinction(PolicySettings(weekendStartsFridayAtMinute = 14 * 60)))
        assertTrue(SetupPresets.hasWeekendDistinction(PolicySettings(weekendEndsSundayAtMinute = 20 * 60)))
    }

    @Test
    fun `dropping the distinction copies the weekday cap over and resets both edges`() {
        val split = SetupPresets
            .withWeekendDefaultBudget(SetupPresets.withWeekdayDefaultBudget(PolicySettings(), 60), 180)
            .copy(weekendStartsFridayAtMinute = 14 * 60, weekendEndsSundayAtMinute = 20 * 60)

        val merged = SetupPresets.withoutWeekendDistinction(split)
        for (day in DayType.entries) assertEquals(60, merged.defaultAppBudget[day.name])
        assertEquals(null, merged.weekendStartsFridayAtMinute)
        assertEquals(null, merged.weekendEndsSundayAtMinute)
        assertFalse(SetupPresets.hasWeekendDistinction(merged))
    }

    @Test
    fun `dropping the distinction from an unlimited weekday means unlimited everywhere`() {
        val weekendOnly = SetupPresets.withWeekendDefaultBudget(PolicySettings(), 180)
        val merged = SetupPresets.withoutWeekendDistinction(weekendOnly)
        assertTrue(merged.defaultAppBudget.isEmpty())
    }

    @Test
    fun `the default idle-earn starter is internally consistent`() {
        val earn = SetupPresets.defaultIdleEarn()
        assertTrue(earn.minutesIdlePerReward > 0 && earn.rewardMinutes > 0)
        // The rolling-window cap must fit at least one whole reward block, or the wizard
        // would enable a feature that can never grant anything.
        assertTrue(earn.windowCapMinutes >= earn.rewardMinutes)
        assertTrue(earn.weeklyCapMinutes >= earn.windowCapMinutes)
    }

    @Test
    fun `protection preset adds the recommended set plus watching new apps`() {
        val out = SetupPresets.withProtection(PolicySettings(), watchInstalls = true)
        assertTrue(out.deviceRestrictions.containsAll(DeviceRestrictions.RECOMMENDED_DEFAULTS))
        assertTrue(DeviceRestrictions.KEY_INSTALLS in out.deviceRestrictions)
    }

    @Test
    fun `declining to watch new apps removes it but keeps everything else`() {
        val watched = SetupPresets.withProtection(
            PolicySettings(deviceRestrictions = setOf(DeviceRestrictions.KEY_BIOMETRICS)),
            watchInstalls = true,
        )
        val without = SetupPresets.withProtection(watched, watchInstalls = false)
        assertFalse(DeviceRestrictions.KEY_INSTALLS in without.deviceRestrictions)
        assertTrue(
            without.deviceRestrictions.containsAll(
                DeviceRestrictions.RECOMMENDED_DEFAULTS - DeviceRestrictions.KEY_INSTALLS,
            ),
            "declining ONE recommendation must not quietly withdraw the others",
        )
        // A restriction outside the preset's scope survives both passes.
        assertTrue(DeviceRestrictions.KEY_BIOMETRICS in without.deviceRestrictions)
    }
}
