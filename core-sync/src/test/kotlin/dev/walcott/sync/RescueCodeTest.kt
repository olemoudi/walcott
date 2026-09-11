package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The six digits that open a phone with no network (see [RescueCode]).
 *
 * Everything about this is decided here rather than on a device: it is a credential, it runs on
 * the phone of the person it exists to keep out, and the three properties that make it safe —
 * single use, no mining by moving the clock, worthless to guess — are each one line that would
 * look optional to anybody reading the code without these tests beside it.
 */
class RescueCodeTest {

    private val key = FamilyCrypto.generateFamilyKey()
    private val other = FamilyCrypto.generateFamilyKey()

    /** A Monday morning, and the slot it falls in. */
    private val now = 1_772_000_000_000L
    private val slot = RescueCode.slotOf(now)

    private fun code(action: String = RescueCode.ACTION_OPEN_1H, at: Long = slot) =
        RescueCode.codeFor(key, action, at)

    @Test
    fun `a code is six digits, leading zeros and all`() {
        // Read out loud and typed into a fixed-width field: "1234" for a code that happens to
        // start with two zeros is a code nobody can enter.
        val codes = (0..200L).map { code(at = slot + it) }
        assertTrue(codes.all { it.length == RescueCode.DIGITS }, "not all six digits: $codes")
        assertTrue(codes.all { c -> c.all { it.isDigit() } })
    }

    @Test
    fun `the parent's code opens the child's phone`() {
        val accepted = RescueCode.verify(key, code(), now, lastUsedSlot = Long.MIN_VALUE)
        assertEquals(RescueCode.ACTION_OPEN_1H, accepted?.action)
        assertEquals(slot, accepted?.slot)
        assertEquals(60, RescueCode.grantMinutes(accepted!!.action))
    }

    @Test
    fun `each action has its own code, and the code says which`() {
        val one = code(RescueCode.ACTION_OPEN_1H)
        val three = code(RescueCode.ACTION_OPEN_3H)
        val install = code(RescueCode.ACTION_INSTALL)
        assertNotEquals(one, three)
        assertNotEquals(one, install)
        assertEquals(
            RescueCode.ACTION_OPEN_3H,
            RescueCode.verify(key, three, now, Long.MIN_VALUE)?.action,
        )
        assertEquals(
            RescueCode.ACTION_INSTALL,
            RescueCode.verify(key, install, now, Long.MIN_VALUE)?.action,
        )
    }

    @Test
    fun `another family's code is worth nothing`() {
        val theirs = RescueCode.codeFor(other, RescueCode.ACTION_OPEN_1H, slot)
        assertNull(RescueCode.verify(key, theirs, now, Long.MIN_VALUE))
    }

    @Test
    fun `a code is spent once, even inside the half hour it is still valid`() {
        // The property that makes this safe to say out loud in a room: overheard, photographed
        // or remembered, it is worth nothing a second time.
        val accepted = RescueCode.verify(key, code(), now, Long.MIN_VALUE)!!
        assertNull(
            RescueCode.verify(key, code(), now + 60_000, lastUsedSlot = accepted.slot),
            "the same code opened the phone twice",
        )
    }

    @Test
    fun `the neighbouring slots are accepted, and nothing further`() {
        // A code read out at 10:29 is typed at 10:31, and two phones never agree about the
        // minute. Beyond that it stops: a code from this morning must not still work tonight.
        val slotMs = RescueCode.SLOT_MINUTES * 60_000L
        assertTrue(RescueCode.verify(key, code(), now + slotMs, Long.MIN_VALUE) != null)
        assertTrue(RescueCode.verify(key, code(), now - slotMs, Long.MIN_VALUE) != null)
        assertNull(RescueCode.verify(key, code(), now + 2 * slotMs, Long.MIN_VALUE))
        assertNull(RescueCode.verify(key, code(), now - 2 * slotMs, Long.MIN_VALUE))
    }

