package dev.walcott.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.WalcottApplication
import dev.walcott.data.PolicySettings
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The nightly hour in which a blocked phone may update the apps it already has.
 *
 * An alarm rather than a check on some existing loop, for the same reason the check-in is one:
 * this has to fire at four in the morning on a phone that is asleep, and the enforcement loop
 * parks while the screen is off. Inexact and allow-while-idle, so Doze honours it and is free to
 * batch it — nothing here needs the hour to the second.
 *
 * The window is closed by [InstallBlockAlarm], which wakes the phone at its deadline. It used to
 * be closed by "whoever re-applies the restrictions next", on the reasoning that
 * [DeviceRestrictions.effectiveKeys] lifts the block only while the clock is inside the window
 * so a missed close costs nothing. That reasoning was wrong in the one direction that matters:
 * `effectiveKeys` is consulted only when `apply()` actually runs, and on a sleeping phone
 * nothing ran — the block stayed lifted long past the hour the parent agreed to.
 */
object AppUpdateWindowAlarm {

    private const val TAG = "WalcottUpdates"
    private const val REQUEST_CODE = 4713

    /**
     * Brings this phone in line with the family's window: opens the one this moment is inside,
     * closes one the family has stopped wanting, and arms the alarm for the next.
     *
     * The one entry point, called from the alarm itself, from boot, from process start and
     * whenever the policy changes — because all four ask the same question. Opening from here
     * rather than from a past-dated alarm is what a device that wakes up mid-window (a reboot at
     * 04:10, a policy that arrives at 04:10) needs, and it costs no alarm at all; see
     * [AppUpdates.nextStart] for what the past-dated version cost.
     */
    suspend fun sync(context: Context) {
        val app = context.applicationContext as? WalcottApplication ?: return
        val settings = runCatching { app.repository.settingsFlow.first() }.getOrNull() ?: return
        val now = LocalDateTime.now()
        val window = settings.updateWindowAt(now).takeIf { wanted(context, settings) && it.length > 0 }
        if (window == null) {
            cancel(context)
            // A window still open under a rule that has just been withdrawn is a block left down
            // that nobody is expecting any more.
            runCatching { app.syncManager.endUpdateWindow() }
                .onFailure { DebugLog.e(TAG, "could not close the update window", it) }
            return
        }
        // The window is exactly what the policy says it is at this instant, in both directions:
        // opened (or extended) while this moment is inside one, and closed when it is not. A
        // parent who shortens it, or moves it, means it now — not at the end of hours they have
        // already changed their mind about — and an alarm Doze batched past the end of a window
        // must not leave the one it finds open running on yesterday's terms.
        val end = AppUpdates.windowEnd(now, window)
        runCatching {
            if (end != null) {
                app.syncManager.openUpdateWindow(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            } else {
                app.syncManager.endUpdateWindow()
            }
        }.onFailure { DebugLog.e(TAG, "could not bring the update window in line", it) }
        schedule(context, window, now)
    }

    /** Arms the alarm for the next window's start. Always a future instant — never a re-fire. */
    private fun schedule(context: Context, window: AppUpdates.Window, now: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val next = AppUpdates.nextStart(now, window)
        val atMs = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent(context))
            DebugLog.i(TAG, "next update window at $next for ${window.length} min")
        }.onFailure { DebugLog.e(TAG, "could not schedule the update window", it) }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    /**
     * Whether this phone wants one at all.
     *
     * Four ways to want nothing: the window is switched off, new apps are not policed at all
     * (there is nothing to lift), the family is in the guarded mode — where the platform was
     * never told about installs and Play has been working all along, which is where a family set
     * up today starts — or this device is not a Device Owner, the only thing that can set the
     * restriction in the first place. Without that last one an accessibility-only child would
     * lift a block it never had, nightly, and tell its parent it was updating.
     */
    private fun wanted(context: Context, settings: PolicySettings): Boolean =
        settings.updateWindowEnabled &&
            DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions &&
            AppUpdates.modeOf(settings.installMode) == AppUpdates.MODE_STRICT &&
            isDeviceOwner(context)

    private fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AppUpdateWindowReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Fires at the window's start; see [AppUpdateWindowAlarm].
 *
 * Also listens for the clock moving under it. The alarm is an absolute instant, computed from a
 * local hour: a family that flies two timezones east has armed 4am in the timezone they left,
 * and nothing would recompute it until the next firing — a whole night at the wrong hour, and a
 * window opened while the house is awake.
 */
class AppUpdateWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppUpdateWindowAlarm.sync(context)
            } finally {
                pending.finish()
            }
        }
    }
}
