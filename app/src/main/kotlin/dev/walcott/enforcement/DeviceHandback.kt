package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog

/**
 * Everything a Device Owner has to put back before it stops being one.
 *
 * The last step of a release — `clearDeviceOwnerApp` — is a door that only opens outwards. After
 * it, every call in this file is refused, and anything still set stays set for the life of the
 * install: an app suspended for ever with no rule and no loop to lift it, a settings screen the
 * owner cannot reach, a phone that says it is managed by an organisation that no longer exists.
 * The only remaining cure is the factory reset this whole feature exists to avoid.
 *
 * So this errs by excess, deliberately, and in three ways:
 *
 *  - **Every restriction, not the ones we know we set.** [DeviceRestrictions.FEATURES] is what
 *    the product offers today; a device may be carrying one from a build that offered something
 *    else, or one the platform added a restriction key for since. Both lists the system will
 *    answer with are swept — what this admin set, and what is in force — so what comes off does
 *    not depend on this code's memory of what it once put on.
 *  - **Every installed package, not the managed set.** The managed set is derived from a policy
 *    that is being erased in the same breath, and the install guard's quarantine is not in it at
 *    all. What is actually asked is the only question that matters: is this package suspended,
 *    hidden or undeletable right now.
 *  - **Every knob a Device Owner has that this app has ever touched, plus the ones that would be
 *    catastrophic to leave behind** — permitted input methods, permitted accessibility services,
 *    lock task — even though nothing here has ever set them. Clearing one that was never set
 *    costs a binder call; leaving one behind costs the phone.
 *
 * Every call is individually guarded. A restriction that was not there, a package that has just
 * been uninstalled, an OEM that refuses a knob it does not implement — none of them may stop the
 * next line from running.
 */
object DeviceHandback {

    private const val TAG = "WalcottPanic"

    /**
     * Puts everything back, and says what it did. Returns false when something was refused, so
     * the caller can leave a trace worth reading afterwards — but it never throws and never
     * stops early, because a partial handback is the thing to avoid, not to report.
     *
     * Safe on a device that is not a Device Owner: it does nothing at all.
     */
    fun run(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return true
        if (!dpm.isDeviceOwnerApp(context.packageName)) return true
        val admin = WalcottAdminReceiver.componentName(context)
        val failures = Failures()
        clearEveryRestriction(context, dpm, admin, failures)
        freeEveryPackage(context, dpm, admin, failures)
        clearEveryPolicy(context, dpm, admin, failures)
        if (failures.count == 0) {
            DebugLog.w(TAG, "handback: everything came off cleanly")
        } else {
            DebugLog.e(TAG, "handback: ${failures.count} step(s) refused: ${failures.what.joinToString()}")
        }
        return failures.count == 0
    }

    /**
     * What would not come off, kept so the log line names it rather than counting it — and names
     * WHY. This runs once, on a phone nobody will look at again, and "3 steps refused" is not a
     * thing anybody can act on a week later.
     */
    private class Failures {
        var count = 0
        val what = mutableListOf<String>()
        fun note(step: String, cause: Throwable? = null) {
            count++
            if (what.size < MAX_NAMED) {
                what += if (cause == null) step else "$step (${cause.javaClass.simpleName}: ${cause.message})"
            }
        }
    }

    /**
     * Every user restriction in force, cleared by name.
     *
     * Two sources, because they answer different questions and neither alone is enough.
     * [DevicePolicyManager.getUserRestrictions] says what THIS admin set — the authoritative
     * list, and the one that covers keys this build has never heard of.
     * [UserManager.getUserRestrictions] says what is in force by any means, which catches
     * anything a previous install of this app left behind before it learned to clean up.
     * [DeviceRestrictions.FEATURES] is added on top so a restriction the system declines to
     * report is still asked about by name.
     */
    private fun clearEveryRestriction(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        failures: Failures,
    ) {
        val keys = sortedSetOf<String>()
        runCatching { dpm.getUserRestrictions(admin).keySet() }
            .onSuccess { keys += it }
            .onFailure { failures.note("read own restrictions") }
        runCatching {
            val users = context.getSystemService(UserManager::class.java)
            users?.userRestrictions?.let { bundle ->
                keys += bundle.keySet().filter { bundle.getBoolean(it) }
            }
        }.onFailure { failures.note("read active restrictions") }
        keys += DeviceRestrictions.FEATURES.flatMap { it.restrictions }
        DebugLog.i(TAG, "handback: clearing ${keys.size} restriction(s)")
        for (key in keys) {
            runCatching { dpm.clearUserRestriction(admin, key) }
                .onFailure { failures.note("restriction $key", it) }
        }
    }

    /**
     * Every package on the device, asked one at a time whether this admin is still holding it.
     *
     * The batch call first because it is one binder round trip for the whole phone, then the
     * per-package check because the batch reports refusals as a list and a package it could not
     * reach must not be left suspended on the strength of a return value nobody looked at.
     */
    private fun freeEveryPackage(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        failures: Failures,
    ) {
        val packages = runCatching {
            context.packageManager.getInstalledApplications(0).map { it.packageName }.distinct()
        }.getOrElse {
            failures.note("list installed packages")
            emptyList()
        }
        if (packages.isEmpty()) return
        DebugLog.i(TAG, "handback: freeing ${packages.size} package(s)")
        runCatching { dpm.setPackagesSuspended(admin, packages.toTypedArray(), false) }
            .onSuccess { refused ->
                if (!refused.isNullOrEmpty()) DebugLog.w(TAG, "handback: not unsuspended in bulk: ${refused.size}")
            }
            .onFailure { failures.note("bulk unsuspend") }
        for (pkg in packages) {
            // Suspended: asked again per package, because the bulk call above can refuse
            // silently and this is the last chance anything has to notice.
            runCatching {
                if (dpm.isPackageSuspended(admin, pkg)) {
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                    DebugLog.w(TAG, "handback: $pkg was still suspended after the bulk pass")
                }
            }.onFailure { failures.note("unsuspend $pkg") }
            // Hidden: a different mechanism with the same effect on the owner — the app is
            // simply not there — and nothing would ever lift it again.
            runCatching {
                if (dpm.isApplicationHidden(admin, pkg)) dpm.setApplicationHidden(admin, pkg, false)
            }.onFailure { failures.note("unhide $pkg") }
            // And the uninstall block, including this app's own: a released Walcott that cannot
            // be removed is exactly the state the release exists to leave behind.
            runCatching {
                if (dpm.isUninstallBlocked(admin, pkg)) dpm.setUninstallBlocked(admin, pkg, false)
            }.onFailure { failures.note("unblock uninstall $pkg") }
        }
    }

