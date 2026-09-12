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
    private val pm = context.packageManager
    private val admin = WalcottAdminReceiver.componentName(context)
    private val ownPackage = context.packageName

    /**
     * The state each package was last left in by this reconciler, true for suspended.
     *
     * What makes the periodic re-assert cheap. It used to ask the system about every managed app
     * AND every preinstalled one with a launcher icon, one binder call each, every thirty seconds
     * with the screen on — a hundred to a hundred and fifty round trips into system_server on a
     * typical phone, twelve thousand an hour of use, to learn nothing had changed. Now only the
     * packages whose wanted state moved are asked about, and a full sweep still runs on a slower
     * clock to catch anything moved behind this app's back.
     *
     * Per instance on purpose: a new service is a new Enforcer and starts by asking about
     * everything. Concurrent because the install guard's quarantine arrives from another thread.
     */
    private val lastLeft = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Whether this reconciler may touch anything: Device Owner, and no release running. The
     * second half is what keeps the loop's re-assert from re-suspending into the middle of the
     * handback that is taking every suspension off (see PanicRelease.inProgress).
     */
    fun isDeviceOwner(): Boolean = !PanicRelease.inProgress && dpm.isDeviceOwnerApp(ownPackage)

    /**
     * Syncs suspension of [managed] so that exactly [blocked] end up suspended. Only calls
     * the system for the differences, avoiding churn.
     *
     * [giveBack] is reconciled too and can never be suspended: apps this phone is NOT managing
     * but may have suspended before. Asked of the system rather than remembered, because the
     * memory of what was managed a moment ago dies with the process — and the moment that
     * matters is a parent withdrawing the opt-in from a preinstalled app (see
     * `AppInventory.systemLaunchablePackages`), after which nothing else on the device would
     * ever unsuspend it. An app blocked with no rule to explain it and no way back is the one
     * failure this cannot have.
     */
    fun apply(
        managed: Set<String>,
        blocked: Set<String>,
        giveBack: Set<String> = emptySet(),
        fullSweep: Boolean = true,
    ) {
        if (!isDeviceOwner()) return
        val targets = managed + giveBack
        val wantBlocked = blocked - giveBack
        val check = packagesToCheck(targets, wantBlocked, lastLeft, fullSweep)
        val plan = plan(check, wantBlocked) { pkg ->
            runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
        }
        // Asked about and already right: remembered as right, so the next pass can skip them.
        for (pkg in check) {
            if (pkg !in plan.toSuspend && pkg !in plan.toUnsuspend) lastLeft[pkg] = pkg in wantBlocked
        }
        if (plan.toSuspend.isNotEmpty()) suspend(plan.toSuspend, true)
        if (plan.toUnsuspend.isNotEmpty()) suspend(plan.toUnsuspend, false)
        // A package that left both sets is nobody's business any more; forgetting it means that if
        // it ever comes back, it is asked about rather than assumed.
        lastLeft.keys.retainAll(targets)
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
        // Whatever this call left behind, for apply() to rely on. A package the system refused is
        // forgotten rather than recorded, so the next pass asks again instead of assuming success.
        val refused = failed?.toSet().orEmpty()
        for (pkg in packages) {
            if (pkg in refused) lastLeft.remove(pkg) else lastLeft[pkg] = suspend
        }
        if (suspend) {
            recentSuspendFailures = nextSuspendFailures(
                previous = recentSuspendFailures,
                attempted = packages,
                failed = failed?.toList().orEmpty(),
                isInstalled = ::isInstalled,
            )
        }
    }

    /**
     * Suspends [packages] outright, outside the rules, and answers which ones the OS actually
     * suspended — the quarantine for apps that appeared without approval (see
     * [dev.walcott.sync.InstallGuard]). Verified rather than assumed, because "we asked" and
     * "it is blocked" are different claims and the parent is shown one of them.
     */
    fun quarantine(packages: List<String>): Set<String> {
        if (packages.isEmpty() || !isDeviceOwner()) return emptySet()
        suspend(packages, true)
        return packages.filter { runCatching { dpm.isPackageSuspended(admin, it) }.getOrDefault(false) }.toSet()
    }

    /** Lifts the suspension of [packages] (the parent allowed a quarantined app to stay). */
    fun release(packages: List<String>) {
        if (packages.isEmpty() || !isDeviceOwner()) return
        suspend(packages, false)
    }

    private fun isInstalled(packageName: String): Boolean =
        runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess

    /**
     * The subset of [blocked] the system does NOT currently report suspended — the heartbeat
     * self-test's gap. Empty when not Device Owner (suspension state isn't measurable then).
     * A package the query throws on (just uninstalled) is not counted: it can't be used either.
     */
    fun unenforced(blocked: Set<String>): List<String> {
        if (!isDeviceOwner()) return emptyList()
        return blocked.filter { runCatching { !dpm.isPackageSuspended(admin, it) }.getOrDefault(false) }
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
         * Packages the OS recently refused to SUSPEND, kept process-wide for the remote
         * diagnostics report. Best-effort by design (lost on process death — the debug log
         * is the durable record); a bounded distinct list so it can't grow.
         */
        @Volatile var recentSuspendFailures: List<String> = emptyList()
            private set

        /** How many failing packages the report carries at most. */
        private const val MAX_SUSPEND_FAILURES = 8

        /**
         * The failure list after an attempt to suspend [attempted], of which [failed] came back
         * refused. Pure, because what it drops matters as much as what it keeps:
         *
         * - a package that is no longer installed is NOT a gap. The OS refuses to suspend it
         *   forever, so it would otherwise pin its own name — in red, as a package name, with
         *   no app to point at — to every future health report. It can't be used either way.
         * - a package that suspended fine this time drops off. The list answers "what is still
         *   broken", not "what ever broke once".
         */
        fun nextSuspendFailures(
            previous: List<String>,
            attempted: List<String>,
            failed: List<String>,
            isInstalled: (String) -> Boolean,
        ): List<String> {
            val healed = attempted.toSet() - failed.toSet()
            return (previous - healed + failed)
                // Filters the WHOLE list, not just the new entries: the name that pins itself to
                // every report got there while the app was still installed.
                .filter(isInstalled)
                .distinct()
                .takeLast(MAX_SUSPEND_FAILURES)
        }

        /**
         * The suspend/unsuspend diff to make exactly [blocked] suspended among [managed], given
         * the current [isSuspended] state. Pure (no Android), so the "touch only what changes"
         * reconciliation is unit-tested.
         */
        /**
         * Which of [targets] this pass has to ask the system about: all of them on a [fullSweep],
         * otherwise only those this reconciler did not leave in the state now wanted — changed,
         * refused last time, or never seen. Pure, because a package wrongly skipped here is an
         * app that stays open past its limit.
         */
        fun packagesToCheck(
            targets: Set<String>,
            wantBlocked: Set<String>,
            lastLeft: Map<String, Boolean>,
            fullSweep: Boolean,
        ): Set<String> =
            if (fullSweep) targets else targets.filterTo(mutableSetOf()) { lastLeft[it] != (it in wantBlocked) }

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
