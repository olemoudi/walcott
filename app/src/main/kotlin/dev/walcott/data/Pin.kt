package dev.walcott.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Parent PIN hashing with PBKDF2-HMAC-SHA256 + random salt. Avoids storing the PIN in the
 * clear; a child reading the DataStore only sees hash and salt. Uses java.util.Base64 (not
 * android.util) so it stays unit-testable on the JVM.
 */
object Pin {
    /**
     * Digits a new PIN must have. Four was ten thousand guesses: an afternoon against a lockout
     * a moved clock could skip, and seconds against the hash every child carries in its policy.
     * PINs set before this stay valid; the parent's PIN card asks for a longer one.
     */
    const val MIN_LENGTH = 6

    /** Longest PIN accepted, everywhere one is typed. Beyond this nobody reads one out loud. */
    const val MAX_LENGTH = 8

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    data class Hashed(val hash: String, val salt: String)

    fun hash(pin: String): Hashed {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Hashed(hash = derive(pin, salt), salt = salt.toB64())
    }

    fun verify(pin: String, hash: String, salt: String): Boolean =
        derive(pin, salt.fromB64()).equalsConstantTime(hash)

    private fun derive(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded.toB64()
    }

    private fun ByteArray.toB64() = Base64.getEncoder().encodeToString(this)
    private fun String.fromB64() = Base64.getDecoder().decode(this)

    private fun String.equalsConstantTime(other: String): Boolean {
        if (length != other.length) return false
        var diff = 0
        for (i in indices) diff = diff or (this[i].code xor other[i].code)
        return diff == 0
    }
}
