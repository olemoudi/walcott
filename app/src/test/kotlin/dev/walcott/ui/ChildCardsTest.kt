package dev.walcott.ui

import dev.walcott.rules.AppPolicy
import dev.walcott.rules.DayType
import dev.walcott.rules.FamilyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Which apps the child's own screen offers a card for. The rule that keeps it honest: a card
 * is a thing the child can go and open, so an app that is no longer installed has no business
 * on it — however long its limit and its counters outlive it.
 */
class ChildCardsTest {

    private val installed = mapOf(
        "com.game" to "Roblox",
        "com.chat" to "WhatsApp",
    )
    private val label: (String) -> String? = { installed[it] }

    private fun config(
        default: Map<DayType, Duration> = emptyMap(),
        perApp: Map<String, AppPolicy> = emptyMap(),
    ) = FamilyConfig(version = 1, defaultAppBudget = default, perAppPolicies = perApp)

    @Test
    fun `an app whose limit outlived the uninstall gets no card`() {
        // The limit stays in the policy on purpose — uninstalling and reinstalling must not be
        // the way to wipe it — but the child's screen must not keep offering it.
        val cfg = config(
            perApp = mapOf(
                "com.game" to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30))),
                "com.gone" to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30))),
            ),
        )
        val cards = childCardPackages(cfg, emptySet(), DayType.SCHOOL, label)
        assertEquals(listOf("com.game" to "Roblox"), cards)
    }

    @Test
    fun `time spent today in an app since uninstalled gets no card either`() {
        val cfg = config(default = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        val cards = childCardPackages(cfg, setOf("com.chat", "com.gone"), DayType.SCHOOL, label)
        assertEquals(listOf("com.chat" to "WhatsApp"), cards)
    }

    @Test
    fun `an installed app the child used today shows under the family default`() {
        val cfg = config(default = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        assertEquals(
            listOf("com.chat" to "WhatsApp"),
            childCardPackages(cfg, setOf("com.chat"), DayType.SCHOOL, label),
        )
    }

    @Test
    fun `with no limit today there are no cards at all`() {
        // Limits are opt-in: a family that set none leaves the screen empty rather than
        // listing every app the child happens to have opened.
        val cards = childCardPackages(config(), setOf("com.chat", "com.game"), DayType.SCHOOL, label)
        assertEquals(emptyList<Pair<String, String>>(), cards)
    }

    @Test
    fun `an app set free of the default gets no card`() {
        val cfg = config(
            default = mapOf(DayType.SCHOOL to Duration.ofHours(1)),
            perApp = mapOf("com.chat" to AppPolicy(unlimited = true)),
        )
        assertEquals(
            listOf("com.game" to "Roblox"),
            childCardPackages(cfg, setOf("com.chat", "com.game"), DayType.SCHOOL, label),
        )
    }

    @Test
    fun `an app is listed once even when it is both limited and used`() {
        val cfg = config(
            perApp = mapOf("com.game" to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)))),
        )
        assertEquals(1, childCardPackages(cfg, setOf("com.game"), DayType.SCHOOL, label).size)
    }
}
