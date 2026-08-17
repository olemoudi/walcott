package dev.walcott.data

import dev.walcott.enforcement.DeviceRestrictions
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
