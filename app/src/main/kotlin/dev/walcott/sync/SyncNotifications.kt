package dev.walcott.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.walcott.MainActivity
import dev.walcott.R

/** Parent-side heads-up notifications for child requests and health/tamper alerts. */
object SyncNotifications {

    private const val CHANNEL = "walcott_requests"
    private const val ALERT_CHANNEL = "walcott_alerts"
    private const val NOTIF_ID = 42

    /** Intent extra + values used to deep-link a notification tap to a screen. */
    const val EXTRA_DEST = "walcott_dest"
    const val DEST_APPS = "apps"

    /** Alert when a child device has been silent for a long time (see [Staleness]). */
    fun notifyStaleChild(context: Context, childName: String, silence: String, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.stale_alert_title, childName),
        text = context.getString(R.string.stale_alert_text, silence),
        notifId = deviceId.hashCode(),
    )

    /** Alert when a child device reports that blocking is no longer active (tamper/lapse). */
    fun notifyEnforcementInactive(context: Context, childName: String, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.enforcement_off_title, childName),
        text = context.getString(R.string.enforcement_off_text),
        notifId = "enf".hashCode() + deviceId.hashCode(),
    )

    /** Alert when a registered child device has never checked in (enrollment likely didn't finish). */
    fun notifyNeverReported(context: Context, childName: String, childId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.never_reported_title, childName),
        text = context.getString(R.string.never_reported_text),
        notifId = "never".hashCode() + childId.hashCode(),
    )

    /** Alert when a child loses full (Device Owner) protection but a weaker backend remains. */
    fun notifyEnforcementDegraded(context: Context, childName: String, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.enforcement_degraded_title, childName),
        text = context.getString(R.string.enforcement_degraded_text),
        notifId = "deg".hashCode() + deviceId.hashCode(),
    )

    /** Alert when a child device reports wrong parent-PIN attempts (someone guessing the PIN). */
    fun notifyWrongPin(context: Context, childName: String, total: Int, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.wrong_pin_title, childName),
        text = context.resources.getQuantityString(R.plurals.wrong_pin_text, total, total),
        notifId = "pin".hashCode() + deviceId.hashCode(),
    )

    /** Alert when usage access is off on a child: screen-time budgets silently stop counting. */
    fun notifyUsageAccessLost(context: Context, childName: String, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.usage_access_off_title, childName),
        text = context.getString(R.string.usage_access_off_text),
        notifId = "usage".hashCode() + deviceId.hashCode(),
    )

    /** Alert when a child's self-test reports blocked apps that are NOT actually suspended. */
    fun notifyEnforcementGap(context: Context, childName: String, count: Int, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.enforcement_gap_title, childName),
        text = context.resources.getQuantityString(R.plurals.enforcement_gap_text, count, count),
        notifId = "gap".hashCode() + deviceId.hashCode(),
    )

    /** Alert when a child's clock disagrees with the sync server far beyond drift (tamper). */
    fun notifyClockTamper(context: Context, childName: String, skewMs: Long, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.clock_tamper_title, childName),
        text = context.getString(R.string.clock_tamper_text, formatSkew(context, skewMs)),
        notifId = "clock".hashCode() + deviceId.hashCode(),
    )

    /** "2 h 5 min behind" / "35 min ahead", for the clock-tamper alert and card. */
    fun formatSkew(context: Context, skewMs: Long): String {
        val minutes = kotlin.math.abs(skewMs) / 60_000
        val amount = when {
            minutes >= 60 && minutes % 60 == 0L -> context.getString(R.string.hours_fmt, minutes / 60)
            minutes >= 60 -> context.getString(R.string.skew_hours_minutes, minutes / 60, minutes % 60)
            else -> context.getString(R.string.minutes_fmt, minutes)
        }
        return context.getString(if (skewMs < 0) R.string.skew_behind else R.string.skew_ahead, amount)
    }

    /** Alert when a child's reported locations include mock (spoofed) fixes. */
    fun notifyMockLocation(context: Context, childName: String, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.mock_location_title, childName),
        text = context.getString(R.string.mock_location_text),
        notifId = "mock".hashCode() + deviceId.hashCode(),
    )

    /** Alert when a child device drops below the low-battery mark unplugged (it may die soon). */
    fun notifyLowBattery(context: Context, childName: String, percent: Int, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.low_battery_title, childName),
        text = context.getString(R.string.low_battery_text, percent),
        notifId = "batt".hashCode() + deviceId.hashCode(),
    )

    /** Alert when network (Wi-Fi/cell) location is off on a child: indoor tracking stops working. */
    fun notifyNetworkLocationOff(context: Context, childName: String, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.net_location_off_title, childName),
        text = context.getString(R.string.net_location_off_text),
        notifId = "netloc".hashCode() + deviceId.hashCode(),
    )

    /** A child installed app(s) the family hasn't classified yet (blocked until classified). */
    fun notifyNewApp(context: Context, childName: String, label: String, extraCount: Int, deviceId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.new_app_title, childName),
        text = if (extraCount > 0) {
            context.getString(R.string.new_app_text_more, label, extraCount)
        } else {
            context.getString(R.string.new_app_text, label)
        },
        notifId = "newapp".hashCode() + deviceId.hashCode(),
        dest = DEST_APPS,
    )

    /** A child asked for something (an app install, anything free-form). */
    fun notifyAsk(context: Context, childName: String, text: String, requestId: String) = post(
        context, CHANNEL, R.string.sync_request_channel_name,
        title = context.getString(R.string.sync_ask_title, childName),
        text = text,
        // Per-ask id so several pending asks don't clobber each other (or the requests notification).
        notifId = ("ask$requestId").hashCode(),
    )

    fun notifyRequest(context: Context, childName: String, minutes: Int) = post(
        context, CHANNEL, R.string.sync_request_channel_name,
        title = context.getString(R.string.sync_request_title),
        text = context.getString(R.string.sync_request_text, childName, minutes),
        notifId = NOTIF_ID,
    )

    private fun post(
        context: Context,
        channel: String,
        channelNameRes: Int,
        title: String,
        text: String,
        notifId: Int,
        dest: String? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channel, context.getString(channelNameRes), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (dest != null) putExtra(EXTRA_DEST, dest) }
        val tap = PendingIntent.getActivity(
            // Unique request code per destination so distinct extras aren't collapsed into one PendingIntent.
            context, notifId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
    }
}
