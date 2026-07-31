package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import dev.walcott.rules.DayType
import org.junit.jupiter.api.Test

/**
 * The UI only edits weekdays (SCHOOL) and weekends; the wire's HOLIDAY slot must mirror
 * WEEKEND on every write so calendar special days behave like weekends — on this build AND
 * on already-deployed children that still resolve special days to the HOLIDAY key.
 */
class HolidayMirrorTest {

    private val window = WindowDto(16 * 60, 18 * 60)

    @Test
    fun `the default budget copies the weekend value into the holiday slot`() {
        val out = PolicySettings(
            defaultAppBudget = mapOf("SCHOOL" to 30, "WEEKEND" to 120),
        ).withHolidayMirroringWeekend()
        assertEquals(mapOf("SCHOOL" to 30, "WEEKEND" to 120, "HOLIDAY" to 120), out.defaultAppBudget)
    }

    @Test
    fun `a stale distinct holiday value is overwritten and a weekendless map loses it`() {
        val out = PolicySettings(
            defaultAppBudget = mapOf("WEEKEND" to 60, "HOLIDAY" to 999),
        ).withHolidayMirroringWeekend()
        assertEquals(60, out.defaultAppBudget["HOLIDAY"])
        val weekendless = PolicySettings(
            defaultAppBudget = mapOf("SCHOOL" to 30, "HOLIDAY" to 999),
        ).withHolidayMirroringWeekend()
        assertNull(weekendless.defaultAppBudget["HOLIDAY"])
    }

    @Test
    fun `bedtime and screen-free windows mirror too`() {
        val out = PolicySettings(
            bedtime = mapOf("SCHOOL" to window, "WEEKEND" to WindowDto(22 * 60, 8 * 60)),
            allAppsBlockedWindows = mapOf("WEEKEND" to listOf(window)),
        ).withHolidayMirroringWeekend()
        assertEquals(WindowDto(22 * 60, 8 * 60), out.bedtime["HOLIDAY"])
        assertEquals(listOf(window), out.allAppsBlockedWindows["HOLIDAY"])
    }

