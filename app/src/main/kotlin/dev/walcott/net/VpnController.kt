package dev.walcott.net

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import dev.walcott.WalcottAdminReceiver

/**
 * Turns the DNS filter on/off. On a Device Owner device it pins the VPN as always-on so the
 * child can't disable it. Lockdown is deliberately OFF: our tun only routes DNS, so lockdown
 * would block all other traffic and kill the child's internet.
 */
object VpnController {

    private const val TAG = "WalcottVpn"

    fun apply(context: Context, enabled: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = WalcottAdminReceiver.componentName(context)
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

        if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Also what grants the VPN consent a child can't be asked for: without it,
            // establish() returns null and the filter is quietly off. Log the refusal.
            runCatching {
                dpm.setAlwaysOnVpnPackage(
                    admin,
                    if (enabled) context.packageName else null,
                    /* lockdownEnabled = */ false,
                )
            }.onFailure { dev.walcott.debug.DebugLog.e(TAG, "setAlwaysOnVpnPackage(enabled=$enabled) refused", it) }
        }
        if (enabled) WalcottVpnService.start(context) else WalcottVpnService.stop(context)
    }
}