    /**
     * Every other knob a Device Owner holds.
     *
     * The ones this app sets are here because they must be; the ones it does not are here
     * because the cost of clearing a knob nobody touched is a binder call, and the cost of
     * leaving a real one behind is a phone whose keyboard is gone or whose screen is pinned.
     *
     * Two things are deliberately NOT put back, and they are the same two the older teardown
     * spared: location and automatic time. A Device Owner turning them ON leaves an ordinary
     * setting in an ordinary state, not a trace of management — and turning them off on the way
     * out would be this app's last act being to break something.
     */
    private fun clearEveryPolicy(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        failures: Failures,
    ) {
        fun step(name: String, block: () -> Unit) =
            runCatching(block).onFailure { failures.note(name, it) }

        /**
         * A knob this admin was never granted the power to touch, and therefore cannot have set.
         *
         * Walcott's device-admin declaration asks for `force-lock` and nothing else, so the
         * password and keyguard calls below are refused outright on every device — three
         * guaranteed failures in a report whose whole job is to say when something went wrong.
         * Asking first is not a corner cut: a policy that was never granted is a policy that was
         * never applied, and there is nothing to put back.
         */
        fun withPolicy(policy: Int, name: String, block: () -> Unit) {
            if (runCatching { dpm.hasGrantedPolicy(admin, policy) }.getOrDefault(false)) {
                step(name, block)
            }
        }

        // The filter, and the consent it rides on.
        step("always-on vpn") { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        // The lock screen: biometrics, the timeout, the wipe threshold, and the token this app
        // registered so it could reset a forgotten PIN (see LockScreen). The token in particular
        // has to go — it is a key to this phone, held by an app that is about to stop being
        // trusted with anything.
        withPolicy(android.app.admin.DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES, "keyguard features") {
            dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
        }
        withPolicy(android.app.admin.DeviceAdminInfo.USES_POLICY_FORCE_LOCK, "maximum time to lock") {
            dpm.setMaximumTimeToLock(admin, 0)
        }
        withPolicy(android.app.admin.DeviceAdminInfo.USES_POLICY_WIPE_DATA, "failed-password wipe") {
            dpm.setMaximumFailedPasswordsForWipe(admin, 0)
        }
        step("reset-password token") { dpm.clearResetPasswordToken(admin) }
        @Suppress("DEPRECATION")
        withPolicy(android.app.admin.DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, "password quality") {
            dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            step("password complexity") {
                dpm.setRequiredPasswordComplexity(DevicePolicyManager.PASSWORD_COMPLEXITY_NONE)
            }
        }
        // Permissions this app granted itself by policy rather than by asking (see LocationPolicy
        // and NotificationPolicy). Back to DEFAULT, which is what makes them the owner's to
        // revoke again — left GRANTED, the settings screen simply refuses to turn them off.
        for (permission in POLICY_GRANTED_PERMISSIONS) {
            step("permission $permission") {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
                )
            }
        }
        step("permission policy") {
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_PROMPT)
        }
        // The ringer floor (see AudioGuard), and the three knobs that would leave a phone the
        // owner cannot type on, cannot leave, or cannot photograph anything with.
        step("master volume") { dpm.setMasterVolumeMuted(admin, false) }
        step("permitted input methods") { dpm.setPermittedInputMethods(admin, null) }
        step("permitted accessibility services") { dpm.setPermittedAccessibilityServices(admin, null) }
        step("lock task packages") { dpm.setLockTaskPackages(admin, emptyArray()) }
        step("camera") { dpm.setCameraDisabled(admin, false) }
        step("screen capture") { dpm.setScreenCaptureDisabled(admin, false) }
        step("status bar") { dpm.setStatusBarDisabled(admin, false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // "This app cannot be force-stopped or have its data cleared." Nothing here sets it,
            // and a phone left carrying it has an app it cannot get rid of.
            step("user-control exemptions") { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        }
        // And the marks management leaves on the screens the owner reads: the organisation name
        // under Settings, the line on the lock screen, the support text on every blocked action.
        step("organization name") { dpm.setOrganizationName(admin, null) }
        step("lock screen info") { dpm.setDeviceOwnerLockScreenInfo(admin, null) }
        step("short support message") { dpm.setShortSupportMessage(admin, null) }
        step("long support message") { dpm.setLongSupportMessage(admin, null) }
        step("system update policy") { dpm.setSystemUpdatePolicy(admin, null) }
    }

    /** The permissions this app grants itself as Device Owner rather than by asking. */
    private val POLICY_GRANTED_PERMISSIONS = listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    /** How many refused steps are named in the log line before it is just a count. */
    private const val MAX_NAMED = 12
}
