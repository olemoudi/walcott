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
 * Deliberately NOT offered while the app is alpha: blocking factory reset, safe mode,
 * sideloading or USB debugging. Those are the recovery paths if Walcott itself
 * misbehaves — locking them could leave the device unrecoverable. The parent gets a
 * check-in staleness alert instead (see StaleChildWorker).
 *
 * Note: Android has no restriction that prevents the primary user from CHANGING the
 * screen lock; the closest supported control is disabling biometric unlock entirely
 * (keyguard feature), which is what [KEY_BIOMETRICS] does.
 */
object DeviceRestrictions {

    const val KEY_VPN = "vpn"
    const val KEY_LOCATION = "location"
    const val KEY_DATETIME = "datetime"
    const val KEY_BIOMETRICS = "biometrics"
    const val KEY_ADD_USER = "add_user"
    const val KEY_APPS_CONTROL = "apps_control"

    data class Feature(val key: String, val restrictions: List<String>)

    val FEATURES = listOf(
        Feature(KEY_VPN, listOf(UserManager.DISALLOW_CONFIG_VPN)),
        Feature(KEY_LOCATION, listOf(UserManager.DISALLOW_CONFIG_LOCATION)),
        Feature(KEY_DATETIME, listOf(UserManager.DISALLOW_CONFIG_DATE_TIME)),
        Feature(KEY_BIOMETRICS, emptyList()), // keyguard feature, not a user restriction
        Feature(KEY_ADD_USER, listOf(UserManager.DISALLOW_ADD_USER)),
        Feature(KEY_APPS_CONTROL, listOf(UserManager.DISALLOW_APPS_CONTROL)),
    )

    /** Applies exactly the [enabledKeys] feature set (clears the rest). Device Owner only. */
    fun apply(context: Context, enabledKeys: Set<String>) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = WalcottAdminReceiver.componentName(context)

        for (feature in FEATURES) {
            val enabled = feature.key in enabledKeys
            for (restriction in feature.restrictions) {
                runCatching {
                    if (enabled) dpm.addUserRestriction(admin, restriction)
                    else dpm.clearUserRestriction(admin, restriction)
                }
            }
        }

        // Side effects: locking the setting is only useful if the setting is in the safe state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (KEY_LOCATION in enabledKeys) runCatching { dpm.setLocationEnabled(admin, true) }
            if (KEY_DATETIME in enabledKeys) runCatching { dpm.setAutoTimeEnabled(admin, true) }
        }
        runCatching {
            dpm.setKeyguardDisabledFeatures(
                admin,
                if (KEY_BIOMETRICS in enabledKeys) DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS
                else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE,
            )
        }
    }
}
