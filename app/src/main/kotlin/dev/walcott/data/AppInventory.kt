package dev.walcott.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
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

    @Volatile private var launchable: List<InstalledApp>? = null
    @Volatile private var launchableReadAt = 0L

    /**
     * Launchable apps, sorted by name. Excludes Walcott itself.
     *
     * Cached, because building this is not cheap and nothing about it changes between installs:
     * it queries every launcher activity on the device and then loads a LABEL for each one, which
     * means opening that app's resources. On a phone with eighty apps that is eighty resource
     * loads — and the child publishes a snapshot carrying this list every fifteen minutes, for
     * ever, to say the same thing every time.
     *
     * [invalidate] is called by the package receivers that already exist for exactly this event,
     * so the cache is normally exact rather than merely fresh; the TTL is only a backstop for a
     * process with no receiver registered.
     */
    fun launchableApps(): List<InstalledApp> {
        val now = android.os.SystemClock.elapsedRealtime()
        launchable?.let { if (now - launchableReadAt <= LAUNCHABLE_TTL_MS) return it }
        val fresh = readLaunchableApps()
        launchable = fresh
        launchableReadAt = now
        return fresh
    }

    /** Drops the cached app list; call when a package is added or removed. */
    fun invalidate() {
        launchable = null
    }

    private fun readLaunchableApps(): List<InstalledApp> {
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

    /**
     * Every non-system package installed here, whether or not it has an icon — the baseline the
     * install guard judges new arrivals against (see [dev.walcott.sync.InstallGuard]).
     *
     * Wider than [managedPackages] on purpose, and read straight from the system rather than
     * from the launchable cache: "what turned up on this phone" must not depend on the app
     * having a launcher activity, which is precisely what something arriving quietly would skip.
     *
     * Null means the question could not be answered, and is not the same as the empty set: a
     * child's phone with nothing installed but Walcott is a perfectly ordinary state — a fresh
     * one, in fact — and treating it as a failure would leave the guard unseeded until the first
     * app arrived, which is exactly the app it exists to catch.
     */
    fun userPackages(): Set<String>? = runCatching {
        pm.getInstalledApplications(0)
            .filterNot { it.isSystemApp() }
            .map { it.packageName }
            .toSet() - ownPackage
    }.getOrNull()

    /**
     * Packages whose screen time is COUNTED — deliberately wider than [managedPackages], which is
     * about what may be blocked.
     *
     * A parent asked to see what the phone is being used for cannot be shown only the apps they
     * happen to limit, and least of all only the non-system ones: on most phones the browser, the
     * video app and the gallery ship as system apps, so the very apps a day disappears into were
     * the ones missing from the report. Knowing is what leads to setting a limit; requiring the
     * limit first to be allowed to know had it backwards.
     *
     * The launcher is excluded because "time on the home screen" is an artefact of walking
     * between apps, not something anyone did. Walcott excludes itself for the same reason.
     */
    fun trackedPackages(): Set<String> =
        launchableApps().map { it.packageName }.toSet() - ownPackage - setOfNotNull(homePackage())

    /** The current home app, asked of the system the way [dialerPackage] is. */
    private fun homePackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it != RESOLVER_PACKAGE }
    }.getOrNull()

    fun icon(packageName: String): Drawable? =
        runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    /** Display label for one installed package, or null when it isn't installed. */
    fun label(packageName: String): String? =
        runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrNull()

    /**
     * Who installed [packageName], as the platform records it: "com.android.vending" for Play,
     * null for a sideload or when the platform will not say.
     *
     * Asked because Play installs things on its own account — a component a preinstalled app
     * turned out to need, a split it decided to fetch — and on the parent's screen that arrives
     * looking exactly like a child downloading a game. It is context, never a verdict: a child
     * installing from Play produces the same answer, which is why the parent is still asked.
     */
    fun installerOf(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private companion object {
        /** How long the resolved apps are trusted; they change about as often as never. */
        const val REACH_OUT_TTL_MS = 60 * 60 * 1000L

        /** Backstop only — the package receivers invalidate this the moment it goes stale. */
        const val LAUNCHABLE_TTL_MS = 10 * 60 * 1000L

        /** The system's "which app should open this?" chooser, not an app in its own right. */
        const val RESOLVER_PACKAGE = "android"
    }
}
