package dev.walcott.ui.parent

import dev.walcott.data.AppPolicyDto
import dev.walcott.data.DomainAppRuleDto
import dev.walcott.data.PolicySettings
import dev.walcott.data.WindowDto
import dev.walcott.rules.DayType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the badges in the app list claim. They have to mean exactly the sections that light up
 * inside the app's own screen — a badge for a rule the parent then can't find is worse than no
 * badge at all.
 */
class AppRestrictionsTest {

    private val pkg = "com.game"

    @Test
    fun `an app with nothing of its own carries no badges`() {
        assertEquals(emptyList<AppRestriction>(), appRestrictions(PolicySettings(), pkg))
    }

    @Test
    fun `its own daily budget shows the budget badge`() {
        val settings = PolicySettings(appPolicies = mapOf(pkg to AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 30))))
        assertEquals(listOf(AppRestriction.OWN_BUDGET), appRestrictions(settings, pkg))
    }

    @Test
    fun `its own blocked hours show the hours badge`() {
        val settings = PolicySettings(
            appPolicies = mapOf(
                pkg to AppPolicyDto(blockedWindows = mapOf(DayType.SCHOOL.name to listOf(WindowDto(60, 120)))),
            ),
        )
        assertEquals(listOf(AppRestriction.OWN_WINDOWS), appRestrictions(settings, pkg))
    }

    @Test
    fun `an empty window list is not a restriction`() {
        // The editor leaves the key behind after the last window is deleted; a badge for it
        // would point at a section with nothing in it.
        val settings = PolicySettings(
            appPolicies = mapOf(pkg to AppPolicyDto(blockedWindows = mapOf(DayType.SCHOOL.name to emptyList()))),
        )
        assertEquals(emptyList<AppRestriction>(), appRestrictions(settings, pkg))
    }

    @Test
    fun `a domain rule for this app shows the web badge, one for another app does not`() {
        val settings = PolicySettings(
            domainAppRules = listOf(DomainAppRuleDto("youtube.com", "com.other", allowOnlyFromApp = true)),
        )
        assertEquals(emptyList<AppRestriction>(), appRestrictions(settings, pkg))
        val mine = settings.copy(
            domainAppRules = settings.domainAppRules + DomainAppRuleDto("twitch.tv", pkg, allowOnlyFromApp = false),
        )
        assertEquals(listOf(AppRestriction.WEB_RULE), appRestrictions(mine, pkg))
    }

    @Test
    fun `badges come in the order the sections appear inside the app`() {
        val settings = PolicySettings(
            appPolicies = mapOf(
                pkg to AppPolicyDto(
                    budgets = mapOf(DayType.SCHOOL.name to 30),
                    blockedWindows = mapOf(DayType.SCHOOL.name to listOf(WindowDto(60, 120))),
                ),
            ),
            domainAppRules = listOf(DomainAppRuleDto("twitch.tv", pkg, allowOnlyFromApp = false)),
        )
        assertEquals(
            listOf(AppRestriction.OWN_BUDGET, AppRestriction.OWN_WINDOWS, AppRestriction.WEB_RULE),
            appRestrictions(settings, pkg),
        )
    }

    @Test
    fun `another app's rules never leak into this one's badges`() {
        val settings = PolicySettings(
            appPolicies = mapOf("com.other" to AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 30))),
        )
        assertEquals(emptyList<AppRestriction>(), appRestrictions(settings, pkg))
    }
}
