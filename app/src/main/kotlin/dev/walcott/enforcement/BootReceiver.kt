package dev.walcott.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.update.UpdateWorker

/** Restarts enforcement and checks for updates after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            EnforcementService.start(context)
            UpdateWorker.runNow(context)
        }
    }
}
