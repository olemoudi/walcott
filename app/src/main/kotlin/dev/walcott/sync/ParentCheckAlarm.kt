package dev.walcott.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The parent's guaranteed catch-up, and the mirror image of [HeartbeatAlarm].
 *
 * The child's check-in was moved off WorkManager precisely because Doze defers periodic work
 * into maintenance windows that can be hours apart — but the parent's catch-up poll was left on
 * it, with the same consequence pointing the other way. A parent phone resting overnight is
 * exactly when a child's extra-time request, a tamper alert, or the two-hourly notice of an
 * emergency release has nobody watching the socket: the app process is gone, so the only thing
 * that fetches them is this poll. Deferred for hours, the drum-beat that IS the release
 * protocol's guarantee — that a living parent finds out — beats into an empty room.
 *
 * So: an inexact `setAndAllowWhileIdle` alarm, the one primitive Doze still honours (roughly one
 * per app every ~9 minutes) and one that needs no special permission. Each firing chains the
 * next. The worker stays as well, because the two fail differently: the worker survives a reboot
 * on its own and this doesn't, and this survives Doze and the worker doesn't.
 *
 * Battery: one wakeup every 15 minutes on the PARENT's phone, which is an ordinary phone the
 * user is holding — no foreground service, no location, and the poll is a single HTTP request
 * that usually returns nothing.
 */
object ParentCheckAlarm {

    /** How often the parent catches up while the app is closed. */
    const val INTERVAL_MS = 15 * 60 * 1000L

    private const val TAG = "WalcottParentCheck"
    private const val REQUEST_CODE = 4712

    /** Schedules (or re-schedules) the next catch-up. Idempotent — the PendingIntent is unique. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + INTERVAL_MS
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }.onFailure { DebugLog.e(TAG, "could not schedule the parent catch-up", it) }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ParentCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Runs one catch-up; kept short, because a broadcast's goAsync budget is not generous. */
    internal suspend fun runCheck(context: Context) {
        if (IdentityStore(context).current().effectiveMode != DeviceMode.PARENT) {
            // Not (or no longer) a parent: stop the chain rather than waking up for ever.
            cancel(context)
            return
        }
        runCatching { ParentPoll.pollAll(context) }
            .onFailure { DebugLog.e(TAG, "parent catch-up poll failed", it) }
    }
}

/** Fires every [ParentCheckAlarm.INTERVAL_MS]; re-arms itself first so the chain can't break. */
class ParentCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm before doing any work: if the poll below throws, the chain still survives.
        ParentCheckAlarm.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ParentCheckAlarm.runCheck(context)
            } finally {
                pending.finish()
            }
        }
    }
}
