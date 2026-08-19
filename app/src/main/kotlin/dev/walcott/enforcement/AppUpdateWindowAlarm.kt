package dev.walcott.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.WalcottApplication
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
 * The window is closed by whoever re-applies the restrictions next (see the collector in
 * [EnforcementService] and the ~30-minute check-in), and by the stored deadline in any case:
 * [DeviceRestrictions.effectiveKeys] lifts the block only while the clock is inside it, so a
 * missed close costs nothing.
 */
object AppUpdateWindowAlarm {

    private const val TAG = "WalcottUpdates"
    private const val REQUEST_CODE = 4713

    /** Schedules the next window's start, or cancels the alarm when the family has none. */
    suspend fun schedule(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val settings = runCatching { app.repository.settingsFlow.first() }.getOrNull() ?: return
        if (!wanted(settings)) {
            cancel(context)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val next = AppUpdates.nextStart(
            LocalDateTime.now(),
            settings.updateWindowHour,
            settings.updateWindowMinutes,
        )
        val atMs = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent(context))
            DebugLog.i(TAG, "next update window at $next for ${settings.updateWindowMinutes} min")
        }.onFailure { DebugLog.e(TAG, "could not schedule the update window", it) }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    /**
     * Whether this family wants one at all.
     *
     * Three ways to want nothing: the window is switched off, the block is not armed (there is
     * nothing to lift), or the family is in the guarded mode, where the platform was never told
     * about installs and Play has been working all along.
     */
    private fun wanted(settings: dev.walcott.data.PolicySettings): Boolean =
        settings.updateWindowEnabled &&
            settings.updateWindowMinutes > 0 &&
            DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions &&
            AppUpdates.modeOf(settings.installMode) == AppUpdates.MODE_STRICT

    /** Opens the window this alarm is for, then arms the next one. */
    internal suspend fun runWindow(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val settings = runCatching { app.repository.settingsFlow.first() }.getOrNull()
        if (settings != null && wanted(settings)) {
            // How much of the window is actually left: an alarm Doze batched twenty minutes late
            // must not extend the hour the parent agreed to, and one that arrives after the
            // window has passed must not open a new one out of nowhere.
            val end = AppUpdates.windowEnd(
                LocalDateTime.now(),
                settings.updateWindowHour,
                settings.updateWindowMinutes,
            )
            val minutesLeft = end
                ?.let { java.time.Duration.between(LocalDateTime.now(), it).toMinutes().toInt() }
                ?: 0
            if (minutesLeft > 0) {
                runCatching { app.syncManager.openUpdateWindow(minutesLeft) }
                    .onFailure { DebugLog.e(TAG, "could not open the update window", it) }
            } else {
                DebugLog.i(TAG, "update window alarm arrived outside its window; nothing opened")
            }
        }
        schedule(context)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AppUpdateWindowReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires at the window's start; see [AppUpdateWindowAlarm]. */
class AppUpdateWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppUpdateWindowAlarm.runWindow(context)
            } finally {
                pending.finish()
            }
        }
    }
}
