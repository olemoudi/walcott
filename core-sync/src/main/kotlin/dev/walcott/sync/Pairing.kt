package dev.walcott.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything a device needs to join the family, encoded into the parent's pairing QR:
 * the ntfy topic, the shared family key, and the parent's public key (to verify parent
 * messages). The parent's private key never leaves the parent device.
 */
@Serializable
data class PairingPayload(
    val topic: String,
    val familyKeyB64: String,
    val parentPublicKeyB64: String,
    val ntfyServer: String = "https://ntfy.sh",
) {
    fun encode(): String = PREFIX + FamilyCrypto.toB64(json.encodeToString(serializer(), this).toByteArray())

    companion object {
        private const val PREFIX = "walcott1:"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): PairingPayload? {
            if (!text.startsWith(PREFIX)) return null
            return runCatching {
                val bytes = FamilyCrypto.fromB64(text.removePrefix(PREFIX))
                json.decodeFromString(serializer(), String(bytes))
            }.getOrNull()
        }
    }
}
