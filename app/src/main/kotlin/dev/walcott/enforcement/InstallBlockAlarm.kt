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
import kotlinx.coroutines.launch

/**
 * Puts the install block back at the instant the window that lifted it runs out.
 *
 * Every window this app opens closes on a wall-clock deadline, and the closing used to be a
 * `delay()` in the enforcement service's collector plus whenever the ~15-minute watchdog next
 * ran. Neither is a timer on a sleeping phone: `delay()` does not tick in deep sleep and
 * WorkManager is deferred by Doze into maintenance windows hours apart. So a window that ended
 * at five in the morning ended, in fact, when somebody next picked the phone up — with
 * `DISALLOW_INSTALL_APPS` lifted for every hour in between.
 *
 * That is not academic for either kind of window: the nightly update hour ends on a sleeping
 * phone BY DESIGN (see [AppUpdateWindowAlarm]), and the eight-hour "I don't know how long I
 * need" PIN window ends on one by accident often enough.
 *
 * Inexact and allow-while-idle, like every other alarm here: Doze honours it, may batch it by a
 * few minutes, and a block that comes back three minutes late is a different order of problem
 * from one that comes back eight hours late. The service's own countdown stays as the precise
 * path while the phone is awake; this is the one that survives it not being.
 */
object InstallBlockAlarm {

    private const val TAG = "WalcottInstalls"
    private const val REQUEST_CODE = 4714

    /** A second past the deadline, so a clock read a hair early cannot re-arm INSIDE the window. */
    private const val SLACK_MS = 1_000L

    /** Wakes the phone at [untilMs] to re-arm the block; cancels the alarm when nothing is open. */
    fun arm(context: Context, untilMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (untilMs <= System.currentTimeMillis()) {
            runCatching { alarms.cancel(pendingIntent(context)) }
            return
        }
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs + SLACK_MS, pendingIntent(context))
        }.onFailure { DebugLog.e(TAG, "could not schedule the install block re-arm", it) }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, InstallBlockReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires when an install window runs out; see [InstallBlockAlarm]. */
class InstallBlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? WalcottApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.syncManager.rearmInstallBlock()
            } finally {
                pending.finish()
            }
        }
    }
}
