package dev.walcott.debug

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The next start after an early crash (see [CrashBreaker.recordCrash]).
 *
 * Its whole job is to exist: delivering it starts the process, which runs the breaker's count in
 * `Application.onCreate`, and then it starts enforcement the way the boot receiver would. Either
 * the build gets through this time, or the count reaches the breaker's limit and the phone is
 * opened — in a minute or two rather than whenever something else next happens to run.
 *
 * An alarm, armed from the dying thread with one synchronous call. It can be relied on because
 * the breaker takes early crashes out of Android's crash handling: a process Android had marked
 * bad would have its alarms dropped. (A JobScheduler job was tried first and measured on an
 * emulator: it revived the first crash, but a process that died while its own job was being bound
 * left the next job pending for minutes.) Android's own restarts — the sticky service, the
 * device-admin binding — race this one; whichever comes first is the next start.
 */
object CrashRevivalAlarm {

    private const val REQUEST_CODE = 4716

    /** Long enough that a phone in a loop is not restarted flat out, short enough to matter. */
    private const val DELAY_MS = 30_000L

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, CrashRevivalReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { dev.walcott.OnTimeAlarm.set(alarms, System.currentTimeMillis() + DELAY_MS, operation) }
    }
}

/** Fires [CrashRevivalAlarm]; by the time it runs, the process has already started. */
class CrashRevivalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.w("WalcottBreaker", "starting again after an early crash")
        runCatching { dev.walcott.enforcement.EnforcementService.start(context) }
            .onFailure { DebugLog.e("WalcottBreaker", "could not start enforcement after an early crash", it) }
    }
}
