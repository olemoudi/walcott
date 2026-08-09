package dev.walcott.sync

import dev.walcott.data.PolicySettings
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Where the readable parent PIN is allowed to exist, pinned as a test because the whole
 * security property is a matter of which object it sits in.
 *
 * [FamilyIdentity.pinPlain] is device-local and never published; [PolicySettings] is the
 * family's brain and is decrypted and stored on every child's phone. Moving the field, or
 * teaching some future payload to carry the identity along, would quietly put the PIN in the
 * clear on the device it exists to keep out — and nothing else in the app would complain.
 */
class PinPlaintextScopeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `the policy that travels to the children carries no plaintext PIN`() {
        // What children legitimately receive: the material to verify a PIN offline, never the PIN.
        val settings = PolicySettings(pinHash = "hashed", pinSalt = "salted")
        val wire = json.encodeToString(PolicySettings.serializer(), settings)
        assertFalse(wire.contains("pinPlain"), "PolicySettings must not carry a readable PIN")
        assertFalse(wire.contains("1234"))
    }

    @Test
    fun `the family backup carries the policy, so it carries no plaintext PIN either`() {
        // The backup is the policy plus the keys. It is passphrase-sealed, but it is also a file
        // a parent mails to themselves — the readable copy has no business travelling in it.
        val payload = FamilyBackupPayload(
            topic = "t",
            ntfyServer = "https://ntfy.sh",
            familyKeyB64 = "k",
            signingPublicKeyB64 = "pub",
            signingPrivateKeyB64 = "priv",
            policyJson = json.encodeToString(
                PolicySettings.serializer(), PolicySettings(pinHash = "hashed", pinSalt = "salted"),
            ),
        )
        val encoded = json.encodeToString(FamilyBackupPayload.serializer(), payload)
        assertFalse(encoded.contains("pinPlain"))
    }

    @Test
    fun `the readable copy survives a restart, or the reminder would be useless`() {
        val stored = json.encodeToString(
            FamilyIdentity.serializer(),
            FamilyIdentity(role = Role.PARENT, mode = DeviceMode.PARENT, pinPlain = "4291"),
        )
        assertEquals("4291", json.decodeFromString(FamilyIdentity.serializer(), stored).pinPlain)
    }

    @Test
    fun `an identity written before the field existed still reads`() {
        // Every install that updates into this build has one of these on disk.
        val legacy = """{"role":"PARENT","mode":"PARENT","deviceId":"parent"}"""
        assertEquals("", json.decodeFromString(FamilyIdentity.serializer(), legacy).pinPlain)
    }
}
