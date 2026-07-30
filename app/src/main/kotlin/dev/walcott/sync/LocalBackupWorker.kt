package dev.walcott.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Keeps the on-device backup copies fresh (see [LocalBackupStore] for why they exist and
 * [BackupRotation] for which of the three get rewritten).
 *
 * Cheap enough to run nightly: the KDF ran once when the PIN was last entered, so a night's work
 * is building a few KB of JSON, gzipping it and one AES-GCM pass. No network, no wakelock worth
 * the name. It still asks for battery-not-low, because a backup is never urgent enough to be
 * worth the last few percent of a parent's phone.
 */
class LocalBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? WalcottApplication ?: return Result.success()
        if (IdentityStore(applicationContext).current().role != Role.PARENT) return Result.success()
        val written = runCatching { app.syncManager.writeDueLocalBackups(LocalDate.now()) }
            .onFailure { DebugLog.w(TAG, "local backup run failed", it) }
            .getOrDefault(emptySet())
        if (written.isNotEmpty()) DebugLog.i(TAG, "local backup rewrote $written")
        // Always success: a periodic worker that retries would just try again before the day
        // rolls over, and the next night's run covers anything missed anyway.
        return Result.success()
    }

    companion object {
        private const val TAG = "LocalBackup"
        private const val PERIODIC = "walcott-local-backup"

        /**
         * Runs about once a day. WorkManager decides the exact moment inside the window, so this
         * lands while the phone is idle rather than at a fixed hour that could catch it in use.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocalBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
