package dev.walcott.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The child's guaranteed ~30-minute check-in.
 *
 * WorkManager's periodic work (the watchdog) is deferred by Doze into maintenance windows that
 * can be hours apart, so a resting phone could go a long time without reporting — which is
 * exactly when a parent wonders whether the device is still protected. An inexact
 * `setAndAllowWhileIdle` alarm is the one scheduling primitive Doze still honours (roughly one
 * per app every ~9 minutes), and unlike an exact alarm it needs no special permission. Each
 * firing chains the next one.
 *
 * Battery: one wakeup every 30 minutes that piggybacks BOTH the snapshot publish and the
 * update check onto the same radio wake. The radio tail — not the payload — is what costs
 * power, so pairing them is nearly free compared to either alone, and the publish is skipped
 * entirely when something else already published recently.
 */
object HeartbeatAlarm {

    /** How often the child checks in while idle. */
    const val INTERVAL_MS = 30 * 60 * 1000L

    /** Skip the publish when something else already published this recently. */
    private const val PUBLISH_MIN_INTERVAL_MS = 20 * 60 * 1000L

    private const val TAG = "WalcottHeartbeat"
    private const val REQUEST_CODE = 4711

    /** Schedules (or re-schedules) the next check-in. Idempotent — the PendingIntent is unique. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + INTERVAL_MS
        runCatching {
            // Inexact + allow-while-idle: Doze lets this through, and the OS is free to batch it
            // with other wakeups, which is the battery-friendly behaviour we want.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
            DebugLog.i(TAG, "next check-in in ${INTERVAL_MS / 60_000} min")
        }.onFailure { DebugLog.e(TAG, "could not schedule heartbeat", it) }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HeartbeatReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Publishes and kicks an update check; called on the alarm and kept short (goAsync budget). */
    internal suspend fun runCheckIn(context: Context) {
        val app = context.applicationContext as WalcottApplication
        if (!IdentityStore(context).current().enforcesLocally) return
        // This alarm is the one wake path Doze reliably honours, so it doubles as the
        // enforcement watchdog: make sure the service is up, then verify blocking actually
        // works (the self-test re-asserts and records any gap for the publish below).
        runCatching { dev.walcott.enforcement.EnforcementService.start(context) }
            .onFailure { DebugLog.e(TAG, "enforcement service start failed", it) }
        runCatching { dev.walcott.enforcement.EnforcementSelfTest.run(context) }
            .onFailure { DebugLog.e(TAG, "enforcement self-test failed", it) }
        runCatching { app.syncManager.publishHeartbeatIfStale(PUBLISH_MIN_INTERVAL_MS) }
            .onFailure { DebugLog.e(TAG, "heartbeat publish failed", it) }
        // The radio is already awake: the update check rides along for almost nothing. The
        // worker (not this receiver) does any actual download, which can outlive our budget.
        runCatching { UpdateWorker.runIfStale(context) }
    }
}

/** Fires every [HeartbeatAlarm.INTERVAL_MS]; re-arms itself first so the chain can't break. */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm before doing any work: if the check-in below throws, the chain still survives.
        HeartbeatAlarm.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                HeartbeatAlarm.runCheckIn(context)
            } finally {
                pending.finish()
            }
        }
    }
}
