package dev.walcott.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.R
import dev.walcott.install.InstallPromptActivity

/**
 * Shown on the child device when an app has been approved for install (see
 * [RemoteAction.INSTALL_APP]). Tapping routes through [InstallPromptActivity], which re-opens
 * the install window at tap time and forwards to the app's Play page.
 *
 * The one child-side notification that is NOT silent, and deliberately so. Every other one
 * tells the child something about the rules; this one is the answer they have been waiting for,
 * it opens a window that expires, and it is the only thing on the phone that says where to go
 * next. A silent low-importance line at the bottom of the shade was the difference between "it
 * installs by itself" and a child concluding that nothing happened.
 *
 * It stays up (no auto-cancel) until the install completes, so a failed first attempt can be
 * re-tapped, and the child-home card is the backstop if it is swiped away.
 */
object InstallPromptNotifications {

    /** Importance is fixed at creation, so raising it needs a new id (the old one is deleted). */
    private const val CHANNEL = "walcott_install"
    private const val OLD_CHANNEL = "walcott_install_quiet"

    fun notify(context: Context, pkg: String, label: String = "") {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel(OLD_CHANNEL)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.install_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val open = Intent(context, InstallPromptActivity::class.java)
            .putExtra(InstallPromptActivity.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, pkg.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val shown = appLabel(context, pkg, label)
        val text = context.getString(R.string.install_prompt_text, shown)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.install_prompt_title, shown))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(pkg.hashCode(), notification) }
    }

    /** Dismisses the prompt for [pkg] (the pushed install completed or was superseded). */
    fun cancel(context: Context, pkg: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(pkg.hashCode()) }
    }

    /**
     * The app's name: the one the parent's device sent, since the app is not installed here yet
     * and the local package manager can only offer "com.some.package" — which is exactly the
     * string a child cannot recognise as the game they asked for.
     */
    private fun appLabel(context: Context, pkg: String, label: String): String {
        if (label.isNotBlank()) return label
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
