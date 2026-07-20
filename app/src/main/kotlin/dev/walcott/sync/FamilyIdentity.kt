package dev.walcott.sync

import kotlinx.serialization.Serializable

enum class Role { UNPAIRED, PARENT, CHILD }

/** The user-chosen role of this device, picked once at first launch. */
enum class DeviceMode { UNSET, PARENT, CHILD }

/**
 * This device's place in the family. The parent's private signing key is NOT here — it
 * lives in the Android Keystore ([ParentKeystore]); only its public key is stored/shared.
 */
@Serializable
data class FamilyIdentity(
    val role: Role = Role.UNPAIRED,
    val mode: DeviceMode = DeviceMode.UNSET,
    val deviceId: String = "",
    val displayName: String = "",
    /** Registry id from the per-child enrollment QR; "" for legacy/anonymous children. */
    val childId: String = "",
    val topic: String = "",
    val familyKeyB64: String = "",
    val parentPublicKeyB64: String = "",
    /**
     * Parent only: the signing private key (PKCS#8, base64url) when it lives in software —
     * families created since v0.11, and any restored family. "" = legacy family whose key is
     * in the Android Keystore ([ParentKeystore]). A software key sits beside the family key
     * in the same store — equal at-rest exposure, but unlike the Keystore it IS exportable
     * (root/forensic access can lift it): the accepted cost of making the backup possible.
     */
    val parentPrivateKeyB64: String = "",
    /**
     * Parent only, set by a restore: the [RotationCert] (encoded) proving the old key vouched
     * for the current one. Attached to every envelope so children adopt the restored key.
     */
    val rotationCertB64: String = "",
    /**
     * Parent only, legacy (Keystore) families: the recovery keypair + cert embedded in every
     * backup, minted ONCE and persisted — if each backup minted its own, two old files would
     * rotate children to different keys and the second restore would orphan whoever had
     * followed the first. "" until the first backup is created.
     */
    val recoveryPublicKeyB64: String = "",
    val recoveryPrivateKeyB64: String = "",
    val recoveryCertB64: String = "",
    /** Parent only: nudge notifications when the family backup is missing or stale. */
    val backupReminders: Boolean = true,
    val ntfyServer: String = "https://ntfy.sh",
    /** Parent mode: require the PIN (or biometrics) on every app open / regain of focus. */
    val appLock: Boolean = false,
    /** Whether device biometrics may be used to satisfy [appLock]. */
    val appLockBiometric: Boolean = false,
) {
    val isPaired: Boolean get() = role != Role.UNPAIRED

    /** Migration for installs predating [mode]: an explicit choice wins, else derive from role. */
    val effectiveMode: DeviceMode
        get() = when {
            mode != DeviceMode.UNSET -> mode
            role == Role.PARENT -> DeviceMode.PARENT
            role == Role.CHILD -> DeviceMode.CHILD
            else -> DeviceMode.UNSET
        }

    /**
     * Whether this device runs the enforcement service. Parent phones don't enforce
     * anything on themselves; UNSET keeps enforcing so local-fallback installs stay safe.
     */
    val enforcesLocally: Boolean get() = effectiveMode != DeviceMode.PARENT
}
