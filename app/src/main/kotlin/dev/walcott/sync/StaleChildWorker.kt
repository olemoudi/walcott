package dev.walcott.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import dev.walcott.FamilyScope
import dev.walcott.WalcottApplication
import dev.walcott.ui.format.humanize
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Parent-side watchdog: notifies when a child device hasn't checked in for a long time
 * (device off, offline, or protection tampered with). Local reads only — no network,
 * negligible battery. No-op on non-parent devices.
 *
 * Runs over EVERY family this device manages, not just the one on screen: a child going quiet
 * matters exactly as much in the family the parent isn't currently looking at.
 */
class StaleChildWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val app = context as? WalcottApplication ?: return Result.success()
        if (app.identityStore.current().effectiveMode != DeviceMode.PARENT) return Result.success()

        val multi = app.hub.isMultiNow()
        for (family in app.hub.allNow()) {
            runCatching { checkFamily(context, family, multi) }
                .onFailure { dev.walcott.debug.DebugLog.w(TAG, "stale check failed for ${family.id}", it) }
        }
        return Result.success()
    }

    private suspend fun checkFamily(context: Context, family: FamilyScope, multi: Boolean) {
        val syncStore = family.syncStore
        val state = syncStore.current()
        val settings = family.settingsStore.current()
        val now = System.currentTimeMillis()
        val registry = settings.children
        val label = settings.familyName.takeIf { multi && it.isNotBlank() }

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
            SyncNotifications.notifyStaleChild(
                context, SyncNotifications.who(name, label), silence.humanize(), deviceId,
                snapshot?.childId.orEmpty(),
            )
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
            SyncNotifications.notifyNeverReported(context, SyncNotifications.who(name, label), childId)
            feedEvent(ParentEvent.TYPE_NEVER_REPORTED, childId, name)
        }

        // An install window still open on a child device after its first hour: nag hourly with
        // a one-tap re-block, until the parent acts or the child re-arms itself at the 8 h mark
        // (see InstallWindowReminder). Short windows expire before the first nag is due.
        val reminded = mutableMapOf<String, Long>()
        for (snapshot in state.children) {
            val due = InstallWindowReminder.shouldRemind(
                untilMs = snapshot.installExemptionUntilMs,
                firstSeenMs = state.installWindowSeen[snapshot.deviceId] ?: 0L,
                lastReminderMs = state.installWindowRemindedAt[snapshot.deviceId] ?: 0L,
                nowMs = now,
            )
            if (!due) continue
            val name = registry.firstOrNull { it.childId == snapshot.childId && it.childId.isNotBlank() }?.name
                ?: snapshot.displayName
            val remaining = Duration.ofMillis(snapshot.installExemptionUntilMs - now).humanize()
            SyncNotifications.notifyInstallWindowOpen(
                context, SyncNotifications.who(name, label), remaining, snapshot.deviceId, snapshot.childId,
            )
            feedEvent(ParentEvent.TYPE_INSTALL_WINDOW, snapshot.childId, name)
            reminded[snapshot.deviceId] = now
        }

        if (toAlert.isEmpty() && neverReported.isEmpty() && reminded.isEmpty()) return
        syncStore.update {
            events.fold(
                it.copy(
                    staleNotifiedLastSeen = it.staleNotifiedLastSeen + toAlert +
                        neverReported.associateWith { Staleness.NEVER },
                    installWindowRemindedAt = it.installWindowRemindedAt + reminded,
                ),
            ) { state, event -> state.plusEvent(event) }
        }
    }

    companion object {
        private const val TAG = "WalcottSync"
        private const val PERIODIC = "walcott-stale-child"

        /** Idempotent hourly check. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StaleChildWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
