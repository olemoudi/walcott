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
 * Shown on the child device when a parent pushes an app to install (see
 * [RemoteAction.INSTALL_APP]). Tapping routes through [InstallPromptActivity], which re-opens
 * the install window at tap time and forwards to the app's Play page — Play can't be driven
 * silently, and a background activity start would be blocked anyway (Android 10+), so a
 * notification is the reliable way to surface it. Silent, like every other child-side
 * notification; the child-home card is the backstop if it goes unnoticed. It stays up (no
 * auto-cancel) until the install completes, so a failed first attempt can be re-tapped.
 */
object InstallPromptNotifications {

    private const val CHANNEL = "walcott_install_quiet"

    fun notify(context: Context, pkg: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.install_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
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
        val label = appLabel(context, pkg)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.install_prompt_title))
            .setContentText(context.getString(R.string.install_prompt_text, label))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.install_prompt_text, label)))
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(pkg.hashCode(), notification) }
    }

    /** Dismisses the prompt for [pkg] (the pushed install completed or was superseded). */
    fun cancel(context: Context, pkg: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(pkg.hashCode()) }
    }

    /** Whatever the package resolves to (if it's already known); otherwise the package name. */
    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
