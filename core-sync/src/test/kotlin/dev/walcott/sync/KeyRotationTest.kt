package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KeyRotationTest {

    private val oldPair = FamilyCrypto.generateSigningKeyPair()
    private val newPair = FamilyCrypto.generateSigningKeyPair()

    @Test
    fun `a cert minted by the trusted key attests the new key`() {
        val cert = KeyRotation.create(newPair.public, oldPair.private)
        val attested = KeyRotation.verify(cert, oldPair.public)
        assertNotNull(attested)
        assertEquals(newPair.public, attested)
    }

    @Test
    fun `a cert minted by some other key is rejected`() {
        val impostor = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(newPair.public, impostor.private)
        assertNull(KeyRotation.verify(cert, oldPair.public))
    }

    @Test
    fun `a cert whose key was swapped after signing is rejected`() {
        val cert = KeyRotation.create(newPair.public, oldPair.private)
        val swapped = cert.copy(
            newPublicKeyB64 = FamilyCrypto.toB64(FamilyCrypto.generateSigningKeyPair().public.encoded),
        )
        assertNull(KeyRotation.verify(swapped, oldPair.public))
    }

    @Test
    fun `garbage certs are rejected, not thrown`() {
        assertNull(KeyRotation.verify(RotationCert("not-a-key", "not-a-sig"), oldPair.public))
        assertNull(KeyRotation.decode("not json"))
    }

    @Test
    fun `certs survive their storage encoding`() {
        val cert = KeyRotation.create(newPair.public, oldPair.private)
        val decoded = KeyRotation.decode(KeyRotation.encode(cert))
        assertEquals(cert, decoded)
        assertEquals(newPair.public, KeyRotation.verify(decoded!!, oldPair.public))
    }
}
