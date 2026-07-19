package dev.walcott.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.debug.DebugLog
import dev.walcott.sync.IdentityStore
import dev.walcott.sync.ParentPollWorker
import dev.walcott.sync.StaleChildWorker
import dev.walcott.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts enforcement (on enforcing devices) after a reboot AND after a self-update: a
 * committed install kills the process, and without MY_PACKAGE_REPLACED the enforcement loop
 * would stay dead until the watchdog (≤15 min) or a manual launch. Both broadcasts are on the
 * Android 12+ exemption list for starting a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DebugLog.i(TAG, "restart after $action")
        UpdateWorker.runNow(context)
        // Belt and braces: WorkManager persists across updates, but rescheduling is free.
        WatchdogWorker.schedule(context)
        StaleChildWorker.schedule(context)
        ParentPollWorker.schedule(context)
        // The mode lives in DataStore; keep the receiver alive while we read it so the
        // foreground-service start stays within the broadcast's exemption window.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (IdentityStore(context).current().enforcesLocally) {
                    runCatching { EnforcementService.start(context) }
                        .onFailure { DebugLog.e(TAG, "enforcement restart failed", it) }
                    // Alarms don't survive a reboot: re-arm the 30-min check-in chain.
                    runCatching { dev.walcott.sync.HeartbeatAlarm.schedule(context) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WalcottBoot"
    }
}
