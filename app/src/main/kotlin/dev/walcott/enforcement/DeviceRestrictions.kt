package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import dev.walcott.WalcottAdminReceiver

/**
 * Device-protection features the parent can toggle: each maps to Device Owner user
 * restrictions (plus a couple of side effects like forcing location/auto-time on).
 * No-ops on devices that aren't Device Owner. Only the restrictions listed here are
 * ever touched, so Walcott never clears a restriction it doesn't own.
 *
 * Deliberately NOT offered while the app is beta: blocking factory reset, safe mode,
 * sideloading or USB debugging. Those are the recovery paths if Walcott itself
 * misbehaves — locking them could leave the device unrecoverable. The parent gets a
 * check-in staleness alert instead (see StaleChildWorker). [KEY_NETWORK_RESET] is not one of
 * those: it undoes Wi-Fi and mobile settings, not this app, and it is one of the buttons an
 * adult being helped presses while looking for something else.
 *
 * Note: Android has no restriction that prevents the primary user from CHANGING the
 * screen lock; the closest supported control is disabling biometric unlock entirely
 * (keyguard feature), which is what [KEY_BIOMETRICS] does. What DOES exist is resetting the
 * lock remotely, which is a different feature (see [LockScreen]).
 *
 * And one restriction that looks made for this and is not: `DISALLOW_ADJUST_VOLUME`. Reaching for
 * it to stop somebody silencing their phone does the opposite — "if set, the master volume will be
 * muted". Keeping a ringer audible is re-assertion, not prohibition (see [AudioGuard]).
 */
object DeviceRestrictions {

    const val KEY_VPN = "vpn"
    const val KEY_LOCATION = "location"
    const val KEY_DATETIME = "datetime"
    const val KEY_BIOMETRICS = "biometrics"
    const val KEY_INSTALLS = "installs"
    const val KEY_ADD_USER = "add_user"
    const val KEY_APPS_CONTROL = "apps_control"
    const val KEY_UNKNOWN_SOURCES = "unknown_sources"

    // The settings a person changes by accident and cannot find their way back from. Written for
    // an adult being helped (see MemberKind.ADULT) and offered for a child too, because a phone
    // whose language has been switched to one nobody in the house reads is the same problem at
    // any age.
    const val KEY_AIRPLANE = "airplane"
    const val KEY_LOCALE = "locale"
    const val KEY_BRIGHTNESS = "brightness"
    const val KEY_SCREEN_TIMEOUT = "screen_timeout"
    const val KEY_WIFI = "wifi"
    const val KEY_MOBILE_NETWORKS = "mobile_networks"
    const val KEY_DEFAULT_APPS = "default_apps"
    const val KEY_ACCOUNTS = "accounts"
    const val KEY_UNINSTALL = "uninstall"
    const val KEY_NETWORK_RESET = "network_reset"

    /** The PIN-gated window choices: a quick errand, a session, and "I don't know" (8 h). */
    const val INSTALL_EXEMPTION_SHORT_MS = 10 * 60 * 1000L
    const val INSTALL_EXEMPTION_MEDIUM_MS = 30 * 60 * 1000L
    const val INSTALL_EXEMPTION_UNSURE_MS = 8 * 60 * 60 * 1000L

    /**
     * Seeded on by default for new families (see PolicySettings.seedRestrictions).
     *
     * [KEY_INSTALLS] is in it, and that is not the same as "no new apps" any more: a new family
     * is seeded into the guarded mode (see [dev.walcott.enforcement.AppUpdates]), where this key
     * means "judge what appears" and the platform is never told to refuse installs at all. Play
     * keeps working — which is what keeps the phone's apps patched — and anything that turns up
     * unapproved is suspended and put in front of the parent within seconds.
     *
     * That is the default worth having: the alternative starts a family off with a phone whose
     * apps quietly stop updating, in exchange for a promise ("nothing installs, ever") most
     * families did not know they were making.
     */
    val RECOMMENDED_DEFAULTS =
        setOf(KEY_DATETIME, KEY_VPN, KEY_APPS_CONTROL, KEY_UNKNOWN_SOURCES, KEY_INSTALLS)

    /**
     * What an adult being helped is offered as a starting point: the accidents, plus not installing
     * apps without being asked.
     *
     * Airplane mode and the connectivity ones are in it because the failure they cause is the one
     * that matters most — a phone nobody can reach, whose owner does not know why. Brightness and
     * screen timeout are in it because a screen at zero reads as a broken phone. The language is in
     * it because recovering from it means navigating Settings in a script you cannot read.
     */
    val RECOMMENDED_FOR_ADULT = setOf(
        KEY_AIRPLANE, KEY_LOCALE, KEY_BRIGHTNESS, KEY_SCREEN_TIMEOUT,
        KEY_MOBILE_NETWORKS, KEY_DEFAULT_APPS, KEY_ACCOUNTS, KEY_UNINSTALL,
        KEY_NETWORK_RESET, KEY_INSTALLS, KEY_APPS_CONTROL, KEY_DATETIME,
    )

    /** Which part of the screen a feature belongs under, so twenty switches read as three lists. */
    enum class Group { TAMPER, SETTINGS, APPS }

    data class Feature(val key: String, val restrictions: List<String>, val group: Group = Group.TAMPER)

