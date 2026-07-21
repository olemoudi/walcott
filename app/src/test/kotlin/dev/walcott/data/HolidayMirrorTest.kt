package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The UI only edits weekdays (SCHOOL) and weekends; the wire's HOLIDAY slot must mirror
 * WEEKEND on every write so calendar special days behave like weekends — on this build AND
 * on already-deployed children that still resolve special days to the HOLIDAY key.
 */
class HolidayMirrorTest {

    private val window = WindowDto(16 * 60, 18 * 60)

    @Test
    fun `budgets copy the weekend value into the holiday slot`() {
        val out = PolicySettings(
            budgets = mapOf("games" to mapOf("SCHOOL" to 30, "WEEKEND" to 120)),
        ).withHolidayMirroringWeekend()
        assertEquals(mapOf("SCHOOL" to 30, "WEEKEND" to 120, "HOLIDAY" to 120), out.budgets.getValue("games"))
    }

    @Test
    fun `a stale distinct holiday value is overwritten and a weekendless map loses it`() {
        val out = PolicySettings(
            budgets = mapOf(
                "games" to mapOf("WEEKEND" to 60, "HOLIDAY" to 999),
                "video" to mapOf("SCHOOL" to 30, "HOLIDAY" to 999),
            ),
        ).withHolidayMirroringWeekend()
        assertEquals(60, out.budgets.getValue("games")["HOLIDAY"])
        assertNull(out.budgets.getValue("video")["HOLIDAY"])
    }

    @Test
    fun `a category left with no per-day entries is dropped entirely`() {
        val out = PolicySettings(
            budgets = mapOf("games" to mapOf("HOLIDAY" to 999)),
        ).withHolidayMirroringWeekend()
        assertTrue(out.budgets.isEmpty())
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
            idleEarn = IdleEarnDto("games", 10, 5, 4, 20, 120, earnWindows = mapOf("WEEKEND" to listOf(window))),
            children = listOf(
                ChildEntry(
                    "c1", "Kid",
                    overrides = ChildOverrides(
                        budgets = mapOf("games" to mapOf("WEEKEND" to 90, "HOLIDAY" to 999)),
                        bedtime = mapOf("WEEKEND" to window),
                    ),
                ),
            ),
        ).withHolidayMirroringWeekend()
        assertEquals(45, out.appPolicies.getValue("com.game").budgets["HOLIDAY"])
        assertEquals(listOf(window), out.idleEarn!!.earnWindows["HOLIDAY"])
        val overrides = out.children.single().overrides
        assertEquals(90, overrides.budgets!!.getValue("games")["HOLIDAY"])
        assertEquals(window, overrides.bedtime!!["HOLIDAY"])
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
}
