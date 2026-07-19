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
