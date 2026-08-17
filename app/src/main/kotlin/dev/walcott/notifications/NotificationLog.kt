package dev.walcott.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.walcott.data.NotificationDao
import dev.walcott.data.NotificationEntity
import dev.walcott.debug.DebugLog
import java.util.concurrent.TimeUnit

/**
 * What this device's notifications are kept in, and for how long.
 *
 * The point of it is remote support: "did the bank's message arrive?", "has anybody called you
 * today?", "what did that alert actually say?" — questions a family currently answers by asking
 * somebody to read their own screen aloud, which is the part they cannot do.
 *
 * It is also the most intrusive thing in this app, so the shape is deliberately mean:
 *
 *  - **Only while the rules ask for it.** No switch, no rows. The listener checks on every
 *    notification rather than caching the answer, because the moment the family turns it off is the
 *    moment recording has to stop.
 *  - **[RETAIN_HOURS] and no longer**, enforced on write rather than by a cleanup job that might
 *    not run. What is useful for support is today and yesterday; what is left after that is only a
 *    liability on a phone that gets lost.
 *  - **[MAX_ROWS] and no more**, so a chatty group and a bad night cannot turn into a year of
 *    somebody's messages.
 *  - **Nothing leaves the device unasked.** There is no periodic upload: a parent asks, and one
 *    bounded answer is published (see `RemoteAction.NOTIFICATION_LOG`).
 */
object NotificationLog {

    /** How long a recorded notification is kept. */
    const val RETAIN_HOURS = 48L

    /** Ceiling on stored rows, whatever the retention says. */
    const val MAX_ROWS = 600

    /** Longest title/text kept, so one pathological notification cannot dominate the table. */
    const val MAX_FIELD_CHARS = 300

    /** Trimming runs on one write in this many, since it costs a delete and finds nothing most times. */
    private const val TRIM_EVERY = 25

    private var writes = 0

    /**
     * Stores one notification, trimmed to shape. Called from the listener's callback thread, so it
     * does the least it can and swallows its own failures — a log that crashes the listener takes
     * the phone's notifications down with it.
     */
    suspend fun record(dao: NotificationDao, entity: NotificationEntity) {
        val trimmed = entity.copy(
            title = entity.title.take(MAX_FIELD_CHARS),
            text = entity.text.take(MAX_FIELD_CHARS),
        )
        runCatching {
            if (trimmed.key.isNotEmpty()) dao.deleteByKey(trimmed.key)
            dao.insert(trimmed)
            if (++writes % TRIM_EVERY == 0) {
                dao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(RETAIN_HOURS))
                dao.trimTo(MAX_ROWS)
            }
        }.onFailure { DebugLog.w(TAG, "could not record a notification", it) }
    }

    /** Drops everything, for the moment the family switches the log off. */
    suspend fun forget(dao: NotificationDao) {
        runCatching { dao.clear() }.onFailure { DebugLog.w(TAG, "could not clear the log", it) }
    }

    /**
     * True when this device may keep a log at all — the ONE question that decides whether the
     * listener records anything.
     */
    fun enabledBy(settings: dev.walcott.data.PolicySettings): Boolean = settings.notificationLogEnabled

    // --- Access, which only the phone's owner can grant ---

    /**
     * Whether the phone's owner has let Walcott read notifications.
     *
     * There is no way to grant this from a Device Owner: notification listeners are enabled by a
     * human in Settings, full stop. So the app asks for it the same way it asks for usage access —
     * a guided step with a button that opens the exact screen (see [settingsIntent]).
     */
    fun accessGranted(context: Context): Boolean = runCatching {
        val flat = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
        val me = ComponentName(context, WalcottNotificationListener::class.java)
        flat.split(':').any { entry ->
            entry.isNotEmpty() && ComponentName.unflattenFromString(entry) == me
        }
    }.getOrDefault(false)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val ENABLED_LISTENERS = "enabled_notification_listeners"
    private const val TAG = "WalcottNotif"
}
