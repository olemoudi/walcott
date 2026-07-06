package dev.walcott.data

import dev.walcott.rules.DayType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

class PolicySettingsTest {

    private val settings = PolicySettings(
        version = 3,
        budgets = mapOf(
            "games" to mapOf("SCHOOL" to 30, "WEEKEND" to 120),
        ),
        blockedWindows = mapOf(
            "games" to mapOf("SCHOOL" to listOf(WindowDto(8 * 60 + 30, 14 * 60 + 30))),
        ),
        bedtime = mapOf("SCHOOL" to WindowDto(21 * 60 + 30, 7 * 60 + 30)),
        holidays = setOf(LocalDate.of(2026, 10, 12).toEpochDay()),
    )

    @Test
    fun `toFamilyConfig maps budgets from minutes to Duration per day type`() {
        val config = settings.toFamilyConfig(assignments = emptyMap(), essentials = emptySet())
        val games = config.policies.getValue("games")
        assertEquals(Duration.ofMinutes(30), games.dailyBudget[DayType.SCHOOL])
        assertEquals(Duration.ofHours(2), games.dailyBudget[DayType.WEEKEND])
        assertNull(games.dailyBudget[DayType.HOLIDAY])
    }

    @Test
    fun `toFamilyConfig maps blocked windows and bedtime`() {
        val config = settings.toFamilyConfig(emptyMap(), emptySet())
        val window = config.policies.getValue("games").blockedWindows.getValue(DayType.SCHOOL).single()
        assertTrue(window.contains(java.time.LocalTime.of(10, 0)))
        val bedtime = config.bedtime.getValue(DayType.SCHOOL)
        assertTrue(bedtime.contains(java.time.LocalTime.of(23, 0)))
    }

    @Test
    fun `toFamilyConfig maps holidays and carries assignments and essentials`() {
        val config = settings.toFamilyConfig(
            assignments = mapOf("com.game" to "games"),
            essentials = setOf("dev.walcott"),
        )
        assertEquals(DayType.HOLIDAY, config.calendar.dayTypeOf(LocalDate.of(2026, 10, 12)))
        assertEquals("games", config.assignments["com.game"])
        assertTrue("dev.walcott" in config.essentialPackages)
        assertEquals(3, config.version)
    }

    @Test
    fun `an assigned category with no rules still gets a (permissive) policy entry`() {
        val config = PolicySettings().toFamilyConfig(
            assignments = mapOf("com.game" to "games"),
            essentials = emptySet(),
        )
        val games = config.policies.getValue("games")
        assertTrue(games.dailyBudget.isEmpty())
        assertTrue(games.blockedWindows.isEmpty())
    }

    @Test
    fun `serialization round-trips`() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(PolicySettings.serializer(), settings)
        val decoded = json.decodeFromString(PolicySettings.serializer(), encoded)
        assertEquals(settings, decoded)
    }

    @Test
    fun `decoding tolerates unknown keys`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            """{"version":5,"somethingNew":true}""",
        )
        assertEquals(5, decoded.version)
    }
}
