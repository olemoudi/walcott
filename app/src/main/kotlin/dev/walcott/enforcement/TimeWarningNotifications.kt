package dev.walcott.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.R
import dev.walcott.rules.BlockReason
import dev.walcott.rules.ClosingSoon

/**
 * The heads-up on the child's own phone that something is about to close: their time in an app,
 * bedtime, a screen-free window. Shown only while the phone is in use (see the enforcement
 * loop), because the point is to be read before the screen goes.
 *
 * A banner, but silent: inside a game a shade-only notification is invisible, which is exactly
 * the moment this exists for; a sound or a buzz would make a warning feel like a punishment.
 */
object TimeWarningNotifications {

    private const val CHANNEL = "walcott_time_warning"

    /** One id per reason: the 5-minute warning replaces the 30-minute one instead of stacking. */
    private fun idFor(closing: ClosingSoon) = ("warn|" + closing.reason + "|" + closing.packageName).hashCode()

    fun notify(context: Context, closing: ClosingSoon, minutes: Int, appLabel: String) {
        val title = when (closing.reason) {
            BlockReason.BUDGET_EXHAUSTED ->
                context.resources.getQuantityString(R.plurals.warn_budget_title, minutes, minutes, appLabel)
            BlockReason.BEDTIME ->
                context.resources.getQuantityString(R.plurals.warn_bedtime_title, minutes, minutes)
            BlockReason.BLOCKED_WINDOW ->
                context.resources.getQuantityString(R.plurals.warn_screen_free_title, minutes, minutes)
            else -> return
        }
        val text = context.getString(
            when (closing.reason) {
                BlockReason.BUDGET_EXHAUSTED -> R.string.warn_budget_text
                BlockReason.BEDTIME -> R.string.warn_bedtime_text
                else -> R.string.warn_screen_free_text
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.warn_channel_name),
                // HIGH so it surfaces over whatever is on screen; silenced below.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            // Gone by the time it could confuse: the thing it warned about has happened.
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(idFor(closing), notification) }
    }

    private const val TIMEOUT_MS = 10 * 60 * 1000L
}
