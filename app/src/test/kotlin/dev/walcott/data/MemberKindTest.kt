package dev.walcott.data

import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.rules.DayType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who a member is, and the one promise the two kinds make: the kind decides DEFAULTS, never
 * availability (see [MemberKind]).
 *
 * That promise is the whole reason this is a field on the entry rather than a fork in the product.
 * If it ever stops holding — if some option becomes reachable only for a child, or a rule becomes
 * unreachable for an adult — every screen that says "you can switch this on for anybody" starts
 * lying, and there is no way to see that by looking at one of them.
 */
class MemberKindTest {

    @Test
    fun `an entry with no kind at all is a child`() {
        // Every install predating this field, which is all of them: the registry travels as JSON
        // and an absent key must land on the shape those families already had.
        assertEquals(MemberKind.CHILD, ChildEntry(childId = "c1", name = "Ana").kind)
        assertFalse(ChildEntry(childId = "c1", name = "Ana").isAdult)
    }

    @Test
    fun `a kind from a newer build degrades to child rather than breaking`() {
        // Forward compatibility in the direction that actually happens: a member created on an
        // updated parent phone, read by a child device still on the old build.
        assertEquals(MemberKind.CHILD, MemberKind.of("carer"))
        assertEquals(MemberKind.CHILD, MemberKind.of(""))
        assertEquals(MemberKind.ADULT, MemberKind.of(MemberKind.ADULT))
        assertEquals(MemberKind.CHILD, MemberKind.of(MemberKind.CHILD))
    }

    @Test
    fun `the adult starting set is made of restrictions the app really offers`() {
        // A recommended default naming a key no Feature implements would be silently applied and
        // silently do nothing — a phone the family believes is protected and is not.
        val known = DeviceRestrictions.FEATURES.map { it.key }.toSet()
        val unknown = DeviceRestrictions.RECOMMENDED_FOR_ADULT - known
        assertTrue(unknown.isEmpty(), "adult defaults name restrictions that do not exist: $unknown")
    }

    @Test
    fun `the adult starting set covers the accidents that make a phone unreachable`() {
        // Named one by one on purpose. These are the failures the whole adult mode exists for, and
        // a well-meant trim of this set would quietly remove the reason it was built.
        for (key in listOf(
            DeviceRestrictions.KEY_AIRPLANE,
            DeviceRestrictions.KEY_LOCALE,
            DeviceRestrictions.KEY_BRIGHTNESS,
            DeviceRestrictions.KEY_MOBILE_NETWORKS,
            DeviceRestrictions.KEY_NETWORK_RESET,
        )) {
            assertTrue(key in DeviceRestrictions.RECOMMENDED_FOR_ADULT, "$key is not in the adult defaults")
        }
    }

    @Test
    fun `the adult starting set leaves Wi-Fi alone`() {
        // Deliberately NOT recommended: a phone that loses its Wi-Fi and cannot be reconnected has
        // no way back on its own, and the point of this mode is a phone that keeps working.
        assertFalse(
            DeviceRestrictions.KEY_WIFI in DeviceRestrictions.RECOMMENDED_FOR_ADULT,
            "blocking Wi-Fi config by default can strand the phone this mode exists to keep usable",
        )
    }

    /** A family that has written one of every rule, the way a family with children has. */
    private val familyWithRules = PolicySettings(
        bedtime = mapOf(DayType.SCHOOL.name to WindowDto(21 * 60, 7 * 60)),
        allAppsBlockedWindows = mapOf(DayType.SCHOOL.name to listOf(WindowDto(17 * 60, 18 * 60))),
        defaultAppBudget = mapOf(DayType.SCHOOL.name to 60),
        dailyScreenBudget = mapOf(DayType.SCHOOL.name to 120),
        appPolicies = mapOf("com.game" to AppPolicyDto(budgets = mapOf(DayType.SCHOOL.name to 30))),
        blockedDomains = setOf("casino.example"),
        domainAppRules = listOf(DomainAppRuleDto("ads.example", "com.game", allowOnlyFromApp = false)),
        deviceRestrictions = setOf(DeviceRestrictions.KEY_DEBUGGING),
    )

    private fun assertNoRules(member: PolicySettings) {
        assertTrue(member.bedtime.isEmpty(), "bedtime: ${member.bedtime}")
        assertTrue(member.allAppsBlockedWindows.isEmpty(), "screen-free: ${member.allAppsBlockedWindows}")
        assertTrue(member.defaultAppBudget.isEmpty(), "default limit: ${member.defaultAppBudget}")
        assertTrue(member.dailyScreenBudget.isEmpty(), "screen total: ${member.dailyScreenBudget}")
        assertTrue(member.appPolicies.isEmpty(), "app limits: ${member.appPolicies}")
        assertTrue(member.blockedDomains.isEmpty(), "blocked domains: ${member.blockedDomains}")
        assertTrue(member.domainAppRules.isEmpty(), "domain rules: ${member.domainAppRules}")
    }

