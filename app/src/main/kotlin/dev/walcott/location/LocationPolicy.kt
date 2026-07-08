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
        // Force-grant location so the child can't revoke it. Order matters: background ("all the
        // time") only sticks once the foreground grant is in place, and both must be declared in
        // the manifest. As Device Owner all three are granted silently (API 30+ honors background).
        val perms = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        for (perm in perms) {
            runCatching {
                dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setLocationEnabled(admin, true) }
        }
    }

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
