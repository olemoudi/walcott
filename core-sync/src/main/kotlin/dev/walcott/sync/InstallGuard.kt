package dev.walcott.sync

import kotlinx.serialization.Serializable

/**
 * An install the parent approved and pushed to the phone, waiting for the app to land.
 *
 * Several at once, which is what a family asks for: a child shares two apps from Play in a row,
 * the parent approves both. There used to be one slot for this, and the second approval overwrote
 * the first — whose app, installed a minute later, was then judged as never approved: suspended,
 * removed, and reported to the parent as installed behind their back.
 *
 * [windowUntilMs] is when this approval's own install window closes, 0 when it is shut — the
 * approval outlives its window (the child taps the card later, and the window opens again);
 * [approvedAtMs] is when the approval itself runs out (see [InstallGuard.PUSH_LIFETIME_MS]).
 */
@Serializable
data class PendingPush(
    val pkg: String,
    val commandId: String = "",
    /** The app's name as the parent's device sent it: it is not installed, so nothing here knows it. */
    val label: String = "",
    val approvedAtMs: Long = 0,
    val windowUntilMs: Long = 0,
)

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
     * How many removal attempts a case survives before the phone is told to stop expecting it to
     * work, and the parent is told instead.
     *
     * A Device Owner uninstall is silent but refusable — an app the OS itself protects, an OEM
     * package, one another admin has pinned — and the retry is per reconciliation, so four
     * attempts is already several passes and at least one heartbeat apart. Beyond that the
     * removal is not late, it is not going to happen, and a parent waiting for it needs to hear
     * so: the app is suspended and unusable either way, which is the promise this screen makes.
     */
    const val STUCK_REMOVAL_ATTEMPTS = 4

    /**
     * Whether the window open right now is a BLANKET one: a person standing at the phone with
     * their PIN entered, installing things themselves.
     *
     * This used to be inferred from "no pushed install is pending", and the inference was wrong
     * in the one case that costs a family an app. A pushed install stays pending until it lands
     * — deliberately, so the child can tap the card an hour later — so a push nobody acted on
     * sits on file indefinitely, and while it does, the parent's OWN window was read as that
     * push's window: everything they installed in it was unapproved, suspended and silently
     * uninstalled while they watched.
     *
     * So the window says what it is. The old inference is kept as the fallback for a window that
     * was already open when this field arrived, and only ever ADDS blanket-ness: being wrong that
     * way costs a window in which nothing is judged, and being wrong the other way costs the
     * parent the apps they were standing there installing. [pushWindowOpen] is whether any pushed
     * install's own window is open now — not whether one is merely approved, which can last days.
     */
    fun blanketOpen(
        untilMs: Long,
        nowMs: Long,
        blanket: Boolean,
        maintenance: Boolean,
        pushWindowOpen: Boolean,
    ): Boolean {
        if (untilMs <= nowMs || maintenance) return false
        return blanket || !pushWindowOpen
    }

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
     * What is left of the ledger on a pass that judges nothing (see [guarding]).
     *
     * The early return used to leave the ledger untouched, on the reasoning that a pass which
     * indicts nobody should also exonerate nobody. That was wrong twice over, and both ways
     * ended with an app the family never chose to lose:
     *
     *  - **The family turned the install block off.** Installing is no longer a violation, so
     *    there is no longer such a thing as an unauthorized app here. Leaving cases open keeps
     *    suspending apps under a rule that has been withdrawn, and nothing will ever clear them:
     *    the only code that closes a case is the pass this state skips.
     *  - **The case's app is already gone.** A case ends when its app does. Holding the entry
     *    means the next install of that same package — including the parent approving it
     *    properly, in its own window — walks straight into a stale accusation and is suspended
     *    and removed, because a still-open case is kept for any package that is installed.
     *
     * So: with the block withdrawn, nothing is retained; otherwise only cases whose app is
     * still here. Neither branch can create a case — that is still [fresh]'s job alone.
     */
    fun retained(
        current: List<UnauthorizedApp>,
        installed: Set<String>,
        installsBlocked: Boolean,
    ): List<UnauthorizedApp> =
        if (!installsBlocked) emptyList() else current.filter { it.pkg in installed }

    /**
     * The packages allowed to appear right now: every approved push, window open or not — the
     * child taps Install whenever they get to it, and Play finishes whenever it finishes — plus
     * the last one that landed, for as long as [LATE_LANDING_GRACE_MS] allows (a second copy of
     * the same session can report in a moment after the first).
     */
    fun approved(
        pushes: List<PendingPush>,
        lastWindowPackage: String,
        lastWindowClosedAtMs: Long,
        nowMs: Long,
    ): Set<String> = buildSet {
        pushes.forEach { add(it.pkg) }
        if (lastWindowPackage.isNotBlank() && nowMs - lastWindowClosedAtMs <= LATE_LANDING_GRACE_MS) {
            add(lastWindowPackage)
        }
    }

    // --- Pushed installs (see PendingPush) ---

    /** How many approvals can wait at once; the oldest makes room. A family approves a handful. */
    const val MAX_PENDING_PUSHES = 8

    /**
     * How long an approval waits for its app before it runs out: as long as the parent's own
     * queue keeps the command that carried it, so the two never disagree about whether it is
     * still meant.
     */
    const val PUSH_LIFETIME_MS = SyncEngine.COMMAND_TTL_MS

    /**
     * [pushes] with [push] in it: replacing an approval for the same app (a re-push retries, it
     * does not stack, and keeps the name and the age the first one had), and making room by
     * forgetting the oldest when there are too many.
     */
    fun withPush(pushes: List<PendingPush>, push: PendingPush): List<PendingPush> {
        val previous = pushes.firstOrNull { it.pkg == push.pkg }
        val merged = if (previous == null) {
            push
        } else {
            push.copy(
                commandId = push.commandId.ifBlank { previous.commandId },
                label = push.label.ifBlank { previous.label },
                approvedAtMs = previous.approvedAtMs,
            )
        }
        return (pushes.filterNot { it.pkg == push.pkg } + merged).takeLast(MAX_PENDING_PUSHES)
    }

    /** [pushes] without the one for [pkg] — it landed, or the parent withdrew it. */
    fun withoutPush(pushes: List<PendingPush>, pkg: String): List<PendingPush> = pushes.filterNot { it.pkg == pkg }

    /**
     * The approvals that have not run out at [nowMs] (see [PUSH_LIFETIME_MS]). One whose window
     * is open right now stays until it closes: dropped mid-window, it left the install block lifted
     * with no push behind it, which reads as somebody's blanket window — nothing judged at all.
     */
    fun livePushes(pushes: List<PendingPush>, nowMs: Long): List<PendingPush> =
        pushes.filter { it.approvedAtMs <= 0 || nowMs - it.approvedAtMs <= PUSH_LIFETIME_MS || it.windowUntilMs > nowMs }

    /** When the last open push window closes, or 0 when none is open at [nowMs]. */
    fun windowDeadline(pushes: List<PendingPush>, nowMs: Long): Long =
        pushes.maxOfOrNull { it.windowUntilMs }?.takeIf { it > nowMs } ?: 0L

    /**
     * Every push window shut, every approval kept: what "re-apply protection" and "close now" do.
     * They end the windows a parent can see open; withdrawing an approval was never what anybody
     * pressing them meant, and it is what they used to do — while the parent was trying to work
     * out why the app would not install.
     */
    fun closedWindows(pushes: List<PendingPush>): List<PendingPush> = pushes.map { it.copy(windowUntilMs = 0) }

    /**
     * The approvals, with the one a build before [PendingPush] kept in its single slot folded in.
     * Read rather than migrated once: the old slot is cleared by the first write that touches the
     * approvals, and until then this answers the same either way.
     */
    fun migratedPushes(
        pushes: List<PendingPush>,
        legacyPackage: String,
        legacyCommandId: String,
        legacyLabel: String,
        legacyUntilMs: Long,
        nowMs: Long,
    ): List<PendingPush> {
        if (legacyPackage.isBlank() || pushes.any { it.pkg == legacyPackage }) return pushes
        // Dated by its window rather than by this read, or every read would make it new again and
        // it would never run out.
        val approvedAt = legacyUntilMs.takeIf { it > 0 } ?: nowMs
        return withPush(pushes, PendingPush(legacyPackage, legacyCommandId, legacyLabel, approvedAt, legacyUntilMs))
    }

    /**
     * The baseline once, on the first pass of a build that sees archived apps: the ones archived
     * now go in without being judged. Before this build an archived app had already dropped out
     * of the baseline, and seeing it again would read as a stranger turning up — every app Play
     * had archived on a full phone, quarantined at once. An open case keeps its case.
     */
    fun seedArchived(known: Set<String>, archived: Set<String>, openCases: Set<String>): Set<String> =
        known + (archived - openCases)

    // --- Holding an app for the parent's answer (the "watched" mode) ---

    /**
     * How long an app that appeared unapproved is kept, suspended, for the parent to answer —
     * as long as any other question a child asks stays open (see [SyncEngine.REQUEST_TTL_MS]).
     * Unanswered by then, it is removed.
     */
    const val HOLD_MS = SyncEngine.REQUEST_TTL_MS

    /** Whether [entry] is kept for an answer at [nowMs] rather than being removed. */
    fun held(entry: UnauthorizedApp, nowMs: Long): Boolean = entry.holdUntilMs > nowMs

    /** Whether to try removing [entry]'s app on this pass. */
    fun dueForRemoval(entry: UnauthorizedApp, nowMs: Long): Boolean = !held(entry, nowMs)

    /**
     * The ledger under the family's current mode: in "watched" mode a new case is held for
     * [HOLD_MS] from [nowMs]; in the strict mode nothing is held, including what was held under
     * the watched one — a family that tightened the rules meant them for what is already here.
     */
    fun heldUnder(entries: List<UnauthorizedApp>, fresh: Set<String>, guarded: Boolean, nowMs: Long): List<UnauthorizedApp> =
        entries.map { entry ->
            when {
                !guarded -> entry.copy(holdUntilMs = 0)
                entry.pkg in fresh -> entry.copy(holdUntilMs = nowMs + HOLD_MS)
                else -> entry
            }
        }

    /**
     * Makes room for [waiting] apps the cap is holding out, by giving up on that many of the
     * oldest held cases (they go to removal).
     *
     * Holding turned the cap into a door: five apps kept waiting, and a sixth — the one that was
     * wanted — left usable, unjudged, for as long as the five were waiting for an answer. The
     * oldest held case goes first; the parent has had longest to answer it.
     */
    fun makeRoom(entries: List<UnauthorizedApp>, waiting: Int, nowMs: Long): List<UnauthorizedApp> {
        if (waiting <= 0) return entries
        val toRemove = entries.filter { held(it, nowMs) }.sortedBy { it.atMs }.take(waiting).map { it.pkg }.toSet()
        return entries.map { if (it.pkg in toRemove) it.copy(holdUntilMs = 0) else it }
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
     * ones, newest last, capped at [MAX_QUARANTINE] — by holding the NEW ones out.
     *
     * An entry disappears exactly when its app does. That is what makes the removal retryable:
     * a uninstall the OS refused, or one interrupted by a reboot, leaves the case open and the
     * app suspended until the next pass tries again. The cap used to keep the newest and drop
     * the oldest case — which unsuspended an app nobody had approved, to make room.
     */
    fun nextQuarantine(
        current: List<UnauthorizedApp>,
        fresh: List<UnauthorizedApp>,
        installed: Set<String>,
    ): List<UnauthorizedApp> {
        val open = current.filter { it.pkg in installed }
        val openPackages = open.map { it.pkg }.toSet()
        return (open + fresh.filterNot { it.pkg in openPackages }).take(MAX_QUARANTINE)
    }

    /**
     * The apps [nextQuarantine]'s cap left out — not a count, because the caller has to do
     * something with them and not merely mention them.
     *
     * A dropped app is one that appeared without approval and is NOT suspended, NOT reported and
     * NOT being removed. It must therefore stay a stranger to this device: folded into the
     * baseline of known packages, as it was, it becomes an app that arrived unnoticed and can
     * never be noticed again, because the guard only ever asks what is new. Held out of the
     * baseline, it is simply a case waiting for a slot — and the cap costs a delay rather than
     * an app.
     */
    fun overflow(
        current: List<UnauthorizedApp>,
        fresh: List<UnauthorizedApp>,
        installed: Set<String>,
    ): List<UnauthorizedApp> {
        val open = current.filter { it.pkg in installed }
        val openPackages = open.map { it.pkg }.toSet()
        val all = open + fresh.filterNot { it.pkg in openPackages }
        return all.drop(MAX_QUARANTINE)
    }
}
