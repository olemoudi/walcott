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
    fun `feature keys are unique`() {
        val allKeys = DeviceRestrictions.FEATURES.map { it.key }
        assertEquals(allKeys.size, allKeys.toSet().size)
        assertTrue(DeviceRestrictions.FEATURES.isNotEmpty())
    }
}
