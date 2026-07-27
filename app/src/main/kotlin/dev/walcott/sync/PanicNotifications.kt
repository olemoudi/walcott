package dev.walcott.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.MainActivity
import dev.walcott.R
import dev.walcott.WalcottApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notifications shown *on the child device* about its own emergency release: every notice
 * sent, the parent's refusal, the cancellation when connectivity fails, and the release
 * itself. The child needs to see this without keeping the app open for a day — a countdown
 * that only exists inside a screen nobody is looking at is not a countdown.
 *
 * Quiet by design (LOW importance, silent): these are progress reports, not alarms.
 */
object PanicNotifications {

    private const val CHANNEL = "walcott_panic"
    private const val NOTIF_ID = 4711

    /** A two-hourly notice went out; [remaining] more to go before the device is freed. */
    fun notifyProgress(context: Context, remaining: Int) = post(
        context,
        context.getString(R.string.panic_child_progress_title),
        context.resources.getQuantityString(R.plurals.panic_child_progress_text, remaining, remaining),
    )

    /** The parent refused: the request is dead and locked out for three days. */
    fun notifyDenied(context: Context) = post(
        context,
        context.getString(R.string.panic_child_denied_title),
        context.getString(R.string.panic_child_denied_text),
    )

    /** The channel failed when a notice was due, so the request is void. */
    fun notifyExpired(context: Context) = post(
        context,
        context.getString(R.string.panic_child_expired_title),
        context.getString(R.string.panic_child_expired_text),
    )

    /** The 24 hours are complete and the device is being released. */
    fun notifyReleased(context: Context) = post(
        context,
        context.getString(R.string.panic_child_released_title),
        context.getString(R.string.panic_child_released_text),
    )

    private fun post(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.panic_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val tap = PendingIntent.getActivity(
            context, NOTIF_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }
}

/**
 * The "Refuse" action on the parent's emergency-release alert. A refusal must be one tap from
 * the notification: the alert can arrive at any hour, and the whole point of the two-hourly
 * drum-beat is that a parent who sees ONE of them can stop the countdown.
 */
class PanicDenyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DENY) return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as WalcottApplication
                app.syncManager.denyPanicRequest(deviceId, requestId)
                NotificationManagerCompat.from(context).cancel(SyncNotifications.panicNotifId(deviceId))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DENY = "dev.walcott.action.DENY_PANIC"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
