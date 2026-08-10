package dev.walcott.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Parent-side nudge: notifies when the family backup is missing or stale, on the escalation
 * ladder defined by [BackupReminder]. Local reads only; no-op on non-parent devices.
 */
class BackupReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val app = context as? dev.walcott.WalcottApplication ?: return Result.success()
        // Per family: each keeps its own backup file, so each can be the one that has none.
        val multi = app.hub.isMultiNow()
        for (family in app.hub.allNow()) {
            runCatching { remindFor(context, family, multi) }
                .onFailure { dev.walcott.debug.DebugLog.w("WalcottSync", "backup reminder failed", it) }
        }
        return Result.success()
    }

    private suspend fun remindFor(context: Context, family: dev.walcott.FamilyScope, multi: Boolean) {
        val identity = family.identityStore.current()
        if (identity.role != Role.PARENT) return

        val syncStore = family.syncStore
        val state = syncStore.current()
        val now = System.currentTimeMillis()
        // Parents from before reminders existed have no anchor: stamp one now, so their
        // "first nudge" counts from this update, not from 1970.
        if (state.parentSetupAtMs <= 0) {
            syncStore.update { it.copy(parentSetupAtMs = now) }
            return
        }

        val remind = BackupReminder.shouldRemind(
            nowMs = now,
            enabled = identity.backupReminders,
            parentSetupAtMs = state.parentSetupAtMs,
            lastBackupAtMs = state.lastBackupAtMs,
            lastPolicyEditAtMs = state.lastPolicyEditAtMs,
            lastReminderAtMs = state.lastBackupReminderAtMs,
        )
        if (!remind) return
        SyncNotifications.notifyBackupReminder(
            context,
            neverBackedUp = state.lastBackupAtMs <= 0,
            family = family.name().takeIf { multi && it.isNotBlank() },
            familyId = family.id,
        )
        syncStore.update { it.copy(lastBackupReminderAtMs = now) }
    }

    companion object {
        private const val PERIODIC = "walcott-backup-reminder"

        /** Idempotent ~6h check; the ladder itself is day-grained, so this is plenty. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupReminderWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
