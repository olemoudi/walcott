package dev.walcott.enforcement

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.sync.IdentityStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Belt-and-suspenders on enforcing devices: periodically make sure the enforcement service is
 * running and re-assert the Device Owner restrictions. Recovers from system/low-memory kills;
 * a manual force-stop still can't be recovered from a worker (the package is left "stopped"),
 * which is why [DeviceRestrictions.KEY_APPS_CONTROL] is on by default to grey out force-stop.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WalcottApplication
        if (IdentityStore(applicationContext).current().enforcesLocally) {
            // Failures here mean enforcement may be down while looking healthy — keep them visible.
            runCatching { EnforcementService.start(applicationContext) }
                .onFailure { DebugLog.e(TAG, "enforcement restart failed", it) }
            // Location is granted-but-not-held (see LocationPolicy): if the child revoked
            // it from Settings, this re-grants within one watchdog period.
            runCatching { dev.walcott.location.LocationPolicy.ensureEnforced(applicationContext) }
            runCatching {
                val settings = app.repository.settingsFlow.first()
                DeviceRestrictions.apply(
                    applicationContext,
                    settings.deviceRestrictions,
                    app.syncManager.installExemption.value,
                )
            }.onFailure { DebugLog.e(TAG, "restriction reassert failed", it) }
            // Doze-resilient check-in: the in-process re-emit freezes while the device
            // sleeps, but this worker still runs in Doze maintenance windows — so a phone
            // resting all night keeps checking in a few times without any extra wakeups.
            runCatching { app.syncManager.publishHeartbeatIfStale(HEARTBEAT_MIN_INTERVAL_MS) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "walcott_watchdog"
        private const val TAG = "WalcottWatchdog"
        /** Skip the heartbeat when something already published this recently (awake periods). */
        private const val HEARTBEAT_MIN_INTERVAL_MS = 10 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
