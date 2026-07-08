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
        val config = settings.toFamilyConfig(essentials = emptySet())
        val games = config.policies.getValue("games")
        assertEquals(Duration.ofMinutes(30), games.dailyBudget[DayType.SCHOOL])
        assertEquals(Duration.ofHours(2), games.dailyBudget[DayType.WEEKEND])
        assertNull(games.dailyBudget[DayType.HOLIDAY])
    }

    @Test
    fun `toFamilyConfig maps blocked windows and bedtime`() {
        val config = settings.toFamilyConfig(emptySet())
        val window = config.policies.getValue("games").blockedWindows.getValue(DayType.SCHOOL).single()
        assertTrue(window.contains(java.time.LocalTime.of(10, 0)))
        val bedtime = config.bedtime.getValue(DayType.SCHOOL)
        assertTrue(bedtime.contains(java.time.LocalTime.of(23, 0)))
    }

    @Test
    fun `toFamilyConfig maps holidays and carries assignments and essentials`() {
        val config = settings.copy(assignments = mapOf("com.game" to "games"))
            .toFamilyConfig(essentials = setOf("dev.walcott"))
        assertEquals(DayType.HOLIDAY, config.calendar.dayTypeOf(LocalDate.of(2026, 10, 12)))
        assertEquals("games", config.assignments["com.game"])
        assertTrue("dev.walcott" in config.essentialPackages)
        assertEquals(3, config.version)
    }

    @Test
    fun `an assigned category with no rules still gets a (permissive) policy entry`() {
        val config = PolicySettings(assignments = mapOf("com.game" to "games"))
            .toFamilyConfig(essentials = emptySet())
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

    @Test
    fun `round-trips family name and children with overrides`() {
        val json = Json { encodeDefaults = true }
        val withChildren = settings.copy(
            familyName = "Moudis",
            children = listOf(
                ChildEntry("c1", "Ana", ChildOverrides(budgets = mapOf("games" to mapOf("SCHOOL" to 60)))),
            ),
        )
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            json.encodeToString(PolicySettings.serializer(), withChildren),
        )
        assertEquals(withChildren, decoded)
    }

    @Test
    fun `withBudget sets, clears and drops empty categories`() {
        val budgets = mapOf("games" to mapOf("SCHOOL" to 30))
        assertEquals(
            mapOf("games" to mapOf("SCHOOL" to 30, "WEEKEND" to 60)),
            budgets.withBudget("games", "WEEKEND", 60),
        )
        assertEquals(emptyMap<String, Map<String, Int>>(), budgets.withBudget("games", "SCHOOL", null))
        assertEquals(budgets, budgets.withBudget("video", "SCHOOL", null))
    }

    @Test
    fun `legacy JSON without family fields decodes to defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            """{"version":5,"budgets":{"games":{"SCHOOL":30}}}""",
        )
        assertEquals("", decoded.familyName)
        assertTrue(decoded.children.isEmpty())
        assertTrue(decoded.assignments.isEmpty())
    }

    @Test
    fun `assignments round-trip through serialization`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val withApps = settings.copy(assignments = mapOf("com.game" to "games", "com.chat" to "social"))
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            json.encodeToString(PolicySettings.serializer(), withApps),
        )
        assertEquals(withApps.assignments, decoded.assignments)
    }

    @Test
    fun `tracking interval defaults to off and survives round-trip`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // Legacy JSON without the field decodes to the off default.
        val legacy = json.decodeFromString(PolicySettings.serializer(), """{"version":5}""")
        assertEquals(0, legacy.trackingIntervalMinutes)
        // A set value round-trips.
        val withTracking = settings.copy(trackingIntervalMinutes = 15)
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            json.encodeToString(PolicySettings.serializer(), withTracking),
        )
        assertEquals(15, decoded.trackingIntervalMinutes)
    }

    @Test
    fun `seedRestrictions adds defaults once and respects later removal`() {
        val defaults = setOf("datetime", "vpn", "apps_control")
        val seeded = PolicySettings().seedRestrictions(defaults)
        assertEquals(defaults, seeded.deviceRestrictions)
        assertTrue(seeded.hardeningSeeded)
        // Once seeded, a parent removing one of them is not undone by a later seed call.
        val afterRemoval = seeded.copy(deviceRestrictions = setOf("vpn"))
        assertEquals(setOf("vpn"), afterRemoval.seedRestrictions(defaults).deviceRestrictions)
    }

    @Test
    fun `withLegacyAssignments adopts only when none set yet`() {
        val legacy = mapOf("com.game" to "games")
        assertEquals(legacy, PolicySettings().withLegacyAssignments(legacy).assignments)
        // Already-populated policy is left untouched.
        val populated = PolicySettings(assignments = mapOf("com.chat" to "social"))
        assertEquals(populated, populated.withLegacyAssignments(legacy))
        // Nothing to migrate.
        val empty = PolicySettings()
        assertEquals(empty, empty.withLegacyAssignments(emptyMap()))
    }
}
