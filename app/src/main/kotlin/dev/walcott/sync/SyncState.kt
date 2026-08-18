package dev.walcott.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One idle-earn grant (extra minutes earned at a moment), for the rolling-window caps. */
@Serializable
data class EarnGrantEntry(val epochMs: Long, val minutes: Int)

/**
 * What the child home shows about the parents' latest answer (approval, denial, bonus).
 * Kept until the child dismisses it, so an answer that arrives while the phone is in a
 * drawer is still seen.
 */
@Serializable
data class NoticeEntry(
    /** "time" | ChildRequest.KIND_APP | ChildRequest.KIND_OTHER | "bonus". */
    val kind: String,
    val approved: Boolean,
    val minutes: Int = 0,
    val categoryId: String = "",
    val text: String = "",
    val atMs: Long,
)

/**
 * One entry of the parent's activity feed — the durable record behind every alert that
 * otherwise only exists as a dismissable notification. Raw data only (no localized text):
 * the UI renders each [type] in the device's current locale.
 */
@Serializable
data class ParentEvent(
    /** Unique id for list keys and dedup ("" on legacy entries). */
    val id: String = "",
    val atMs: Long,
    /** One of the TYPE_* constants; the UI skips types it doesn't know (forward compat). */
    val type: String,
    val childId: String = "",
    /** Display name at record time; the UI prefers the current registry name by [childId]. */
    val childName: String = "",
    /** Type-specific payload (app label, ask text, command action, skew/silence ms…). */
    val detail: String = "",
    /** Type-specific number (gap count, minutes, battery percent…). */
    val count: Int = 0,
) {
    companion object {
        const val TYPE_UNPROTECTED = "unprotected"
        const val TYPE_PROTECTION_DEGRADED = "protection_degraded"
        const val TYPE_USAGE_ACCESS_OFF = "usage_access_off"
        const val TYPE_MOCK_LOCATION = "mock_location"
        const val TYPE_LOW_BATTERY = "low_battery"
        const val TYPE_ENFORCEMENT_GAP = "enforcement_gap"
        const val TYPE_ENFORCEMENT_GAP_CLEARED = "enforcement_gap_cleared"
        const val TYPE_CLOCK_TAMPER = "clock_tamper"
        const val TYPE_RULES_APPLIED = "rules_applied"
        const val TYPE_WEB_FILTER_DOWN = "web_filter_down"
        const val TYPE_WEB_FILTER_BACK = "web_filter_back"
        const val TYPE_CHILD_CRASHED = "child_crashed"
        const val TYPE_INDOOR_LOCATION_OFF = "indoor_location_off"
        const val TYPE_NEW_APP = "new_app"
        const val TYPE_WRONG_PIN = "wrong_pin"
        const val TYPE_STALE = "stale"
        const val TYPE_NEVER_REPORTED = "never_reported"

        /**
         * A device the parent was told had gone quiet has checked in again. Only ever recorded
         * after a [TYPE_STALE] or [TYPE_NEVER_REPORTED], so the wall reads as a pair of lines
         * rather than as news about every phone that ever slept (see [Staleness.recoveryKeys]).
         */
        const val TYPE_BACK = "back_online"
        const val TYPE_TIME_REQUEST = "time_request"
        const val TYPE_ASK = "ask"
        const val TYPE_REQUEST_APPROVED = "request_approved"
        const val TYPE_REQUEST_DENIED = "request_denied"
        const val TYPE_BONUS = "bonus"
        const val TYPE_REMOTE_DONE = "remote_done"
        const val TYPE_PANIC_REQUEST = "panic_request"
        const val TYPE_PANIC_RELEASED = "panic_released"
        const val TYPE_PANIC_DENIED = "panic_denied"
        const val TYPE_PANIC_CANCELLED = "panic_cancelled"

        /** An install window has been open on a child device past its first hour. */
        const val TYPE_INSTALL_WINDOW = "install_window"

        /**
         * A child's phone is still missing settings only the person holding it can grant
         * ([count] = how many), and the closing line when the last one is finally granted.
         */
        const val TYPE_SETUP_PENDING = "setup_pending"
        const val TYPE_SETUP_DONE = "setup_done"

        /** A different app than the approved one was installed during a window; [detail] = its package. */
        const val TYPE_WRONG_APP = "wrong_app"

        /** A child sent a selection of domains to block; [detail] is the app, [count] how many. */
        const val TYPE_DOMAINS = "domains"

        /**
         * The everyday rhythm, reported by the child itself (see [ChildEvent]): one app's daily
         * limit ran out ([detail] names it), bedtime began, a screen-free window began. No
         * notification goes with these — they are what the wall is for.
         */
        const val TYPE_APP_TIME_OUT = "app_time_out"
        const val TYPE_BEDTIME = "bedtime"
        const val TYPE_SCREEN_FREE = "screen_free"

        /**
         * The wall entry for something the child reported its rules doing, or null when this
         * build doesn't know the kind (a newer child; skipped rather than shown as a blank
         * line). The event keeps its own id, which is what makes folding it in idempotent
         * however many times the snapshot is re-emitted.
         */
        fun fromChildEvent(event: ChildEvent, childId: String, childName: String): ParentEvent? {
            val type = when (event.kind) {
                ChildEvent.KIND_BUDGET_OUT -> TYPE_APP_TIME_OUT
                ChildEvent.KIND_BEDTIME -> TYPE_BEDTIME
                ChildEvent.KIND_SCREEN_FREE -> TYPE_SCREEN_FREE
                else -> return null
            }
            return ParentEvent(
                id = event.id,
                atMs = event.atMs,
                type = type,
                childId = childId,
                childName = childName,
                // The child named the app for us: the parent may never have heard of it.
                detail = event.label.ifBlank { event.pkg },
            )
        }

        /**
         * Collapses runs of identical consecutive entries (same type, child, detail and count)
         * into the run's first element plus how many it stands for. The feed can legitimately
         * repeat itself — two equal bonuses in a row are two grants — but N identical adjacent
         * lines read as noise, so the UI shows one line with a ×N mark instead.
         */
        fun collapseRepeats(events: List<ParentEvent>): List<Pair<ParentEvent, Int>> {
            val collapsed = mutableListOf<Pair<ParentEvent, Int>>()
            for (event in events) {
                val last = collapsed.lastOrNull()
                if (last != null && last.first.type == event.type && last.first.childId == event.childId &&
                    last.first.detail == event.detail && last.first.count == event.count
                ) {
                    collapsed[collapsed.size - 1] = last.copy(second = last.second + 1)
                } else {
                    collapsed += event to 1
                }
            }
            return collapsed
        }
    }
}

