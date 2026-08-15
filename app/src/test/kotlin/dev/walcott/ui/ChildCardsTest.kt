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

    /** What this device can actually block. The system apps below are deliberately outside it. */
    private val managed = setOf("com.game", "com.chat", "com.gone")

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
        val cards = childCardPackages(cfg, emptySet(), DayType.SCHOOL, managed, label)
        assertEquals(listOf("com.game" to "Roblox"), cards)
    }

    @Test
    fun `time spent today in an app since uninstalled gets no card either`() {
        val cfg = config(default = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        val cards = childCardPackages(cfg, setOf("com.chat", "com.gone"), DayType.SCHOOL, managed, label)
        assertEquals(listOf("com.chat" to "WhatsApp"), cards)
    }

    @Test
    fun `an installed app the child used today shows under the family default`() {
        val cfg = config(default = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        assertEquals(
            listOf("com.chat" to "WhatsApp"),
            childCardPackages(cfg, setOf("com.chat"), DayType.SCHOOL, managed, label),
        )
    }

    @Test
    fun `with no limit today there are no cards at all`() {
        // Limits are opt-in: a family that set none leaves the screen empty rather than
        // listing every app the child happens to have opened.
        val cards = childCardPackages(config(), setOf("com.chat", "com.game"), DayType.SCHOOL, managed, label)
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
            childCardPackages(cfg, setOf("com.chat", "com.game"), DayType.SCHOOL, managed, label),
        )
    }

    @Test
    fun `an app this device cannot block gets no card, however much it was used`() {
        // The browser, the video app and the gallery ship as system apps on most phones: their
        // screen time is counted (a parent must see where the day went) but the enforcement loop
        // has never been able to suspend them. Under a family default they were getting a card
        // that counted down to "Blocked" over an app that went on opening — the screen saying one
        // thing and the phone doing another, on the apps that matter most.
        val cfg = config(default = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        val withBrowser: (String) -> String? = { (installed + ("com.android.browser" to "Browser"))[it] }
        assertEquals(
            listOf("com.chat" to "WhatsApp"),
            childCardPackages(cfg, setOf("com.chat", "com.android.browser"), DayType.SCHOOL, managed, withBrowser),
        )
    }

    @Test
    fun `a limit somebody set on an unblockable app is not shown either`() {
        // Same rule from the other source: a policy can name any package at all.
        val cfg = config(
            perApp = mapOf(
                "com.android.browser" to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30))),
            ),
        )
        val withBrowser: (String) -> String? = { (installed + ("com.android.browser" to "Browser"))[it] }
        assertEquals(emptyList<Pair<String, String>>(), childCardPackages(cfg, emptySet(), DayType.SCHOOL, managed, withBrowser))
    }

    @Test
    fun `an app is listed once even when it is both limited and used`() {
        val cfg = config(
            perApp = mapOf("com.game" to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)))),
        )
        assertEquals(1, childCardPackages(cfg, setOf("com.game"), DayType.SCHOOL, managed, label).size)
    }
}
