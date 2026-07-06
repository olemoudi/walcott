package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import dev.walcott.WalcottAdminReceiver

/** Applies the desired block state via Device Owner, touching only what changes. */
class Enforcer(context: Context) {

    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = WalcottAdminReceiver.componentName(context)
    private val ownPackage = context.packageName

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(ownPackage)

    /**
     * Syncs suspension of [managed] so that exactly [blocked] end up suspended. Only calls
     * the system for the differences, avoiding churn.
     */
    fun apply(managed: Set<String>, blocked: Set<String>) {
        if (!isDeviceOwner()) return
        val toSuspend = mutableListOf<String>()
        val toUnsuspend = mutableListOf<String>()
        for (pkg in managed) {
            val shouldBlock = pkg in blocked
            val isSuspended = runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
            if (shouldBlock && !isSuspended) toSuspend += pkg
            if (!shouldBlock && isSuspended) toUnsuspend += pkg
        }
        if (toSuspend.isNotEmpty()) {
            runCatching { dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true) }
        }
        if (toUnsuspend.isNotEmpty()) {
            runCatching { dpm.setPackagesSuspended(admin, toUnsuspend.toTypedArray(), false) }
        }
    }

    /** Lifts all suspensions of [managed] (e.g. if enforcement is turned off). */
    fun releaseAll(managed: Set<String>) {
        if (!isDeviceOwner()) return
        val suspended = managed.filter {
            runCatching { dpm.isPackageSuspended(admin, it) }.getOrDefault(false)
        }
        if (suspended.isNotEmpty()) {
            runCatching { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false) }
        }
    }
}
