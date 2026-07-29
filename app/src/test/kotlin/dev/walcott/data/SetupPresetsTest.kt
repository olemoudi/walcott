package dev.walcott.data

import dev.walcott.AppCategory
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
    fun `leisure budget caps games social video and other for all day types`() {
        val out = SetupPresets.withLeisureBudget(PolicySettings(), 90)
        for (categoryId in SetupPresets.LEISURE_CATEGORY_IDS) {
            for (day in DayType.entries) {
                assertEquals(90, out.budgets[categoryId]?.get(day.name))
            }
        }
        // The exceptions stay open: no budget entry at all for education/creative.
        assertFalse(AppCategory.EDUCATION.id in out.budgets)
        assertFalse(AppCategory.CREATIVE.id in out.budgets)
    }

    @Test
    fun `a null leisure budget clears what the wizard previously set`() {
        val set = SetupPresets.withLeisureBudget(PolicySettings(), 60)
        val cleared = SetupPresets.withLeisureBudget(set, null)
        assertTrue(cleared.budgets.isEmpty())
    }

    @Test
    fun `clearing the leisure budget leaves manually configured categories alone`() {
        val manual = PolicySettings(budgets = mapOf(AppCategory.EDUCATION.id to mapOf(DayType.SCHOOL.name to 45)))
        val cleared = SetupPresets.withLeisureBudget(SetupPresets.withLeisureBudget(manual, 60), null)
        assertEquals(45, cleared.budgets[AppCategory.EDUCATION.id]?.get(DayType.SCHOOL.name))
    }

    // --- The weekend question the wizard asks ---

    @Test
    fun `the weekday cap leaves the weekend alone and vice versa`() {
        val split = SetupPresets.withWeekendLeisureBudget(
            SetupPresets.withWeekdayLeisureBudget(PolicySettings(), 60),
            180,
        )
        for (categoryId in SetupPresets.LEISURE_CATEGORY_IDS) {
            assertEquals(60, split.budgets[categoryId]?.get(DayType.SCHOOL.name))
            assertEquals(180, split.budgets[categoryId]?.get(DayType.WEEKEND.name))
        }
        // HOLIDAY is left to the parent-write mirror, not written here.
        assertEquals(null, split.budgets[AppCategory.GAMES.id]?.get(DayType.HOLIDAY.name))
    }

    @Test
    fun `a policy only counts as weekend-aware once a cap or an edge differs`() {
        assertFalse(SetupPresets.hasWeekendDistinction(PolicySettings()))
        assertFalse(SetupPresets.hasWeekendDistinction(SetupPresets.withLeisureBudget(PolicySettings(), 90)))
        assertTrue(
            SetupPresets.hasWeekendDistinction(
                SetupPresets.withWeekendLeisureBudget(SetupPresets.withLeisureBudget(PolicySettings(), 90), 180),
            ),
        )
        // An edge on its own counts, even with identical caps.
        assertTrue(SetupPresets.hasWeekendDistinction(PolicySettings(weekendStartsFridayAtMinute = 14 * 60)))
        assertTrue(SetupPresets.hasWeekendDistinction(PolicySettings(weekendEndsSundayAtMinute = 20 * 60)))
    }

    @Test
    fun `dropping the distinction copies the weekday cap over and resets both edges`() {
        val split = SetupPresets
            .withWeekendLeisureBudget(SetupPresets.withWeekdayLeisureBudget(PolicySettings(), 60), 180)
            .copy(weekendStartsFridayAtMinute = 14 * 60, weekendEndsSundayAtMinute = 20 * 60)

        val merged = SetupPresets.withoutWeekendDistinction(split)
        for (day in DayType.entries) {
            assertEquals(60, merged.budgets[AppCategory.GAMES.id]?.get(day.name))
        }
        assertEquals(null, merged.weekendStartsFridayAtMinute)
        assertEquals(null, merged.weekendEndsSundayAtMinute)
        assertFalse(SetupPresets.hasWeekendDistinction(merged))
    }

    @Test
    fun `dropping the distinction from an unlimited weekday means unlimited everywhere`() {
        val weekendOnly = SetupPresets.withWeekendLeisureBudget(PolicySettings(), 180)
        val merged = SetupPresets.withoutWeekendDistinction(weekendOnly)
        assertTrue(merged.budgets.isEmpty())
    }

    @Test
    fun `the default idle-earn starter is internally consistent`() {
        val earn = SetupPresets.defaultIdleEarn()
        assertTrue(earn.minutesIdlePerReward > 0 && earn.rewardMinutes > 0)
        // The rolling-window cap must fit at least one whole reward block, or the wizard
        // would enable a feature that can never grant anything.
        assertTrue(earn.windowCapMinutes >= earn.rewardMinutes)
        assertTrue(earn.weeklyCapMinutes >= earn.windowCapMinutes)
        assertEquals(AppCategory.GAMES.id, earn.targetCategoryId)
    }

    @Test
    fun `protection preset adds the recommended set plus the install block`() {
        val out = SetupPresets.withProtection(PolicySettings(), blockInstalls = true)
        assertTrue(out.deviceRestrictions.containsAll(DeviceRestrictions.RECOMMENDED_DEFAULTS))
        assertTrue(DeviceRestrictions.KEY_INSTALLS in out.deviceRestrictions)
    }

    @Test
    fun `declining the install block removes it but keeps everything else`() {
        val withInstalls = SetupPresets.withProtection(
            PolicySettings(deviceRestrictions = setOf(DeviceRestrictions.KEY_BIOMETRICS)),
            blockInstalls = true,
        )
        val without = SetupPresets.withProtection(withInstalls, blockInstalls = false)
        assertFalse(DeviceRestrictions.KEY_INSTALLS in without.deviceRestrictions)
        assertTrue(without.deviceRestrictions.containsAll(DeviceRestrictions.RECOMMENDED_DEFAULTS))
        // A restriction outside the preset's scope survives both passes.
        assertTrue(DeviceRestrictions.KEY_BIOMETRICS in without.deviceRestrictions)
    }
}
