package dev.walcott.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Keeps this device's copy of the public blocklists current (see [BlocklistStore]).
 *
 * Child-side only: the parent's phone filters nothing, so downloading a 494 000-domain porn list
 * onto it would spend a stranger's data plan to answer a question nobody asks there. The gate is
 * the same one the rest of the child-side workers use — whether this device enforces locally.
 *
 * Runs daily, plus immediately whenever the family's set of lists changes, because a parent who
 * has just switched "Betting and gambling" on is standing there looking at the phone and a
 * filter that starts working tomorrow reads as a filter that does not work.
 */
class BlocklistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WalcottApplication
        if (!app.identityStore.current().enforcesLocally) return Result.success()

        val settings = app.repository.settingsFlow.first()
        val complete = BlocklistStore.get(applicationContext).refresh(
            ids = settings.enabledBlocklists,
            intervalHours = settings.blocklistRefreshHours,
        )
        if (!complete) {
            // Retried with backoff rather than abandoned: a list the parent switched on and this
            // device never got is a filter that is thinner than the family thinks it is.
            DebugLog.w(TAG, "some lists are still missing; will retry")
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "Blocklists"
        private const val PERIODIC = "walcott-blocklists-periodic"
        private const val ONE_OFF = "walcott-blocklists-now"
        private const val MAX_RETRIES = 5

        private val unmetered = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        private val connected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Idempotent periodic refresh on the family's chosen interval. No-op on a device that
         * doesn't enforce (see [doWork]); re-called whenever the interval changes, and UPDATE
         * then re-periods the existing work rather than stacking a second one.
         *
         * [wifiOnly] is the family's own answer to "may we spend the child's mobile data on
         * background downloads?" (`PolicySettings.updateWifiOnly`). It is deliberately NOT
         * unmetered-always: a cold cache is tens of MB, but a teenager whose phone never sees
         * Wi-Fi would then never get a list at all, and a filter that never arrives is the one
         * failure this whole feature exists to avoid. After the first pass the ETags make a
         * refresh cost nothing anyway (see [BlocklistStore]).
         */
        fun schedule(context: Context, intervalHours: Int, wifiOnly: Boolean) {
            val hours = intervalHours.toLong().coerceAtLeast(1)
            val request = PeriodicWorkRequestBuilder<BlocklistWorker>(hours, TimeUnit.HOURS)
                .setConstraints(if (wifiOnly) unmetered else connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Fetch what is missing now — a policy that just changed, a boot, an app launch.
         *
         * REPLACE rather than KEEP: the newest call knows the newest set of lists, and the store
         * serialises the work anyway.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BlocklistWorker>()
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
