package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import dev.walcott.R
import dev.walcott.WalcottAdminReceiver

/**
 * Device-protection features the parent can toggle: each maps to Device Owner user
 * restrictions (plus a couple of side effects like forcing location/auto-time on).
 * No-ops on devices that aren't Device Owner. Only the restrictions listed here are
 * ever touched, so Walcott never clears a restriction it doesn't own.
 *
 * Factory reset is deliberately NOT offered: it is the one way out that does not depend on
 * this app, and the README promises it. USB debugging and safe mode used to be left out for
 * the same reason — recovery paths if Walcott itself misbehaves — but a phone now has three
 * doors of its own (the parent PIN, the remote release, the twelve-hour request) and a rescue
 * code that needs no network, so those two are offered and on by default for a child: a
 * laptop with adb, or a boot with every third-party app off, is a way round every rule here.
 * [KEY_NETWORK_RESET] is not one of those: it undoes Wi-Fi and mobile settings, not this app,
 * and it is one of the buttons an adult being helped presses while looking for something else.
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
    const val KEY_DEBUGGING = "debugging"
    const val KEY_SAFE_BOOT = "safe_boot"
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
    val RECOMMENDED_DEFAULTS = setOf(
        KEY_DATETIME, KEY_VPN, KEY_APPS_CONTROL, KEY_UNKNOWN_SOURCES, KEY_INSTALLS,
        KEY_ADD_USER, KEY_DEBUGGING, KEY_SAFE_BOOT,
    )

    /**
     * What 0.107 added to the defaults, seeded once more into families that already existed
     * (see PolicySettings.seedRestrictionsV2). A guest user is a phone where this app does not
     * exist and nothing is suspended; the other two are the two doors the beta left open.
     */
    val RECOMMENDED_SINCE_107 = setOf(KEY_ADD_USER, KEY_DEBUGGING, KEY_SAFE_BOOT)

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
        // A second user is a setting nobody changes on purpose, and a phone that has switched
        // to one is a phone whose owner cannot find their own apps.
        KEY_ADD_USER,
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
        // Both halves: a user that cannot be created, and one that already exists (a guest,
        // an OEM's second profile) that cannot be switched to. Suspension is per user, so
        // either is a phone with none of the rules on it.
        //
        // And the containers that are users underneath: a work profile, and Android 15's private
        // space. An app in one runs as another user, so it is neither suspended nor counted nor
        // seen by the blocker. Added by platform version, because the read-back below would
        // otherwise report a key the platform has never heard of as a restriction the phone
        // refused.
        //
        // NOT the clone profile behind Samsung's Dual Messenger and Xiaomi's Dual Apps: its
        // restriction is hidden from the public SDK, and asking for a key a device owner may not
        // set would put a false "the phone refused this" card in front of the parent. Whether
        // those clones are closed on a given phone has to be checked on that phone.
        Feature(KEY_ADD_USER, addUserRestrictions()),
        // Developer options and adb: `am force-stop`, `settings put`, a sideload past the
        // install block — a laptop is the way round every rule the phone itself enforces.
        Feature(KEY_DEBUGGING, listOf(UserManager.DISALLOW_DEBUGGING_FEATURES)),
        // Safe mode boots with every third-party app off, this one and its filter included.
        Feature(KEY_SAFE_BOOT, listOf(UserManager.DISALLOW_SAFE_BOOT)),

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

    private fun addUserRestrictions(): List<String> = buildList {
        add(UserManager.DISALLOW_ADD_USER)
        add(UserManager.DISALLOW_USER_SWITCH)
        add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            add(UserManager.DISALLOW_ADD_PRIVATE_PROFILE)
        }
    }

    /** [enabledKeys] minus the install block while a PIN-gated exemption window is open. */
    fun effectiveKeys(enabledKeys: Set<String>, installExemptUntilMs: Long, nowMs: Long): Set<String> =
        if (nowMs < installExemptUntilMs) enabledKeys - KEY_INSTALLS else enabledKeys

    /**
     * Applies exactly the [enabledKeys] feature set (clears the rest). Device Owner only.
     *
     * Answers with the keys the phone REFUSED — features that are on in the policy and whose
     * restriction the system does not report in force afterwards. Every other enforcement
     * surface here measures rather than assumes (the suspension reconciler, the handback), and
     * this one, which is the whole anti-tamper story, used to fire and forget: an OEM refusing
     * `DISALLOW_CONFIG_VPN` left the parent looking at a switch that was on and a filter the
     * child could turn off in Settings. Empty on a device that is not Device Owner.
     */
    fun apply(context: Context, enabledKeys: Set<String>, installExemptUntilMs: Long = 0): Set<String> {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return emptySet()
        if (!dpm.isDeviceOwnerApp(context.packageName)) return emptySet()
        // An alarm firing mid-release would put back what the handback is taking off, for good.
        if (PanicRelease.inProgress) return emptySet()
        val admin = WalcottAdminReceiver.componentName(context)
        val effective = effectiveKeys(enabledKeys, installExemptUntilMs, System.currentTimeMillis())

        // Every write below is made only when the system says the state differs. This runs from
        // the policy observer and from the watchdog every fifteen minutes, and it used to issue
        // every restriction's add or clear, the uninstall block and both support messages each
        // time whether anything had changed or not — about thirty privileged calls, most of which
        // rewrite the device-policy file in system_server. The READ is what makes skipping safe:
        // nothing is remembered here, so a restriction the handback took off, or the install
        // block the updater lifts, is seen as missing and put back like anything else.

        // Self-protection: as Device Owner, Walcott can't be uninstalled (always on).
        runCatching {
            if (!dpm.isUninstallBlocked(admin, context.packageName)) {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            }
        }

        // What the phone says on its own behalf wherever Android tells somebody an action is
        // "managed by your administrator" — changing the date, installing something, resetting
        // the phone. Until now those screens named an administrator and nothing else, which is
        // the least useful true sentence a phone can produce: the person reading it is the one
        // holding the phone, and what they need is which app to open and what it can do for
        // them. Cleared again on handback (see DeviceHandback).
        runCatching {
            val short = context.getString(R.string.admin_support_short)
            val long = context.getString(R.string.admin_support_long)
            // Compared as text, so a change of the phone's language still rewrites them.
            if (dpm.getShortSupportMessage(admin)?.toString() != short) dpm.setShortSupportMessage(admin, short)
            if (dpm.getLongSupportMessage(admin)?.toString() != long) dpm.setLongSupportMessage(admin, long)
        }

        // Null when the system will not say: then every restriction is written, as before.
        val inForce = runCatching { dpm.getUserRestrictions(admin) }.getOrNull()
        for (feature in FEATURES) {
            val enabled = feature.key in effective
            for (restriction in feature.restrictions) {
                if (inForce != null && inForce.getBoolean(restriction) == enabled) continue
                runCatching {
                    if (enabled) dpm.addUserRestriction(admin, restriction)
                    else dpm.clearUserRestriction(admin, restriction)
                }.onFailure {
                    dev.walcott.debug.DebugLog.w(TAG, "the system refused $restriction for ${feature.key}", it)
                }
            }
        }
        val refused = refusedFeatures(dpm, admin, effective)
        if (refused.isNotEmpty()) {
            dev.walcott.debug.DebugLog.w(TAG, "restrictions not in force after applying: ${refused.sorted().joinToString()}")
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
        return refused
    }

    /**
     * The features in [enabled] whose restrictions the system does not report as set by this
     * admin. Asked of the system, not tallied from exceptions: a call can return normally and
     * change nothing. Empty when the system will not answer — an unreadable answer is not a
     * refusal, and reporting one would alarm every parent on a phone that merely declined the
     * question.
     */
    private fun refusedFeatures(dpm: DevicePolicyManager, admin: ComponentName, enabled: Set<String>): Set<String> {
        val inForce = runCatching { dpm.getUserRestrictions(admin) }.getOrNull() ?: return emptySet()
        return FEATURES
            .filter { it.key in enabled && it.restrictions.any { restriction -> !inForce.getBoolean(restriction) } }
            .map { it.key }
            .toSet()
    }

    private const val TAG = "WalcottRestrict"
}
