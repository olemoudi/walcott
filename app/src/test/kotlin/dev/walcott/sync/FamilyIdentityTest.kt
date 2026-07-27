package dev.walcott.sync

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FamilyIdentityTest {

    @Test
    fun `explicit mode wins over role`() {
        val identity = FamilyIdentity(role = Role.PARENT, mode = DeviceMode.CHILD)
        assertEquals(DeviceMode.CHILD, identity.effectiveMode)
    }

    @Test
    fun `unset mode derives from role for existing installs`() {
        assertEquals(DeviceMode.PARENT, FamilyIdentity(role = Role.PARENT).effectiveMode)
        assertEquals(DeviceMode.CHILD, FamilyIdentity(role = Role.CHILD).effectiveMode)
        assertEquals(DeviceMode.UNSET, FamilyIdentity(role = Role.UNPAIRED).effectiveMode)
    }

    @Test
    fun `legacy identity JSON without mode or childId decodes to defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            FamilyIdentity.serializer(),
            """{"role":"CHILD","deviceId":"d1","displayName":"Pixel","topic":"t","familyKeyB64":"k","parentPublicKeyB64":"p"}""",
        )
        assertEquals(DeviceMode.UNSET, decoded.mode)
        assertEquals("", decoded.childId)
        assertEquals(DeviceMode.CHILD, decoded.effectiveMode)
    }

    @Test
    fun `only parent mode disables local enforcement`() {
        assertEquals(false, FamilyIdentity(mode = DeviceMode.PARENT).enforcesLocally)
        assertEquals(false, FamilyIdentity(role = Role.PARENT).enforcesLocally)
        assertEquals(true, FamilyIdentity(mode = DeviceMode.CHILD).enforcesLocally)
        assertEquals(true, FamilyIdentity().enforcesLocally)
    }

    @Test
    fun `a released device never enforces again`() {
        // The emergency release wipes the identity, and a wiped identity is UNSET — which
        // enforces. Without this flag the boot receiver and watchdog would start enforcing an
        // empty policy, i.e. block every app on a device that was just handed back.
        assertEquals(false, FamilyIdentity(released = true).enforcesLocally)
        assertEquals(false, FamilyIdentity(mode = DeviceMode.CHILD, released = true).enforcesLocally)
        // Pairing again builds a fresh identity, so the flag can't outlive a re-enrollment.
        assertEquals(true, FamilyIdentity(mode = DeviceMode.CHILD).enforcesLocally)
    }

    @Test
    fun `serialization round-trips with new fields`() {
        val json = Json { encodeDefaults = true }
        val identity = FamilyIdentity(
            role = Role.CHILD,
            mode = DeviceMode.CHILD,
            deviceId = "d1",
            displayName = "Ana",
            childId = "c1",
            topic = "t",
            familyKeyB64 = "k",
            parentPublicKeyB64 = "p",
        )
        val decoded = json.decodeFromString(FamilyIdentity.serializer(), json.encodeToString(FamilyIdentity.serializer(), identity))
        assertEquals(identity, decoded)
    }
}
