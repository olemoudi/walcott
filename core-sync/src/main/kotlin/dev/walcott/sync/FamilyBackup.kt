package dev.walcott.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The parent's disaster-recovery file (TODO #1, option (a)): everything needed to resurrect
 * the family on a new phone without touching the children, encrypted under a passphrase the
 * parent chooses. The file is deliberately self-contained and format-versioned so a backup
 * taken today restores on a future build.
 *
 * Crypto: PBKDF2-HMAC-SHA256 (600k iterations, random 16-byte salt) stretches the passphrase
 * into an AES-256 key; the payload is gzipped and sealed with AES-GCM ([FamilyCrypto.encrypt]),
 * so a wrong passphrase or a tampered file fails authentication instead of yielding garbage.
 *
 * Every backup file is a standing full-takeover credential for the family (for a legacy
 * family, each one carries its own pre-signed rotation cert): old copies in mail threads or
 * cloud version history stay as sensitive as the newest one, protected only by their
 * passphrase. Hence the deliberately high [MIN_PASSPHRASE_CHARS].
 */
@Serializable
data class FamilyBackupPayload(
    val familyName: String = "",
    val topic: String,
    val ntfyServer: String,
    val familyKeyB64: String,
    /** The signing key the restored parent will use (children's trusted key, or a rotated one). */
    val signingPublicKeyB64: String,
    /** PKCS#8, base64url. */
    val signingPrivateKeyB64: String,
    /**
     * Present when [signingPublicKeyB64] is NOT the key children currently trust (legacy
     * family whose active key is locked in the Keystore): a [RotationCert] minted by that
     * key, which the restored parent attaches to envelopes so children adopt the new one.
     */
    val rotationCertB64: String = "",
    /** Full PolicySettings JSON: rules, children registry, PIN hash — the family's brain. */
    val policyJson: String,
    val parentVersion: Long = 0,
    val createdAtMs: Long = 0,
)

@Serializable
data class FamilyBackupFile(
    val format: String = FamilyBackup.FORMAT,
    val version: Int = 1,
    val kdfIterations: Int,
    val saltB64: String,
    /** AES-GCM(PBKDF2(passphrase), gzip(payload JSON)), base64url. */
    val ciphertextB64: String,
)

object FamilyBackup {

    const val FORMAT = "walcott-family-backup"
    const val KDF_ITERATIONS = 600_000
    const val MIN_PASSPHRASE_CHARS = 12
    /** Bounds on the (unauthenticated) header field, so a crafted file can't pin a core. */
    private val SANE_ITERATIONS = 10_000..2_000_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    /** Serializes and seals [payload] under [passphrase]. Returns the backup file's JSON text. */
    fun encrypt(payload: FamilyBackupPayload, passphrase: CharArray, iterations: Int = KDF_ITERATIONS): String {
        val saltB64 = newSaltB64()
        return encryptWithDerivedKey(payload, deriveKeyB64(passphrase, saltB64, iterations), saltB64, iterations)
    }

    /** A fresh random KDF salt, encoded for storage next to the derived key. */
    fun newSaltB64(): String = FamilyCrypto.toB64(ByteArray(SALT_BYTES).also { random.nextBytes(it) })

    /**
     * The KDF output, encodable so the auto-refreshing backup can re-seal without keeping the
     * passphrase itself around. Caching this on the parent adds nothing an attacker with the
     * device doesn't already have (family + signing keys are there in the clear); the
     * passphrase keeps protecting the FILE wherever it is parked.
     */
    fun deriveKeyB64(passphrase: CharArray, saltB64: String, iterations: Int = KDF_ITERATIONS): String {
        val spec = PBEKeySpec(passphrase, FamilyCrypto.fromB64(saltB64), iterations, KEY_BITS)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return FamilyCrypto.toB64(derived)
    }

    /** Seals with an already-derived key; the file stays decryptable by passphrase as usual. */
    fun encryptWithDerivedKey(
        payload: FamilyBackupPayload,
        derivedKeyB64: String,
        saltB64: String,
        iterations: Int = KDF_ITERATIONS,
    ): String {
        val key = SecretKeySpec(FamilyCrypto.fromB64(derivedKeyB64), "AES")
        val plaintext = gzip(json.encodeToString(FamilyBackupPayload.serializer(), payload).toByteArray())
        val file = FamilyBackupFile(
            kdfIterations = iterations,
            saltB64 = saltB64,
            ciphertextB64 = FamilyCrypto.toB64(FamilyCrypto.encrypt(key, plaintext)),
        )
        return json.encodeToString(FamilyBackupFile.serializer(), file)
    }

    /** Opens a backup file. Null when the passphrase is wrong or the file isn't a valid backup. */
    fun decrypt(fileJson: String, passphrase: CharArray): FamilyBackupPayload? {
        val file = runCatching { json.decodeFromString(FamilyBackupFile.serializer(), fileJson) }.getOrNull()
            ?: return null
        if (file.format != FORMAT || file.version != 1) return null
        if (file.kdfIterations !in SANE_ITERATIONS) return null
        return runCatching {
            val keyB64 = deriveKeyB64(passphrase, file.saltB64, file.kdfIterations)
            val key: SecretKey = SecretKeySpec(FamilyCrypto.fromB64(keyB64), "AES")
            val plaintext = gunzip(FamilyCrypto.decrypt(key, FamilyCrypto.fromB64(file.ciphertextB64)))
            json.decodeFromString(FamilyBackupPayload.serializer(), String(plaintext))
        }.getOrNull()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
