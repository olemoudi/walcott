package dev.walcott.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        const val TYPE_INDOOR_LOCATION_OFF = "indoor_location_off"
        const val TYPE_NEW_APP = "new_app"
        const val TYPE_WRONG_PIN = "wrong_pin"
        const val TYPE_STALE = "stale"
        const val TYPE_NEVER_REPORTED = "never_reported"
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

        /** A child sent a selection of domains to block; [detail] is the app, [count] how many. */
        const val TYPE_DOMAINS = "domains"

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
    /** Ids of remote commands this device already ran, so a replayed snapshot can't re-run them. */
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
     * deviceId -> "requestId@checkpoints" of the emergency release already alerted about, so
     * each two-hourly notice raises exactly one alert and a re-started request alerts again.
     */
    val panicAlerted: Map<String, String> = emptyMap(),
    /**
     * deviceId -> wall-clock ms when the parent FIRST saw that device's install window open
     * (from [ChildSnapshot.installExemptionUntilMs]), and when it last reminded about it.
     * Cleared when the window closes; drives the hourly "installs are still allowed" nag
     * (see [InstallWindowReminder]).
     */
    val installWindowSeen: Map<String, Long> = emptyMap(),
    val installWindowRemindedAt: Map<String, Long> = emptyMap(),
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
         * How far back the feed goes. "Recent activity" that shows something from three weeks
         * ago isn't recent, and on a quiet family the count cap alone would never bite.
         */
        const val EVENT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

        /** Events worth keeping at [nowMs]: inside the window, and at most [EVENT_LOG_MAX]. */
        fun pruneEvents(events: List<ParentEvent>, nowMs: Long): List<ParentEvent> =
            events.filter { nowMs - it.atMs <= EVENT_RETENTION_MS }.takeLast(EVENT_LOG_MAX)
    }
}

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "walcott_sync")

class SyncStore(private val context: Context) {

    private val key = stringPreferencesKey("sync_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = SyncState.serializer()

    private fun decode(raw: String?): SyncState =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: SyncState()

    val state: Flow<SyncState> = context.syncDataStore.data.map { decode(it[key]) }

    suspend fun current(): SyncState = state.first()

    suspend fun update(transform: (SyncState) -> SyncState) {
        context.syncDataStore.edit { it[key] = json.encodeToString(serializer, transform(decode(it[key]))) }
    }
}
