package dev.walcott.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.R

/** "Update ready — tap to install" prompt for devices where installs need confirmation. */
object UpdateNotifications {

    // "_quiet" id: see ChildFixNotifications — channel importance is immutable, so going
    // silent needs a fresh channel. An update prompt is never urgent enough to buzz.
    private const val CHANNEL = "walcott_updates_quiet"
    private const val OLD_CHANNEL = "walcott_updates"
    private const val NOTIF_ID = 43

    fun notifyConfirmationNeeded(context: Context, confirmIntent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel(OLD_CHANNEL)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val tap = PendingIntent.getActivity(
            context, 0, confirmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }
}
