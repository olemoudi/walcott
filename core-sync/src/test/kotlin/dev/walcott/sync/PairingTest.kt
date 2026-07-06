package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingTest {

    @Test
    fun `pairing payload round-trips`() {
        val payload = PairingPayload(
            topic = "walcott-abc123",
            familyKeyB64 = FamilyCrypto.toB64(FamilyCrypto.generateFamilyKey().encoded),
            parentPublicKeyB64 = FamilyCrypto.toB64(FamilyCrypto.generateSigningKeyPair().public.encoded),
        )
        val decoded = PairingPayload.decode(payload.encode())
        assertEquals(payload, decoded)
    }

    @Test
    fun `encoded payload carries the walcott prefix`() {
        val payload = PairingPayload("t", "k", "p")
        assertTrue(payload.encode().startsWith("walcott1:"))
    }

    @Test
    fun `decoding a non-walcott string returns null`() {
        assertNull(PairingPayload.decode("https://example.com"))
        assertNull(PairingPayload.decode("walcott1:not-base64!!"))
    }

    @Test
    fun `end-to-end - a child paired from the QR can read parent messages`() {
        // Parent creates identity.
        val familyKey = FamilyCrypto.generateFamilyKey()
        val parentKeys = FamilyCrypto.generateSigningKeyPair()
        val qr = PairingPayload(
            topic = "walcott-xyz",
            familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
            parentPublicKeyB64 = FamilyCrypto.toB64(parentKeys.public.encoded),
        ).encode()

        // Child scans the QR and reconstructs the keys.
        val paired = PairingPayload.decode(qr)!!
        val childFamilyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(paired.familyKeyB64))
        val childParentPublic = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(paired.parentPublicKeyB64))

        // Parent sends a config snapshot; child decodes and verifies it.
        val wire = SyncProtocol.encodeParent(ParentSnapshot(1, """{"version":1}"""), familyKey, parentKeys.private)
        val decoded = SyncProtocol.decode(wire, childFamilyKey, childParentPublic)
        assertTrue(decoded is IncomingMessage.FromParent)
    }
}
