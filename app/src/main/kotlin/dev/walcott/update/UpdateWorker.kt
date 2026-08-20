package dev.walcott.update

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runs the update check off the main thread, retrying transient failures with backoff. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = Updater(applicationContext)
        val outcome = if (inputData.getBoolean(KEY_STAGED_ONLY, false)) {
            updater.installStaged()
        } else {
            updater.checkAndUpdate(force = inputData.getBoolean(KEY_FORCE, false))
        }
        return when (outcome) {
            UpdateCheckOutcome.TRANSIENT_FAILURE ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "walcott-update-periodic"
        private const val MAX_RETRIES = 5
        private const val KEY_FORCE = "force"
        private const val KEY_STAGED_ONLY = "staged_only"
        /** Minimum spacing between focus-triggered checks, so regaining focus repeatedly doesn't hammer GitHub. */
        private const val FOCUS_GUARD_MILLIS = 15 * 60 * 1000L
        private val lastEnqueueMs = AtomicLong(0)
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Idempotent periodic check (~ every 12h). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One-off immediate check (app launch / boot). Bypasses the guard. */
        fun runNow(context: Context) {
            lastEnqueueMs.set(SystemClock.elapsedRealtime())
            enqueue(context, connected, Data.EMPTY)
        }

        /**
         * The manual "check now" from settings.
         *
         * Forced, because somebody is standing there asking — the same override a parent's
         * remote "Update now" carries, over the same Wi-Fi-only policy. And unconstrained on
         * purpose: a tap made with no signal should fail visibly within a second, not be queued
         * into a silence indistinguishable from a button that does nothing.
         */
        fun checkNow(context: Context) {
            lastEnqueueMs.set(SystemClock.elapsedRealtime())
            enqueue(context, Constraints.NONE, workDataOf(KEY_FORCE to true))
        }

        /** Installs the APK already in the cache. Needs no network, so it asks for none. */
        fun installStagedNow(context: Context) {
            enqueue(context, Constraints.NONE, workDataOf(KEY_STAGED_ONLY to true))
        }

        /** Focus-triggered check: runs at most once per guard window. */
        fun runIfStale(context: Context) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val last = lastEnqueueMs.get()
                if (last != 0L && now - last < FOCUS_GUARD_MILLIS) return
                if (lastEnqueueMs.compareAndSet(last, now)) break
            }
            runNow(context)
        }

        private fun enqueue(context: Context, constraints: Constraints, input: Data) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
