package dev.walcott.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The "don't remind me again" action on the backup nudge: mutes reminders for good. */
class BackupReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MUTE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = IdentityStore(context.applicationContext)
                store.save(store.current().copy(backupReminders = false))
                NotificationManagerCompat.from(context).cancel(SyncNotifications.NOTIF_BACKUP_REMINDER)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "dev.walcott.action.MUTE_BACKUP_REMINDERS"
    }
}
