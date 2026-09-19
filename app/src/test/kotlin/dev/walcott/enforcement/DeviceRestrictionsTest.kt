package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeviceRestrictionsTest {

    @Test
    fun `a screen timeout shorter than a minute is raised before it is locked`() {
        // A phone enrolled at fifteen seconds would otherwise go dark mid-sentence for good.
        org.junit.jupiter.api.Assertions.assertEquals(
            DeviceRestrictions.MIN_LOCKED_SCREEN_TIMEOUT_MS,
            DeviceRestrictions.lockedScreenTimeoutFloor(15_000),
        )
    }

    @Test
    fun `a minute or longer is somebody's choice and stays`() {
        org.junit.jupiter.api.Assertions.assertNull(DeviceRestrictions.lockedScreenTimeoutFloor(60_000))
        org.junit.jupiter.api.Assertions.assertNull(DeviceRestrictions.lockedScreenTimeoutFloor(10 * 60_000))
        org.junit.jupiter.api.Assertions.assertNull(DeviceRestrictions.lockedScreenTimeoutFloor(Int.MAX_VALUE))
        // An unreadable value is not a short one.
        org.junit.jupiter.api.Assertions.assertNull(DeviceRestrictions.lockedScreenTimeoutFloor(0))
    }

    private val keys = setOf(DeviceRestrictions.KEY_INSTALLS, DeviceRestrictions.KEY_VPN)

    @Test
    fun `an open exemption window lifts only the install block`() {
        val effective = DeviceRestrictions.effectiveKeys(keys, installExemptUntilMs = 1_000, nowMs = 500)
        assertEquals(setOf(DeviceRestrictions.KEY_VPN), effective)
    }

    @Test
    fun `an expired or absent exemption changes nothing`() {
        assertEquals(keys, DeviceRestrictions.effectiveKeys(keys, installExemptUntilMs = 1_000, nowMs = 1_000))
        assertEquals(keys, DeviceRestrictions.effectiveKeys(keys, installExemptUntilMs = 0, nowMs = 500))
    }

    @Test
    fun `protecting the filter locks private DNS too`() {
        // The DNS filter is a tun that only routes the sentinel resolver, so "Private DNS: a
        // hostname I typed" sends every lookup out over TLS to somebody else and the filter
        // — and the bedtime curfew built on it — sees nothing. Locking the VPN and leaving
        // that reachable is a lock with the window open.
        val vpn = DeviceRestrictions.FEATURES.first { it.key == DeviceRestrictions.KEY_VPN }
        assertTrue(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS in vpn.restrictions)
        assertTrue(android.os.UserManager.DISALLOW_CONFIG_VPN in vpn.restrictions)
    }

    @Test
    fun `feature keys are unique`() {
        val allKeys = DeviceRestrictions.FEATURES.map { it.key }
        assertEquals(allKeys.size, allKeys.toSet().size)
        assertTrue(DeviceRestrictions.FEATURES.isNotEmpty())
    }

    @Test
    fun `a child starts with the three doors closed, and an adult being helped does not`() {
        // A guest user is a phone where this app does not exist; adb and safe mode are the two
        // ways round every rule the phone enforces. All three are on for a child from the start,
        // and seeded once more into families that predate them (see RECOMMENDED_SINCE_107).
        for (key in listOf(DeviceRestrictions.KEY_ADD_USER, DeviceRestrictions.KEY_DEBUGGING, DeviceRestrictions.KEY_SAFE_BOOT)) {
            assertTrue(key in DeviceRestrictions.RECOMMENDED_DEFAULTS, key)
            assertTrue(key in DeviceRestrictions.RECOMMENDED_SINCE_107, key)
        }
        // An adult keeps developer options and safe mode: those are theirs. A second user is
        // still a setting nobody changes on purpose.
        assertTrue(DeviceRestrictions.KEY_ADD_USER in DeviceRestrictions.RECOMMENDED_FOR_ADULT)
        assertTrue(DeviceRestrictions.KEY_DEBUGGING !in DeviceRestrictions.RECOMMENDED_FOR_ADULT)
        assertTrue(DeviceRestrictions.KEY_SAFE_BOOT !in DeviceRestrictions.RECOMMENDED_FOR_ADULT)
    }

    @Test
    fun `blocking a second user blocks switching to one as well`() {
        val users = DeviceRestrictions.FEATURES.first { it.key == DeviceRestrictions.KEY_ADD_USER }
        assertTrue(android.os.UserManager.DISALLOW_ADD_USER in users.restrictions)
        assertTrue(android.os.UserManager.DISALLOW_USER_SWITCH in users.restrictions)
        assertEquals(
            listOf(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES),
            DeviceRestrictions.FEATURES.first { it.key == DeviceRestrictions.KEY_DEBUGGING }.restrictions,
        )
        assertEquals(
            listOf(android.os.UserManager.DISALLOW_SAFE_BOOT),
            DeviceRestrictions.FEATURES.first { it.key == DeviceRestrictions.KEY_SAFE_BOOT }.restrictions,
        )
    }

    @Test
    fun `a dark manual screen is raised before its brightness is locked, and nothing else is touched`() {
        // Locking freezes the value, so the floor is the difference between "a screen that stays
        // readable" and "a screen that stays black". An adaptive screen is the phone choosing, and
        // a brighter one is somebody's preference: neither is corrected.
        assertEquals(
            DeviceRestrictions.MIN_LOCKED_BRIGHTNESS,
            DeviceRestrictions.lockedBrightnessFloor(manual = true, current = 1),
        )
        assertNull(
            DeviceRestrictions.lockedBrightnessFloor(manual = true, current = DeviceRestrictions.MIN_LOCKED_BRIGHTNESS),
        )
        assertNull(DeviceRestrictions.lockedBrightnessFloor(manual = true, current = 200))
        assertNull(DeviceRestrictions.lockedBrightnessFloor(manual = false, current = 0))
    }

    @Test
    fun `locking the mobile settings is held back only when it would strand the phone`() {
        // Measured on API 35: with DISALLOW_CONFIG_MOBILE_NETWORKS in force the mobile network
        // screen does not open at all, and nothing a device owner can call switches mobile data
        // back on. So a phone whose data is already off would be locked out of the network for
        // good — by the switch whose own description is "with mobile data switched off, the phone
        // goes quiet the moment it leaves home".
        assertTrue(DeviceRestrictions.locksOutOfMobileData(simReady = true, mobileDataEnabled = false))

        // Data on: locking it keeps it that way, which is the whole point.
        assertFalse(DeviceRestrictions.locksOutOfMobileData(simReady = true, mobileDataEnabled = true))

        // No SIM: nothing to strand, and a phone that is Wi-Fi only must not spend the rest of its
        // life telling the family about a protection that has nothing to protect.
        assertFalse(DeviceRestrictions.locksOutOfMobileData(simReady = false, mobileDataEnabled = false))
        assertFalse(DeviceRestrictions.locksOutOfMobileData(simReady = false, mobileDataEnabled = true))
    }
}
