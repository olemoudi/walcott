package dev.walcott.data

import dev.walcott.rules.DayType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

class PolicySettingsTest {

    private val settings = PolicySettings(
        version = 3,
        defaultAppBudget = mapOf("SCHOOL" to 30, "WEEKEND" to 120),
        appPolicies = mapOf(
            "com.game" to AppPolicyDto(
                blockedWindows = mapOf("SCHOOL" to listOf(WindowDto(8 * 60 + 30, 14 * 60 + 30))),
            ),
        ),
        bedtime = mapOf("SCHOOL" to WindowDto(21 * 60 + 30, 7 * 60 + 30)),
        holidays = setOf(LocalDate.of(2026, 10, 12).toEpochDay()),
    )

    @Test
    fun `toFamilyConfig maps budgets from minutes to Duration per day type`() {
        val config = settings.toFamilyConfig(essentials = emptySet())
        assertEquals(Duration.ofMinutes(30), config.defaultAppBudget[DayType.SCHOOL])
        assertEquals(Duration.ofHours(2), config.defaultAppBudget[DayType.WEEKEND])
        assertNull(config.defaultAppBudget[DayType.HOLIDAY])
    }

    @Test
    fun `toFamilyConfig maps blocked windows and bedtime`() {
        val config = settings.toFamilyConfig(emptySet())
        val window = config.perAppPolicies.getValue("com.game").blockedWindows.getValue(DayType.SCHOOL).single()
        assertTrue(window.contains(java.time.LocalTime.of(10, 0)))
        val bedtime = config.bedtime.getValue(DayType.SCHOOL)
        assertTrue(bedtime.contains(java.time.LocalTime.of(23, 0)))
    }

    @Test
    fun `toFamilyConfig maps family-wide screen-free windows`() {
        val config = settings.copy(
            allAppsBlockedWindows = mapOf(
                "SCHOOL" to listOf(WindowDto(14 * 60, 15 * 60 + 30), WindowDto(17 * 60, 19 * 60)),
            ),
        ).toFamilyConfig(emptySet())
        val windows = config.blockedWindows.getValue(DayType.SCHOOL)
        assertEquals(2, windows.size)
        assertTrue(windows[0].contains(java.time.LocalTime.of(14, 30)))
        assertTrue(windows[1].contains(java.time.LocalTime.of(18, 0)))
        assertNull(config.blockedWindows[DayType.WEEKEND])
    }

    @Test
    fun `toFamilyConfig maps holidays and carries essentials`() {
        val config = settings.toFamilyConfig(essentials = setOf("dev.walcott"))
        assertEquals(DayType.HOLIDAY, config.calendar.dayTypeOf(LocalDate.of(2026, 10, 12).atTime(12, 0)))
        assertTrue("dev.walcott" in config.essentialPackages)
        assertEquals(3, config.version)
    }

    @Test
    fun `weekend edges default to off and map to the calendar when set`() {
        // A config written before the fields existed keeps the plain Saturday–Sunday weekend.
        val legacy = PolicySettings().toFamilyConfig(emptySet()).calendar
        assertNull(legacy.weekendStartsFriday)
        assertNull(legacy.weekendEndsSunday)

        val edged = PolicySettings(weekendStartsFridayAtMinute = 14 * 60, weekendEndsSundayAtMinute = 20 * 60)
            .toFamilyConfig(emptySet()).calendar
        assertEquals(java.time.LocalTime.of(14, 0), edged.weekendStartsFriday)
        assertEquals(java.time.LocalTime.of(20, 0), edged.weekendEndsSunday)
        // 2026-03-13 is a Friday.
        assertEquals(DayType.SCHOOL, edged.dayTypeOf(LocalDate.of(2026, 3, 13).atTime(13, 0)))
        assertEquals(DayType.WEEKEND, edged.dayTypeOf(LocalDate.of(2026, 3, 13).atTime(14, 0)))
    }

