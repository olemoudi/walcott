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
            if (enabled) clearStrictPrivateDns(dpm, admin)
        }
        // Wrapped, because this is a plain startService and the callers cannot afford it to
        // throw. The watchdog would abandon the rest of its pass (the ringer, the pruning), and
        // the enforcement service's collector — a single `collect` over the reasons to be up —
        // would die for the life of the service, leaving the filter frozen at whatever it last
        // did. A background-start refusal is a thing to log and be re-tried by the next pass.
        runCatching {
            if (enabled) WalcottVpnService.start(context) else WalcottVpnService.stop(context)
        }.onFailure { dev.walcott.debug.DebugLog.e(TAG, "could not ${if (enabled) "start" else "stop"} the filter", it) }
    }

    /**
     * Takes the phone off "Private DNS: a hostname I typed" — the one switch in Settings that
     * walks straight past this filter without touching it.
     *
     * In strict mode the system resolver sends every lookup to a resolver of the child's choosing
     * over TLS on port 853. That is not DNS to the sentinel address, so the tun does not route it
     * and it leaves on the ordinary network: the filter stays up, reports itself healthy, blocks
     * nothing and sees nothing. Two lines in Settings and bedtime is over — including the curfew,
     * which is the same filter (see [dev.walcott.rules.Curfew]).
     *
     * Only ever pushed back to the platform default, and only while the filter is wanted:
     * "automatic" still encrypts DNS wherever the network's own resolver supports it, and it is
     * safe here because the sentinel does not answer on 853, so the resolver falls back to plain
     * DNS through the tun. Nothing here turns encryption off for a family that chose it.
     */
    private fun clearStrictPrivateDns(dpm: DevicePolicyManager, admin: android.content.ComponentName) {
        runCatching {
            if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                val result = dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    dev.walcott.debug.DebugLog.w(TAG, "private DNS pointed at its own resolver; put back to automatic")
                } else {
                    dev.walcott.debug.DebugLog.e(TAG, "private DNS is set to a hostname and would not reset ($result)")
                }
            }
        }.onFailure { dev.walcott.debug.DebugLog.e(TAG, "could not read or reset the private DNS mode", it) }
    }
}
