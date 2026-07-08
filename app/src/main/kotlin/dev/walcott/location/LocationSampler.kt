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

    /** Best current fix, or the freshest cached one, or null if unavailable. */
    suspend fun currentFix(timeoutMs: Long = FIX_TIMEOUT_MS): LocationPoint? {
        val lm = lm ?: return null
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val loc = withTimeoutOrNull(timeoutMs) { requestSingle(lm, provider) }
            if (loc != null) return loc.toPoint()
        }
        // Fall back to the most recent cached fix from any provider.
        return (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toPoint()
    }

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
    )

    companion object {
        private const val FIX_TIMEOUT_MS = 20_000L
    }
}