    @Test
    fun `a window's day filter maps to the engine, and junk day numbers are dropped`() {
        val window = WindowDto(17 * 60, 19 * 60, days = listOf(2, 4), skipSpecialDays = true).toTimeWindowOrNull()!!
        assertEquals(setOf(java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.THURSDAY), window.days)
        assertEquals(dev.walcott.rules.SpecialDays.NEVER, window.specialDays)

        // A window written before the fields existed: every day, never stands down.
        val legacy = WindowDto(17 * 60, 19 * 60).toTimeWindowOrNull()!!
        assertTrue(legacy.days.isEmpty())
        assertEquals(dev.walcott.rules.SpecialDays.ALWAYS, legacy.specialDays)

        // Out-of-range numbers are ignored rather than thrown inside the enforcement loop.
        // All-junk collapses to "every day", which over-blocks rather than silently stopping.
        assertEquals(setOf(java.time.DayOfWeek.MONDAY), WindowDto(0, 60, days = listOf(1, 9, -3)).toTimeWindowOrNull()!!.days)
        assertTrue(WindowDto(0, 60, days = listOf(0, 8)).toTimeWindowOrNull()!!.days.isEmpty())
    }

    @Test
    fun `an out-of-range weekend edge is ignored, not thrown`() {
        // Same doctrine as unknown day-type keys: bad rules from another device must degrade,
        // never crash the enforcement loop.
        val calendar = PolicySettings(weekendStartsFridayAtMinute = 5000, weekendEndsSundayAtMinute = -1)
            .toFamilyConfig(emptySet()).calendar
        assertNull(calendar.weekendStartsFriday)
        assertNull(calendar.weekendEndsSunday)
    }

    @Test
    fun `an app nobody set a rule for gets no entry at all`() {
        val config = PolicySettings().toFamilyConfig(essentials = emptySet())
        assertTrue(config.perAppPolicies.isEmpty())
        assertNull(config.budgetFor("com.game", DayType.SCHOOL))
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
    fun `new-app alerts default on and a legacy config without the field stays on`() {
        // Default: a family that never opens the setting still gets warned.
        assertEquals(true, PolicySettings().newAppAlerts)
        // A config written before the field existed decodes with the default (no silent opt-out).
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(PolicySettings.serializer(), """{"version":1}""")
        assertEquals(true, legacy.newAppAlerts)
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
    fun `withBudget sets and clears one day type`() {
        val budget = mapOf("SCHOOL" to 30)
        assertEquals(mapOf("SCHOOL" to 30, "WEEKEND" to 60), budget.withBudget("WEEKEND", 60))
        assertEquals(emptyMap<String, Int>(), budget.withBudget("SCHOOL", null))
        assertEquals(budget, budget.withBudget("WEEKEND", null))
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
    fun `per-app policies map into FamilyConfig, unassigned General apps included`() {
        val s = PolicySettings(
            assignments = mapOf("com.chat" to "social", "com.stale" to "games"),
            appPolicies = mapOf(
                "com.chat" to AppPolicyDto(budgets = mapOf("SCHOOL" to 20)),
                // Unassigned = General now, and a General app's own cap must hold.
                "com.gone" to AppPolicyDto(budgets = mapOf("SCHOOL" to 5)),
            ),
        )
        val config = s.toFamilyConfig(essentials = emptySet())
        assertEquals(
            java.time.Duration.ofMinutes(20),
            config.perAppPolicies.getValue("com.chat").dailyBudget[dev.walcott.rules.DayType.SCHOOL],
        )
        assertEquals(
            java.time.Duration.ofMinutes(5),
            config.perAppPolicies.getValue("com.gone").dailyBudget[dev.walcott.rules.DayType.SCHOOL],
        )
    }

    @Test
    fun `config written before per-app policies still decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            """{"version":5,"assignments":{"com.chat":"social"}}""",
        )
        assertTrue(decoded.appPolicies.isEmpty())
        assertEquals(false, decoded.updateWifiOnly)
    }

    @Test
    fun `config written before location history existed still decodes`() {
        // The additive-change contract: an existing install must upgrade without a migration,
        // and must not silently start collecting a location trail it never opted into.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            PolicySettings.serializer(),
            """{"version":5,"trackingIntervalMinutes":15,"children":[{"childId":"a","name":"Ana"}]}""",
        )
        assertEquals(false, decoded.locationHistoryEnabled)
        assertEquals(15, decoded.trackingIntervalMinutes)
        assertEquals(null, decoded.children.single().overrides.locationHistoryEnabled)
        // Resolving that legacy child must not invent a value either.
        assertEquals(false, decoded.resolveForChild("a").locationHistoryEnabled)
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

    // --- Leaving categories behind (see migratedFromCategories) ---

    @Test
    fun `the General budget becomes the limit every app gets`() {
        val old = PolicySettings(budgets = mapOf("other" to mapOf("SCHOOL" to 60, "WEEKEND" to 120)))
        val migrated = old.migratedFromCategories()
        assertEquals(mapOf("SCHOOL" to 60, "WEEKEND" to 120), migrated.defaultAppBudget)
        assertTrue(migrated.budgets.isEmpty())
    }

    @Test
    fun `every other category's rules become the rules of the apps that were in it`() {
        val old = PolicySettings(
            assignments = mapOf("com.game" to "games", "com.other.game" to "games", "com.chat" to "social"),
            budgets = mapOf("games" to mapOf("SCHOOL" to 45)),
            blockedWindows = mapOf("games" to mapOf("SCHOOL" to listOf(WindowDto(600, 660)))),
        )
        val migrated = old.migratedFromCategories()
        // Both games keep a cap — each with the whole 45 minutes, which is looser than the pot
        // they shared. Splitting it would invent a rule the parent never wrote.
        assertEquals(45, migrated.appPolicies.getValue("com.game").budgets["SCHOOL"])
        assertEquals(45, migrated.appPolicies.getValue("com.other.game").budgets["SCHOOL"])
        assertEquals(1, migrated.appPolicies.getValue("com.game").blockedWindows.getValue("SCHOOL").size)
        // The chat app's category had no rules, so it ends up with none.
        assertTrue("com.chat" !in migrated.appPolicies)
        assertTrue(migrated.assignments.isEmpty())
    }

    @Test
    fun `a limit already set on an app survives the migration untouched`() {
        val old = PolicySettings(
            assignments = mapOf("com.game" to "games"),
            budgets = mapOf("games" to mapOf("SCHOOL" to 45)),
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("SCHOOL" to 10))),
        )
        assertEquals(10, old.migratedFromCategories().appPolicies.getValue("com.game").budgets["SCHOOL"])
    }

