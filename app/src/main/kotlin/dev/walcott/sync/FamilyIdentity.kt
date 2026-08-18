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
    /** The relay this family's phones talk through (see [RelayServer]); default unless chosen. */
    val ntfyServer: String = RelayServer.DEFAULT,
    /**
     * PARENT DEVICES ONLY: the parent PIN in the clear, so a parent who forgot it can be
     * reminded instead of having to set a new one — which bumps the policy and has to reach
     * every child before any of them can be released again.
     *
     * It lives HERE, and nowhere else, on purpose. [dev.walcott.data.PolicySettings] is the
     * family's brain and travels to every child device (they verify an emergency release
     * offline against the hash), so a plaintext copy there would put the PIN on the phone of
     * the person it exists to keep out. [FamilyIdentity] is device-local: nothing in
     * `:core-sync` so much as names it, no snapshot carries it, and the family backup rebuilds
     * it from the payload rather than restoring it — so a restored parent gets a working PIN
     * (the hash is in the policy) that simply can't be displayed until it is next entered.
     *
     * The write is gated on being a parent (see [SyncManager.rememberPinIfParent]), unlike the
     * local-backup key beside it, which is deliberately ungated: that one is a KDF output that
     * reveals nothing, and this one is the secret itself.
     */
    val pinPlain: String = "",
    /** Parent mode: require the PIN (or biometrics) on every app open / regain of focus. */
    val appLock: Boolean = false,
    /** Whether device biometrics may be used to satisfy [appLock]. */
    val appLockBiometric: Boolean = false,
    /**
     * Set by an emergency release (see [dev.walcott.enforcement.PanicRelease]): this device was
     * freed and must never enforce again. Everything else about the enrollment is wiped, and
     * the wiped identity is UNSET — which enforces by default (see [enforcesLocally]) — so this
     * one bit is what keeps the boot receiver, the watchdog and the heartbeat standing down
     * until someone deliberately pairs the device again.
     */
    val released: Boolean = false,
    /**
     * The relay this family used before a migration, and when the move was ordered.
     *
     * Parent only, and only while a migration is in flight: the parent keeps publishing to the old
     * relay as well until every device has confirmed the move (see
     * [RemoteAction.RELAY_MIGRATION_WINDOW_MS]). A phone that was off when the family moved comes
     * back to the only address it knows and finds the instruction waiting there — without this it
     * would come back to silence, for ever, with no way to be told where everyone went.
     */
    val previousNtfyServer: String = "",
    val relayMigratedAtMs: Long = 0,
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
     * anything on themselves; UNSET keeps enforcing so local-fallback installs stay safe —
     * except after a [released] emergency teardown, which must stay released.
     */
    val enforcesLocally: Boolean get() = !released && effectiveMode != DeviceMode.PARENT
}
