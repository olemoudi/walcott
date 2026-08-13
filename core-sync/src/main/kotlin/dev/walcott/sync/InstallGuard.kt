package dev.walcott.sync

/**
 * What may be installed on a child device, and what to do with whatever else turns up.
 *
 * The install block is all-or-nothing: Android can lift it for a while, never "for this one
 * app". So the promise that an approval installs exactly the approved app cannot be kept at
 * the door — it is kept afterwards, by comparing what is installed against what was installed
 * before and answering for the difference. That comparison is this file.
 *
 * Two things it must not get wrong:
 *  - the approved app itself is never quarantined, even when it lands LATE. Play can finish a
 *    download after the window closed (another install closed it, or it simply took longer),
 *    so approval survives the window by [LATE_LANDING_GRACE_MS].
 *  - an app already quarantined is not re-reported on every pass. The ledger is a set of open
 *    cases, and a case closes when the app is gone or the parent allows it — not when the next
 *    reconciliation runs.
 *
 * Pure, so all of it is unit-tested on the JVM: this decides what gets suspended and removed
 * from a child's phone, which is not a thing to leave to an emulator run.
 */
object InstallGuard {

    /**
     * How many open cases are tracked at once. Everything here is suspended and reported, so
     * the cap is both; five apps sneaked past one window is already far past any real spree,
     * and dropping the oldest is logged rather than silent.
     */
    const val MAX_QUARANTINE = 5

    /**
     * How long an approved package stays approved after its window closed.
     *
     * Play commits an install session when the child taps Install, and the package lands
     * whenever the download finishes — which can be after the window shut, especially since
     * the FIRST install to land is what shuts it. Without this grace the approved app would be
     * quarantined for the crime of being slow.
     */
    const val LATE_LANDING_GRACE_MS = 5 * 60 * 1000L

    /**
     * Whether new arrivals are judged at all right now.
     *
     * Two cases must go unjudged, and both would be disasters if they didn't:
     *  - the family doesn't block installs. Then installing is not a policy violation, it is
     *    Tuesday, and quarantining every app the child installs would be a betrayal of a
     *    setting they deliberately left off.
     *  - a blanket window is open. That is a parent standing at the phone having entered their
     *    PIN to install something themselves — every install in it is theirs, by definition.
     *
     * The baseline still advances in both cases, so turning the block on later doesn't
     * retroactively indict apps that were installed while it was off.
     */
    fun guarding(installsBlocked: Boolean, blanketWindowOpen: Boolean): Boolean =
        installsBlocked && !blanketWindowOpen

    /**
     * The packages allowed to appear right now: the open window's target, plus the last one
     * for as long as [LATE_LANDING_GRACE_MS] allows.
     */
    fun approved(
        pendingPackage: String,
        lastWindowPackage: String,
        lastWindowClosedAtMs: Long,
        nowMs: Long,
    ): Set<String> = buildSet {
        if (pendingPackage.isNotBlank()) add(pendingPackage)
        if (lastWindowPackage.isNotBlank() && nowMs - lastWindowClosedAtMs <= LATE_LANDING_GRACE_MS) {
            add(lastWindowPackage)
        }
    }

    /**
     * Packages that appeared since [baseline] and answer to nobody: not approved, not already
     * an open case. Deliberately blind to whether a window was open at all — an app that
     * appears with installs blocked and no window is the most interesting case of the lot, not
     * an impossible one.
     */
    fun fresh(
        installed: Set<String>,
        baseline: Set<String>,
        approved: Set<String>,
        quarantined: Set<String>,
    ): Set<String> = installed - baseline - approved - quarantined

    /**
     * The ledger after a reconciliation: open cases that are still installed, plus the new
     * ones, newest last, capped at [MAX_QUARANTINE].
     *
     * An entry disappears exactly when its app does. That is what makes the removal retryable:
     * a uninstall the OS refused, or one interrupted by a reboot, leaves the case open and the
     * app suspended until the next pass tries again.
     */
    fun nextQuarantine(
        current: List<UnauthorizedApp>,
        fresh: List<UnauthorizedApp>,
        installed: Set<String>,
    ): List<UnauthorizedApp> {
        val open = current.filter { it.pkg in installed }
        val openPackages = open.map { it.pkg }.toSet()
        return (open + fresh.filterNot { it.pkg in openPackages }).takeLast(MAX_QUARANTINE)
    }

    /** Entries dropped by [nextQuarantine]'s cap, so the caller can say so out loud. */
    fun overflow(current: List<UnauthorizedApp>, fresh: List<UnauthorizedApp>, installed: Set<String>): Int {
        val open = current.filter { it.pkg in installed }
        val openPackages = open.map { it.pkg }.toSet()
        return (open.size + fresh.count { it.pkg !in openPackages } - MAX_QUARANTINE).coerceAtLeast(0)
    }
}
