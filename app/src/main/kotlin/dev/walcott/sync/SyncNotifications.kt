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

    /**
     * Parent alerts used to share one channel at maximum importance, which made Android's own
     * per-channel controls useless to the person receiving them: silencing "the battery is at
     * 18%" also silenced "the clock has been changed" and "your child is asking to be released".
     * A parent who mutes the noise loses the alarm, so most keep all of it and stop reading any.
     *
     * Two channels instead, split by what the parent is supposed to DO about it:
     *
     * - [URGENT_CHANNEL]: something is wrong with the protection itself, or the child is asking
     *   for their phone back. Worth interrupting for.
     * - [STATUS_CHANNEL]: worth knowing, not worth waking up for — a low battery, a phone that
     *   has gone quiet, a newly installed app.
     *
     * New ids on purpose: a channel's importance is immutable once created, so the old one is
     * deleted rather than reused (the same trick the quiet child-side channels needed).
     */
    private const val URGENT_CHANNEL = "walcott_urgent"
    private const val STATUS_CHANNEL = "walcott_status"
    private const val OLD_ALERT_CHANNEL = "walcott_alerts"

    /**
     * Fixed: there is one family per scope, so a second takeover replaces the first alert
     * rather than stacking beside it.
     */
    private const val SUPERSEDED_NOTIF_ID = 8801

    /** Fixed too: one outage, one line in the shade. */
    private const val RELAY_REFUSING_NOTIF_ID = 8802

    /** Intent extra + values used to deep-link a notification tap to a screen. */
    const val EXTRA_DEST = "walcott_dest"
    const val DEST_APPS = "apps"
    const val DEST_APP_SETTINGS = "app_settings"

    /** Prefix + childId: a tap lands on that child's detail screen instead of the bare home. */
    const val DEST_CHILD_PREFIX = "child:"

    /**
     * Prefix + requestId: a tap on a child's request carries WHICH request, so the app can say
     * what became of it when there is no longer a card to show (see [SyncManager.requestState]).
     */
    const val DEST_REQUEST_PREFIX = "request:"

    private fun requestDest(requestId: String): String? =
        requestId.takeIf { it.isNotBlank() }?.let { DEST_REQUEST_PREFIX + it }

    /**
     * Retires both notifications a request could have posted, whichever kind it was. Called
     * when the answer happened somewhere other than this notification — in the app, or from
     * the other button — so no shade is left offering a question that has one.
     */
    fun cancelRequest(context: Context, requestId: String) {
        val nm = NotificationManagerCompat.from(context)
        runCatching { nm.cancel(("request$requestId").hashCode()) }
        runCatching { nm.cancel(("ask$requestId").hashCode()) }
    }

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

    /**
     * Alert when a child device has been silent for a long time (see [Staleness]).
     *
     * [lastWord] is the phone's own account of why it went quiet, when it left one (see
     * [LastGaspText]); it replaces "hasn't checked in for 12 hours", which is true and useless
     * beside "its battery was about to run out at 22:41".
     */
    fun notifyStaleChild(
        context: Context,
        childName: String,
        silence: String,
        deviceId: String,
        childId: String = "",
        lastWord: String? = null,
    ) =
        post(
            context, STATUS_CHANNEL, R.string.status_channel_name,
            title = context.getString(R.string.stale_alert_title, childName),
            text = lastWord ?: context.getString(R.string.stale_alert_text, silence),
            notifId = deviceId.hashCode(),
            dest = childDest(childId),
        )

    /**
     * The other side of [notifyStaleChild]: a phone that had gone quiet has just checked in.
     *
     * [silence] is how long it was gone, or null when this child had never reported at all until
     * now (a botched enrollment that finally finished). On the status channel and never on the
     * urgent one: it is good news, and good news at 3 a.m. is still 3 a.m.
     *
     * Retiring the alarm it answers is the CALLER's job ([cancelStale]), because that happens on
     * every return and this only on the ones worth mentioning (see Staleness.worthAnnouncingReturn).
     */
    fun notifyChildBack(
        context: Context,
        childName: String,
        silence: String?,
        deviceId: String,
        childId: String = "",
    ) {
        post(
            context, STATUS_CHANNEL, R.string.status_channel_name,
            title = context.getString(R.string.back_online_title, childName),
            text = if (silence == null) {
                context.getString(R.string.back_online_first_text)
            } else {
                context.getString(R.string.back_online_text, silence)
            },
            notifId = ("back$deviceId").hashCode(),
            dest = childDest(childId),
        )
    }

    /** Retires the "gone quiet" alert for [deviceId] (it is back, or it has been let go). */
    fun cancelStale(context: Context, deviceId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(deviceId.hashCode()) }
    }

    /** Alert when a child device reports that blocking is no longer active (tamper/lapse). */
    fun notifyEnforcementInactive(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.enforcement_off_title, childName),
        text = context.getString(R.string.enforcement_off_text),
        notifId = "enf".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * Another phone restored this family's backup and is now the parent the children follow.
     *
     * URGENT, and it names the consequence rather than the mechanism: what a parent needs to know
     * is that the rules they change here stop arriving, not that a version counter was outranked.
     */
    fun notifyFamilyTakenOver(context: Context, family: String?) = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.superseded_title),
        text = family?.let { context.getString(R.string.superseded_text_family, it) }
            ?: context.getString(R.string.superseded_text),
        notifId = SUPERSEDED_NOTIF_ID,
    )

    /** Taken back, or the parent said they meant it: the alert has nothing left to say. */
    fun cancelFamilyTakenOver(context: Context) {
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(SUPERSEDED_NOTIF_ID) }
    }

    /**
     * A child checked in from a phone this family had not seen before.
     *
     * Almost always a replacement the parent is holding — a factory reset cannot keep the old
     * device id — and it needs saying because until the old row is retired both are on file, and
     * the phone shown on the screens is chosen by which one spoke last rather than by which one
     * the family means.
     */
    fun notifyDeviceReplaced(context: Context, childName: String, deviceId: String, childId: String) = post(
        context, STATUS_CHANNEL, R.string.status_channel_name,
        title = context.getString(R.string.device_replaced_title, childName),
        text = context.getString(R.string.device_replaced_text),
        notifId = "replaced".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * A phone that was counting down to freeing itself has stopped asking.
     *
     * Posted for every ending except the parent's own refusal: withdrawn by the child, killed by
     * the connectivity rule — or claimed by a message this parent cannot verify, since child
     * messages carry no signature. An alarm that ends by silently vanishing is indistinguishable
     * from one somebody switched off.
     */
    fun notifyPanicStopped(context: Context, childName: String, deviceId: String, childId: String) = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.panic_stopped_title, childName),
        text = context.getString(R.string.panic_stopped_text),
        notifId = "panicstop".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * A rescue code was typed into a child's phone and opened it.
     *
     * URGENT: the code lifts bedtime, every budget and the DNS curfew for up to three hours, and
     * it is designed to work with no network and no PIN — so this notice, whenever the phone
     * next reaches the family, is the only account of it a parent gets.
     */
    fun notifyRescueUsed(context: Context, childName: String, deviceId: String, childId: String) = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.rescue_used_title, childName),
        text = context.getString(R.string.rescue_used_text),
        notifId = "rescue".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * The relay is refusing this phone's messages.
     *
     * Rate limiting gets its own wording because it has its own answer — the public server's
     * limits are per visitor and a household shares one address, so the fix is a relay of the
     * family's own rather than waiting.
     */
    fun notifyRelayRefusing(context: Context, family: String?, rateLimited: Boolean) = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = family?.let { context.getString(R.string.relay_refusing_title_family, it) }
            ?: context.getString(R.string.relay_refusing_title),
        text = context.getString(
            if (rateLimited) R.string.relay_refusing_rate_limited else R.string.relay_refusing_text,
        ),
        notifId = RELAY_REFUSING_NOTIF_ID,
    )

    /** A publish got through: whatever was in the way is gone. */
    fun cancelRelayRefusing(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(RELAY_REFUSING_NOTIF_ID) }
    }

    /** Alert when a registered child device has never checked in (enrollment likely didn't finish). */
    fun notifyNeverReported(context: Context, childName: String, childId: String) = post(
        context, STATUS_CHANNEL, R.string.status_channel_name,
        title = context.getString(R.string.never_reported_title, childName),
        text = context.getString(R.string.never_reported_text),
        notifId = "never".hashCode() + childId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * Reminder that a child's phone is still missing settings nobody granted at enrollment.
     *
     * URGENT rather than STATUS: what is missing is not a nicety — usage access, the blocker,
     * notifications — and a phone in this state enforces less than the parent believes it does.
     * It also cannot be fixed from here, which is the whole message: someone has to pick that
     * phone up. Tapping lands on the child, where the list and the remote nudge live.
     */
    fun notifySetupPending(
        context: Context,
        childName: String,
        count: Int,
        deviceId: String,
        childId: String = "",
    ) = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.setup_pending_title, childName),
        text = context.resources.getQuantityString(R.plurals.setup_pending_text, count, count),
        notifId = "setup".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child loses full (Device Owner) protection but a weaker backend remains. */
    fun notifyEnforcementDegraded(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.enforcement_degraded_title, childName),
        text = context.getString(R.string.enforcement_degraded_text),
        notifId = "deg".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child device reports wrong parent-PIN attempts (someone guessing the PIN). */
    fun notifyWrongPin(context: Context, childName: String, total: Int, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.wrong_pin_title, childName),
        text = context.resources.getQuantityString(R.plurals.wrong_pin_text, total, total),
        notifId = "pin".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when usage access is off on a child: screen-time budgets silently stop counting. */
    fun notifyUsageAccessLost(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.usage_access_off_title, childName),
        text = context.getString(R.string.usage_access_off_text),
        notifId = "usage".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child's self-test reports blocked apps that are NOT actually suspended. */
    fun notifyEnforcementGap(context: Context, childName: String, count: Int, deviceId: String, childId: String = "") =
        post(
            context, URGENT_CHANNEL, R.string.urgent_channel_name,
            title = context.getString(R.string.enforcement_gap_title, childName),
            text = context.resources.getQuantityString(R.plurals.enforcement_gap_text, count, count),
            notifId = "gap".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
        )

    /** Alert when a child's clock disagrees with the sync server far beyond drift (tamper). */
    fun notifyClockTamper(context: Context, childName: String, skewMs: Long, deviceId: String, childId: String = "") =
        post(
            context, URGENT_CHANNEL, R.string.urgent_channel_name,
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

    /**
     * Alert when the rules ask a child for a DNS filter and the tunnel isn't up. Consent
     * withdrawn, another VPN app holding the tun, or a refused device-policy call all look
     * identical from here — and all mean the blocked domains are resolving normally.
     */
    fun notifyWebFilterDown(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.web_filter_down_title, childName),
        text = context.getString(R.string.web_filter_down_text),
        notifId = "webfilter".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * Alert when a child device has died of an uncaught exception since the last check-in.
     * [count] is how many new ones, not the lifetime total — "it crashed again" is the news.
     */
    fun notifyChildCrashed(
        context: Context,
        childName: String,
        count: Int,
        deviceId: String,
        childId: String = "",
    ) = post(
        context, STATUS_CHANNEL, R.string.status_channel_name,
        title = context.getString(R.string.child_crashed_title, childName),
        text = context.resources.getQuantityString(R.plurals.child_crashed_text, count, count),
        notifId = "crash".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child's reported locations include mock (spoofed) fixes. */
    fun notifyMockLocation(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.mock_location_title, childName),
        text = context.getString(R.string.mock_location_text),
        notifId = "mock".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Alert when a child device drops below the low-battery mark unplugged (it may die soon). */
    fun notifyLowBattery(context: Context, childName: String, percent: Int, deviceId: String, childId: String = "") =
        post(
            context, STATUS_CHANNEL, R.string.status_channel_name,
            title = context.getString(R.string.low_battery_title, childName),
            text = context.getString(R.string.low_battery_text, percent),
            notifId = "batt".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
        )

    /**
     * A phone's last word before going quiet (see [LastGasp]): [word] is the sentence
     * ([LastGaspText]), and the text says where to look when the word came with a position.
     * On the status channel like the low-battery alert it follows: news, not an alarm.
     */
    fun notifyLastGasp(
        context: Context,
        childName: String,
        word: String,
        deviceId: String,
        childId: String = "",
        hasFix: Boolean = false,
    ) =
        post(
            context, STATUS_CHANNEL, R.string.status_channel_name,
            title = context.getString(R.string.last_gasp_title, childName),
            text = if (hasFix) context.getString(R.string.last_gasp_text_with_fix, word) else word,
            notifId = "gasp".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
        )

    /** Alert when network (Wi-Fi/cell) location is off on a child: indoor tracking stops working. */
    fun notifyNetworkLocationOff(context: Context, childName: String, deviceId: String, childId: String = "") = post(
        context, STATUS_CHANNEL, R.string.status_channel_name,
        title = context.getString(R.string.net_location_off_title, childName),
        text = context.getString(R.string.net_location_off_text),
        notifId = "netloc".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /** Notification id of a child's emergency-release alert (also cancelled by the deny action). */
    fun panicNotifId(deviceId: String): Int = "panic".hashCode() + deviceId.hashCode()

    /**
     * A child asked to be released from Walcott (see [PanicProtocol]). Posted on the request and
     * again on every hourly notice — the drum-beat is what makes the release refusable — and
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
            context, URGENT_CHANNEL, R.string.urgent_channel_name,
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
            // Only while the countdown is running. The closing "it has been released" notice is
            // news, not an alarm, and an ongoing one could never be swiped away.
            alarm = !released,
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
            // Approving GRANTS something, so the phone has to be unlocked for it. A broadcast
            // action otherwise fires straight from the lock screen: the child who sent the
            // request only has to wait for the parent's phone to be face-up on the table.
            guarded(approveLabel, broadcast(RequestActionReceiver.ACTION_APPROVE)),
            NotificationCompat.Action(0, context.getString(R.string.deny), broadcast(RequestActionReceiver.ACTION_DENY)),
        )
    }

    /**
     * A notification action that Android will not fire until the phone is unlocked.
     *
     * For the ones that hand something out — minutes, or leave to keep an app. Refusals are left
     * ungated on purpose: they are the safe answer, and the emergency-release refusal in
     * particular has to work at three in the morning without finding a PIN first.
     *
     * `setAuthenticationRequired` needs API 31; below it the platform has no such concept and the
     * builder simply ignores the request.
     */
    private fun guarded(label: String, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, label, intent)
            .setAuthenticationRequired(true)
            .build()

    /**
     * A child installed app(s) the family has not given a limit of its own.
     *
     * NOT "blocked until you classify it", which is what this used to say. There are no categories
     * to classify into any more, and an app with no assignment is ALLOWED: it obeys bedtime, the
     * screen-free windows and the day's total like everything else, and it is limited only if the
     * family set a default per-app budget. The install guard is the thing that blocks a new app,
     * and it has its own, louder notification (see [notifyUnauthorizedApp]) — this one fires
     * precisely when the guard did not act.
     */
    fun notifyNewApp(context: Context, childName: String, label: String, extraCount: Int, deviceId: String) = post(
        context, STATUS_CHANNEL, R.string.status_channel_name,
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
            dest = requestDest(requestId),
            actions = answerActions(context, requestId, notifId, context.getString(R.string.approve), quickAnswer),
        )
    }

    /**
     * Somebody pressed the help button on an assisted phone (see [ChildRequest.KIND_HELP]).
     *
     * No quick-answer actions, unlike every other ask: there is nothing to approve from the shade,
     * and a notification offering "Approve" over "Mum needs a hand" would be answering the wrong
     * question. Tapping opens the request, which is where the phone's cards are one step away.
     */
    fun notifyHelpAsk(context: Context, childName: String, requestId: String) = post(
        context, CHANNEL, R.string.sync_request_channel_name,
        title = context.getString(R.string.sync_help_ask_title, childName),
        text = context.getString(R.string.sync_help_ask_text),
        notifId = ("ask$requestId").hashCode(),
        dest = requestDest(requestId),
    )

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
            dest = requestDest(requestId),
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
        /** True in the guarded mode, where what is open is the guard's eyes and not a block. */
        watching: Boolean = false,
    ) {
        val reblock = PendingIntent.getBroadcast(
            context, "reblock$deviceId".hashCode(),
            Intent(context, InstallReminderReceiver::class.java)
                .setAction(InstallReminderReceiver.ACTION_REBLOCK)
                .putExtra(InstallReminderReceiver.EXTRA_DEVICE_ID, deviceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, STATUS_CHANNEL, R.string.status_channel_name,
            // The same window, and not the same sentence. Told "installs are still allowed" by
            // a phone where installs are ALWAYS allowed, a parent in the guarded mode learns
            // nothing; what they left open is the judging of what turns up.
            title = context.getString(
                if (watching) R.string.pause_watch_open_title else R.string.install_window_open_title,
                childName,
            ),
            text = context.getString(
                if (watching) R.string.pause_watch_open_text else R.string.install_window_open_text,
                remaining,
            ),
            notifId = "installwin".hashCode() + deviceId.hashCode(),
            dest = childDest(childId),
            actions = listOf(
                NotificationCompat.Action(
                    0,
                    context.getString(
                        if (watching) R.string.pause_watch_resume else R.string.install_window_reblock,
                    ),
                    reblock,
                ),
            ),
        )
    }

    /**
     * An app appeared on a child device that nobody approved. It is already suspended there and
     * a removal is already under way, so this is not a request for permission — it is the parent
     * being told, with the one decision that is actually theirs: let it stay, or make sure it goes.
     */
    fun notifyUnauthorizedApp(
        context: Context,
        childName: String,
        appLabel: String,
        pkg: String,
        deviceId: String,
        childId: String = "",
        installer: String = "",
        quickAnswer: Boolean = true,
    ) {
        fun action(intentAction: String, labelRes: Int): NotificationCompat.Action {
            val intent = Intent(context, UnauthorizedAppReceiver::class.java)
                .setAction(intentAction)
                .putExtra(UnauthorizedAppReceiver.EXTRA_DEVICE_ID, deviceId)
                .putExtra(UnauthorizedAppReceiver.EXTRA_PACKAGE, pkg)
            return NotificationCompat.Action(
                0,
                context.getString(labelRes),
                PendingIntent.getBroadcast(
                    context, (intentAction + deviceId + pkg).hashCode(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        post(
            context, URGENT_CHANNEL, R.string.urgent_channel_name,
            title = context.getString(R.string.unauthorized_app_title, childName),
            // Who installed it, on its own line. Play installs components on its own account, and
            // without this that arrives worded exactly like a child downloading a game — the
            // parent is asked the same question with none of what decides it.
            text = context.getString(R.string.unauthorized_app_text, appLabel) +
                installerLine(context, installer)?.let { "\n" + it }.orEmpty(),
            notifId = UnauthorizedAppReceiver.notificationId(deviceId, pkg),
            dest = childDest(childId),
            // "Allow it to stay" un-suspends a sideloaded app on a child's phone, so it answers
            // to the app lock like every other answer in the shade — and, when it is offered at
            // all, only to an unlocked phone. Removing it stays available either way: it is what
            // the child's phone is already doing.
            actions = if (quickAnswer) {
                listOf(
                    action(UnauthorizedAppReceiver.ACTION_REMOVE, R.string.unauthorized_app_remove),
                    NotificationCompat.Action.Builder(
                        0,
                        context.getString(R.string.unauthorized_app_allow),
                        allowIntent(context, deviceId, pkg),
                    ).setAuthenticationRequired(true).build(),
                )
            } else {
                listOf(action(UnauthorizedAppReceiver.ACTION_REMOVE, R.string.unauthorized_app_remove))
            },
        )
    }

    private fun allowIntent(context: Context, deviceId: String, pkg: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (UnauthorizedAppReceiver.ACTION_ALLOW + deviceId + pkg).hashCode(),
            Intent(context, UnauthorizedAppReceiver::class.java)
                .setAction(UnauthorizedAppReceiver.ACTION_ALLOW)
                .putExtra(UnauthorizedAppReceiver.EXTRA_DEVICE_ID, deviceId)
                .putExtra(UnauthorizedAppReceiver.EXTRA_PACKAGE, pkg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * "Installed by the Play Store" / "by <package>" / "from outside the Play Store", or null
     * when the platform would not say — in which case silence is better than a guess.
     */
    fun installerLine(context: Context, installer: String): String? = when {
        installer == PLAY_STORE -> context.getString(R.string.unauthorized_app_installer_play)
        installer.isNotBlank() -> context.getString(R.string.unauthorized_app_installer_other, installer)
        else -> context.getString(R.string.unauthorized_app_installer_unknown)
    }

    /** Play's own package name, as the installer field reports it. */
    const val PLAY_STORE = "com.android.vending"

    /** A different app than the approved one was installed during a window (and removed). */
    fun notifyWrongApp(context: Context, childName: String, pkg: String, deviceId: String, childId: String = "") = post(
        context, URGENT_CHANNEL, R.string.urgent_channel_name,
        title = context.getString(R.string.wrong_app_title, childName),
        text = context.getString(R.string.wrong_app_text, pkg),
        notifId = "wrongapp".hashCode() + deviceId.hashCode(),
        dest = childDest(childId),
    )

    /**
     * Drops the alerts about a device that has just been freed (see [RemoteAction.RELEASE_DEVICE]).
     *
     * Only the two that outlive the device itself: the "not heard from" alert — which is exactly
     * what a released phone looks like from here — and the open-install-window nag. Everything
     * else is a moment, not a standing state, and a released phone posts no new ones.
     */
    fun cancelForDevice(context: Context, deviceId: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(deviceId.hashCode())
            manager.cancel("enf".hashCode() + deviceId.hashCode())
        }
        cancelInstallWindowOpen(context, deviceId)
    }

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
            dest = requestDest(requestId),
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
            context, STATUS_CHANNEL, R.string.status_channel_name,
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
        /**
         * Treat this as an alarm: audible through Do Not Disturb, and categorised so the
         * platform and any watch or car pairing know it is not chatter. Only the emergency
         * release asks for it (see [notifyPanicRequest]).
         */
        alarm: Boolean = false,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The single all-importance-HIGH channel these were split out of; deleting it is what
            // makes an updated install actually quieten down (see URGENT_CHANNEL).
            nm.deleteNotificationChannel(OLD_ALERT_CHANNEL)
            nm.createNotificationChannel(
                NotificationChannel(channel, context.getString(channelNameRes), importanceOf(channel)).apply {
                    // ASKED, not assumed. A child picks the hour the emergency-release countdown
                    // starts, so twelve hourly notices from seven in the evening put nine of them
                    // inside a typical Do Not Disturb and the phone free by breakfast. Bypassing
                    // it needs notification-policy access, which this app does not ask a family
                    // for; without it the platform quietly drops the request, and the notice is
                    // ongoing (below) so that it accumulates in the shade instead of being
                    // missed. Channel settings are fixed at creation, so this can only be set
                    // here and never per notification.
                    if (channel == URGENT_CHANNEL) {
                        runCatching { setBypassDnd(true) }
                    }
                },
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
            // An alarm-class notice stays until it is answered or the thing it is about ends.
            // The emergency release is the case: an alert that a sleeve can swipe away is one a
            // parent can lose without ever having read it, and the countdown goes on regardless.
            .setAutoCancel(!alarm)
            .setOngoing(alarm)
            .setContentIntent(tap)
            // Pre-O phones have no channels, so the priority is what separates them there.
            .setPriority(
                if (importanceOf(channel) == NotificationManager.IMPORTANCE_HIGH) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .apply {
                actions.forEach { addAction(it) }
                if (alarm) {
                    setCategory(NotificationCompat.CATEGORY_ALARM)
                    // Insistent rather than a glance: it re-alerts on every hourly notice
                    // instead of arriving once and sitting quietly in a shade nobody opens.
                    setOnlyAlertOnce(false)
                }
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notification) }
    }

    /** How loud a channel is. [STATUS_CHANNEL] lands in the shade without a sound or heads-up. */
    private fun importanceOf(channel: String): Int =
        if (channel == STATUS_CHANNEL) NotificationManager.IMPORTANCE_DEFAULT
        else NotificationManager.IMPORTANCE_HIGH
}
