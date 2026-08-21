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

    /**
     * Whether the DNS filter is wanted at all right now — and the ONE place that answers it.
     *
     * Three reasons, and they are asked from two places that must agree: the enforcement
     * service's collector, and the watchdog's periodic re-assert. They did not agree. The
     * curfew was added to the collector alone, so a family with no domain rules had the
     * watchdog pulling the tunnel out from under a running bedtime every fifteen minutes —
     * a filter that turned itself off, on a timer, in the middle of the hours it exists for.
     *
     * Every input is passed in rather than read here, so what the answer depends on is visible
     * at the call site: [curfew] in particular is computed differently by the two callers (see
     * [dev.walcott.rules.Curfew]), because they know different things.
     */
    fun wanted(settings: dev.walcott.data.PolicySettings, monitoring: Boolean, curfew: Set<String>): Boolean =
        settings.hasWebFilter() || monitoring || curfew.isNotEmpty()

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
