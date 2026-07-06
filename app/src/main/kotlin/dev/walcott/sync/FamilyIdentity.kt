package dev.walcott.sync

import kotlinx.serialization.Serializable

enum class Role { UNPAIRED, PARENT, CHILD }

/**
 * This device's place in the family. The parent's private signing key is NOT here — it
 * lives in the Android Keystore ([ParentKeystore]); only its public key is stored/shared.
 */
@Serializable
data class FamilyIdentity(
    val role: Role = Role.UNPAIRED,
    val deviceId: String = "",
    val displayName: String = "",
    val topic: String = "",
    val familyKeyB64: String = "",
    val parentPublicKeyB64: String = "",
    val ntfyServer: String = "https://ntfy.sh",
) {
    val isPaired: Boolean get() = role != Role.UNPAIRED
}
