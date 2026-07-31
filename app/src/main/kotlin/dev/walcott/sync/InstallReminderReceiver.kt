package dev.walcott.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.WalcottApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The "re-block now" action on the open-install-window nag: sends the existing
 * [RemoteAction.REAPPLY_POLICY] to the child device, which kills any open exemption window
 * and re-asserts the restrictions there (see RemoteCommandRunner). The notification is
 * cancelled optimistically; if the command never lands, the next hourly check re-raises it.
 */
class InstallReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REBLOCK) return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val app = context.applicationContext as WalcottApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { app.syncManager.sendCommand(deviceId, RemoteAction.REAPPLY_POLICY) }
                SyncNotifications.cancelInstallWindowOpen(context, deviceId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REBLOCK = "dev.walcott.action.REBLOCK_INSTALLS"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
