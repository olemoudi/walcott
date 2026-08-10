package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyDiffTest {

    private val deployed = PolicySettings(
        familyName = "Obiols",
        defaultAppBudget = mapOf("WEEKDAY" to 60),
        bedtime = mapOf("WEEKDAY" to WindowDto(1320, 420)),
        appPolicies = mapOf("com.whatsapp" to AppPolicyDto(budgets = mapOf("WEEKDAY" to 30))),
        children = listOf(ChildEntry("c1", "Ana"), ChildEntry("c2", "Leo")),
    )

    @Test
    fun `an unchanged policy has nothing pending`() {
        assertTrue(PolicyDiff.changedKeys(deployed, deployed).isEmpty())
    }

    @Test
    fun `nothing published yet reports nothing, not everything`() {
        // On a fresh family every field differs from "no policy at all"; marking the whole app
        // as pending would say nothing about what the parent actually touched.
        assertTrue(PolicyDiff.changedKeys(null, deployed).isEmpty())
    }

    @Test
    fun `each section is reported on its own`() {
        assertEquals(
            setOf(PolicyDiff.DEFAULT_BUDGET),
            PolicyDiff.changedKeys(deployed, deployed.copy(defaultAppBudget = mapOf("WEEKDAY" to 90))),
        )
        assertEquals(
            setOf(PolicyDiff.BEDTIME),
            PolicyDiff.changedKeys(deployed, deployed.copy(bedtime = emptyMap())),
        )
        assertEquals(
            setOf(PolicyDiff.WEB_FILTER),
            PolicyDiff.changedKeys(deployed, deployed.copy(blockedDomains = setOf("x.com"))),
        )
        assertEquals(
            setOf(PolicyDiff.RESTRICTIONS),
            PolicyDiff.changedKeys(deployed, deployed.copy(deviceRestrictions = setOf("installs"))),
        )
    }

    @Test
    fun `the calendar's several fields are one key, because they are one screen`() {
        assertEquals(
            setOf(PolicyDiff.CALENDAR),
            PolicyDiff.changedKeys(deployed, deployed.copy(holidays = setOf(20_000L))),
        )
        assertEquals(
            setOf(PolicyDiff.CALENDAR),
            PolicyDiff.changedKeys(deployed, deployed.copy(specialDaysOwnRules = true)),
        )
    }

    @Test
    fun `one app's change marks that app and no other`() {
        val edited = deployed.copy(
            appPolicies = deployed.appPolicies + ("com.whatsapp" to AppPolicyDto(budgets = mapOf("WEEKDAY" to 45))),
        )
        assertEquals(setOf(PolicyDiff.appKey("com.whatsapp")), PolicyDiff.changedKeys(deployed, edited))
    }

    @Test
    fun `adding and removing an app both count`() {
        val added = deployed.copy(appPolicies = deployed.appPolicies + ("com.tiktok" to AppPolicyDto()))
        assertEquals(setOf(PolicyDiff.appKey("com.tiktok")), PolicyDiff.changedKeys(deployed, added))
        // A removal is a change the child has not been told about either.
        val removed = deployed.copy(appPolicies = emptyMap())
        assertEquals(setOf(PolicyDiff.appKey("com.whatsapp")), PolicyDiff.changedKeys(deployed, removed))
    }

    @Test
    fun `only the child that changed is marked`() {
        val renamed = deployed.copy(
            children = listOf(ChildEntry("c1", "Ana Maria"), ChildEntry("c2", "Leo")),
        )
        assertEquals(setOf(PolicyDiff.childKey("c1")), PolicyDiff.changedKeys(deployed, renamed))
    }

    @Test
    fun `several edits at once are all reported`() {
        val edited = deployed.copy(
            bedtime = emptyMap(),
            blockedDomains = setOf("x.com"),
            appPolicies = deployed.appPolicies + ("com.tiktok" to AppPolicyDto()),
        )
        assertEquals(
            setOf(PolicyDiff.BEDTIME, PolicyDiff.WEB_FILTER, PolicyDiff.appKey("com.tiktok")),
            PolicyDiff.changedKeys(deployed, edited),
        )
    }

    @Test
    fun `a value changed and changed back is not pending`() {
        // The whole reason this is a diff against what was deployed rather than a log of edits.
        val there = deployed.copy(defaultAppBudget = mapOf("WEEKDAY" to 90))
        val andBack = there.copy(defaultAppBudget = mapOf("WEEKDAY" to 60))
        assertTrue(PolicyDiff.changedKeys(deployed, andBack).isEmpty())
    }
}
