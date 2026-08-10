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
     * Best current fix, or the freshest cached one, or null if unavailable.
     *
     * [maxCacheAgeMs] is the battery lever: a cached fix at least that fresh is returned without
     * powering anything at all. Someone else's fix — a maps app, a weather widget, the system
     * itself — is free to us, and a phone sitting on a desk produces the same coordinates whether
     * we spin the GPS or not. Pass 0 to insist on a live attempt.
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

        // Free first: a recent enough fix from anyone means no radio at all this cycle.
        if (maxCacheAgeMs > 0) {
            val fresh = freshestCached(lm, providers)
            if (fresh != null && System.currentTimeMillis() - fresh.time <= maxCacheAgeMs) {
                DebugLog.i(TAG, "using a cached fix (${(System.currentTimeMillis() - fresh.time) / 1000}s old)")
                return fresh.toPoint()
            }
        }

        val locationOn = runCatching { lm.isLocationEnabled }.getOrDefault(false)
        DebugLog.i(TAG, "requesting fix; locationEnabled=$locationOn enabled providers=$providers")

        // The first provider gets the full budget; the rest get a short one. Every provider used
        // to get the full 20 s, so a cycle where none of them can answer — indoors, aeroplane
        // mode — burnt a solid minute of GPS and radio before giving up, and then did it again a
        // moment later. The fallbacks are worth a try, not another twenty seconds each.
        providers.forEachIndexed { index, provider ->
            val budget = if (index == 0) timeoutMs else FALLBACK_TIMEOUT_MS
            val loc = withTimeoutOrNull(budget) { requestSingle(lm, provider) }
            if (loc != null) {
                DebugLog.i(TAG, "live fix from $provider acc=${if (loc.hasAccuracy()) loc.accuracy else -1f}m")
                return loc.toPoint()
            }
        }
        // Fall back to the most recent cached fix from any provider, at any age.
        val cached = freshestCached(lm, providers)
        if (cached == null) DebugLog.w(TAG, "no live fix and no cached location available")
        else DebugLog.i(TAG, "no live fix; using cached location")
        return cached?.toPoint()
    }

    /** The newest last-known fix across [providers] plus the (free) passive one. */
    private fun freshestCached(lm: LocationManager, providers: List<String>): Location? =
        (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

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
        private const val TAG = "WalcottLocation"
    }
}
