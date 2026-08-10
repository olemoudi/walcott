package dev.walcott.sync

import android.content.Context
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.setup.DeviceRequirement
import dev.walcott.setup.DeviceSetup
import dev.walcott.setup.DeviceSetupProbe

/**
 * The child device checking its own settings, on every check-in rather than only at setup.
 *
 * Everything Walcott needs from the person holding the phone — usage access, location, the
 * accessibility blocker, VPN consent, an exemption from battery optimisation — can be granted
 * during setup and taken away the next day, by a child who worked out what it does or by an OEM
 * "cleaner" that revokes permissions for apps it thinks are idle. The parent already learns about
 * most of it from the snapshot, but a parent in another building cannot repair any of it: these
 * are exactly the settings only the holder of the device can change.
 *
 * So the device says so itself, with a notification that deep-links straight to the screen that
 * fixes it ([ChildFixNotifications]) — repeated at most once every [REPEAT_AFTER_MS] while the
 * problem lasts, and armed again the moment it recovers, so a relapse is reported promptly and a
 * standing problem never becomes wallpaper.
 *
 * WHAT is wrong comes from [DeviceSetup], the same list the on-screen cards render. Only the
 * throttle lives here, and it is pure ([due], [nextNotifiedAt]) because it is the whole design:
 * a check that runs every half hour and a notification that must not appear every half hour.
 */
object ChildHealthCheck {

    /** How long a reported problem stays quiet before it is worth saying again. */
    const val REPEAT_AFTER_MS = 12 * 60 * 60 * 1000L

    /**
     * Which of the currently [broken] settings are worth notifying about now: the ones never
     * reported, and the ones last reported longer ago than [REPEAT_AFTER_MS].
     *
     * A clock that jumps backwards (which on this app is a thing children actually do) would
     * otherwise park a stamp in the future and silence the nudge indefinitely, so a stamp that
     * is ahead of [nowMs] counts as due rather than as recent.
     */
    fun due(broken: Set<String>, notifiedAt: Map<String, Long>, nowMs: Long): List<String> =
        broken.filter { fix ->
            val last = notifiedAt[fix] ?: return@filter true
            last > nowMs || nowMs - last >= REPEAT_AFTER_MS
        }.sorted()

    /**
     * The throttle map after this round. Settings that recovered lose their stamp — so the next
     * lapse is reported at once instead of waiting out a window that started while it was still
     * broken — and the ones just notified are stamped [nowMs].
     */
    fun nextNotifiedAt(
        previous: Map<String, Long>,
        broken: Set<String>,
        notifiedNow: Collection<String>,
        nowMs: Long,
    ): Map<String, Long> =
        previous.filterKeys { it in broken } + notifiedNow.associateWith { nowMs }

    /**
     * Reads the device's current state and nudges about whatever the holder of the phone can
     * repair. Called from the heartbeat — the one wakeup Doze always honours — so a setting
     * revoked at any point is caught within about half an hour rather than never.
     */
    suspend fun run(context: Context) {
        val app = context.applicationContext as? WalcottApplication ?: return
        val unmet = runCatching { DeviceSetup.unmet(DeviceSetupProbe.read(context)) }
            .onFailure { DebugLog.e(TAG, "reading device settings failed", it) }
            .getOrNull() ?: return
        // Notifications are the one requirement this channel cannot report: a phone that can't
        // post a notification can't be told by a notification that it can't post notifications.
        // The card on its home screen is what covers that one.
        val reportable = unmet.filterNot { it == DeviceRequirement.NOTIFICATIONS }
        val broken = reportable.map { it.key }.toSet()

        val previous = app.syncManager.state.value.childFixNotifiedAt
        val now = System.currentTimeMillis()
        val notify = due(broken, previous, now)
        if (broken.isNotEmpty()) {
            DebugLog.i(TAG, "settings needing the child's attention: ${broken.sorted().joinToString()}")
        }
        val byKey = reportable.associateBy { it.key }
        notify.forEach { key ->
            byKey[key]?.let { requirement -> runCatching { ChildFixNotifications.notify(context, requirement) } }
        }
        app.syncManager.recordChildFixNudges(nextNotifiedAt(previous, broken, notify, now))
    }

    private const val TAG = "WalcottHealth"
}
