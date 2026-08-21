package dev.walcott.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The clock an emergency release runs on: one wake-up an hour for twelve hours, and one last
 * one three minutes after the twelfth notice.
 *
 * An alarm rather than a timer, for the reason every cadence in this app is an alarm: a
 * `delay()` stops counting the moment the phone suspends, and a phone in a drawer overnight is
 * exactly the phone serving out a twelve-hour countdown. `setAndAllowWhileIdle` is the primitive
 * Doze honours with no special permission; on a Device Owner child — the normal case — the power
 * allowlist means it is not subject to the roughly-one-per-nine-minutes quota either.
 *
 * Alarms do not survive a reboot, so [sync] is called from the boot receiver and from the
 * half-hourly heartbeat as well as from each step: a request whose alarm was lost would
 * otherwise sit there for ever, neither advancing nor dying, which is the one outcome this
 * feature must never produce.
 */
object PanicAlarm {

    private const val TAG = "WalcottPanic"
    private const val REQUEST_CODE = 4714

    /**
     * Ceiling on the wakelock held while a notice is sent.
     *
     * It has to outlast the whole retry ladder — the first attempt plus 30 s, 1 min and 3 min of
     * waiting, each followed by a publish that is itself capped — because the CPU is free to
     * suspend the instant `onReceive` returns and a ladder that stops counting halfway is a
     * request cancelled for a failure that never happened.
     */
    private const val WAKELOCK_TIMEOUT_MS = 7 * 60 * 1000L

    /** Arms the next step [delayMs] from now. Idempotent — the PendingIntent is unique. */
    fun schedule(context: Context, delayMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(0)
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
            DebugLog.i(TAG, "next emergency-release step in ${delayMs / 1000}s")
        }.onFailure { DebugLog.e(TAG, "could not schedule the emergency-release step", it) }
    }

    /**
     * Arms whatever the request in the store is owed next, or cancels the alarm when there is no
     * request at all. Safe to call from anywhere, including on a device that never had one.
     */
    suspend fun sync(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val state = runCatching { app.syncManager.panicStateNow() }.getOrNull() ?: return
        val request = state.first
        if (request == null) {
            cancel(context)
            return
        }
        val at = PanicProtocol.nextWakeUpAtMs(request, state.second)
        schedule(context, at - System.currentTimeMillis())
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PanicStepReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Keeps the CPU up while the notice is sent and, if it will not go, while the retry ladder
     * plays out. Always taken with a timeout, and always released in a `finally`.
     */
    internal fun wakeLock(context: Context): PowerManager.WakeLock? =
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walcott:panic")
                .apply { setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS) }
        }.getOrNull()
}

/** Fires on each hour of a live request; the step it runs arms the next one. */
class PanicStepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lock = PanicAlarm.wakeLock(context)
        // The broadcast returns at once and the wakelock — not goAsync — is what holds the CPU:
        // a step can spend minutes on a retry ladder, and a receiver's budget is ten seconds.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as WalcottApplication
                app.syncManager.runPanicStep()
            } catch (t: Throwable) {
                DebugLog.e("WalcottPanic", "emergency-release step failed", t)
                // Never leave a live request with no alarm behind it: a step that threw must
                // still come back, or the countdown simply stops with nothing to say so.
                runCatching { PanicAlarm.sync(context) }
            } finally {
                runCatching { lock?.release() }
            }
        }
    }
}
