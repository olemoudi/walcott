package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog

/** The minimal set of system calls to reconcile suspension state, touching only what changes. */
data class SuspensionPlan(val toSuspend: List<String>, val toUnsuspend: List<String>) {
    val isEmpty: Boolean get() = toSuspend.isEmpty() && toUnsuspend.isEmpty()
}

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
        val plan = plan(managed, blocked) { pkg ->
            runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
        }
        if (plan.toSuspend.isNotEmpty()) suspend(plan.toSuspend, true)
        if (plan.toUnsuspend.isNotEmpty()) suspend(plan.toUnsuspend, false)
    }

    /**
     * Suspends/unsuspends [packages], surfacing the ones the system refused. A non-empty
     * return from [DevicePolicyManager.setPackagesSuspended] is a real enforcement gap — a
     * blocked app the OS won't suspend (launcher, IME, an OEM-exempt package) would otherwise
     * stay usable with no trace. The 30s self-heal reassert retries transient failures; this
     * makes a persistent one diagnosable from the child's debug log.
     */
    private fun suspend(packages: List<String>, suspend: Boolean) {
        val failed = runCatching { dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspend) }
            .getOrElse {
                DebugLog.e(TAG, "setPackagesSuspended(suspend=$suspend) threw", it)
                return
            }
        if (!failed.isNullOrEmpty()) {
            val verb = if (suspend) "suspend" else "unsuspend"
            DebugLog.w(TAG, "could not $verb: ${failed.joinToString()}")
        }
    }

    /** Lifts all suspensions of [managed] (e.g. if enforcement is turned off). */
    fun releaseAll(managed: Set<String>) {
        if (!isDeviceOwner()) return
        val suspended = managed.filter {
            runCatching { dpm.isPackageSuspended(admin, it) }.getOrDefault(false)
        }
        if (suspended.isNotEmpty()) suspend(suspended, false)
    }

    companion object {
        private const val TAG = "WalcottEnforce"

        /**
         * The suspend/unsuspend diff to make exactly [blocked] suspended among [managed], given
         * the current [isSuspended] state. Pure (no Android), so the "touch only what changes"
         * reconciliation is unit-tested.
         */
        fun plan(managed: Set<String>, blocked: Set<String>, isSuspended: (String) -> Boolean): SuspensionPlan {
            val toSuspend = mutableListOf<String>()
            val toUnsuspend = mutableListOf<String>()
            for (pkg in managed) {
                val shouldBlock = pkg in blocked
                val suspended = isSuspended(pkg)
                if (shouldBlock && !suspended) toSuspend += pkg
                if (!shouldBlock && suspended) toUnsuspend += pkg
            }
            return SuspensionPlan(toSuspend, toUnsuspend)
        }
    }
}