    @Test
    fun `per-app policies, earn windows and child overrides mirror too`() {
        val out = PolicySettings(
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("WEEKEND" to 45))),
            idleEarn = IdleEarnDto(
                minutesIdlePerReward = 10,
                rewardMinutes = 5,
                windowHours = 4,
                windowCapMinutes = 20,
                weeklyCapMinutes = 120,
                earnWindows = mapOf("WEEKEND" to listOf(window)),
            ),
            children = listOf(
                ChildEntry(
                    "c1", "Kid",
                    overrides = ChildOverrides(
                        defaultAppBudget = mapOf("WEEKEND" to 90, "HOLIDAY" to 999),
                        bedtime = mapOf("WEEKEND" to window),
                        appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("WEEKEND" to 30))),
                        allAppsBlockedWindows = mapOf("WEEKEND" to listOf(window)),
                    ),
                ),
            ),
        ).withHolidayMirroringWeekend()
        assertEquals(45, out.appPolicies.getValue("com.game").budgets["HOLIDAY"])
        assertEquals(listOf(window), out.idleEarn!!.earnWindows["HOLIDAY"])
        val overrides = out.children.single().overrides
        assertEquals(90, overrides.defaultAppBudget!!["HOLIDAY"])
        assertEquals(window, overrides.bedtime!!["HOLIDAY"])
        assertEquals(30, overrides.appPolicies!!.getValue("com.game").budgets["HOLIDAY"])
        assertEquals(listOf(window), overrides.allAppsBlockedWindows!!["HOLIDAY"])
    }

    @Test
    fun `an app policy left empty by the mirror is dropped`() {
        val out = PolicySettings(
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("HOLIDAY" to 999))),
        ).withHolidayMirroringWeekend()
        assertTrue(out.appPolicies.isEmpty())
    }

    @Test
    fun `null child overrides stay null (inherit) instead of materializing`() {
        val out = PolicySettings(children = listOf(ChildEntry("c1", "Kid"))).withHolidayMirroringWeekend()
        assertTrue(out.children.single().overrides.isEmpty)
    }

    // --- Special days claiming their own budget column ---

    @Test
    fun `with the column on, the default budget keeps a distinct holiday value`() {
        val out = PolicySettings(
            specialDaysOwnRules = true,
            defaultAppBudget = mapOf("SCHOOL" to 30, "WEEKEND" to 120, "HOLIDAY" to 240),
        ).withHolidayMirroringWeekend()
        assertEquals(240, out.defaultAppBudget["HOLIDAY"])
    }

    @Test
    fun `with the switch on, schedules keep their own special-day value too`() {
        // The switch governs the whole day-type dimension, not just budgets: a family that has
        // claimed special days can give them their own bedtime and their own screen-free windows.
        // It used to govern budgets alone, which is why bedtime had no special-day row at all.
        val other = WindowDto(23 * 60, 9 * 60)
        val out = PolicySettings(
            specialDaysOwnRules = true,
            bedtime = mapOf("SCHOOL" to window, "WEEKEND" to window, "HOLIDAY" to other),
            allAppsBlockedWindows = mapOf("WEEKEND" to listOf(window), "HOLIDAY" to listOf(other)),
            appPolicies = mapOf(
                "com.game" to AppPolicyDto(
                    blockedWindows = mapOf("WEEKEND" to listOf(window), "HOLIDAY" to listOf(other)),
                ),
            ),
        ).withHolidayMirroringWeekend()
        assertEquals(other, out.bedtime["HOLIDAY"])
        assertEquals(listOf(other), out.allAppsBlockedWindows["HOLIDAY"])
        assertEquals(listOf(other), out.appPolicies.getValue("com.game").blockedWindows["HOLIDAY"])
    }

    @Test
    fun `with the switch off, schedules still collapse onto the weekend`() {
        // The default, and what protects a special day from having no bedtime at all.
        val other = WindowDto(23 * 60, 9 * 60)
        val out = PolicySettings(
            bedtime = mapOf("SCHOOL" to window, "WEEKEND" to window, "HOLIDAY" to other),
            allAppsBlockedWindows = mapOf("WEEKEND" to listOf(window), "HOLIDAY" to listOf(other)),
        ).withHolidayMirroringWeekend()
        assertEquals(window, out.bedtime["HOLIDAY"])
        assertEquals(listOf(window), out.allAppsBlockedWindows["HOLIDAY"])
    }

    @Test
    fun `turning the switch on seeds the schedules from the weekend too`() {
        // Same promise as budgets, and the one that matters most: dropping the mirror with
        // nothing behind it would leave a special day with no bedtime, which reads as "no rule".
        val out = PolicySettings(
            bedtime = mapOf("SCHOOL" to window, "WEEKEND" to window),
            allAppsBlockedWindows = mapOf("SCHOOL" to listOf(window), "WEEKEND" to listOf(window)),
            appPolicies = mapOf("com.game" to AppPolicyDto(blockedWindows = mapOf("WEEKEND" to listOf(window)))),
            idleEarn = IdleEarnDto(
                minutesIdlePerReward = 30,
                rewardMinutes = 10,
                windowHours = 4,
                windowCapMinutes = 60,
                weeklyCapMinutes = 300,
                earnWindows = mapOf("WEEKEND" to listOf(window)),
            ),
        ).withSpecialDaysOwnRules(true)
        assertEquals(window, out.bedtime["HOLIDAY"])
        assertEquals(listOf(window), out.allAppsBlockedWindows["HOLIDAY"])
        assertEquals(listOf(window), out.appPolicies.getValue("com.game").blockedWindows["HOLIDAY"])
        assertEquals(listOf(window), out.idleEarn?.earnWindows?.get("HOLIDAY"))
    }

    @Test
    fun `turning the column on seeds it from the weekend, everywhere a budget lives`() {
        // Nothing may change at the instant the parent takes control: dropping the mirror with
        // no value behind it would read as "no limit on special days".
        val before = PolicySettings(
            defaultAppBudget = mapOf("SCHOOL" to 30, "WEEKEND" to 120),
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("WEEKEND" to 45))),
            children = listOf(
                ChildEntry("c1", "Ana", ChildOverrides(defaultAppBudget = mapOf("WEEKEND" to 90))),
            ),
        )
        val out = before.withSpecialDaysOwnRules(true)
        assertTrue(out.specialDaysOwnRules)
        assertEquals(120, out.defaultAppBudget["HOLIDAY"])
        assertEquals(45, out.appPolicies.getValue("com.game").budgets["HOLIDAY"])
        assertEquals(90, out.children.first().overrides.defaultAppBudget?.get("HOLIDAY"))
    }

    @Test
    fun `turning it off re-collapses the column on the next write`() {
        val split = PolicySettings(
            specialDaysOwnRules = true,
            defaultAppBudget = mapOf("WEEKEND" to 120, "HOLIDAY" to 240),
        )
        val out = split.withSpecialDaysOwnRules(false).withHolidayMirroringWeekend()
        assertFalse(out.specialDaysOwnRules)
        assertEquals(120, out.defaultAppBudget["HOLIDAY"])
    }

    @Test
    fun `a special day's own bedtime reaches the engine, not just the policy`() {
        // The whole point of the switch, end to end: the parent sets a later bedtime for special
        // days, the write keeps it, and the config the child enforces from actually carries it.
        val lateNight = WindowDto(23 * 60, 9 * 60)
        val config = PolicySettings(
            specialDaysOwnRules = true,
            bedtime = mapOf("SCHOOL" to window, "WEEKEND" to window, "HOLIDAY" to lateNight),
        ).withHolidayMirroringWeekend().toFamilyConfig(emptySet())

        assertEquals(lateNight.toTimeWindowOrNull(), config.bedtime[DayType.HOLIDAY])
        assertEquals(window.toTimeWindowOrNull(), config.bedtime[DayType.SCHOOL])
    }

    @Test
    fun `seeding a budget with no weekend value leaves it unlimited on special days`() {
        // Weekday-only limit: there is nothing to copy, and inventing one would tighten the
        // rules behind the parent's back.
        val out = PolicySettings(defaultAppBudget = mapOf("SCHOOL" to 30)).withSpecialDaysOwnRules(true)
        assertNull(out.defaultAppBudget["HOLIDAY"])
    }
}