    @Test
    fun `earn rules are dropped, they cannot be said any more`() {
        val old = PolicySettings(earnRules = listOf(EarnRuleDto("education", "games", 30, 10, 60)))
        assertTrue(old.migratedFromCategories().earnRules.isEmpty())
    }

    @Test
    fun `a child who overrode the family budgets keeps overriding, in the new shape`() {
        val old = PolicySettings(
            assignments = mapOf("com.game" to "games"),
            budgets = mapOf("other" to mapOf("SCHOOL" to 60)),
            children = listOf(
                ChildEntry(
                    "c1", "Ana",
                    ChildOverrides(
                        budgets = mapOf("other" to mapOf("SCHOOL" to 30), "games" to mapOf("SCHOOL" to 15)),
                    ),
                ),
                ChildEntry("c2", "Bea"),
            ),
        )
        val migrated = old.migratedFromCategories()
        val ana = migrated.children.first().overrides
        assertEquals(mapOf("SCHOOL" to 30), ana.defaultAppBudget)
        assertEquals(15, ana.appPolicies?.get("com.game")?.budgets?.get("SCHOOL"))
        assertNull(ana.budgets)
        // Bea never overrode anything and still inherits.
        assertTrue(migrated.children[1].overrides.isEmpty)
    }

    @Test
    fun `the migration is idempotent and a no-op on a policy written since`() {
        val modern = PolicySettings(
            defaultAppBudget = mapOf("SCHOOL" to 60),
            appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf("SCHOOL" to 10))),
        )
        assertEquals(modern, modern.migratedFromCategories())
        val old = PolicySettings(budgets = mapOf("other" to mapOf("SCHOOL" to 60)))
        val once = old.migratedFromCategories()
        assertEquals(once, once.migratedFromCategories())
    }
}
