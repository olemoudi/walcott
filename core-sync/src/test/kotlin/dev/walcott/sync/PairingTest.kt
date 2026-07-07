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
    fun `per-child fields round-trip`() {
        val payload = PairingPayload("t", "k", "p", childId = "c1", childName = "Ana", familyName = "Moudis")
        assertEquals(payload, PairingPayload.decode(payload.encode()))
    }

    @Test
    fun `a legacy QR without per-child fields decodes with blank defaults`() {
        // Encoded exactly as the old app did: only the four original fields.
        val legacyJson = """{"topic":"t","familyKeyB64":"k","parentPublicKeyB64":"p","ntfyServer":"https://ntfy.sh"}"""
        val legacyQr = "walcott1:" + FamilyCrypto.toB64(legacyJson.toByteArray())
        val decoded = PairingPayload.decode(legacyQr)!!
        assertEquals("", decoded.childId)
        assertEquals("", decoded.childName)
        assertEquals("", decoded.familyName)
        assertEquals("t", decoded.topic)
    }

    @Test
    fun `decoding tolerates unknown future fields`() {
        val futureJson = """{"topic":"t","familyKeyB64":"k","parentPublicKeyB64":"p","futureField":42}"""
        val futureQr = "walcott1:" + FamilyCrypto.toB64(futureJson.toByteArray())
        assertEquals("t", PairingPayload.decode(futureQr)?.topic)
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
