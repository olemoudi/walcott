package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog

/**
 * What lost mode does to the device itself (see `RemoteAction.LOST_MODE`): the screen locks at
 * once, and the line the parent wrote — a way to reach the family — goes on the lock screen, where
 * a finder reads it without unlocking anything. The tracking half lives in `SyncManager`, because
 * it is a session like any other; the status line on the child's own notification follows the
 * same flag from `EnforcementService`.
 *
 * Both are Device Owner calls. A phone that is not one (the accessibility fallback) can neither
 * lock itself nor write on its lock screen, and says so in the log rather than pretending: the
 * parent's card reads the mode from the snapshot, which is still honest — it says "asked for",
 * not "done" — and the tracking still runs.
 */
object LostMode {

    private const val TAG = "WalcottLost"

    /** Locks the screen (when [on]) and writes or clears the lock-screen line. */
    fun apply(context: Context, on: Boolean, message: String) {
        // A lock-screen line written mid-release would outlive the app that wrote it.
        if (on && PanicRelease.inProgress) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            DebugLog.w(TAG, "not Device Owner: cannot lock the screen or write on it")
            return
        }
        val admin = WalcottAdminReceiver.componentName(context)
        runCatching { dpm.setDeviceOwnerLockScreenInfo(admin, message.takeIf { on && it.isNotBlank() }) }
            .onFailure { DebugLog.w(TAG, "could not write the lock-screen line", it) }
        if (on) runCatching { dpm.lockNow() }.onFailure { DebugLog.w(TAG, "lockNow refused", it) }
    }
}
