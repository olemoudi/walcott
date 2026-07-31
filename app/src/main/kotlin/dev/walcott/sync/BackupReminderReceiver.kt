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
                // "Stop nagging me" is about the person, not about one family: mute every one
                // this device holds, whichever family's reminder they happened to tap.
                val app = context.applicationContext as dev.walcott.WalcottApplication
                for (family in app.hub.allNow()) {
                    val store = family.identityStore
                    store.save(store.current().copy(backupReminders = false))
                    NotificationManagerCompat.from(context).cancel(
                        if (family.id == dev.walcott.data.FamilyIds.DEFAULT) {
                            SyncNotifications.NOTIF_BACKUP_REMINDER
                        } else {
                            SyncNotifications.NOTIF_BACKUP_REMINDER + family.id.hashCode()
                        },
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "dev.walcott.action.MUTE_BACKUP_REMINDERS"
    }
}
