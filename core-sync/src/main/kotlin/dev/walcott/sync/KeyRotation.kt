package dev.walcott.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.PrivateKey
import java.security.PublicKey

/**
 * A signed hand-over from one parent signing key to another: "the key you already trust
 * vouches for this new key". Created at backup time (while the old, non-exportable Keystore
 * key can still sign) and presented in envelopes after a restore, so children accept the
 * restored parent without re-enrolling.
 *
 * Security: only the holder of the OLD private key can mint a cert, so a child never adopts
 * a key that the parent it already trusts didn't vouch for. An attacker with the decrypted
 * backup holds the new private key AND the family key — a compromised backup was always full
 * compromise; the passphrase (see [FamilyBackup]) is the defense there.
 */
@Serializable
data class RotationCert(val newPublicKeyB64: String, val signatureB64: String)

object KeyRotation {

    /** Domain separation: this signature can never be confused with a message signature. */
    private val CONTEXT = "walcott-key-rotation:".toByteArray()

    private val json = Json { ignoreUnknownKeys = true }

    fun create(newPublicKey: PublicKey, oldPrivateKey: PrivateKey): RotationCert =
        RotationCert(
            newPublicKeyB64 = FamilyCrypto.toB64(newPublicKey.encoded),
            signatureB64 = FamilyCrypto.toB64(
                FamilyCrypto.sign(oldPrivateKey, CONTEXT + newPublicKey.encoded),
            ),
        )

    /** The attested new key when [cert] is validly signed by [trustedKey]; null otherwise. */
    fun verify(cert: RotationCert, trustedKey: PublicKey): PublicKey? = runCatching {
        val newKeyBytes = FamilyCrypto.fromB64(cert.newPublicKeyB64)
        val signature = FamilyCrypto.fromB64(cert.signatureB64)
        if (!FamilyCrypto.verify(trustedKey, CONTEXT + newKeyBytes, signature)) return null
        FamilyCrypto.publicKeyFromBytes(newKeyBytes)
    }.getOrNull()

    /** Compact string form for storage in the device identity. */
    fun encode(cert: RotationCert): String = json.encodeToString(RotationCert.serializer(), cert)

    fun decode(text: String): RotationCert? =
        runCatching { json.decodeFromString(RotationCert.serializer(), text) }.getOrNull()
}