    @Test
    fun `an adult added to a family with rules starts with none of them`() {
        // The promise the screen that creates them makes: "no bedtime and no limits". It used to be
        // kept only in families that had no rules to inherit.
        val adult = familyWithRules
            .withMember("a1", "Abuela", MemberKind.ADULT, addedAtMs = 1, trackingMinutes = 15)
            .resolveForChild("a1")
        assertNoRules(adult)
        assertEquals(0, adult.trackingIntervalMinutes, "an adult's location is not switched on for them")
        assertTrue(adult.keepRingerAudible)
        assertTrue(DeviceRestrictions.RECOMMENDED_FOR_ADULT.all { it in adult.deviceRestrictions })
        // Anti-tamper is not a rule about the person, and an adult's phone keeps the family's.
        assertTrue(DeviceRestrictions.KEY_DEBUGGING in adult.deviceRestrictions)
    }

    @Test
    fun `a child added to the same family inherits every rule`() {
        val settings = familyWithRules.withMember("c1", "Ana", MemberKind.CHILD, addedAtMs = 1, trackingMinutes = 15)
        val child = settings.resolveForChild("c1")
        assertEquals(familyWithRules.bedtime, child.bedtime)
        assertEquals(familyWithRules.appPolicies, child.appPolicies)
        assertEquals(familyWithRules.blockedDomains, child.blockedDomains)
        assertEquals(15, child.trackingIntervalMinutes)
        assertTrue(settings.children.single().overrides.copy(trackingIntervalMinutes = null).isEmpty)
    }

    @Test
    fun `adults enrolled before are taken off the family's rules, keeping what was set for them`() {
        // What 0.63 to 0.113 created: three overrides, and every rule left to inherit. One rule was
        // then set for this adult on purpose, and that one has to survive.
        val ownBedtime = mapOf(DayType.SCHOOL.name to WindowDto(23 * 60, 6 * 60))
        val before = familyWithRules.copy(
            children = listOf(
                ChildEntry(
                    "a1", "Abuela",
                    ChildOverrides(trackingIntervalMinutes = 0, keepRingerAudible = true, bedtime = ownBedtime),
                    kind = MemberKind.ADULT,
                ),
                ChildEntry("c1", "Ana"),
            ),
        )
        val after = before.separateAdultRules()

        val adult = after.resolveForChild("a1")
        assertEquals(ownBedtime, adult.bedtime)
        assertNoRules(adult.copy(bedtime = emptyMap()))
        // The child's rules are the family's, exactly as before (the registry itself is compared
        // out: it is the thing that changed, for the adult).
        assertEquals(
            before.resolveForChild("c1").copy(children = emptyList()),
            after.resolveForChild("c1").copy(children = emptyList(), adultRulesSeparated = false),
        )
        assertTrue(after.adultRulesSeparated)
    }

    @Test
    fun `the separation happens once, so an adult put back on the family's rules stays there`() {
        val separated = familyWithRules
            .copy(children = listOf(ChildEntry("a1", "Abuela", kind = MemberKind.ADULT)))
            .separateAdultRules()
        // The parent's "use the family's rules for everything" button, pressed on purpose.
        val backOnFamily = separated.copy(
            children = separated.children.map { it.copy(overrides = ChildOverrides()) },
        )
        assertEquals(familyWithRules.bedtime, backOnFamily.separateAdultRules().resolveForChild("a1").bedtime)
    }

    @Test
    fun `only a registered adult reads as a member being helped`() {
        val settings = PolicySettings(
            children = listOf(ChildEntry("a1", "Abuela", kind = MemberKind.ADULT), ChildEntry("c1", "Ana")),
        )
        assertTrue(settings.isAssistedMember("a1"))
        assertFalse(settings.isAssistedMember("c1"))
        assertFalse(settings.isAssistedMember("unknown"))
        assertFalse(settings.isAssistedMember(null))
    }

    @Test
    fun `every restriction belongs to a group, so none can go missing from the screen`() {
        // The protection screen is built from groups now. A Feature whose group nothing renders is
        // a lock nobody can ever turn off.
        val grouped = DeviceRestrictions.Group.entries.flatMap { group ->
            DeviceRestrictions.FEATURES.filter { it.group == group }.map { it.key }
        }
        assertEquals(
            DeviceRestrictions.FEATURES.map { it.key }.toSet(),
            grouped.toSet(),
            "some restriction is in no rendered group",
        )
    }
}
