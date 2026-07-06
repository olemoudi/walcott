package dev.walcott.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs the update check off the main thread, retrying if the network is down. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Updater(applicationContext).checkAndUpdate()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "walcott-update-periodic"
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Idempotent periodic check (~ every 12h). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(connected)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One-off immediate check (on app launch / boot). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(connected).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
