package dev.walcott.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.WalcottApplication
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
 * Battery: at most one wakeup every ten minutes on the PARENT's phone, which is an ordinary
 * phone the user is holding — no foreground service, no location, and the poll is a single HTTP
 * request that usually returns nothing. How often it really fires is [ParentCadence]'s call.
 */
object ParentCheckAlarm {

    private const val TAG = "WalcottParentCheck"
    private const val REQUEST_CODE = 4712

    /**
     * Schedules (or re-schedules) the next catch-up. Idempotent — the PendingIntent is unique,
     * so a second call replaces the pending alarm rather than stacking another one.
     *
     * Defaults to the fast cadence because every caller that can't yet know better — boot, app
     * start, the receiver re-arming before it has polled — should err towards hearing a child
     * sooner. [runCheck] re-paces it the moment it has the state to judge.
     */
    fun schedule(context: Context, intervalMs: Long = ParentCadence.FAST_MS) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + intervalMs
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
        // Re-pace the chain now that the poll has refreshed what the children look like. This
        // replaces the fast default the receiver armed before doing any of it — that one is the
        // safety net for a poll that throws, this one is the considered answer.
        runCatching { schedule(context, nextInterval(context)) }
            .onFailure { DebugLog.w(TAG, "could not re-pace the parent catch-up", it) }
    }

    /** The cadence the freshest check-in across every family justifies (see [ParentCadence]). */
    private suspend fun nextInterval(context: Context): Long {
        val app = context.applicationContext as? WalcottApplication ?: return ParentCadence.FAST_MS
        val newestSeen = app.hub.allNow()
            .flatMap { family -> family.syncStore.current().lastSeen.values }
            .maxOrNull()
        return ParentCadence.nextIntervalMs(newestSeen, System.currentTimeMillis())
    }
}

/** Fires on the cadence [ParentCadence] set; re-arms itself first so the chain can't break. */
class ParentCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm before doing any work: if the poll below throws, the chain still survives. At
        // the fast cadence deliberately — the failure this guards is one where nothing was
        // learned, and the cheap mistake to make with no information is waking up too often.
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
