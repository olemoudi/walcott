package dev.walcott.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.R

/**
 * Shown on the child device when a parent pushes an app to install (see
 * [RemoteAction.INSTALL_APP]). Tapping opens the app's Play page so the child taps Install —
 * Play can't be driven silently, and a background activity start would be blocked anyway
 * (Android 10+), so a notification is the reliable way to surface it. Silent, like every
 * other child-side notification.
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
        // Prefer the Play app; fall back to the Play website if Play isn't installed.
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = if (marketIntent.resolveActivity(context.packageManager) != null) marketIntent else webIntent

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
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(pkg.hashCode(), notification) }
    }

    /** Whatever the package resolves to (if it's already known); otherwise the package name. */
    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
