package dev.walcott.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

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
        Log.i(TAG, "install status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                UpdateCenter.report(UpdateUiState.PendingConfirmation(target = null))
                UpdateNotifications.notifyConfirmationNeeded(context, Intent(confirm))
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Self-update: the process is normally restarted before this runs; tidy up if not.
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Idle)
            }
            else -> {
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Failed("install status $status"))
            }
        }
    }

    companion object {
        const val ACTION = "dev.walcott.update.INSTALL_STATUS"
        private const val TAG = "WalcottUpdater"
    }
}
