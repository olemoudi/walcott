package dev.walcott.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.R
import dev.walcott.setup.DeviceRequirement
import dev.walcott.setup.DeviceSetupProbe

/**
 * Notifications shown *on the child device* for the failures a parent cannot repair remotely:
 * the settings that only the person holding the phone can change.
 *
 * Each one deep-links straight to the system screen that grants it, because "go to Settings, then
 * Special app access, then Usage access, then Walcott" is exactly the kind of instruction that
 * never gets followed. What each requirement is, and where it is fixed, comes from
 * [DeviceRequirement] / [DeviceSetupProbe] — the same source the on-screen cards use, so the
 * notification and the card can never disagree about what is wrong.
 *
 * Raised by the parent tapping "Ask to fix" ([RemoteAction.REQUEST_PERMISSIONS]) and by the
 * device's own periodic check ([ChildHealthCheck]).
 */
object ChildFixNotifications {

    // "_quiet" channel id: the old HIGH-importance channel is immutable once created, so
    // silencing child-side notifications requires a new channel and deleting the old one.
    private const val CHANNEL = "walcott_child_fix_quiet"
    private const val OLD_CHANNEL = "walcott_child_fix"

    fun notify(context: Context, requirement: DeviceRequirement) {
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
        val body = context.getString(requirement.bodyRes)
        val tap = PendingIntent.getActivity(
            context,
            requirement.key.hashCode(),
            DeviceSetupProbe.fixIntent(context, requirement),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(requirement.titleRes))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(requirement.key.hashCode(), notification)
        }
    }
}
