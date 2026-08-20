package dev.walcott.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.walcott.debug.DebugLog

/**
 * Receives PackageInstaller status callbacks. On a Device Owner device the install is silent
 * and lands as STATUS_SUCCESS. On a non-owner device (the parent) the system may ask for
 * confirmation, which arrives here as STATUS_PENDING_USER_ACTION with an intent to launch.
 * Launching directly only works while the app is foregrounded (background activity starts
 * are blocked since Android 10), so we also post a tappable notification — that's what makes
 * the parent flow reliable when the check ran in the background.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        DebugLog.i(TAG, "install status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                UpdateCenter.report(UpdateUiState.PendingConfirmation(UpdateCenter.lastTarget()))
                UpdateNotifications.notifyConfirmationNeeded(context, Intent(confirm))
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Self-update: the process is normally restarted before this runs; tidy up if not.
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Idle)
                discardApk(context)
            }
            else -> {
                val detail = message?.let { ": $it" } ?: ""
                UpdateCenter.report(UpdateUiState.Failed("install status $status$detail"))
                if (keepsApkAfterFailure(status)) {
                    // Somebody said no. The APK is fine, so it stays and the shade does not go
                    // quiet on it: the notification turns into the way back, and the button in
                    // settings installs what is already on disk without touching the network.
                    // Losing an update to one reflexive "Cancel" is not a thing this should do.
                    //
                    // Not on a Device Owner child, though: there was no dialog to decline there,
                    // so a blocked install is our own restriction misfiring, and the person who
                    // can act on it is the parent — who hears about it through the child's
                    // reported update error, not through a notification on the child's phone.
                    UpdateNotifications.cancel(context)
                    if (!isDeviceOwner(context)) UpdateNotifications.notifyInstallDeclined(context)
                } else {
                    UpdateNotifications.cancel(context)
                    discardApk(context)
                }
            }
        }
    }

    private fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    /**
     * Drops the downloaded APK once the install reached a terminal state. It is ~50 MB sitting
     * in the cache of a child's phone, which is exactly the device least likely to have room
     * to spare — and the next check downloads it again anyway.
     */
    private fun discardApk(context: Context) {
        runCatching { java.io.File(context.cacheDir, Updater.APK_FILE).delete() }
    }

    companion object {
        const val ACTION = "dev.walcott.update.INSTALL_STATUS"
        private const val TAG = "WalcottUpdater"
    }
}
