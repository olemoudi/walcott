package dev.walcott.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.walcott.debug.DebugLog
import dev.walcott.sync.LocationPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot GPS sampling via the platform [LocationManager] (no Google Play Services).
 * Requires location permission (force-granted on the child by [LocationPolicy]) and must be
 * called from a location-typed foreground service so background access is allowed.
 */
class LocationSampler(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)

    /**
     * Best current fix, or the freshest usable cached one, or null if unavailable.
     *
     * [maxCacheAgeMs] is the battery lever: a cached fix at least that fresh AND precise enough
     * is returned without powering anything at all. Someone else's fix — a maps app, a weather
     * widget, the system itself — is free to us, and a phone sitting on a desk produces the same
     * coordinates whether we spin the GPS or not. Pass 0 to insist on a live attempt.
     *
     * The ceiling on that shortcut is [MAX_CACHE_AGE_MS] however long the caller's period is.
     * It used to be a third of the sampling interval, which at hourly tracking meant a
     * twenty-minute-old fix presented to the parent as where their child is now; twenty minutes
     * is fifteen kilometres in a car, and the map has no way to show that doubt.
     */
    suspend fun currentFix(
        timeoutMs: Long = FIX_TIMEOUT_MS,
        maxCacheAgeMs: Long = 0,
    ): LocationPoint? {
        val lm = lm ?: run { DebugLog.w(TAG, "no LocationManager service"); return null }
        if (!hasPermission()) { DebugLog.w(TAG, "location permission not granted"); return null }
        // Prefer the platform fused provider (API 31+, better/faster with less battery), then GPS.
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        // Free first: a recent AND precise enough fix from anyone means no radio at all this cycle.
        val cacheCeiling = minOf(maxCacheAgeMs, MAX_CACHE_AGE_MS)
        if (cacheCeiling > 0) {
            val fresh = bestCached(lm, providers)
            if (fresh != null && ageMs(fresh) <= cacheCeiling && errorOf(fresh) <= CACHE_ACCEPT_M) {
                DebugLog.i(TAG, "using a cached fix (${ageMs(fresh) / 1000}s old, ~${errorOf(fresh).toInt()}m)")
                return fresh.toPoint()
            }
        }

        val locationOn = runCatching { lm.isLocationEnabled }.getOrDefault(false)
        DebugLog.i(TAG, "requesting fix; locationEnabled=$locationOn enabled providers=$providers")

        // The first provider gets the full budget; the rest get a short one. Every provider used
        // to get the full 20 s, so a cycle where none of them can answer — indoors, aeroplane
        // mode — burnt a solid minute of GPS and radio before giving up, and then did it again a
        // moment later. The fallbacks are worth a try, not another twenty seconds each.
        //
        // The BEST answer wins, not the first. FUSED usually replies at once with whatever it
        // has, which on a phone that has just woken indoors is a cell-tower fix a kilometre
        // wide; returning that and never asking the GPS is how a map ends up confidently
        // pointing at the wrong end of a neighbourhood.
        var best: Location? = null
        providers.forEachIndexed { index, provider ->
            val budget = if (index == 0) timeoutMs else FALLBACK_TIMEOUT_MS
            val loc = withTimeoutOrNull(budget) { requestSingle(lm, provider) }
            if (loc != null) {
                DebugLog.i(TAG, "fix from $provider acc=${if (loc.hasAccuracy()) loc.accuracy else -1f}m")
                if (best == null || errorOf(loc) < errorOf(best)) best = loc
                // Good enough to stop paying for: nothing further down the list will beat this
                // by enough to be worth another radio window.
                if (errorOf(loc) <= GOOD_ENOUGH_ACCURACY_M) return loc.toPoint()
            }
        }
        best?.let { return it.toPoint() }

        // Fall back to the most usable cached fix from any provider, at any age. Better a stale
        // position the parent can see the age of than nothing at all.
        val cached = bestCached(lm, providers)
        if (cached == null) DebugLog.w(TAG, "no live fix and no cached location available")
        else DebugLog.i(TAG, "no live fix; using a cached one (${ageMs(cached) / 1000}s old)")
        return cached?.toPoint()
    }

    /**
     * The most trustworthy last-known fix across [providers] plus the (free) passive one.
     *
     * Scored, not just dated. Picking the newest was wrong in the way that matters: a cell-tower
     * fix two kilometres wide from thirty seconds ago beat an eight-metre GPS fix from three
     * minutes ago, and the map drew both the same. [errorOf] prices staleness in metres so the
     * two are comparable at all.
     */
    private fun bestCached(lm: LocationManager, providers: List<String>): Location? =
        (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .minByOrNull { errorOf(it) }

    /**
     * How wrong a fix could be by now, in metres: its own accuracy plus what the child could
     * have travelled since it was taken.
     *
     * [DRIFT_M_PER_S] is deliberately a vehicle, not a walk. The question this answers is "how
     * far might they be from here", and being pessimistic about that is free, while being
     * optimistic about it is what puts a marker on the wrong street.
     */
    private fun errorOf(location: Location?): Float {
        if (location == null) return Float.MAX_VALUE
        val accuracy = if (location.hasAccuracy()) location.accuracy else UNKNOWN_ACCURACY_M
        return accuracy + (ageMs(location) / 1000f) * DRIFT_M_PER_S
    }

    /**
     * How old a fix is, by the monotonic clock rather than the wall clock.
     *
     * `Location.time` is wall clock, so a device whose clock jumps — which this app already
     * knows happens, and sometimes deliberately (see `ChildSnapshot.clockSkewMs`) — would make
     * every cached fix look freshly minted or impossibly old. `elapsedRealtimeNanos` cannot be
     * moved by anybody.
     */
    private fun ageMs(location: Location): Long =
        ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
            .coerceAtLeast(0L)

    private suspend fun requestSingle(lm: LocationManager, provider: String): Location? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } else {
            @Suppress("DEPRECATION")
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                runCatching {
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    /**
     * Whether the network (Wi-Fi/cell) location provider is enabled. It's the only provider that
     * yields indoor fixes; a Device Owner can't force it on, so the parent is warned when it's off.
     */
    fun networkProviderEnabled(): Boolean =
        runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false }.getOrDefault(false)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toPoint() = LocationPoint(
        lat = latitude,
        lng = longitude,
        epochMs = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        accuracyM = if (hasAccuracy()) accuracy else 0f,
        mock = isMockFix(),
    )

    @Suppress("DEPRECATION")
    private fun Location.isMockFix(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider

    companion object {
        private const val FIX_TIMEOUT_MS = 20_000L

        /** Budget for each provider after the first: a try, not a second full wait. */
        private const val FALLBACK_TIMEOUT_MS = 5_000L

        /**
         * Hard ceiling on reusing somebody else's fix, however long the sampling period is.
         * Two minutes is a couple of streets on foot and about a kilometre in a car — the most
         * staleness that can hide behind a marker without misleading the person reading it.
         */
        private const val MAX_CACHE_AGE_MS = 2 * 60 * 1000L

        /** Assumed travel while a fix ages: a bus, not a stroll. Pessimism here is free. */
        private const val DRIFT_M_PER_S = 5f

        /** Total doubt a cached fix may carry before it is worth powering the radio instead. */
        private const val CACHE_ACCEPT_M = 250f

        /** Accuracy at which the search stops: better than this changes nothing on a map. */
        private const val GOOD_ENOUGH_ACCURACY_M = 50f

        /** What a fix that won't state its accuracy is assumed to be worth. */
        private const val UNKNOWN_ACCURACY_M = 1000f

        private const val TAG = "WalcottLocation"
    }
}
