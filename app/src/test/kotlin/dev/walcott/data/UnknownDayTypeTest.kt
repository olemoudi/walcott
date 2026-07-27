package dev.walcott.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.walcott.rules.DayType

/**
 * Rules arrive from the parent, which may be running a newer build than the child. A day-type
 * key this build doesn't know used to throw out of `toFamilyConfig` — inside the enforcement
 * loop, which then crash-restarted every few seconds forever, enforcing nothing. One unknown
 * key must cost one rule, never the device.
 */
class UnknownDayTypeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `an unknown day type is skipped instead of throwing`() {
        val settings = PolicySettings(
            assignments = mapOf("com.game" to "games"),
            budgets = mapOf("games" to mapOf("SCHOOL" to 60, "FUTURE_DAY" to 5)),
            bedtime = mapOf("SCHOOL" to WindowDto(1320, 420), "FUTURE_DAY" to WindowDto(0, 60)),
            allAppsBlockedWindows = mapOf("WEEKEND" to listOf(WindowDto(600, 660)), "FUTURE_DAY" to emptyList()),
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("FUTURE_DAY" to 10, "WEEKEND" to 30))),
        )

        val config = settings.toFamilyConfig(essentials = emptySet())

        assertEquals(setOf(DayType.SCHOOL), config.policies.getValue("games").dailyBudget.keys)
        assertEquals(setOf(DayType.SCHOOL), config.bedtime.keys)
        assertEquals(setOf(DayType.WEEKEND), config.blockedWindows.keys)
        assertEquals(setOf(DayType.WEEKEND), config.perAppPolicies.getValue("com.game").dailyBudget.keys)
    }

    @Test
    fun `a policy made only of unknown day types degrades to no rules, not a crash`() {
        val settings = PolicySettings(
            assignments = mapOf("com.game" to "games"),
            budgets = mapOf("games" to mapOf("FUTURE_DAY" to 5)),
        )
        val config = settings.toFamilyConfig(essentials = emptySet())
        assertTrue(config.policies.getValue("games").dailyBudget.isEmpty())
    }

    @Test
    fun `idle-earn windows survive an unknown day type too`() {
        val dto = IdleEarnDto(
            targetCategoryId = "games",
            minutesIdlePerReward = 30,
            rewardMinutes = 10,
            windowHours = 24,
            windowCapMinutes = 60,
            weeklyCapMinutes = 300,
            earnWindows = mapOf("WEEKEND" to listOf(WindowDto(600, 720)), "FUTURE_DAY" to listOf(WindowDto(0, 60))),
        )
        assertEquals(setOf(DayType.WEEKEND), dto.toConfig().earnWindows.keys)
    }

    @Test
    fun `a policy from a newer build still decodes, unknown fields and all`() {
        val fromTheFuture = """
            {"version":9,"assignments":{"com.game":"games"},
             "budgets":{"games":{"SCHOOL":45,"FUTURE_DAY":5}},
             "somethingNewNobodyKnows":{"a":1}}
        """.trimIndent()
        val decoded = json.decodeFromString(PolicySettings.serializer(), fromTheFuture)
        val config = decoded.toFamilyConfig(essentials = emptySet())
        assertEquals(45L, config.policies.getValue("games").dailyBudget.getValue(DayType.SCHOOL).toMinutes())
    }
}
