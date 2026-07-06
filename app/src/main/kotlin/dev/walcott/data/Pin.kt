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
