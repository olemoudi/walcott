package dev.walcott.location

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.walcott.WalcottAdminReceiver

/**
 * Device Owner enforcement for location tracking: force-grant fine/coarse location to Walcott
 * (so the child can't revoke it) and keep location services on. No-op unless Device Owner.
 * Mirrors the runCatching + isDeviceOwnerApp idiom in [dev.walcott.enforcement.DeviceRestrictions].
 */
object LocationPolicy {

    fun ensureEnforced(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = WalcottAdminReceiver.componentName(context)
        val pkg = context.packageName
        // Grant foreground location if missing, then hand control back to DEFAULT. Keeping
        // the grant admin-HELD (GRANT_STATE_GRANTED) is what makes Android 12+ show the
        // persistent "your admin can access this device's location" transparency notice on
        // fully managed devices. Moving to DEFAULT leaves the permission granted but drops
        // the admin hold — and with it the notice's basis. If the child revokes it from
        // Settings, this method re-grants on the next service start or watchdog pass.
        val foreground = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        for (perm in foreground) {
            runCatching {
                val granted = ContextCompat.checkSelfPermission(context, perm) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                }
                dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            }
        }
        // Deliberately do NOT admin-grant ACCESS_BACKGROUND_LOCATION: an admin-forced background
        // grant triggers a persistent, non-dismissible "your IT admin is allowing … to access your
        // location" system notification. Sampling runs inside the location-typed foreground service
        // (EnforcementService), which grants while-in-use access without the background permission,
        // and on Android 14+ the FGS type is required regardless. DENY it rather than reset to
        // DEFAULT: DEFAULT only releases admin control and leaves a grant from an earlier build in
        // place, so that privacy notification never cleared on devices provisioned before the fix.
        runCatching {
            dpm.setPermissionGrantState(
                admin, pkg, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
            )
        }
        // Only flip location on when it is actually off: every admin setLocationEnabled(true)
        // re-posts the system's "location enabled by your admin" notification, and this runs on
        // every service (re)start, so calling it unconditionally spammed the child with it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !locationEnabled(context)) {
            runCatching { dpm.setLocationEnabled(admin, true) }
        }
    }

    /** Whether system location is currently on (the admin toggle is only needed when it isn't). */
    fun locationEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
    }.getOrDefault(false)

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
