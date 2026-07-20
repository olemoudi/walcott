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
    /** deviceId -> latest health report received from that child (see [DiagPayload]). */
    val diagReports: Map<String, DiagPayload> = emptyMap(),
    /** When the parent last saved a family backup file (0 = never), for the backup card. */
    val lastBackupAtMs: Long = 0,
    /** SAF document the backup auto-refreshes into ("" = auto-backup off). */
    val autoBackupUri: String = "",
    /** KDF output + parameters cached so auto-refresh can re-seal WITHOUT storing the passphrase. */
    val autoBackupKeyB64: String = "",
    val autoBackupSaltB64: String = "",
    val autoBackupIterations: Int = 0,
    /** True while the last auto-refresh failed (file deleted, permission revoked…). */
    val autoBackupError: Boolean = false,
    /** Every app package ever seen across children, to notify only on genuinely new installs. */
    val seenAppPackages: Set<String> = emptySet(),
    /** True once [seenAppPackages] was seeded from existing data (prevents a first-run flood). */
    val seenAppsSeeded: Boolean = false,
    // Both sides
    /**
     * ntfy `time` (unix seconds) of the newest message this device has processed. Used as the
     * `since=` cursor so WebSocket reconnects and background polls replay missed messages
     * instead of losing them.
     */
    val ntfySinceSec: Long = 0,
)

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
