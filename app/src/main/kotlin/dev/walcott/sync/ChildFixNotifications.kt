package dev.walcott.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.R

/**
 * Notifications shown *on the child device* for the failures a parent cannot repair
 * remotely: permissions that only the person holding the phone can grant.
 *
 * Each one deep-links straight to the system screen that grants it, because "go to
 * Settings, then Special app access, then Usage access, then Walcott" is exactly the kind
 * of instruction that never gets followed. Triggered by the parent tapping "Ask to fix"
 * (see [RemoteAction.REQUEST_PERMISSIONS]).
 */
object ChildFixNotifications {

    const val FIX_USAGE_ACCESS = "usage_access"
    const val FIX_NETWORK_LOCATION = "network_location"
    const val FIX_LOCATION_PERMISSION = "location_permission"

    // "_quiet" channel id: the old HIGH-importance channel is immutable once created, so
    // silencing child-side notifications requires a new channel and deleting the old one.
    private const val CHANNEL = "walcott_child_fix_quiet"
    private const val OLD_CHANNEL = "walcott_child_fix"

    fun notify(context: Context, fix: String) {
        val (titleRes, textRes, intent) = when (fix) {
            FIX_USAGE_ACCESS -> Triple(
                R.string.fix_usage_access_title,
                R.string.fix_usage_access_text,
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
            FIX_NETWORK_LOCATION -> Triple(
                R.string.fix_network_location_title,
                R.string.fix_network_location_text,
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            )
            FIX_LOCATION_PERMISSION -> Triple(
                R.string.fix_location_permission_title,
                R.string.fix_location_permission_text,
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null),
                ),
            )
            else -> return
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel(OLD_CHANNEL)
            // LOW: visible in the shade with its deep link, but never a sound, vibration
            // or heads-up — the child device stays quiet.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.fix_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val tap = PendingIntent.getActivity(
            context, fix.hashCode(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textRes)))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(fix.hashCode(), notification) }
    }
}
