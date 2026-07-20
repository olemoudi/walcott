package dev.walcott.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptography for the sync bus. Pure JCA so it stays JVM-testable.
 *
 * - Confidentiality: AES-256-GCM with the shared family key (everyone paired has it).
 * - Authenticity of parent-authored messages: ECDSA over P-256. The parent holds the
 *   private key (Android Keystore in the app); paired devices only get the public key, so a
 *   child that extracts the family key can read messages but cannot forge parent approvals.
 *
 * We use ECDSA (not Ed25519) because Ed25519 via JCA needs API 33; EC works on minSdk 29.
 */
object FamilyCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private val random = SecureRandom()

    // --- Symmetric family key (AES-256) ---

    fun generateFamilyKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun familyKeyFromBytes(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    /** AES-GCM: output is [12-byte IV || ciphertext+tag]. */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    /** Reverses [encrypt]; throws (AEAD tag mismatch) if tampered or wrong key. */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    // --- Asymmetric signing (parent identity) ---

    fun generateSigningKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

    fun publicKeyFromBytes(x509: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))

    /** Rehydrates a software signing key exported with [java.security.Key.getEncoded] (PKCS#8). */
    fun privateKeyFromBytes(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))

    // --- Base64 helpers (URL-safe, no padding, for compact QR payloads) ---

    fun toB64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun fromB64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
