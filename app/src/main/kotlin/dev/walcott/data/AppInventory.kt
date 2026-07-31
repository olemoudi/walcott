package dev.walcott.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/** Reads device apps via PackageManager (requires QUERY_ALL_PACKAGES). */
class AppInventory(context: Context) {

    private val pm: PackageManager = context.packageManager
    private val ownPackage = context.packageName
    private val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)

    @Volatile private var dialer: String? = null
    @Volatile private var dialerReadAt = 0L

    /**
     * The phone app, as the system currently answers it — never limited by anything Walcott
     * does (see [WalcottRepository.essentials]).
     *
     * Asked of the system rather than assumed: on most phones the dialer is a system app and
     * was already out of reach, but a device whose default dialer is an ordinary installed app
     * would have had it counted and capped like any other. "You can always call" cannot rest on
     * how the manufacturer happened to package it.
     *
     * Cached with a TTL because the enforcement loop reads the essentials every tick, and
     * re-read now and then because changing the default dialer is a thing a person can do
     * without restarting the app.
     */
    fun defaultDialerPackage(): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (dialer == null || now - dialerReadAt > DIALER_TTL_MS) {
            dialer = runCatching { telecom?.defaultDialerPackage }.getOrNull()
            dialerReadAt = now
        }
        return dialer
    }

    /** Launchable apps, sorted by name. Excludes Walcott itself. */
    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ownPackage }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    isSystem = it.isSystemApp(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Packages the enforcement manages: every user-installed (non-system) app, never Walcott
     * itself. It used to also include whatever the parent had classified; with limits set per
     * app there is nothing to classify, and an app that isn't installed can't be used anyway.
     */
    fun managedPackages(): Set<String> =
        launchableApps().filterNot { it.isSystem }.map { it.packageName }.toSet() - ownPackage

    fun icon(packageName: String): Drawable? =
        runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    /** Display label for one installed package, or null when it isn't installed. */
    fun label(packageName: String): String? =
        runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrNull()

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private companion object {
        /** How long the resolved dialer is trusted; it changes about as often as never. */
        const val DIALER_TTL_MS = 60 * 60 * 1000L
    }
}