/** How many health reports the parent keeps per child device. */
const val MAX_DIAG_HISTORY = 10

/**
 * How many pages of a device's notification log a parent's phone keeps (see
 * [SyncState.notificationPages]).
 *
 * Six is about a day of paging backwards on a busy phone, and the point of the ceiling is not
 * memory: it is that this is somebody's messages sitting on another person's phone, and it should
 * age out on its own rather than only when a family thinks to clear it.
 */
const val MAX_NOTIFICATION_PAGES = 6

/**
 * A health report as the parent filed it: what the child sent, plus what the parent knew at
 * that moment. [seenAtVersionCode] exists because a report is dated: judging its app-version
 * row against whatever the parent runs *today* would turn every release into a retroactive
 * black mark on reports that were perfectly up to date when they were taken. 0 = filed before
 * this was recorded, which has to read as "can't tell", never as "outdated".
 */
@Serializable
data class StoredDiag(val report: DiagPayload, val seenAtVersionCode: Int = 0)

/** Persistent sync bookkeeping, separate from the rules ([dev.walcott.data.PolicySettings]). */
@Serializable
data class SyncState(
    // Child side
    val childVersion: Long = 0,
    val pendingRequests: List<ExtraTimeRequest> = emptyList(),
    val pendingAsks: List<ChildRequest> = emptyList(),
    /**
     * Ids of answers already applied, so a re-emitted parent snapshot cannot grant the same
     * minutes twice. Bounded (see [SyncState.rememberApplied]): the parent retires an answer long
     * before it could fall out of a list this long, and an unbounded set on a phone enrolled for
     * years is a DataStore blob that grows for ever and is rewritten on every check-in.
     */
    val appliedResolutionIds: Set<String> = emptySet(),
    val appliedBonusIds: Set<String> = emptySet(),
    /** Until when app installs are temporarily allowed on this device (PIN gate or approval). */
    val installExemptionUntilMs: Long = 0,
    /**
     * Target package of a parent-pushed install, while its self-closing window is open.
     * Non-empty means "one install allowed, then re-arm the block"; "" for the blanket
     * PIN-gated window. See [SyncManager.openInstallForPush]/[SyncManager.closeInstallWindow].
     */
    val pendingInstallPackage: String = "",
    /** The [RemoteCommand.id] behind [pendingInstallPackage], to ack "installed" when it lands. */
    val pendingInstallCommandId: String = "",
    /** Human name of [pendingInstallPackage] — the app isn't installed, so nothing else knows it. */
    val pendingInstallLabel: String = "",
    /**
     * The last window's target and when it closed, so an approved app that lands late is still
     * recognised as approved (see [InstallGuard.LATE_LANDING_GRACE_MS]).
     */
    val lastWindowPackage: String = "",
    val lastWindowClosedAtMs: Long = 0,
    /**
     * Non-system packages as of the last reconciliation — the baseline anything new is judged
     * against (see [InstallGuard]). [installBaselineSeeded] separates "nothing installed yet"
     * from "never looked": without it the first pass after an update would report every app on
     * the phone as an unauthorized install.
     */
    val knownPackages: Set<String> = emptySet(),
    val installBaselineSeeded: Boolean = false,
    /** Open quarantine cases: apps that appeared unapproved, suspended until the parent answers. */
    val unauthorizedApps: List<UnauthorizedApp> = emptyList(),
    /** requestedAtMs of the newest location request this child has already answered. */
    val appliedLocationRequestMs: Long = 0,
    /** Version of the newest parent snapshot whose rules this child has adopted. */
    val appliedParentVersion: Long = 0,
    /** The parents' latest answer (approval/denial/bonus), shown until the child dismisses it. */
    val lastNotice: NoticeEntry? = null,
    /** Consecutive wrong-PIN attempts and the lockout deadline (brute-force protection). */
    val pinFailedAttempts: Int = 0,
    val pinLockedUntilMs: Long = 0,
    /** Monotonic tally of wrong PINs (never reset), reported to the parent, and the last one's time. */
    val pinWrongTotal: Int = 0,
    val lastWrongPinMs: Long = 0,
    /**
     * Ids of remote commands this device already ran, so a replayed snapshot can't re-run them.
     * Bounded like the other two applied sets ([SyncState.rememberApplied]); the parent drops a
     * command from its queue after [SyncEngine.COMMAND_TTL_MS], well inside the cap.
     */
    val appliedCommandIds: Set<String> = emptySet(),
    /** Banked idle seconds not yet converted into earned extra time (idle-earn model). */
    val idleEarnBankSeconds: Long = 0,
    /** Idle-earn grants over the last week, for the rolling-window and weekly caps. */
    val earnGrants: List<EarnGrantEntry> = emptyList(),
    /** Result of the most recent remote command, echoed to the parent in the next snapshot. */
    val lastCommandAck: CommandAck? = null,
    /** Why the last self-update attempt failed ("" = the last check was clean). */
    val updateError: String = "",
    /** Blocked-but-not-suspended packages from the last heartbeat self-test (capped; [] = passed). */
    val enforcementGaps: List<String> = emptyList(),
    /** Local minus server clock in ms, as last measured by [ClockGuard]; 0 until measured. */
    val clockSkewMs: Long = 0,
    /**
     * What the rules have just done here, waiting to be seen by the parent (see [ChildEvent]).
     * Bounded by [ChildEventLog]; there is no acknowledgement, the parent folds each in by id.
     */
    val ruleEvents: List<ChildEvent> = emptyList(),
    /** The parent app's build, from its snapshots; the child only self-updates up to it (canary). */
    val parentAppVersionCode: Int = 0,
    /** Wall-clock ms of the last message received over the channel (proof it works end to end). */
    val lastChannelOkMs: Long = 0,
    /** This device's pending emergency-release request (see [PanicProtocol]); null = none. */
    val panic: PanicRequest? = null,
    /**
     * The domain selection this device is delivering to the parent, or the last one it tried:
     * kept after it lands (or runs out of retries) so the monitor screen can say which it was.
     * Replaced wholesale by the next send — one batch at a time is all a parent standing over
     * the phone ever produces. See [DomainDelivery].
     */
    val domainBatch: DomainBatch? = null,
    /**
     * Server second until which a parent's refusal blocks a new request. Counted in server
     * time, like the request itself, so moving the device clock can't wait out the lockout.
     */
    val panicBlockedUntilSec: Long = 0,
    // Parent side
    val parentVersion: Long = 0,
    val resolutions: List<Resolution> = emptyList(),
    val bonuses: List<Bonus> = emptyList(),
    /** Pending "locate now" asks, at most one per target device. */
    val locationRequests: List<LocationRequest> = emptyList(),
    /** Remote fixes queued for child devices, cleared as they are acknowledged. */
    val commands: List<RemoteCommand> = emptyList(),
    val children: List<ChildSnapshot> = emptyList(),
    /** deviceId -> wall-clock ms of the last message received from that child. */
    val lastSeen: Map<String, Long> = emptyMap(),
    /** deviceId -> the lastSeen value we already alerted about (one alert per outage). */
    val staleNotifiedLastSeen: Map<String, Long> = emptyMap(),
    /** deviceIds already alerted for having enforcement inactive (cleared when it recovers). */
    val enforcementNotified: Set<String> = emptySet(),
    /** deviceId -> the child's pinWrongTotal we already alerted about (one alert per new failure). */
    val pinAlertedTotal: Map<String, Int> = emptyMap(),
    /** deviceIds already alerted for missing usage access (cleared when it recovers). */
    val usageAccessNotified: Set<String> = emptySet(),
    /** deviceIds already alerted for mock-GPS fixes (cleared when the trail is clean again). */
    val mockLocationNotified: Set<String> = emptySet(),
    /** deviceIds already alerted for low battery (cleared when charged/plugged in — see HealthAlerts). */
    val lowBatteryNotified: Set<String> = emptySet(),
    /** deviceIds already alerted for network location off (cleared when it recovers). */
    val networkLocationNotified: Set<String> = emptySet(),
    /** deviceIds already alerted for a failed enforcement self-test (cleared when it passes). */
    val selfTestNotified: Set<String> = emptySet(),
    /** deviceIds already alerted for clock tampering (cleared once the skew is back to normal). */
    val clockTamperNotified: Set<String> = emptySet(),
    /**
     * Packages a child has told us it cannot turn into an icon (see [IconPayload.unavailable]),
     * each mapped to when it last said so. Left out of icon requests until the entry ages out
     * ([IconSync.suppressed]), so a drawable that will not render stops being asked for on every
     * publish — without the "not now" hardening into "not ever", which is what left one app
     * behind a monogram permanently while every other icon arrived.
     *
     * Replaces the old `iconsUnavailable` set, and deliberately under a new key: the old one is
     * dropped as an unknown key on first read, so every package a parent had blacklisted for
     * good gets asked for again on the next publish. That re-ask IS the migration.
     */
    val iconsUnrenderable: Map<String, Long> = emptyMap(),
    /**
     * A rule edit is written locally but not yet published (see [dev.walcott.data.PolicyPush]).
     *
     * PERSISTED, and that is the point rather than a detail: a child refuses a policy whose
     * version has not gone up (the replay gate), so a held edit lost to a process death would
     * never be adopted — the periodic re-emit would keep publishing it under the old version and
     * every child would keep rejecting it, for ever. The flag is what lets the next start-up
     * notice and push.
     */
    val pendingPolicyPush: Boolean = false,
    /**
     * When the edit still waiting was first made (0 = nothing waiting). The ceiling in
     * [dev.walcott.data.PolicyPush] is measured from here, so a sitting that goes on and on
     * cannot keep pushing its own earliest change further into the future.
     */
    val policyHoldStartedAtMs: Long = 0,
    /** The policy as last put on the wire, so the screens can say which settings are still local. */
    val deployedPolicyJson: String = "",
    /**
     * True when this family's rules no longer fit in one relay message even stripped of
     * everything else (see [ParentFit]). Every publish is then refused, so no child hears any
     * rule change again — a failure that must be said out loud rather than left to a debug log.
     */
    val policyTooLarge: Boolean = false,
    /** deviceId -> the parent version that child has confirmed, and when it did. */
    val policyConfirmedVersion: Map<String, Long> = emptyMap(),
    val policyConfirmedAtMs: Map<String, Long> = emptyMap(),
    /**
     * deviceIds already alerted for a web filter that the rules ask for but that isn't running
     * (cleared when the tunnel comes back, so a later lapse alerts again).
     */
    val webFilterNotified: Set<String> = emptySet(),
    /**
     * Child-side throttle for the self-repair nudges: fix key -> wall-clock ms of the last
     * notification. Lives here rather than in memory because the check runs from an alarm whose
     * process may not have survived since the previous one (see [dev.walcott.sync.ChildHealthCheck]).
     */
    val childFixNotifiedAt: Map<String, Long> = emptyMap(),
    /**
     * deviceId -> the last health report, as parents before [diagHistory] stored it. Kept only
     * so those reports survive the update: the first new report for a device migrates it into
     * the history and clears this. Never written any more.
     */
    val diagReports: Map<String, DiagPayload> = emptyMap(),
    /**
     * deviceId -> its recent health reports, newest first, capped at [MAX_DIAG_HISTORY]. A
     * report is a snapshot of one moment, not a live status, so they accumulate instead of
     * overwriting: "it was already failing on Tuesday" is the question a single report can't
     * answer. Live status comes from the child's check-in ([ChildSnapshot]).
     */
    val diagHistory: Map<String, List<StoredDiag>> = emptyMap(),
    /**
     * PARENT, device-local: deviceId -> the notification pages that device has sent, newest page
     * first (see [NotificationPayload]).
     *
     * Never in the policy and never in a backup: this is somebody's messages, it belongs to the
     * phone that asked for it, and a family restoring a backup on a new phone should start from a
     * blank page rather than inherit a log they cannot even ask that device to confirm.
     */
    val notificationPages: Map<String, List<NotificationPayload>> = emptyMap(),
    /**
     * PARENT, device-local: deviceId -> the unlock PIN this phone last set on that device.
     *
     * Held in the clear on purpose, and only here. Setting a PIN remotely is useless if the person
     * supporting cannot then read it back down the phone — "I have changed it, but I do not know to
     * what" is not help. Same reasoning, and the same boundaries, as the family PIN a parent can
     * reveal ([FamilyIdentity.pinPlain]): device-local, never on the wire, never in the backup.
     */
    val lastLockPin: Map<String, String> = emptyMap(),
    /**
     * CHILD, device-local: this device's lock-screen reset token, base64. Never travels — it is the
     * thing that would let anybody holding it change the lock (see [dev.walcott.enforcement.LockScreen]).
     */
    val lockTokenB64: String = "",
    /** CHILD: how many times this device has had to put its own ringer back. Cumulative. */
    val ringerRestores: Int = 0,
    /**
     * deviceId -> "requestId@checkpoints" of the emergency release already alerted about, so
     * each two-hourly notice raises exactly one alert and a re-started request alerts again.
     */
    val panicAlerted: Map<String, String> = emptyMap(),
    /**
     * Pending-operation ids the parent dismissed from the home (a delivered install the child
     * never finished). Bounded; the op itself expires with its 7-day TTL anyway.
     */
    val dismissedOpIds: List<String> = emptyList(),
    /**
     * deviceId -> wall-clock ms when the parent FIRST saw that device's install window open
     * (from [ChildSnapshot.installExemptionUntilMs]), and when it last reminded about it.
     * Cleared when the window closes; drives the hourly "installs are still allowed" nag
     * (see [InstallWindowReminder]).
     */
    val installWindowSeen: Map<String, Long> = emptyMap(),
    val installWindowRemindedAt: Map<String, Long> = emptyMap(),
    /**
     * deviceId -> wall-clock ms when the parent first saw that child reporting settings nobody
     * had granted ([ChildSnapshot.setupUnmet]), and when it last reminded about them. Cleared
     * the moment the device reports clean, so a device set up properly is never mentioned again
     * and a later relapse starts a fresh clock (see [SetupReminder]).
     */
    val setupPendingSince: Map<String, Long> = emptyMap(),
    val setupRemindedAt: Map<String, Long> = emptyMap(),
    /** When the parent last saved a family backup file (0 = never), for the backup card. */
    val lastBackupAtMs: Long = 0,
    /** When this device first ran as a parent (0 = not yet); anchors the backup reminders. */
    val parentSetupAtMs: Long = 0,
    /** Last policy edit on this parent — a backup older than this is stale (see BackupReminder). */
    val lastPolicyEditAtMs: Long = 0,
    /** Last backup reminder shown, so the escalation ladder doesn't repeat a step. */
    val lastBackupReminderAtMs: Long = 0,
    /**
     * KDF output for the on-device copies in shared storage ([LocalBackupStore]), derived from the
     * parent PIN the last time it was set or entered. Empty until then, which is what keeps the
     * nightly rewrite silent: no prompt, and the PIN itself is never stored.
     */
    val localBackupKeyB64: String = "",
    val localBackupSaltB64: String = "",
    /** Epoch day each rotation slot was last written on, keyed by [BackupRotation.Slot] name. */
    val localBackupDays: Map<String, Long> = emptyMap(),
    /**
     * The document each slot writes into, keyed by slot name. Remembered because a reinstalled app
     * cannot find the previous install's files again, and inserting over their MediaStore rows
     * fails outright — see [LocalBackupStore.write].
     */
    val localBackupUris: Map<String, String> = emptyMap(),
    /** True while the last nightly write failed, so the backup card can say so. */
    val localBackupError: Boolean = false,
    /** Domain batches arriving from children, complete or still missing slices (see [DomainInbox]). */
    val domainInbox: List<DomainInboxEntry> = emptyList(),
    /** Slice acknowledgements echoed to children so they stop resending; bounded, newest last. */
    val domainAcks: List<String> = emptyList(),
    /**
     * Batch ids the parent has already answered — applied or discarded. Slices keep arriving for
     * a short while after either (an ack takes a round trip), and without this a discarded
     * request would reappear on the home with the next nudge.
     */
    val domainsHandled: List<String> = emptyList(),
    /** Every app package ever seen across children, to notify only on genuinely new installs. */
    val seenAppPackages: Set<String> = emptySet(),
    /** True once [seenAppPackages] was seeded from existing data (prevents a first-run flood). */
    val seenAppsSeeded: Boolean = false,
    /** The activity feed, oldest first, capped at [EVENT_LOG_MAX] (see [ParentEvent]). */
    val events: List<ParentEvent> = emptyList(),
    /**
     * Per-child daily screen-time totals (childId, or deviceId for legacy devices ->
     * epochDay -> seconds), accumulated from snapshots. A snapshot only carries a 7-day
     * window, so this ledger is what makes longer averages possible on the parent.
     */
    val usageHistory: Map<String, Map<Long, Long>> = emptyMap(),
    /**
     * The same ledger keeping WHICH apps the days went to: child key -> day -> package ->
     * seconds (see [UsageLedger.mergeByApp]). Beside the totals rather than replacing them,
     * so the averages every parent already has on file keep working.
     */
    val usageByApp: Map<String, Map<Long, Map<String, Long>>> = emptyMap(),
    /**
     * What each child's filter and rules blocked, keyed the same way (see [BlockLedger]). Its
     * size does not depend on how long the family has been running Walcott: a bounded window of
     * days plus a bounded archive of everything older, which is what makes "all time" a number
     * this phone can keep answering in three years.
     */
    val blockLedgers: Map<String, BlockLedger.Ledger> = emptyMap(),
    // Both sides
    /**
     * ntfy `time` (unix seconds) of the newest message this device has processed. Used as the
     * `since=` cursor so WebSocket reconnects and background polls replay missed messages
     * instead of losing them.
     */
    val ntfySinceSec: Long = 0,
) {
    /** The feed with [event] appended, dropping whatever fell out of the retention window. */
    fun plusEvent(event: ParentEvent): SyncState = copy(events = pruneEvents(events + event, event.atMs))

    companion object {
        /** Feed cap: bounded so DataStore stays small however busy the family is. */
        const val EVENT_LOG_MAX = 120

        /**
         * How many applied ids a child remembers per kind.
         *
         * Idempotence only has to outlive the sender: the parent retires resolutions and bonuses
         * within days ([dev.walcott.sync.ParentFit]) and commands after a week, so an id that
         * falls off the end here can no longer arrive to be re-applied. What it buys is a set
         * that stops growing — on a phone enrolled for years the alternative was thousands of
         * UUIDs re-serialized into DataStore on every single check-in.
         */
        const val APPLIED_IDS_MAX = 200

        /**
         * [current] plus [fresh], newest last, capped at [APPLIED_IDS_MAX].
         *
         * Insertion order is the whole mechanism, so the set must stay a LinkedHashSet — which is
         * what `+` on a Set produces, and what kotlinx.serialization reads back.
         */
        fun rememberApplied(current: Set<String>, fresh: Collection<String>): Set<String> {
            val merged = current + fresh
            if (merged.size <= APPLIED_IDS_MAX) return merged
            return merged.toList().takeLast(APPLIED_IDS_MAX).toSet()
        }

        /**
         * How far back the feed goes. "Recent activity" that shows something from three weeks
         * ago isn't recent, and on a quiet family the count cap alone would never bite.
         */
        const val EVENT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

        /** Events worth keeping at [nowMs]: inside the window, and at most [EVENT_LOG_MAX]. */
        fun pruneEvents(events: List<ParentEvent>, nowMs: Long): List<ParentEvent> =
            events.filter { nowMs - it.atMs <= EVENT_RETENTION_MS }.takeLast(EVENT_LOG_MAX)
    }
}

/**
 * One family's sync bookkeeping. [familyId] picks the file; a parent holding several families
 * keeps their children, feeds and alert state strictly apart (see [dev.walcott.data.FamilyIds]).
 */
class SyncStore(context: Context, familyId: String = dev.walcott.data.FamilyIds.DEFAULT) {

    private val dataStore = dev.walcott.data.WalcottDataStores.get(
        context,
        dev.walcott.data.WalcottDataStores.fileName(FILE, familyId),
    )
    private val key = stringPreferencesKey("sync_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = SyncState.serializer()

    private fun decode(raw: String?): SyncState =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: SyncState()

    val state: Flow<SyncState> = dataStore.data.map { decode(it[key]) }

    suspend fun current(): SyncState = state.first()

    suspend fun update(transform: (SyncState) -> SyncState) {
        dataStore.edit { it[key] = json.encodeToString(serializer, transform(decode(it[key]))) }
    }

    private companion object {
        const val FILE = "walcott_sync"
    }
}
