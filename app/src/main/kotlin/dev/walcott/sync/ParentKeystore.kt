package dev.walcott.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The parent's ECDSA signing key, generated in and kept by the Android Keystore. The private
 * key is non-exportable — signing goes through the Keystore — so it can't be lifted off the
 * device. Only the public key is shared (in the pairing QR).
 */
object ParentKeystore {

    private const val ALIAS = "walcott_parent_sign"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    fun hasKey(): Boolean = keyStore.containsAlias(ALIAS)

    /** Generates the signing key if it doesn't exist yet. Idempotent. */
    fun ensureKeyPair() {
        if (hasKey()) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
    }

    fun privateKey(): PrivateKey =
        (keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey

    fun publicKey(): PublicKey = keyStore.getCertificate(ALIAS).publicKey
}
