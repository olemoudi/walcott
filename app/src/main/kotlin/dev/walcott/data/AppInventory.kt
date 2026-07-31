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

    @Volatile private var reachOut: Set<String> = emptySet()
    @Volatile private var reachOutReadAt = 0L

    /**
     * The apps that let the child reach a person: the phone, and contacts. Never limited by
     * anything Walcott does (see [WalcottRepository.essentials]) — not by a budget, not at
     * bedtime. Calling at three in the morning has to be possible, and so does looking up the
     * number to call, or the pair is worth nothing.
     *
     * Asked of the system rather than assumed. On most phones both ship bundled, so they were
     * already out of reach as system apps — but a device whose dialer or contacts app is an
     * ordinary installed one would have had it counted and capped like any other. This promise
     * cannot rest on how the manufacturer happened to package them.
     *
     * Cached with a TTL because the enforcement loop reads the essentials every tick, and
     * re-read now and then because a person can change their default dialer without
     * restarting the app.
     */
    fun alwaysReachablePackages(): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (reachOut.isEmpty() || now - reachOutReadAt > REACH_OUT_TTL_MS) {
            reachOut = setOfNotNull(dialerPackage(), contactsPackage())
            reachOutReadAt = now
        }
        return reachOut
    }

    private fun dialerPackage(): String? = runCatching { telecom?.defaultDialerPackage }.getOrNull()

    /**
     * Whoever answers "open contacts". There is no contacts *role* to ask for the way there is
     * for the dialer, so this resolves the intent a launcher would fire. With several handlers
     * and no default the system answers with its own chooser, which is nobody's contacts app —
     * hence the guard.
     */
    private fun contactsPackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
        pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != RESOLVER_PACKAGE }
    }.getOrNull()

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
        /** How long the resolved apps are trusted; they change about as often as never. */
        const val REACH_OUT_TTL_MS = 60 * 60 * 1000L

        /** The system's "which app should open this?" chooser, not an app in its own right. */
        const val RESOLVER_PACKAGE = "android"
    }
}