    val FEATURES = listOf(
        // Private DNS belongs here and not in its own switch: "a VPN the child cannot remove" and
        // "a resolver the child cannot redirect" are the same promise, and a filter that survives
        // one and not the other is a filter with two lines of Settings between it and nothing
        // (see [dev.walcott.net.VpnController]).
        Feature(KEY_VPN, listOf(UserManager.DISALLOW_CONFIG_VPN, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)),
        Feature(KEY_LOCATION, listOf(UserManager.DISALLOW_CONFIG_LOCATION)),
        Feature(KEY_DATETIME, listOf(UserManager.DISALLOW_CONFIG_DATE_TIME)),
        Feature(KEY_BIOMETRICS, emptyList()), // keyguard feature, not a user restriction
        Feature(KEY_ADD_USER, listOf(UserManager.DISALLOW_ADD_USER)),

        // Settings somebody changes by accident.
        Feature(KEY_AIRPLANE, listOf(UserManager.DISALLOW_AIRPLANE_MODE), Group.SETTINGS),
        Feature(KEY_LOCALE, listOf(UserManager.DISALLOW_CONFIG_LOCALE), Group.SETTINGS),
        Feature(KEY_BRIGHTNESS, listOf(UserManager.DISALLOW_CONFIG_BRIGHTNESS), Group.SETTINGS),
        Feature(KEY_SCREEN_TIMEOUT, listOf(UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT), Group.SETTINGS),
        Feature(
            KEY_WIFI,
            listOf(UserManager.DISALLOW_CONFIG_WIFI, UserManager.DISALLOW_CHANGE_WIFI_STATE),
            Group.SETTINGS,
        ),
        Feature(KEY_MOBILE_NETWORKS, listOf(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS), Group.SETTINGS),
        Feature(KEY_NETWORK_RESET, listOf(UserManager.DISALLOW_NETWORK_RESET), Group.SETTINGS),
        Feature(KEY_ACCOUNTS, listOf(UserManager.DISALLOW_MODIFY_ACCOUNTS), Group.SETTINGS),

        // Apps.
        Feature(KEY_INSTALLS, listOf(UserManager.DISALLOW_INSTALL_APPS), Group.APPS),
        Feature(KEY_UNKNOWN_SOURCES, listOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY), Group.APPS),
        Feature(KEY_UNINSTALL, listOf(UserManager.DISALLOW_UNINSTALL_APPS), Group.APPS),
        Feature(KEY_APPS_CONTROL, listOf(UserManager.DISALLOW_APPS_CONTROL), Group.APPS),
        Feature(KEY_DEFAULT_APPS, listOf(UserManager.DISALLOW_CONFIG_DEFAULT_APPS), Group.APPS),
    )

    /** [enabledKeys] minus the install block while a PIN-gated exemption window is open. */
    fun effectiveKeys(enabledKeys: Set<String>, installExemptUntilMs: Long, nowMs: Long): Set<String> =
        if (nowMs < installExemptUntilMs) enabledKeys - KEY_INSTALLS else enabledKeys

    /** Applies exactly the [enabledKeys] feature set (clears the rest). Device Owner only. */
    fun apply(context: Context, enabledKeys: Set<String>, installExemptUntilMs: Long = 0) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = WalcottAdminReceiver.componentName(context)
        val effective = effectiveKeys(enabledKeys, installExemptUntilMs, System.currentTimeMillis())

        // Self-protection: as Device Owner, Walcott can't be uninstalled (always on).
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, true) }

        for (feature in FEATURES) {
            val enabled = feature.key in effective
            for (restriction in feature.restrictions) {
                runCatching {
                    if (enabled) dpm.addUserRestriction(admin, restriction)
                    else dpm.clearUserRestriction(admin, restriction)
                }
            }
        }

        // Side effects: locking the setting is only useful if the setting is in the safe state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Skip when location is already on: each admin setLocationEnabled(true) re-posts the
            // system's "enabled by your admin" notification, and apply() runs on every policy sync.
            if (KEY_LOCATION in enabledKeys && !dev.walcott.location.LocationPolicy.locationEnabled(context)) {
                runCatching { dpm.setLocationEnabled(admin, true) }
            }
            if (KEY_DATETIME in enabledKeys) {
                runCatching { dpm.setAutoTimeEnabled(admin, true) }
                runCatching { dpm.setAutoTimeZoneEnabled(admin, true) }
            }
        }
        runCatching {
            dpm.setKeyguardDisabledFeatures(
                admin,
                if (KEY_BIOMETRICS in enabledKeys) DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS
                else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE,
            )
        }
    }

    /**
     * Gives every restriction back: clears the whole [FEATURES] set, re-enables biometrics and
     * lifts the uninstall block on Walcott itself. Used by the emergency release
     * ([dev.walcott.enforcement.PanicRelease]) — it must run BEFORE Device Owner is given up,
     * since afterwards none of these calls are allowed any more. The forced-on side effects
     * (location, automatic time) are deliberately left alone: they are ordinary settings the
     * user can change, not traces of management.
     */
    fun clearAll(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = WalcottAdminReceiver.componentName(context)
        for (feature in FEATURES) {
            for (restriction in feature.restrictions) {
                runCatching { dpm.clearUserRestriction(admin, restriction) }
            }
        }
        runCatching { dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE) }
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
    }
}
