package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceRestrictionsTest {

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
}
