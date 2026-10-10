package dev.walcott.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which phones get the "let Walcott start by itself" step (see [OemPowerSettings.makerOf]). */
class OemPowerSettingsTest {

    @Test
    fun `sub-brands are recognised by either build field`() {
        assertEquals(OemPowerSettings.Maker.XIAOMI, OemPowerSettings.makerOf("Xiaomi", "POCO"))
        assertEquals(OemPowerSettings.Maker.XIAOMI, OemPowerSettings.makerOf("Xiaomi", "Redmi"))
        assertEquals(OemPowerSettings.Maker.HUAWEI, OemPowerSettings.makerOf("HONOR", "HONOR"))
        assertEquals(OemPowerSettings.Maker.OPPO, OemPowerSettings.makerOf("OnePlus", "OnePlus"))
        assertEquals(OemPowerSettings.Maker.OPPO, OemPowerSettings.makerOf("realme", "realme"))
        assertEquals(OemPowerSettings.Maker.VIVO, OemPowerSettings.makerOf("vivo", "iQOO"))
        assertEquals(OemPowerSettings.Maker.SAMSUNG, OemPowerSettings.makerOf("samsung", "samsung"))
    }

    @Test
    fun `phones that behave like stock Android are not asked`() {
        assertNull(OemPowerSettings.makerOf("Google", "google"))
        assertNull(OemPowerSettings.makerOf("motorola", "motorola"))
        assertNull(OemPowerSettings.makerOf("HMD Global", "Nokia"))
        assertNull(OemPowerSettings.makerOf("unknown", "generic"))
    }
}
