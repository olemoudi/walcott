package dev.walcott.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Receives PackageInstaller status callbacks. On a Device Owner device the install is silent
 * and lands as STATUS_SUCCESS. On a non-owner device (the parent) the system asks for
 * confirmation, which arrives here as STATUS_PENDING_USER_ACTION with an intent to launch.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status message=$message")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) runCatching { context.startActivity(confirm) }
        }
    }

    companion object {
        const val ACTION = "dev.walcott.update.INSTALL_STATUS"
        private const val TAG = "WalcottUpdater"
    }
}
