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

        val events = mutableListOf<ParentEvent>()
        fun feedEvent(type: String, childId: String, name: String, detail: String = "") {
            events += ParentEvent(
                id = java.util.UUID.randomUUID().toString(),
                atMs = now, type = type, childId = childId, childName = name, detail = detail,
            )
        }

        val toAlert = Staleness.devicesToAlert(state.lastSeen, state.staleNotifiedLastSeen, now)
        for ((deviceId, seenMs) in toAlert) {
            val snapshot = state.children.firstOrNull { it.deviceId == deviceId }
            val name = registry.firstOrNull { it.childId == snapshot?.childId && it.childId.isNotBlank() }?.name
                ?: snapshot?.displayName
                ?: deviceId
            val silence = Duration.ofMillis(now - seenMs)
            SyncNotifications.notifyStaleChild(context, name, silence.humanize(), deviceId, snapshot?.childId.orEmpty())
            feedEvent(ParentEvent.TYPE_STALE, snapshot?.childId.orEmpty(), name, detail = silence.toMillis().toString())
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
            feedEvent(ParentEvent.TYPE_NEVER_REPORTED, childId, name)
        }

        if (toAlert.isEmpty() && neverReported.isEmpty()) return Result.success()
        syncStore.update {
            events.fold(
                it.copy(
                    staleNotifiedLastSeen = it.staleNotifiedLastSeen + toAlert +
                        neverReported.associateWith { Staleness.NEVER },
                ),
            ) { state, event -> state.plusEvent(event) }
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
