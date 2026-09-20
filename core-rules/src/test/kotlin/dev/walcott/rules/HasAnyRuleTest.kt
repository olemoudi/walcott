package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime

/**
 * "Could any rule here ever stop an app opening?" — asked by two screens that must not answer it
 * differently: the parent's detail page, deciding whether to draw "what is stopping them right
 * now", and the guided setup on the phone itself, deciding whether to ask a person to switch on
 * the accessibility blocker (see `DeviceSetup.applicable`).
 *
 * The second one is why each kind of rule is named separately below. A phone that answers "no"
 * is never asked for the most invasive permission this app has, so a rule this misses is a phone
 * with a bedtime and nothing to enforce it.
 */
class HasAnyRuleTest {

    private val night = TimeWindow(LocalTime.of(21, 0), LocalTime.of(7, 0))

    @Test
    fun `a phone with nothing set has no rule`() {
        // The ordinary state of an adult being helped.
        assertFalse(FamilyConfig(version = 1).hasAnyRule)
    }

    @Test
    fun `a bedtime is a rule`() {
        assertTrue(FamilyConfig(version = 1, bedtime = mapOf(DayType.SCHOOL to night)).hasAnyRule)
    }

    @Test
    fun `a screen-free window is a rule, and an empty list of them is not`() {
        assertTrue(
            FamilyConfig(
                version = 1,
                blockedWindows = mapOf(DayType.SCHOOL to listOf(night)),
            ).hasAnyRule,
        )
        // A day type left with no windows is how a parent turns them off, not a rule.
        assertFalse(
            FamilyConfig(version = 1, blockedWindows = mapOf(DayType.SCHOOL to emptyList())).hasAnyRule,
        )
    }

    @Test
    fun `the family default limit is a rule`() {
        assertTrue(
            FamilyConfig(
                version = 1,
                defaultAppBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)),
            ).hasAnyRule,
        )
    }

    @Test
    fun `the day's total is a rule too`() {
        // The one the parent's own copy of this question used to miss: a phone with no bedtime and
        // no per-app limits, and two hours for the whole day, is a phone that stops.
        assertTrue(
            FamilyConfig(
                version = 1,
                dailyScreenBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(120)),
            ).hasAnyRule,
        )
    }

    @Test
    fun `an app's own budget or window is a rule, and an app policy with neither is not`() {
        assertTrue(
            FamilyConfig(
                version = 1,
                perAppPolicies = mapOf("com.game" to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)))),
            ).hasAnyRule,
        )
        assertTrue(
            FamilyConfig(
                version = 1,
                perAppPolicies = mapOf("com.game" to AppPolicy(blockedWindows = mapOf(DayType.SCHOOL to listOf(night)))),
            ).hasAnyRule,
        )
        // "Unlimited" is a policy that says this app answers to nothing — the opposite of a rule.
        assertFalse(
            FamilyConfig(version = 1, perAppPolicies = mapOf("com.game" to AppPolicy(unlimited = true))).hasAnyRule,
        )
        assertFalse(
            FamilyConfig(
                version = 1,
                perAppPolicies = mapOf("com.game" to AppPolicy(blockedWindows = mapOf(DayType.SCHOOL to emptyList()))),
            ).hasAnyRule,
        )
    }

    @Test
    fun `domain blocks and device locks are not rules about apps`() {
        // Deliberately outside it: an adult being helped normally has both, and neither can stop
        // an app opening. Counting them would put the blocker's permission in front of every
        // assisted phone, which is exactly what this question exists to avoid.
        assertFalse(
            FamilyConfig(version = 1, essentialPackages = setOf("com.android.dialer")).hasAnyRule,
        )
    }
}
