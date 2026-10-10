package dev.walcott.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.walcott.WalcottApplication
import dev.walcott.data.NotificationEntity
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Records what arrives on this phone, while the rules ask for it (see [NotificationLog]).
 *
 * The service is declared in the manifest and bound by the system as soon as the phone's owner
 * enables it in Settings — which means it can be bound on a device whose family has the log turned
 * OFF, and on a parent's phone, and on a child's. So the switch is checked per notification, here,
 * rather than assumed by anybody: being bound is not permission to record.
 *
 * What is deliberately skipped: our own notifications (this app talking to itself is not support
 * information), and ongoing ones — a music player, a download, a navigation session all post a
 * notification that lives for hours and says nothing about who tried to reach this person.
 */
class WalcottNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        // Worth a line: this is the moment the permission actually starts doing something, and
        // without it "nothing is being recorded" has two indistinguishable causes.
        DebugLog.i(TAG, "notification listener connected")
        connected = true
    }

    override fun onListenerDisconnected() {
        DebugLog.i(TAG, "notification listener disconnected")
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        val flags = notification.notification?.flags ?: 0
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val app = application as? WalcottApplication ?: return
        val extras = notification.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString().orEmpty()
        // A notification with neither is a badge or an icon update; there is nothing to read back.
        if (title.isBlank() && text.isBlank()) return

        scope.launch {
            runCatching {
                val settings = app.repository.settingsFlow.first()
                if (!NotificationLog.enabledBy(settings)) return@launch
                NotificationLog.record(
                    app.repository.notifications,
                    NotificationEntity(
                        postedAtMs = notification.postTime,
                        packageName = notification.packageName,
                        title = title,
                        text = text,
                        key = notification.key.orEmpty(),
                    ),
                )
            }.onFailure { DebugLog.w(TAG, "could not handle a posted notification", it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalcottNotif"

        /** Whether the system has this listener bound right now, in this process. */
        @Volatile private var connected = false

        /**
         * Asks for the listener back when access is granted and nothing is bound.
         *
         * Several OEMs unbind notification listeners after an app update or a "clean-up" and do
         * not bind them again until the access is toggled by hand. `requestRebind` is the one
         * request the platform offers; on stock Android it only undoes this app's own
         * `requestUnbind`, so it is no cure, but it costs nothing and some skins honour it.
         * NOT the usual workaround of switching the component off and on: measured on Android 15,
         * that takes the listener out of the access list for good — the grant the phone's owner
         * gave is gone. Not in the first minutes of a process either, when the system simply has
         * not bound it yet; the log line is what tells somebody to toggle it by hand.
         */
        fun rebindIfDropped(context: android.content.Context) {
            if (connected || !NotificationLog.accessGranted(context)) return
            val upMs = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
            if (upMs < BIND_GRACE_MS) return
            DebugLog.w(TAG, "notification access is granted but the listener is not bound; asking for it back")
            runCatching { requestRebind(android.content.ComponentName(context, WalcottNotificationListener::class.java)) }
                .onFailure { DebugLog.w(TAG, "could not ask for the listener back", it) }
        }

        private const val BIND_GRACE_MS = 2 * 60_000L
    }
}
