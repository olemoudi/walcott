package dev.walcott.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Approve/Deny buttons on a child's request notification.
 *
 * Answering a request is the thing a parent does most, and until now it always cost the same
 * four steps: unlock, open the app, find the card, tap. The answer itself is usually already
 * decided by the time the notification is read — so it belongs where it is read.
 *
 * The request id is offered to every family this phone holds and the one that owns it acts
 * ([SyncManager.resolveFromNotification]); no family needs to be named in the intent, and a
 * request already answered in the app is a no-op rather than a second grant.
 */
class RequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val approved = when (intent.action) {
            ACTION_APPROVE -> true
            ACTION_DENY -> false
            else -> return
        }
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        // The shade dismisses the notification the moment it is tapped; cancel it here too so a
        // request answered from a watch or a second device doesn't leave a dead button behind.
        if (notifId != 0) runCatching { NotificationManagerCompat.from(context).cancel(notifId) }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as WalcottApplication
                val handled = app.hub.allNow().any { family ->
                    runCatching { family.syncManager.resolveFromNotification(requestId, approved) }
                        .onFailure { DebugLog.e(TAG, "answering from the notification failed", it) }
                        .getOrDefault(false)
                }
                if (!handled) DebugLog.i(TAG, "notification answer ignored: nothing left to resolve")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "dev.walcott.action.APPROVE_REQUEST"
        const val ACTION_DENY = "dev.walcott.action.DENY_REQUEST"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        private const val TAG = "WalcottSync"
    }
}
