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

    /** Intent extra + values used to deep-link a notification tap to a screen. */
    const val EXTRA_DEST = "walcott_dest"
    const val DEST_APPS = "apps"
    const val DEST_APP_SETTINGS = "app_settings"

    /** Prefix + childId: a tap lands on that child's detail screen instead of the bare home. */
    const val DEST_CHILD_PREFIX = "child:"

    const val NOTIF_BACKUP_REMINDER = 4207

    /** Deep-link target for a per-child alert; legacy devices (blank childId) open the home. */
    private fun childDest(childId: String): String? =
        childId.takeIf { it.isNotBlank() }?.let { DEST_CHILD_PREFIX + it }

    /**
     * Who an alert is about: "Ana", or "Ana · Obiols" on a device that manages more than one
     * family. Notifications are the one surface shared by every family — the screens are all
     * per-family — so this is where a name that exists twice has to be told apart. [family] is
     * null (and the suffix absent) whenever the device holds a single family.
     */
    fun who(childName: String, family: String?): String =
        if (family.isNullOrBlank()) childName else "$childName · $family"

    /** Alert when a child device has been silent for a long time (see [Staleness]). */
    fun notifyStaleChild(context: Context, childName: String, silence: String, deviceId: String, childId: String = "") =
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = context.getString(R.string.stale_alert_title, childName),
            text = context.getString(R.string.stale_alert_text, silence),
            notifId = deviceId.hashCode(),
            dest = childDest(childId),
        )

    /** Alert when a child device reports that blocking is no longer active (tamper/lapse). */
    fun notifyEnforcementInactive(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.enforcement_off_title, childName),
        text = context.getString(R.string.enforcement_off_text),
        notifId = "enf".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a registered child device has never checked in (enrollment likely didn't finish). */
    fun notifyNeverReported(context: Context, childName: String, childId: String) = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.never_reported_title, childName),
        text = context.getString(R.string.never_reported_text),
        notifId = "never".hashCode() + childId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child loses full (Device Owner) protection but a weaker backend remains. */
    fun notifyEnforcementDegraded(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.enforcement_degraded_title, childName),
        text = context.getString(R.string.enforcement_degraded_text),
        notifId = "deg".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child device reports wrong parent-PIN attempts (someone guessing the PIN). */
    fun notifyWrongPin(context: Context, childName: String, total: Int, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.wrong_pin_title, childName),
        text = context.resources.getQuantityString(R.plurals.wrong_pin_text, total, total),
        notifId = "pin".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when usage access is off on a child: screen-time budgets silently stop counting. */
    fun notifyUsageAccessLost(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.usage_access_off_title, childName),
        text = context.getString(R.string.usage_access_off_text),
        notifId = "usage".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child's self-test reports blocked apps that are NOT actually suspended. */
    fun notifyEnforcementGap(context: Context, childName: String, count: Int, deviceId: String, childId: String = "") =
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = context.getString(R.string.enforcement_gap_title, childName),
            text = context.resources.getQuantityString(R.plurals.enforcement_gap_text, count, count),
            notifId = "gap".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
        )

    /** Alert when a child's clock disagrees with the sync server far beyond drift (tamper). */
    fun notifyClockTamper(context: Context, childName: String, skewMs: Long, deviceId: String, childId: String = "") =
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = context.getString(R.string.clock_tamper_title, childName),
            text = context.getString(R.string.clock_tamper_text, formatSkew(context, skewMs)),
            notifId = "clock".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
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
    fun notifyMockLocation(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.mock_location_title, childName),
        text = context.getString(R.string.mock_location_text),
        notifId = "mock".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child device drops below the low-battery mark unplugged (it may die soon). */
    fun notifyLowBattery(context: Context, childName: String, percent: Int, deviceId: String, childId: String = "") =
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = context.getString(R.string.low_battery_title, childName),
            text = context.getString(R.string.low_battery_text, percent),
            notifId = "batt".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
        )

    /** Alert when network (Wi-Fi/cell) location is off on a child: indoor tracking stops working. */
    fun notifyNetworkLocationOff(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.net_location_off_title, childName),
        text = context.getString(R.string.net_location_off_text),
        notifId = "netloc".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Notification id of a child's emergency-release alert (also cancelled by the deny action). */
    fun panicNotifId(deviceId: String): Int = "panic".hashCode() + deviceId.hashCode()

    /**
     * A child asked to be released from Walcott (see [PanicProtocol]). Posted on the request and
     * again on every two-hourly notice — the drum-beat is what makes the release refusable — and
     * carries a one-tap refusal, since the alert may well arrive at 3 a.m. and the parent
     * shouldn't have to open the app to stop it.
     */
    fun notifyPanicRequest(
        context: Context,
        childName: String,
        request: PanicRequest,
        deviceId: String,
        childId: String = "",
    ) {
        val released = request.checkpoints >= PanicProtocol.REQUIRED_CHECKPOINTS
        val remaining = PanicProtocol.remainingCheckpoints(request)
        val hoursLeft = (remaining * PanicProtocol.CHECKPOINT_INTERVAL_SEC / 3600).toInt()
        val notifId = panicNotifId(deviceId)
        val deny = PendingIntent.getBroadcast(
            context, notifId,
            Intent(context, PanicDenyReceiver::class.java)
                .setAction(PanicDenyReceiver.ACTION_DENY)
                .putExtra(PanicDenyReceiver.EXTRA_DEVICE_ID, deviceId)
                .putExtra(PanicDenyReceiver.EXTRA_REQUEST_ID, request.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = context.getString(
                if (released) R.string.panic_released_title else R.string.panic_alert_title,
                childName,
            ),
            text = if (released) {
                context.getString(R.string.panic_released_text)
            } else {
                context.resources.getQuantityString(R.plurals.panic_alert_text, hoursLeft, hoursLeft)
            },
            notifId = notifId,
            dest = childDest(childId),
            actions = if (released) {
                emptyList()
            } else {
                listOf(NotificationCompat.Action(0, context.getString(R.string.panic_deny_action), deny))
            },
        )
    }

    /**
     * The Approve/Deny pair a request notification carries (see [RequestActionReceiver]). One
     * PendingIntent per (verb, request) so two pending requests never collapse into one intent,
     * which would answer the wrong child's question.
     *
     * Empty when [quickAnswer] is false — a parent who turned the app lock on asked for a gate
     * between a phone lying unlocked on the table and their family's rules, and a button in the
     * shade is not behind that gate.
     */
    private fun answerActions(
        context: Context,
        requestId: String,
        notifId: Int,
        approveLabel: String,
        quickAnswer: Boolean,
    ): List<NotificationCompat.Action> {
        if (!quickAnswer) return emptyList()
        fun broadcast(action: String) = PendingIntent.getBroadcast(
            context, (action + requestId).hashCode(),
            Intent(context, RequestActionReceiver::class.java)
                .setAction(action)
                .putExtra(RequestActionReceiver.EXTRA_REQUEST_ID, requestId)
                .putExtra(RequestActionReceiver.EXTRA_NOTIF_ID, notifId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return listOf(
            NotificationCompat.Action(0, approveLabel, broadcast(RequestActionReceiver.ACTION_APPROVE)),
            NotificationCompat.Action(0, context.getString(R.string.deny), broadcast(RequestActionReceiver.ACTION_DENY)),
        )
    }

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
    fun notifyAsk(context: Context, childName: String, text: String, requestId: String, quickAnswer: Boolean) {
        // Per-ask id so several pending asks don't clobber each other (or the requests notification).
        val notifId = ("ask$requestId").hashCode()
        post(
            context, CHANNEL, R.string.sync_request_channel_name,
            title = context.getString(R.string.sync_ask_title, childName),
            text = text,
            notifId = notifId,
            actions = answerActions(context, requestId, notifId, context.getString(R.string.approve), quickAnswer),
        )
    }

    /** A child shared one concrete app from Play and wants it installed (see KIND_INSTALL). */
    fun notifyInstallAsk(
        context: Context,
        childName: String,
        appLabel: String,
        requestId: String,
        quickAnswer: Boolean,
    ) {
        val notifId = ("ask$requestId").hashCode()
        post(
            context, CHANNEL, R.string.sync_request_channel_name,
            title = context.getString(R.string.sync_install_ask_title, childName),
            text = context.getString(R.string.sync_install_ask_text, appLabel),
            notifId = notifId,
            // Approving here opens the single-app install window, exactly as the card does.
            actions = answerActions(context, requestId, notifId, context.getString(R.string.approve), quickAnswer),
        )
    }

    /**
     * An install window has been open on a child device for over an hour (the "I don't know
     * how long" unlock). Hourly nag with a one-tap re-block, so an open-ended window is never
     * quietly forgotten — the child re-arms itself at the 8-hour mark regardless.
     */
    fun notifyInstallWindowOpen(
        context: Context,
        childName: String,
        remaining: String,
        deviceId: String,
        childId: String = "",
    ) {
        val reblock = PendingIntent.getBroadcast(
            context, "reblock$deviceId".hashCode(),
            Intent(context, InstallReminderReceiver::class.java)
                .setAction(InstallReminderReceiver.ACTION_REBLOCK)
                .putExtra(InstallReminderReceiver.EXTRA_DEVICE_ID, deviceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = context.getString(R.string.install_window_open_title, childName),
            text = context.getString(R.string.install_window_open_text, remaining),
            notifId = "installwin".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
            actions = listOf(
                NotificationCompat.Action(0, context.getString(R.string.install_window_reblock), reblock),
            ),
        )
    }

    /** A different app than the approved one was installed during a window (and removed). */
    fun notifyWrongApp(context: Context, childName: String, pkg: String, deviceId: String, childId: String = "") = post(
        context, ALERT_CHANNEL, R.string.stale_channel_name,
        title = context.getString(R.string.wrong_app_title, childName),
        text = context.getString(R.string.wrong_app_text, pkg),
        notifId = "wrongapp".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Cancels the open-window nag once the window is closed (re-blocked or expired). */
    fun cancelInstallWindowOpen(context: Context, deviceId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel("installwin".hashCode() + deviceId.hashCode())
        }
    }

    /**
     * A child's domain selection arrived whole and is waiting to be turned into rules. Its own
     * notification rather than the generic ask: answering it is not yes/no, it is choosing a reach.
     */
    fun notifyDomainRequest(context: Context, childName: String, appLabel: String, count: Int, childId: String) = post(
        context, CHANNEL, R.string.sync_request_channel_name,
        title = context.getString(R.string.sync_domains_title, childName),
        text = context.resources.getQuantityString(R.plurals.sync_domains_text, count, count, appLabel),
        notifId = ("domains$childId$appLabel").hashCode(),
        dest = childDest(childId),
    )

    /**
     * A child is asking for extra time — the most answered notification in the app, so it
     * answers itself: Approve grants the minutes asked for, Deny says no, neither needs the app
     * opened. Any other amount is a considered answer and stays on the card.
     */
    fun notifyRequest(context: Context, childName: String, minutes: Int, requestId: String, quickAnswer: Boolean) {
        // Per-request, like the asks: a fixed id meant a second child asking replaced the first
        // child's notification, and one of the two questions was simply never seen.
        val notifId = ("request$requestId").hashCode()
        post(
            context, CHANNEL, R.string.sync_request_channel_name,
            title = context.getString(R.string.sync_request_title),
            text = context.getString(R.string.sync_request_text, childName, minutes),
            notifId = notifId,
            actions = answerActions(
                context, requestId, notifId,
                context.getString(R.string.request_grant_minutes, minutes), quickAnswer,
            ),
        )
    }

    /**
     * Nudge to create/refresh the family backup; its action mutes the reminders for good.
     * One reminder per family (each has its own backup file), so the id carries the family.
     */
    fun notifyBackupReminder(
        context: Context,
        neverBackedUp: Boolean,
        family: String? = null,
        familyId: String = dev.walcott.data.FamilyIds.DEFAULT,
    ) {
        val notifId = if (familyId == dev.walcott.data.FamilyIds.DEFAULT) {
            NOTIF_BACKUP_REMINDER
        } else {
            NOTIF_BACKUP_REMINDER + familyId.hashCode()
        }
        val mute = PendingIntent.getBroadcast(
            context, notifId,
            Intent(context, BackupReminderReceiver::class.java).setAction(BackupReminderReceiver.ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, ALERT_CHANNEL, R.string.stale_channel_name,
            title = who(
                context.getString(
                    if (neverBackedUp) R.string.backup_reminder_title_never else R.string.backup_reminder_title_stale,
                ),
                family,
            ),
            text = context.getString(
                if (neverBackedUp) R.string.backup_reminder_text_never else R.string.backup_reminder_text_stale,
            ),
            notifId = notifId,
            dest = DEST_APP_SETTINGS,
            actions = listOf(NotificationCompat.Action(0, context.getString(R.string.backup_reminder_mute), mute)),
        )
    }

    private fun post(
        context: Context,
        channel: String,
        channelNameRes: Int,
        title: String,
        text: String,
        notifId: Int,
        dest: String? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
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
            .apply { actions.forEach { addAction(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
    }
}
