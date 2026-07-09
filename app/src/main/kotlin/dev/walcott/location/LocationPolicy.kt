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
        // Force-grant foreground location so the child can't revoke it.
        val foreground = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        for (perm in foreground) {
            runCatching {
                dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        // Deliberately do NOT admin-grant ACCESS_BACKGROUND_LOCATION: an admin-forced background
        // grant triggers a persistent, non-dismissible "your IT admin is allowing … to access your
        // location" system notification. Sampling runs inside the location-typed foreground service
        // (EnforcementService), which grants while-in-use access without the background permission,
        // and on Android 14+ the FGS type is required regardless. Reset any prior force-grant back
        // to DEFAULT so that notification clears on devices provisioned by an earlier build.
        runCatching {
            dpm.setPermissionGrantState(
                admin, pkg, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setLocationEnabled(admin, true) }
        }
    }

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
