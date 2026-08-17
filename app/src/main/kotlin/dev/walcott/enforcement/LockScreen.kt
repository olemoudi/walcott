package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog
import java.security.SecureRandom

/**
 * The escape hatch for a phone whose owner has forgotten how to get into it.
 *
 * Android lets a Device Owner change the lock screen credential, but only through a token it must
 * hold in advance: [DevicePolicyManager.setResetPasswordToken] registers 32 bytes with the system,
 * and [DevicePolicyManager.resetPasswordWithToken] later sets a new PIN by presenting them. The
 * older `resetPassword` has been refused to device owners since Android 8, so this is the only
 * supported path.
 *
 * **The token has to be armed before it is needed, and that is the whole design problem.** The
 * system only activates it once the user has entered their existing credential — so a token
 * registered today becomes usable the next time they unlock, and a family that discovers it on the
 * day somebody is locked out discovers it too late. Hence [state]: whether this device is ready is
 * reported to the parent continuously, next to everything else about that phone's health, so "you
 * cannot rescue this phone yet" is something they read on a calm Tuesday rather than at a locked
 * screen.
 *
 * The token never leaves the device. What travels is the new PIN, inside the encrypted, signed
 * parent snapshot, as a command with a short life (see `RemoteAction.SET_LOCK_PIN`).
 */
object LockScreen {

    private const val TAG = "WalcottLock"

    /** Bytes the platform expects; anything else is refused outright. */
    private const val TOKEN_BYTES = 32

    /** What the parent needs to know before they need it. */
    data class State(
        /** A token is registered with the system for this device. */
        val tokenRegistered: Boolean,
        /**
         * The system has activated it — the person has unlocked the phone at least once since it
         * was registered. Only now can a PIN actually be set remotely.
         */
        val tokenActive: Boolean,
    )

    fun state(context: Context, token: ByteArray?): State {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            return State(tokenRegistered = false, tokenActive = false)
        }
        val active = runCatching {
            dpm.isResetPasswordTokenActive(WalcottAdminReceiver.componentName(context))
        }.getOrDefault(false)
        return State(tokenRegistered = token != null, tokenActive = active)
    }

    /** A fresh token, for the caller to store device-locally and hand back on every call here. */
    fun newToken(): ByteArray = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Registers [token] with the system. Idempotent and cheap enough to re-run on every boot: the
     * platform forgets tokens on a factory reset and can lose one across a credential change, and
     * a token this device believes in but the system does not is exactly the failure that only
     * shows up on the day it is needed.
     *
     * Returns false when the platform refused it (not Device Owner, or no secure lock hardware).
     */
    fun register(context: Context, token: ByteArray): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        return runCatching {
            dpm.setResetPasswordToken(WalcottAdminReceiver.componentName(context), token)
        }.onFailure { DebugLog.w(TAG, "the system refused the reset token", it) }
            .getOrDefault(false)
    }

    /** Outcome of a remote lock-screen change, so the parent is told what actually happened. */
    enum class Result {
        /** The new credential is in force. */
        DONE,

        /** No token is active yet: the person has to unlock the phone once first. */
        NOT_ARMED,

        /** The platform refused it — a PIN too short for a policy, or not Device Owner. */
        REFUSED,
    }

    /**
     * Sets the unlock PIN to [pin], or removes the lock screen entirely when [pin] is blank.
     *
     * Removing it is a real answer, not a cop-out: for somebody who cannot reliably enter four
     * digits, a phone with no lock is a phone they can answer, and the alternative they arrive at
     * on their own is a PIN written on the case.
     */
    fun apply(context: Context, pin: String, token: ByteArray?): Result {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return Result.REFUSED
        if (token == null || !dpm.isDeviceOwnerApp(context.packageName)) return Result.REFUSED
        val admin = WalcottAdminReceiver.componentName(context)
        if (!runCatching { dpm.isResetPasswordTokenActive(admin) }.getOrDefault(false)) {
            DebugLog.w(TAG, "asked to change the lock, but the reset token is not active yet")
            return Result.NOT_ARMED
        }
        val ok = runCatching {
            dpm.resetPasswordWithToken(admin, pin.ifBlank { "" }, token, 0)
        }.onFailure { DebugLog.w(TAG, "the system refused the new lock", it) }.getOrDefault(false)
        if (ok) DebugLog.i(TAG, if (pin.isBlank()) "lock screen removed" else "unlock PIN changed")
        return if (ok) Result.DONE else Result.REFUSED
    }

    /** Locks the screen now. Works with no token at all — it takes nothing away. */
    fun lockNow(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching { dpm.lockNow() }.onFailure { DebugLog.w(TAG, "lockNow refused", it) }
    }

    /**
     * A PIN this app is willing to send. Digits only, 4 to 8 of them: the platform accepts more
     * shapes, and none of them are what somebody being helped over the phone can be told.
     */
    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }
}
