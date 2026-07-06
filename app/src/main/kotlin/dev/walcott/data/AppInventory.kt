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
     * Packages the enforcement manages: user apps (non-system) plus any app the parent has
     * explicitly classified. Never Walcott itself.
     */
    fun managedPackages(assignments: Map<String, String>): Set<String> {
        val userInstalled = launchableApps().filterNot { it.isSystem }.map { it.packageName }
        return (userInstalled + assignments.keys - ownPackage).toSet()
    }

    fun icon(packageName: String): Drawable? =
        runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}
