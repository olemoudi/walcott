package dev.walcott.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.enforcement.EnforcementService
import dev.walcott.sync.LocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The child's periodic location fix, driven by an alarm rather than by a timer inside the
 * enforcement service.
 *
 * **Why this is not a `delay()` loop any more.** It used to be one, on the reasoning that the
 * always-on foreground service is exempt from Doze. It is not, at least not in the way that
 * matters here: an FGS keeps the process alive and (as Device Owner, which is power-allowlisted)
 * keeps its network and its wakelocks honoured — but it holds no wakelock of its own, so the SoC
 * still suspends between wakeups. Coroutine delays on the main dispatcher are
 * `Handler.postDelayed`, which is `SystemClock.uptimeMillis`, which stops during deep sleep and
 * wakes nothing. Android says so in as many words: "time spent in deep sleep will add an
 * additional delay to execution."
 *
 * The consequence was that "every 15 minutes" quietly became "every however-long-this-phone-
 * happened-to-be-awake-for" — always at least the interval, often several times it, and never
 * predictable. A phone in a pocket is exactly the phone a parent wants located, and exactly the
 * one that had stopped keeping time. The rest of this app already knew: [dev.walcott.sync.HeartbeatAlarm]
 * exists for precisely this reason and says so, and the same reasoning had simply never been
 * applied to location.
 *
 * `setAndAllowWhileIdle` is the primitive Doze honours without any special permission. On a
 * Device Owner child — the normal case — the power allowlist means it is not subject to the
 * roughly-one-per-nine-minutes quota either, so short intervals really are short. On a child that
 * is not Device Owner it may be stretched to about nine minutes, which is still enormously better
 * than a timer that does not fire at all.
 */
object LocationAlarm {

    private const val TAG = "WalcottLocation"
    private const val REQUEST_CODE = 4712

    /** First backoff after a cycle that produced no fix (indoors, GPS warming up, aeroplane mode). */
    private const val RETRY_MS = 60_000L

    /** Caps the doubling at 2^4 = 16 minutes, before the period's own cap applies. */
    private const val MAX_BACKOFF_SHIFT = 4

    /** Hard floor between cycles, so a never-succeeding fix can't spin the radio. */
    private const val MIN_GAP_MS = 30_000L

    /**
     * Ceiling on the wakelock the receiver holds while a fix is acquired. Comfortably longer than
     * the sampler's own budget (20 s for the first provider plus 5 s each for the fallbacks) and
     * short enough that a bug here costs a minute of CPU rather than a night of it.
     */
    private const val WAKELOCK_TIMEOUT_MS = 60_000L

    /**
     * Consecutive cycles that produced nothing, so a device that cannot be located backs off
     * instead of retrying at full price for ever. Process-lifetime only: a restart forgets it and
     * costs one prompt retry, which is the right side to err on.
     */
    @Volatile private var failures = 0

    /**
     * The last fix actually published, so a phone that has not moved does not keep saying so.
     * Process-lifetime like [failures]: forgetting it costs one redundant publish.
     */
    @Volatile private var lastPublishedFix: LocationPoint? = null

    /** How far a phone must have gone to be worth its own message, before accuracy widens it. */
    private const val STATIONARY_M = 60f

    /** Slowest a stationary phone's position is re-sent: the heartbeat's own rate. */
    private const val STATIONARY_PUBLISH_MIN_MS = 30 * 60 * 1000L

