package dev.walcott.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateInfoTest {

    @Test
    fun `parses a valid version json`() {
        val info = UpdateInfo.parse("""{"versionCode": 5, "versionName": "0.2.0", "apk": "https://x/walcott.apk"}""")
        assertEquals(5, info?.versionCode)
        assertEquals("0.2.0", info?.versionName)
        assertEquals("https://x/walcott.apk", info?.apk)
    }

    @Test
    fun `tolerates unknown fields`() {
        val info = UpdateInfo.parse("""{"versionCode": 2, "future": true}""")
        assertEquals(2, info?.versionCode)
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(UpdateInfo.parse("not json"))
        assertNull(UpdateInfo.parse(""))
    }

    @Test
    fun `isNewerThan is true only for a higher version code with an apk`() {
        val info = UpdateInfo(versionCode = 3, apk = "https://x/a.apk")
        assertTrue(info.isNewerThan(2))
        assertFalse(info.isNewerThan(3)) // same version
        assertFalse(info.isNewerThan(4)) // older
    }

    @Test
    fun `isNewerThan is false without an apk url`() {
        assertFalse(UpdateInfo(versionCode = 9, apk = "").isNewerThan(1))
    }
}
