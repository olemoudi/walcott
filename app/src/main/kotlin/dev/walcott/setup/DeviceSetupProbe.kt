package dev.walcott.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.walcott.WalcottApplication
import dev.walcott.enforcement.AppBlockerService
import dev.walcott.enforcement.Enforcer
import dev.walcott.enforcement.UsageAccess
import dev.walcott.location.LocationPolicy
import kotlinx.coroutines.flow.first

/**
 * Reads the real state of this device for [DeviceSetup], and knows where each answer is changed.
 *
 * The single place any of this is asked. It used to be answered in three — the child's home, the
 * heartbeat's self-check and the parent's home — with three slightly different ideas of what
 * counted, which is how a device ends up looking healthy on one screen and broken on another.
 */
object DeviceSetupProbe {

    /** One pass over the system's answers. Cheap enough for a screen resume. */
    suspend fun read(context: Context): DeviceFacts {
        val app = context.applicationContext as? WalcottApplication
        val identity = app?.identityStore?.current()
        val settings = app?.repository?.settingsFlow?.first()
        val enforcingChild = identity?.enforcesLocally == true
        return DeviceFacts(
            enforcingChild = enforcingChild,
            deviceOwner = runCatching { Enforcer(context).isDeviceOwner() }.getOrDefault(false),
            notificationsEnabled = notificationsEnabled(context),
            // Only meaningful on an enforcing device; reading it elsewhere costs a binder call
            // to answer a question nothing asks.
            usageAccessGranted = !enforcingChild || UsageAccess.granted(context),
            accessibilityEnabled = runCatching { AppBlockerService.isEnabled(context) }.getOrDefault(true),
            locationPermissionGranted = LocationPolicy.hasFineLocation(context),
            locationServiceEnabled = LocationPolicy.locationEnabled(context),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(context),
            locationWanted = (settings?.trackingIntervalMinutes ?: 0) > 0,
            webFilterWanted = settings?.hasWebFilter() == true,
            // settledDown, not tunnelUp: a process that has just started always has the tunnel
            // down for a few seconds while the service brings it up (see VpnStatus.GRACE_MS).
            webFilterRunning = !dev.walcott.net.VpnStatus.settledDown(),
        )
    }

    fun notificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)

    /** True when the OS has exempted Walcott from battery optimisation (or can't tell us). */
    fun ignoringBatteryOptimizations(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * Opens the exact system screen that fixes [requirement] — the whole point of the button.
     *
     * "Go to Settings, then Special app access, then Usage access, then find Walcott" is an
     * instruction that does not get followed; a deep link is the difference between a nudge that
     * works and one that is dismissed because acting on it is a chore. Each falls back to this
     * app's own settings page, which every OEM has, rather than failing silently.
     */
    fun openFix(context: Context, requirement: DeviceRequirement) {
        val intent = fixIntent(context, requirement)
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            runCatching { context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** The same destination as [openFix], for callers that need a PendingIntent instead. */
    fun fixIntent(context: Context, requirement: DeviceRequirement): Intent {
        val intent = when (requirement) {
            DeviceRequirement.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            DeviceRequirement.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            DeviceRequirement.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            DeviceRequirement.LOCATION_SERVICE -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            DeviceRequirement.BATTERY_OPTIMIZATION ->
                // The list, not the permission-gated direct prompt: no extra permission needed.
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            // No system screen turns a VPN back on — consent is granted by starting it — and the
            // location permission is a runtime prompt the caller handles. Both land on the app's
            // own page, which is where the permission can at least be reviewed.
            DeviceRequirement.WEB_FILTER, DeviceRequirement.LOCATION_PERMISSION -> appDetails(context)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun appDetails(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
}
