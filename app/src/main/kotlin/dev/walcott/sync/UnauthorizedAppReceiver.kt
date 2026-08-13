package dev.walcott.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.walcott.WalcottApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The two answers to "this app appeared and nobody approved it", straight from the shade.
 *
 * Remove sends [RemoteAction.UNINSTALL_APP]; allow sends [RemoteAction.ALLOW_APP]. Neither
 * needs the app open, and neither is urgent for the child device to receive: it has already
 * suspended the app on its own, so the wait for the command to land costs nothing.
 */
class UnauthorizedAppReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_REMOVE -> RemoteAction.UNINSTALL_APP
            ACTION_ALLOW -> RemoteAction.ALLOW_APP
            else -> return
        }
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val app = context.applicationContext as WalcottApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Over the topic of the family that device belongs to, like every other command.
                val family = runCatching { app.hub.scopeForDevice(deviceId) }.getOrNull() ?: app.hub.own
                runCatching { family.syncManager.sendCommand(deviceId, action, arg = pkg) }
                runCatching {
                    NotificationManagerCompat.from(context).cancel(notificationId(deviceId, pkg))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMOVE = "dev.walcott.action.REMOVE_UNAUTHORIZED_APP"
        const val ACTION_ALLOW = "dev.walcott.action.ALLOW_UNAUTHORIZED_APP"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_PACKAGE = "package"

        /** One notification per app per device, so two offenders don't overwrite each other. */
        fun notificationId(deviceId: String, pkg: String): Int = "unauth$deviceId$pkg".hashCode()
    }
}
