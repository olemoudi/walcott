package dev.walcott

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/**
 * An alarm for a moment the family can see: the minute bedtime ends and the apps come back, the
 * end of an install window, the next step of an emergency release.
 *
 * Those used to be inexact, like every other alarm in this app, on the belief that Doze would
 * hold one "a few minutes". It holds it much longer: since Android 12 an inexact alarm may be
 * delivered up to an hour late (three quarters of the time until it is due, capped at an hour),
 * and the system schedules the wake-up at the END of that window unless something else wakes the
 * phone first. Armed at ten at night for seven in the morning, bedtime ended somewhere before
 * eight — with every messaging app still suspended, which on a phone means calls that cannot ring.
 *
 * So these are exact where the platform allows it: `USE_EXACT_ALARM` from Android 13 (granted at
 * install; this app is not distributed through Play, whose policy restricts it to alarm-clock
 * apps), `SCHEDULE_EXACT_ALARM` on Android 12 (granted by default there), and always on 10 and
 * 11. Where it is refused anyway — a user who revoked it, an OEM that withholds it — the inexact
 * alarm is still armed, which is what every one of these was before.
 *
 * The periodic cadences (heartbeat, location, the parent's check) stay inexact on purpose: their
 * exact minute means nothing and waking for it would cost battery.
 */
object OnTimeAlarm {

    /** Arms [operation] for wall-clock [atMs], exactly if this app may, allow-while-idle either way. */
    fun set(alarms: AlarmManager, atMs: Long, operation: PendingIntent) {
        if (mayBeExact(alarms)) {
            val exact = runCatching {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
            }
            // A permission revoked between the check and the call throws; the inexact alarm is
            // still far better than none.
            if (exact.isSuccess) return
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
    }

    private fun mayBeExact(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
}