    /** Arms the next fix [delayMs] from now. Idempotent — the PendingIntent is unique. */
    fun schedule(context: Context, delayMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pendingIntent(context),
            )
            DebugLog.i(TAG, "next fix in ${delayMs / 1000}s")
        }.onFailure { DebugLog.e(TAG, "could not schedule the location alarm", it) }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
        failures = 0
        // Tracking switched off and on again must not compare against where the phone was the
        // last time the family used the feature, which could be days and a holiday ago.
        lastPublishedFix = null
    }

    /** One cycle: take a fix, store it, publish it, and arm the next one. */
    internal suspend fun runCycle(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val minutes = app.repository.settingsFlow.first().trackingIntervalMinutes
        if (minutes <= 0) {
            cancel(context)
            return
        }
        val periodMs = minutes * 60_000L
        // Background location rides entirely on the location-typed foreground service (the
        // background permission is deliberately denied — see LocationPolicy), so make sure it is
        // actually up before asking. A service an OEM killed is otherwise a device that silently
        // stops being locatable.
        runCatching { EnforcementService.start(context, recheck = true) }
            .onFailure { DebugLog.e(TAG, "could not start the enforcement service", it) }

        val gotFix = runCatching {
            // A fix from anyone, recent and precise enough, is as good as ours and costs nothing:
            // a phone on a desk reports the same place whether or not we spin its GPS.
            val fix = LocationSampler(context).currentFix(maxCacheAgeMs = periodMs / 3)
            if (fix != null) {
                app.repository.recordLocation(fix)
                DebugLog.i(TAG, "recorded fix acc=${fix.accuracyM}m mock=${fix.mock}")
            } else {
                DebugLog.w(TAG, "no location fix this cycle")
            }
            publish(app, fix, periodMs)
            fix != null
        }.getOrElse {
            DebugLog.e(TAG, "location sampling cycle failed", it)
            false
        }

        // A failed cycle retries sooner — a device that just walked outdoors shouldn't stay
        // unlocatable for a whole period. But only for a while: a phone that cannot be located
        // usually cannot be located for hours (indoors, aeroplane mode, no sky), and a fixed
        // one-minute retry meant powering the radio every ninety seconds all afternoon to be told
        // the same thing. Each failure doubles the wait, up to the interval the parent asked for.
        failures = if (gotFix) 0 else failures + 1
        val backoff = RETRY_MS shl (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        val next = if (gotFix) periodMs else minOf(backoff, periodMs)
        schedule(context, next.coerceAtLeast(MIN_GAP_MS))
    }

    /**
     * Sends the check-in this cycle earned, which is not always one.
     *
     * A publish is a full snapshot over the radio, and the radio wake is what costs power — the
     * payload barely registers beside it. Two cycles have nothing to say and used to send one
     * anyway, every single period, all day:
     *
     *  - **No fix.** A phone indoors or in aeroplane mode can go a whole evening without one.
     *    Repeating "still nowhere" every few minutes tells the parent nothing the previous
     *    message didn't; the ~30-minute heartbeat already proves the device is alive.
     *  - **It hasn't moved.** A phone on a desk produces the same coordinates for hours. The fix
     *    is still recorded locally — the trail stays as dense as the family asked for — but a
     *    message whose only content is a position the parent already has is a radio wake bought
     *    for nothing.
     *
     * Both fall back to [SyncManager.publishHeartbeatIfStale], so nothing ever goes quiet: they
     * lower the rate to the heartbeat's, they do not switch anything off.
     */
    private suspend fun publish(app: WalcottApplication, fix: LocationPoint?, periodMs: Long) {
        if (fix == null) {
            app.syncManager.publishHeartbeatIfStale(periodMs)
            return
        }
        val previous = lastPublishedFix
        if (previous != null && !hasMoved(previous, fix)) {
            // Deliberately NOT updating the reference here. Comparing each fix against the
            // previous one instead would let a slow walk creep away unreported: ten cycles of
            // "only fifty metres since last time" is half a kilometre nobody was told about.
            // Measuring against what the parent last actually received cannot drift.
            DebugLog.i(TAG, "same place as last published; letting the heartbeat carry it")
            app.syncManager.publishHeartbeatIfStale(maxOf(periodMs, STATIONARY_PUBLISH_MIN_MS))
            return
        }
        lastPublishedFix = fix
        app.syncManager.publishLocationUpdate()
    }

    /**
     * Whether [now] is far enough from [before] to be worth a message.
     *
     * Measured against the fix's own accuracy rather than a flat distance: two fixes 40 m apart
     * are a phone that moved when both are good to 5 m, and the same phone standing still when
     * they are good to 100 m. [STATIONARY_M] is the floor under that, so a jittery fix on a
     * bedside table can't publish its way through the night.
     */
    private fun hasMoved(before: LocationPoint, now: LocationPoint): Boolean {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(before.lat, before.lng, now.lat, now.lng, results)
        return results[0] > maxOf(STATIONARY_M, now.accuracyM, before.accuracyM)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, LocationSampleReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Keeps the CPU up while an asynchronous fix is acquired.
     *
     * The alarm is what wakes the device; without this the SoC is free to suspend again the
     * instant `onReceive` returns, which is the same hole the timer had. Always taken with a
     * timeout, and always released in a `finally`.
     */
    internal fun wakeLock(context: Context): PowerManager.WakeLock? =
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walcott:location")
                .apply { setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS) }
        }.getOrNull()
}

/** Fires on the family's tracking interval; the cycle it runs arms the next one. */
class LocationSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A safety net armed before any work, so a throw below cannot break the chain for good.
        // The real interval is re-armed by the cycle itself and replaces this one.
        LocationAlarm.schedule(context, SAFETY_NET_MS)
        val lock = LocationAlarm.wakeLock(context)
        // The broadcast returns at once and the wakelock — not goAsync — is what holds the CPU:
        // a fix can take twenty seconds, which is far longer than a receiver may sit blocking.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LocationAlarm.runCycle(context)
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    private companion object {
        /** Long enough not to double up on a normal cycle, short enough to heal a broken chain. */
        const val SAFETY_NET_MS = 30 * 60 * 1000L
    }
}
