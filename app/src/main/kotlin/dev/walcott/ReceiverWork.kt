package dev.walcott

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [work] after [BroadcastReceiver.onReceive] returns, holding the broadcast open for as long
 * as it safely can.
 *
 * A wakelock keeps the CPU up; it does nothing for the PROCESS. Once `onReceive` returns without
 * `goAsync()`, the process drops out of the receiver state, and with no foreground service up it
 * is a cached process the low-memory killer may take mid-coroutine — which is exactly the moment
 * the location and emergency-release alarms exist for, since their first job is to bring a killed
 * service back.
 *
 * Why the hold is BOUNDED rather than tied to the work: a broadcast still open when its budget
 * runs out is an ANR, and an ANR kills the process outright — worse than the problem. The work
 * can take minutes (a retry ladder), so the broadcast is released at [HOLD_MS] whether or not it
 * has finished, by which time the service the work started is what keeps the process alive.
 */
fun BroadcastReceiver.runHeld(work: suspend () -> Unit) {
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    fun finishOnce() {
        if (finished.compareAndSet(false, true)) runCatching { pending.finish() }
    }
    val scope = CoroutineScope(Dispatchers.IO)
    scope.launch {
        delay(HOLD_MS)
        finishOnce()
    }
    scope.launch {
        try {
            work()
        } finally {
            finishOnce()
        }
    }
}

/**
 * Under the ten seconds a foreground broadcast gets, which is the tighter of the two budgets —
 * whether an alarm arrives as foreground or background is not something this code controls.
 */
private const val HOLD_MS = 8_000L
