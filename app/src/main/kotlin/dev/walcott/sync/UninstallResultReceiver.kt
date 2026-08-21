package dev.walcott.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.walcott.debug.DebugLog

/**
 * What the OS made of a quarantine's silent uninstall (see `SyncManager.silentUninstall`).
 *
 * The removal is handed to [PackageInstaller] and answered asynchronously, and the answer used
 * to be sent to a broadcast nothing listened for — so a refusal went nowhere at all. The ledger
 * still noticed, eventually, by counting attempts that never worked; what nobody could see was
 * WHY, which on a phone that keeps failing to remove the same app is the entire question. The
 * platform's own reason is in the status message, and the debug log is what the health report
 * carries back to the parent.
 *
 * Logging is all it does. The retry lives in the reconciliation, which runs on every package
 * event and every heartbeat and does not need to have witnessed this.
 */
class UninstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            DebugLog.i(TAG, "removed $pkg")
        } else {
            DebugLog.w(TAG, "could not remove $pkg: status=$status ${message.orEmpty()}")
        }
    }

    private companion object {
        const val TAG = "WalcottInstalls"
    }
}
