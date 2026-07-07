package dev.walcott.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.sync.IdentityStore
import dev.walcott.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restarts enforcement (on enforcing devices) and checks for updates after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        UpdateWorker.runNow(context)
        // The mode lives in DataStore; keep the receiver alive while we read it so the
        // foreground-service start stays within the boot broadcast's exemption window.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (IdentityStore(context).current().enforcesLocally) {
                    EnforcementService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
