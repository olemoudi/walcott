package dev.walcott.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.walcott.data.SettingsStore
import dev.walcott.ui.format.humanize
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Parent-side watchdog: notifies when a child device hasn't checked in for a long time
 * (device off, offline, or protection tampered with). Local reads only — no network,
 * negligible battery. No-op on non-parent devices.
 */
class StaleChildWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (IdentityStore(context).current().effectiveMode != DeviceMode.PARENT) return Result.success()

        val syncStore = SyncStore(context)
        val state = syncStore.current()
        val now = System.currentTimeMillis()
        val registry = SettingsStore(context).current().children

        val toAlert = Staleness.devicesToAlert(state.lastSeen, state.staleNotifiedLastSeen, now)
        for ((deviceId, seenMs) in toAlert) {
            val snapshot = state.children.firstOrNull { it.deviceId == deviceId }
            val name = registry.firstOrNull { it.childId == snapshot?.childId && it.childId.isNotBlank() }?.name
                ?: snapshot?.displayName
                ?: deviceId
            SyncNotifications.notifyStaleChild(context, name, Duration.ofMillis(now - seenMs).humanize(), deviceId)
        }

        // A registered child that never checked in at all (botched enrollment) used to be invisible.
        val reportedChildIds = state.children.map { it.childId }.toSet()
        val neverReported = Staleness.childrenNeverReported(
            registeredSince = registry.associate { it.childId to it.addedAtMs },
            reportedChildIds = reportedChildIds,
            alreadyNotified = state.staleNotifiedLastSeen,
            nowMs = now,
        )
        for (childId in neverReported) {
            val name = registry.firstOrNull { it.childId == childId }?.name ?: childId
            SyncNotifications.notifyNeverReported(context, name, childId)
        }

        if (toAlert.isEmpty() && neverReported.isEmpty()) return Result.success()
        syncStore.update {
            it.copy(
                staleNotifiedLastSeen = it.staleNotifiedLastSeen + toAlert +
                    neverReported.associateWith { Staleness.NEVER },
            )
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "walcott-stale-child"

        /** Idempotent hourly check. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StaleChildWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
