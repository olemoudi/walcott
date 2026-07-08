package dev.walcott.sync

import android.content.Context
import android.os.Build
import dev.walcott.data.PolicySettings
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottRepository
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.location.LocationSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

/**
 * Orchestrates the family sync: pairing, publishing this device's snapshot, applying
 * incoming ones, and the remote extra-time flow. Enforcement stays fully local — this only
 * distributes rules and reports over the [SyncTransport].
 */
class SyncManager(
    private val context: Context,
    private val repository: WalcottRepository,
    private val settingsStore: SettingsStore,
    private val identityStore: IdentityStore,
    private val syncStore: SyncStore,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var transport: SyncTransport? = null

    val identity: StateFlow<FamilyIdentity> =
        identityStore.identity.stateIn(scope, SharingStarted.Eagerly, FamilyIdentity())

    /** Device mode for boot routing: null until the first real DataStore read lands. */
    val bootMode: StateFlow<DeviceMode?> =
        identityStore.identity.map { it.effectiveMode }.stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<SyncState> =
        syncStore.state.stateIn(scope, SharingStarted.Eagerly, SyncState())

    /** Requests from all children that the parent hasn't resolved yet. */
    val pendingRequests: StateFlow<List<PendingRequest>> = syncStore.state.map { s ->
        val resolved = s.resolutions.map { it.requestId }.toSet()
        s.children.flatMap { child -> child.requests.map { PendingRequest(child.displayName, it) } }
            .filter { it.request.requestId !in resolved }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    data class PendingRequest(val childName: String, val request: ExtraTimeRequest)

    /** Generic asks (apps, anything) from all children that the parent hasn't resolved yet. */
    val pendingAsks: StateFlow<List<PendingAsk>> = syncStore.state.map { s ->
        val resolved = s.resolutions.map { it.requestId }.toSet()
        s.children.flatMap { child -> child.asks.map { PendingAsk(child.displayName, it) } }
            .filter { it.ask.requestId !in resolved }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    data class PendingAsk(val childName: String, val ask: ChildRequest)

    /** Until when app installs are temporarily allowed on this device. */
    val installExemption: StateFlow<Long> =
        syncStore.state.map { it.installExemptionUntilMs }.stateIn(scope, SharingStarted.Eagerly, 0L)

    // --- Lifecycle ---

    fun start() {
        scope.launch {
            val id = identityStore.current()
            connect(id)
            if (id.isPaired) {
                publishSelf()
                periodicReEmit()
            }
        }
    }

    private fun connect(id: FamilyIdentity) {
        transport?.close()
        if (!id.isPaired) return
        transport = NtfyTransport(id.ntfyServer, id.topic).also { t ->
            t.connect { raw -> scope.launch { handleIncoming(raw, id) } }
        }
        // Parent republishes whenever the rules change.
        if (id.role == Role.PARENT) {
            scope.launch {
                settingsStore.settings.drop(1).collect { publishConfigChanged() }
            }
        }
    }

    private fun periodicReEmit() {
        scope.launch {
            while (true) {
                delay(RE_EMIT_MILLIS)
                publishSelf()
            }
        }
    }

    // --- Mode & pairing ---

    /** Persist the user-chosen device mode (mode select screen). */
    suspend fun setMode(mode: DeviceMode) {
        identityStore.save(identityStore.current().copy(mode = mode))
    }

    /** Toggle requiring the parent PIN/biometrics on app open (parent mode). */
    suspend fun setAppLock(enabled: Boolean) {
        identityStore.save(identityStore.current().copy(appLock = enabled))
    }

    suspend fun setAppLockBiometric(enabled: Boolean) {
        identityStore.save(identityStore.current().copy(appLockBiometric = enabled))
    }

    /** Unlink from the family and forget the mode choice; local policy and usage stay. */
    suspend fun resetDeviceMode() {
        transport?.close()
        transport = null
        identityStore.save(FamilyIdentity())
    }

    /** Make this device the parent of a new family: generate identity + Keystore key. */
    suspend fun becomeParent(familyName: String) {
        ParentKeystore.ensureKeyPair()
        val familyKey = FamilyCrypto.generateFamilyKey()
        val topic = "walcott-" + FamilyCrypto.toB64(UUID.randomUUID().toString().toByteArray()).take(24)
        val identity = FamilyIdentity(
            role = Role.PARENT,
            mode = DeviceMode.PARENT,
            deviceId = "parent",
            topic = topic,
            familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
            parentPublicKeyB64 = FamilyCrypto.toB64(ParentKeystore.publicKey().encoded),
        )
        identityStore.save(identity)
        settingsStore.update { it.copy(familyName = familyName) }
        connect(identity)
        publishSelf()
    }

    /** Pair this device as a child from a scanned per-child (or legacy) QR. Returns success. */
    suspend fun pairAsChild(pairingText: String): Boolean {
        val payload = PairingPayload.decode(pairingText) ?: return false
        val current = identityStore.current()
        val identity = FamilyIdentity(
            role = Role.CHILD,
            mode = DeviceMode.CHILD,
            // Keep the deviceId across re-pairs so the parent doesn't see ghost duplicates.
            deviceId = current.deviceId.ifBlank { UUID.randomUUID().toString() },
            displayName = payload.childName.ifBlank { Build.MODEL },
            childId = payload.childId,
            topic = payload.topic,
            familyKeyB64 = payload.familyKeyB64,
            parentPublicKeyB64 = payload.parentPublicKeyB64,
            ntfyServer = payload.ntfyServer,
        )
        identityStore.save(identity)
        // Show the family name right away; the first parent snapshot confirms it.
        if (payload.familyName.isNotBlank()) {
            settingsStore.update { it.copy(familyName = payload.familyName) }
        }
        connect(identity)
        publishSelf()
        return true
    }

    // --- Child actions ---

    /** Ask the parents for something (an app, anything). Lands in their pending list. */
    suspend fun askFor(kind: String, text: String) {
        syncStore.update { s ->
            s.copy(
                childVersion = s.childVersion + 1,
                pendingAsks = s.pendingAsks + ChildRequest(
                    requestId = UUID.randomUUID().toString(),
                    kind = kind,
                    text = text,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        publishSelf()
    }

    /** PIN-gated manual exemption: allow installs on this device for a while. */
    suspend fun allowInstallsTemporarily() {
        syncStore.update {
            it.copy(installExemptionUntilMs = System.currentTimeMillis() + DeviceRestrictions.INSTALL_EXEMPTION_MS)
        }
    }

    suspend fun requestExtraTime(categoryId: String, minutes: Int, reason: String) {
        syncStore.update { s ->
            s.copy(
                childVersion = s.childVersion + 1,
                pendingRequests = s.pendingRequests + ExtraTimeRequest(
                    requestId = UUID.randomUUID().toString(),
                    categoryId = categoryId,
                    minutes = minutes,
                    reason = reason,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        publishSelf()
    }

    // --- Parent actions ---

    suspend fun resolveRequest(requestId: String, approved: Boolean, grantedMinutes: Int) {
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                resolutions = s.resolutions.filterNot { it.requestId == requestId } + Resolution(
                    requestId = requestId,
                    approved = approved,
                    grantedMinutes = grantedMinutes,
                    resolvedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        publishSelf()
    }

    /** Parent asks a child device to report its current location on its next check-in. */
    suspend fun requestLocation(targetDeviceId: String) {
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                locationRequests = SyncEngine.withLocationRequest(
                    s.locationRequests, targetDeviceId, System.currentTimeMillis(),
                ),
            )
        }
        publishSelf()
    }

    /** Parent grants an unsolicited bonus (chores, good behaviour) to a child device. */
    suspend fun giveBonus(targetDeviceId: String, categoryId: String, minutes: Int) {
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                bonuses = s.bonuses + Bonus(
                    id = UUID.randomUUID().toString(),
                    targetDeviceId = targetDeviceId,
                    categoryId = categoryId,
                    minutes = minutes,
                    epochDay = LocalDate.now().toEpochDay(),
                ),
            )
        }
        publishSelf()
    }

    private suspend fun publishConfigChanged() {
        syncStore.update { it.copy(parentVersion = it.parentVersion + 1) }
        publishSelf()
    }

    /** Publish this child's snapshot now (used by the periodic location sampler). */
    suspend fun publishLocationUpdate() = publishSelf()

    // --- Publish / receive ---

    private suspend fun publishSelf() {
        val id = identityStore.current()
        val transport = transport ?: return
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        when (id.role) {
            Role.PARENT -> {
                val state = syncStore.current()
                // The PIN hash/salt travel with the policy so the parent's PIN also guards
                // enrolled child devices (gate + leaving child mode).
                val settings = settingsStore.current()
                val snapshot = ParentSnapshot(
                    version = state.parentVersion,
                    policyJson = json.encodeToString(PolicySettings.serializer(), settings),
                    resolutions = state.resolutions,
                    bonuses = state.bonuses,
                    locationRequests = state.locationRequests,
                )
                transport.publish(SyncProtocol.encodeParent(snapshot, familyKey, ParentKeystore.privateKey()))
            }
            Role.CHILD -> {
                val s = syncStore.current()
                val today = LocalDate.now().toEpochDay()
                val history = repository.weeklyUsage().map { (day, usage) ->
                    DayUsage(day, usage.map { UsageEntry(it.key, it.value.seconds) })
                }
                // PackageManager enumeration is blocking; keep it off the caller's thread.
                val apps = withContext(Dispatchers.IO) {
                    repository.inventory.launchableApps()
                        .filterNot { it.isSystem }
                        .map { InstalledAppInfo(it.packageName, it.label) }
                }
                val snapshot = ChildSnapshot(
                    deviceId = id.deviceId,
                    displayName = id.displayName,
                    childId = id.childId,
                    version = s.childVersion,
                    epochDay = today,
                    usage = repository.usageNow().map { UsageEntry(it.key, it.value.seconds) },
                    extra = repository.extraNow().map { UsageEntry(it.key, it.value.seconds) },
                    requests = s.pendingRequests,
                    history = history,
                    asks = s.pendingAsks,
                    apps = apps,
                    locations = repository.recentLocations(),
                )
                transport.publish(SyncProtocol.encodeChild(snapshot, familyKey))
            }
            Role.UNPAIRED -> Unit
        }
    }

    private suspend fun handleIncoming(raw: String, id: FamilyIdentity) {
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        val parentPublic = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(id.parentPublicKeyB64))
        val message = SyncProtocol.decode(raw, familyKey, parentPublic) ?: return

        when {
            id.role == Role.CHILD && message is IncomingMessage.FromParent -> applyParentSnapshot(message.snapshot)
            id.role == Role.PARENT && message is IncomingMessage.FromChild -> applyChildSnapshot(message.snapshot)
        }
    }

    private suspend fun applyParentSnapshot(snapshot: ParentSnapshot) {
        val id = identityStore.current()
        // Adopt the parent's rules, flattened to this child's slice. Prefer the parent's
        // PIN; keep the local one while none has synced yet (old parent, or first snapshot
        // not arrived — until then a locally created PIN still guards the gate).
        val incoming = runCatching { json.decodeFromString(PolicySettings.serializer(), snapshot.policyJson) }.getOrNull()
        if (incoming != null) {
            settingsStore.update { local ->
                incoming.resolveForChild(id.childId).copy(
                    pinHash = incoming.pinHash ?: local.pinHash,
                    pinSalt = incoming.pinSalt ?: local.pinSalt,
                )
            }
        }

        val deviceId = id.deviceId
        val s = syncStore.current()

        // On-demand: answer a fresh "locate now" addressed to this device (one attempt each).
        val locReq = SyncEngine.freshLocationRequest(snapshot, deviceId, s.appliedLocationRequestMs)
        if (locReq != null) {
            LocationSampler(context).currentFix()?.let { repository.recordLocation(it) }
            syncStore.update { it.copy(appliedLocationRequestMs = locReq.requestedAtMs) }
            publishSelf()
        }

        // Apply resolutions to our pending requests and asks, idempotently.
        val asksById = s.pendingAsks.associateBy { it.requestId }
        val pendingIds = s.pendingRequests.map { it.requestId }.toSet() + asksById.keys
        val freshResolutions = SyncEngine.newResolutions(snapshot, pendingIds, s.appliedResolutionIds)
        var approvedAppAsk = false
        for (resolution in freshResolutions) {
            if (!resolution.approved) continue
            if (resolution.grantedMinutes > 0) {
                val req = s.pendingRequests.firstOrNull { it.requestId == resolution.requestId }
                if (req != null) repository.grantExtraMinutes(req.categoryId, resolution.grantedMinutes.toLong())
            }
            // An approved app ask opens the timed install window on this device.
            if (asksById[resolution.requestId]?.kind == ChildRequest.KIND_APP) approvedAppAsk = true
        }

        // Apply bonuses addressed to this device, idempotently.
        val freshBonuses = SyncEngine.newBonuses(snapshot, deviceId, s.appliedBonusIds)
        for (bonus in freshBonuses) {
            if (bonus.minutes > 0) repository.grantExtraMinutes(bonus.categoryId, bonus.minutes.toLong())
        }

        if (freshResolutions.isEmpty() && freshBonuses.isEmpty()) return
        val resolvedIds = freshResolutions.map { it.requestId }.toSet()
        val bonusIds = freshBonuses.map { it.id }.toSet()
        syncStore.update {
            it.copy(
                pendingRequests = it.pendingRequests.filterNot { r -> r.requestId in resolvedIds },
                pendingAsks = it.pendingAsks.filterNot { a -> a.requestId in resolvedIds },
                appliedResolutionIds = it.appliedResolutionIds + resolvedIds,
                appliedBonusIds = it.appliedBonusIds + bonusIds,
                installExemptionUntilMs = if (approvedAppAsk) {
                    System.currentTimeMillis() + DeviceRestrictions.INSTALL_EXEMPTION_MS
                } else {
                    it.installExemptionUntilMs
                },
            )
        }
    }

    private suspend fun applyChildSnapshot(snapshot: ChildSnapshot) {
        val before = syncStore.current()
        val prevRequestIds = before.children.flatMap { it.requests }.map { it.requestId }.toSet()
        val merged = SyncEngine.mergeChild(before.children.associateBy { it.deviceId }, snapshot).values.toList()
        syncStore.update {
            it.copy(
                children = merged,
                lastSeen = it.lastSeen + (snapshot.deviceId to System.currentTimeMillis()),
            )
        }

        val resolved = before.resolutions.map { it.requestId }.toSet()
        val newlyPending = snapshot.requests.map { it.requestId }.toSet() - prevRequestIds - resolved
        if (newlyPending.isNotEmpty()) {
            val req = snapshot.requests.first { it.requestId in newlyPending }
            SyncNotifications.notifyRequest(context, snapshot.displayName, req.minutes)
        }
    }

    companion object {
        // Re-emits only heal lost messages: real changes (settings edits, requests,
        // resolutions) publish immediately, so a long interval costs little freshness
        // and saves a lot of radio/battery.
        private const val RE_EMIT_MILLIS = 15 * 60 * 1000L
    }
}
