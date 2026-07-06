package dev.walcott.sync

import android.content.Context
import android.os.Build
import dev.walcott.data.PolicySettings
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    val state: StateFlow<SyncState> =
        syncStore.state.stateIn(scope, SharingStarted.Eagerly, SyncState())

    /** Requests from all children that the parent hasn't resolved yet. */
    val pendingRequests: StateFlow<List<PendingRequest>> = syncStore.state.map { s ->
        val resolved = s.resolutions.map { it.requestId }.toSet()
        s.children.flatMap { child -> child.requests.map { PendingRequest(child.displayName, it) } }
            .filter { it.request.requestId !in resolved }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    data class PendingRequest(val childName: String, val request: ExtraTimeRequest)

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

    // --- Pairing ---

    /** Make this device the parent: generate identity + Keystore key. Returns the QR text. */
    suspend fun becomeParent(displayName: String): String {
        ParentKeystore.ensureKeyPair()
        val familyKey = FamilyCrypto.generateFamilyKey()
        val topic = "walcott-" + FamilyCrypto.toB64(UUID.randomUUID().toString().toByteArray()).take(24)
        val identity = FamilyIdentity(
            role = Role.PARENT,
            deviceId = "parent",
            displayName = displayName,
            topic = topic,
            familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
            parentPublicKeyB64 = FamilyCrypto.toB64(ParentKeystore.publicKey().encoded),
        )
        identityStore.save(identity)
        connect(identity)
        publishSelf()
        return PairingPayload(topic, identity.familyKeyB64, identity.parentPublicKeyB64, identity.ntfyServer).encode()
    }

    /** Pair this device as a child from the parent's scanned QR text. Returns success. */
    suspend fun pairAsChild(pairingText: String, displayName: String): Boolean {
        val payload = PairingPayload.decode(pairingText) ?: return false
        val identity = FamilyIdentity(
            role = Role.CHILD,
            deviceId = UUID.randomUUID().toString(),
            displayName = displayName.ifBlank { Build.MODEL },
            topic = payload.topic,
            familyKeyB64 = payload.familyKeyB64,
            parentPublicKeyB64 = payload.parentPublicKeyB64,
            ntfyServer = payload.ntfyServer,
        )
        identityStore.save(identity)
        connect(identity)
        publishSelf()
        return true
    }

    // --- Child actions ---

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

    private suspend fun publishConfigChanged() {
        syncStore.update { it.copy(parentVersion = it.parentVersion + 1) }
        publishSelf()
    }

    // --- Publish / receive ---

    private suspend fun publishSelf() {
        val id = identityStore.current()
        val transport = transport ?: return
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        when (id.role) {
            Role.PARENT -> {
                val settings = settingsStore.current().copy(pinHash = null, pinSalt = null)
                val snapshot = ParentSnapshot(
                    version = syncStore.current().parentVersion,
                    policyJson = json.encodeToString(PolicySettings.serializer(), settings),
                    resolutions = syncStore.current().resolutions,
                )
                transport.publish(SyncProtocol.encodeParent(snapshot, familyKey, ParentKeystore.privateKey()))
            }
            Role.CHILD -> {
                val s = syncStore.current()
                val today = LocalDate.now().toEpochDay()
                val snapshot = ChildSnapshot(
                    deviceId = id.deviceId,
                    displayName = id.displayName,
                    version = s.childVersion,
                    epochDay = today,
                    usage = repository.usageNow().map { UsageEntry(it.key, it.value.seconds) },
                    extra = repository.extraNow().map { UsageEntry(it.key, it.value.seconds) },
                    requests = s.pendingRequests,
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
        // Adopt the parent's rules (keep our own local PIN).
        val incoming = runCatching { json.decodeFromString(PolicySettings.serializer(), snapshot.policyJson) }.getOrNull()
        if (incoming != null) {
            settingsStore.update { local -> incoming.copy(pinHash = local.pinHash, pinSalt = local.pinSalt) }
        }
        // Apply resolutions to our pending requests, idempotently.
        val s = syncStore.current()
        val pendingIds = s.pendingRequests.map { it.requestId }.toSet()
        val fresh = SyncEngine.newResolutions(snapshot, pendingIds, s.appliedResolutionIds)
        if (fresh.isEmpty()) return
        for (resolution in fresh) {
            if (resolution.approved && resolution.grantedMinutes > 0) {
                val req = s.pendingRequests.firstOrNull { it.requestId == resolution.requestId } ?: continue
                repository.grantExtraMinutes(req.categoryId, resolution.grantedMinutes.toLong())
            }
        }
        val resolvedIds = fresh.map { it.requestId }.toSet()
        syncStore.update {
            it.copy(
                pendingRequests = it.pendingRequests.filterNot { r -> r.requestId in resolvedIds },
                appliedResolutionIds = it.appliedResolutionIds + resolvedIds,
            )
        }
    }

    private suspend fun applyChildSnapshot(snapshot: ChildSnapshot) {
        val before = syncStore.current()
        val prevRequestIds = before.children.flatMap { it.requests }.map { it.requestId }.toSet()
        val merged = SyncEngine.mergeChild(before.children.associateBy { it.deviceId }, snapshot).values.toList()
        syncStore.update { it.copy(children = merged) }

        val resolved = before.resolutions.map { it.requestId }.toSet()
        val newlyPending = snapshot.requests.map { it.requestId }.toSet() - prevRequestIds - resolved
        if (newlyPending.isNotEmpty()) {
            val req = snapshot.requests.first { it.requestId in newlyPending }
            SyncNotifications.notifyRequest(context, snapshot.displayName, req.minutes)
        }
    }

    companion object {
        private const val RE_EMIT_MILLIS = 5 * 60 * 1000L
    }
}
