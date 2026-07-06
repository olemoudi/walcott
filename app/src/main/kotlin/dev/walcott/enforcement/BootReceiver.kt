package dev.walcott.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts enforcement after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            EnforcementService.start(context)
        }
    }
}
