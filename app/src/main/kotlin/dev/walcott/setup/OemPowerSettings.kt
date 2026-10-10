package dev.walcott.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The makers whose phones decide for themselves which apps may keep running, and where on each
 * one a person says "this one may".
 *
 * Stock Android keeps a Device Owner's app alive and brings it back (see WalcottDeviceAdminService);
 * these makers add their own process killer on top, and with its "autostart" switch off they also
 * drop that app's alarms, jobs and boot broadcast — every way back this app has. No API reads or
 * changes that switch, and its place moves between versions of each skin, so this is the honest
 * best: name the makers known to do it (dontkillmyapp.com keeps the list), open the most specific
 * screen that exists on this phone, and fall back to the app's own page, which on every one of
 * them is at least one tap away from the switch. What happens there cannot be read back, so the
 * card is satisfied by the visit (see DeviceSetupStore.oemBackgroundVisitedAt).
 */
object OemPowerSettings {

    enum class Maker { XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG }

    /**
     * The maker family of a phone from its [manufacturer] and [brand] build fields, or null for
     * one that behaves like stock Android (Pixel, Motorola, Nokia, Sony…).
     *
     * Both fields, because sub-brands report differently: a POCO says "Xiaomi" as manufacturer
     * and "POCO" as brand, an Honor sold after the split says "HONOR" for both, and OnePlus and
     * realme run OPPO's ColorOS underneath whatever they call themselves.
     */
    fun makerOf(manufacturer: String, brand: String): Maker? {
        val names = setOf(manufacturer.lowercase().trim(), brand.lowercase().trim())
        return when {
            names.any { it in setOf("xiaomi", "redmi", "poco") } -> Maker.XIAOMI
            names.any { it in setOf("huawei", "honor") } -> Maker.HUAWEI
            names.any { it in setOf("oppo", "realme", "oneplus") } -> Maker.OPPO
            names.any { it in setOf("vivo", "iqoo") } -> Maker.VIVO
            names.any { it == "samsung" } -> Maker.SAMSUNG
            else -> null
        }
    }

    /** This phone's maker family, or null (see [makerOf]). */
    fun thisPhone(): Maker? =
        makerOf(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.BRAND.orEmpty())

    /**
     * The dedicated screens to try on [maker], most specific first. Empty where the app's own
     * page already holds both switches — MIUI/HyperOS ("Autostart", "Battery saver"), ColorOS
     * ("Battery usage": auto launch and background activity) and One UI ("Battery":
     * Unrestricted) — which is also the fallback everywhere else.
     */
    internal fun screensFor(maker: Maker): List<ComponentName> = when (maker) {
        Maker.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Maker.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        Maker.XIAOMI, Maker.OPPO, Maker.SAMSUNG -> emptyList()
    }

    /** Opens the best screen this phone has for it. Never throws; answers whether anything opened. */
    fun open(context: Context, maker: Maker? = thisPhone()): Boolean {
        val dedicated = maker?.let { screensFor(it) }.orEmpty().map { Intent().setComponent(it) }
        val appPage = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        return (dedicated + appPage).any { intent ->
            // Started and caught rather than resolved first: several of these exist on a phone and
            // still refuse a start from another app, which only the start itself finds out.
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