    @Test
    fun `a clock moved BACK buys nothing`() {
        // Mining, the obvious attack: spend today's code, then wind the clock back and spend it
        // again. The device remembers the newest slot it accepted, so everything behind that is
        // already spent — whatever the clock now says.
        val used = RescueCode.verify(key, code(), now, Long.MIN_VALUE)!!.slot
        val yesterday = now - 24 * 60 * 60_000L
        for (back in -1L..1L) {
            assertNull(
                RescueCode.verify(key, code(at = RescueCode.slotOf(yesterday) + back), yesterday, used),
                "a code from a slot before the last one used was accepted",
            )
        }
    }

    @Test
    fun `a clock moved FORWARD only reaches codes nobody has read out`() {
        // The mirror image, and it needs no defence beyond arithmetic: jumping the clock forward
        // gets you a slot whose code you would still have to know, and the only person who can
        // read it out is the parent.
        val tomorrow = now + 24 * 60 * 60_000L
        assertNull(RescueCode.verify(key, code(), tomorrow, Long.MIN_VALUE))
        // And the parent's code for THAT slot works, which is what makes this a clock question
        // and not a broken feature: a child abroad has a clock hours away from the parent's.
        assertEquals(
            RescueCode.ACTION_OPEN_1H,
            RescueCode.verify(key, code(at = RescueCode.slotOf(tomorrow)), tomorrow, Long.MIN_VALUE)?.action,
        )
    }

    @Test
    fun `spaces are forgiven and everything else is not`() {
        assertTrue(RescueCode.verify(key, " ${code()} ", now, Long.MIN_VALUE) != null)
        assertNull(RescueCode.verify(key, "", now, Long.MIN_VALUE))
        assertNull(RescueCode.verify(key, "12345", now, Long.MIN_VALUE))
        assertNull(RescueCode.verify(key, "1234567", now, Long.MIN_VALUE))
        assertNull(RescueCode.verify(key, "abcdef", now, Long.MIN_VALUE))
    }

    @Test
    fun `a slot knows when it ends, so a screen can count down to it`() {
        val ends = RescueCode.slotEndsAtMs(slot)
        assertTrue(ends > now, "the current slot should end in the future")
        assertTrue(ends - now <= RescueCode.SLOT_MINUTES * 60_000L)
        assertEquals(slot + 1, RescueCode.slotOf(ends))
    }

    @Test
    fun `an action this build does not know buys no time at all`() {
        // Forward compatibility, in the direction that matters: a newer parent naming an action
        // this child has never heard of must grant nothing, not a default hour.
        assertEquals(0, RescueCode.grantMinutes("open999"))
        assertTrue(!RescueCode.opensRules("open999"))
    }

    @Test
    fun `a code is for one phone, and a sibling's phone refuses it`() {
        // Every phone in a family holds the same key and keeps its own record of spent slots,
        // so a family-wide code read out for one child opened every sibling's phone in the same
        // half hour. Bound to the device, the code Ana was given is worth nothing to Leo.
        val ana = "device-ana"
        val leo = "device-leo"
        val forAna = RescueCode.codeFor(key, RescueCode.ACTION_OPEN_1H, slot, ana)
        assertNotEquals(forAna, RescueCode.codeFor(key, RescueCode.ACTION_OPEN_1H, slot, leo))
        assertNotEquals(forAna, RescueCode.codeFor(key, RescueCode.ACTION_OPEN_1H, slot))
        assertEquals(
            RescueCode.ACTION_OPEN_1H,
            RescueCode.verify(key, forAna, now, Long.MIN_VALUE, deviceId = ana)?.action,
        )
        assertNull(RescueCode.verify(key, forAna, now, Long.MIN_VALUE, deviceId = leo))
        assertNull(RescueCode.verify(key, forAna, now, Long.MIN_VALUE))
    }

    @Test
    fun `a child too old to bind still answers to the family-wide code`() {
        assertTrue(RescueCode.bindsToDevice(RescueCode.PER_DEVICE_MIN_CHILD_VERSION))
        assertTrue(!RescueCode.bindsToDevice(RescueCode.PER_DEVICE_MIN_CHILD_VERSION - 1))
        assertTrue(!RescueCode.bindsToDevice(0))
        // And the family-wide code is unchanged, so the parent's screen can still show it.
        assertEquals(code(), RescueCode.codeFor(key, RescueCode.ACTION_OPEN_1H, slot, ""))
    }
}
