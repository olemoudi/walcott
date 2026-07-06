package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FamilyCryptoTest {

    @Test
    fun `AES-GCM round-trips`() {
        val key = FamilyCrypto.generateFamilyKey()
        val message = "hola familia".toByteArray()
        val blob = FamilyCrypto.encrypt(key, message)
        assertArrayEquals(message, FamilyCrypto.decrypt(key, blob))
    }

    @Test
    fun `encryption is non-deterministic (random IV)`() {
        val key = FamilyCrypto.generateFamilyKey()
        val a = FamilyCrypto.encrypt(key, "same".toByteArray())
        val b = FamilyCrypto.encrypt(key, "same".toByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `decrypt with the wrong key fails`() {
        val blob = FamilyCrypto.encrypt(FamilyCrypto.generateFamilyKey(), "secret".toByteArray())
        val otherKey = FamilyCrypto.generateFamilyKey()
        assertThrows(Exception::class.java) { FamilyCrypto.decrypt(otherKey, blob) }
    }

    @Test
    fun `tampered ciphertext fails the AEAD tag`() {
        val key = FamilyCrypto.generateFamilyKey()
        val blob = FamilyCrypto.encrypt(key, "secret".toByteArray()).copyOf()
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertThrows(Exception::class.java) { FamilyCrypto.decrypt(key, blob) }
    }

    @Test
    fun `family key survives a bytes round-trip`() {
        val key = FamilyCrypto.generateFamilyKey()
        val restored = FamilyCrypto.familyKeyFromBytes(key.encoded)
        val blob = FamilyCrypto.encrypt(key, "x".toByteArray())
        assertArrayEquals("x".toByteArray(), FamilyCrypto.decrypt(restored, blob))
    }

    @Test
    fun `ECDSA sign then verify succeeds`() {
        val pair = FamilyCrypto.generateSigningKeyPair()
        val data = "config v3".toByteArray()
        val sig = FamilyCrypto.sign(pair.private, data)
        assertTrue(FamilyCrypto.verify(pair.public, data, sig))
    }

    @Test
    fun `verify fails for tampered data`() {
        val pair = FamilyCrypto.generateSigningKeyPair()
        val sig = FamilyCrypto.sign(pair.private, "approve request 7".toByteArray())
        assertFalse(FamilyCrypto.verify(pair.public, "approve request 8".toByteArray(), sig))
    }

    @Test
    fun `verify fails with a different public key (cannot forge)`() {
        val parent = FamilyCrypto.generateSigningKeyPair()
        val impostor = FamilyCrypto.generateSigningKeyPair()
        val sig = FamilyCrypto.sign(impostor.private, "data".toByteArray())
        assertFalse(FamilyCrypto.verify(parent.public, "data".toByteArray(), sig))
    }

    @Test
    fun `public key survives an X509 bytes round-trip`() {
        val pair = FamilyCrypto.generateSigningKeyPair()
        val restored = FamilyCrypto.publicKeyFromBytes(pair.public.encoded)
        val sig = FamilyCrypto.sign(pair.private, "d".toByteArray())
        assertTrue(FamilyCrypto.verify(restored, "d".toByteArray(), sig))
    }

    @Test
    fun `base64 url round-trips`() {
        val bytes = ByteArray(33) { it.toByte() }
        assertArrayEquals(bytes, FamilyCrypto.fromB64(FamilyCrypto.toB64(bytes)))
        assertNotEquals("", FamilyCrypto.toB64(bytes))
    }
}
