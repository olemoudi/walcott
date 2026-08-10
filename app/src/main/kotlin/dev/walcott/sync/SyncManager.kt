package dev.walcott.sync

import android.content.Context
import android.os.Build
import dev.walcott.data.PinLockout
import dev.walcott.data.PinResult
import dev.walcott.data.PolicySettings
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottRepository
import dev.walcott.data.withChildDomainRules
import dev.walcott.data.withFamilyDomainRules
import dev.walcott.BuildConfig
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.enforcement.EnforcementBackends
import dev.walcott.enforcement.UsageAccess
import dev.walcott.location.LocationSampler
import dev.walcott.rules.EarnGrant
import dev.walcott.rules.IdleEarnConfig
import dev.walcott.rules.IdleEarnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val iconStore: IconStore = IconStore(context),
    /**
     * Which family this manager speaks for. Only ever visible where two families would otherwise
     * collide outside their own stores — the on-device backup files, so far. The wire has no
     * notion of it: a family IS its topic and key (see [dev.walcott.data.FamilyIds]).
     */
    private val familyId: String = dev.walcott.data.FamilyIds.DEFAULT,
    /**
     * This family's name when alerts need to say which family they are about (a parent holding
     * more than one), null otherwise. A lambda because both halves of that answer — the family
     * count and the name — change while the app runs.
     */
    private val familyLabel: suspend () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var transport: SyncTransport? = null
    private var reEmitJob: Job? = null
    /** Parent-only republish-on-edit collector; tracked so reconnects don't stack duplicates. */
    private var settingsWatchJob: Job? = null
    /** In-memory mirror of [SyncState.ntfySinceSec] so the transport's sinceProvider never blocks. */
    @Volatile private var sinceCache: Long = 0
    /** When the live socket was opened, so a socket that never delivered anything can still be
     *  judged stale (see [reconnectIfChannelStale]). */
    @Volatile private var connectedAtMs: Long = 0
    /** Wall clock of the last successful publish, so heartbeats can skip redundant ones. */
    @Volatile private var lastPublishAtMs: Long = 0
    /**
     * The publish whose echo would prove something about this device's clock: its nonce, and
     * what the local clock read when it went out. Replaced by the next publish and cleared once
     * measured, so only the newest publish is ever paired (see [ClockGuard.skewFromOwnEcho]).
     */
    @Volatile private var awaitedEcho: Pair<Long, Long>? = null
    /** Serializes remote-command execution across concurrently handled parent snapshots. */
    private val commandMutex = Mutex()
    /**
     * Serializes [applyChildSnapshot] across concurrently handled child snapshots. Its event
     * gates ("this ack completes a queued command", "this request is new") are check-then-act
     * against a state read before the store update: when an ntfy reconnect replays a backlog,
     * the same snapshot content is in flight several times at once, and without the lock each
     * copy passes the gate and appends a duplicate feed entry.
     */
    private val childSnapshotMutex = Mutex()
    /** Same, for the emergency-release checkpoints (see [evaluatePanic]). */
    private val panicMutex = Mutex()
    /** In-flight debounced auto-backup rewrite; replaced on every new trigger. */
    /** Child-only resend loop for a domain batch in flight (see [nudgeDomainBatch]). */
    private var domainNudgeJob: Job? = null

    val identity: StateFlow<FamilyIdentity> =
        identityStore.identity.stateIn(scope, SharingStarted.Eagerly, FamilyIdentity())

    /** Device mode for boot routing: null until the first real DataStore read lands. */
    val bootMode: StateFlow<DeviceMode?> =
        identityStore.identity.map { it.effectiveMode }.stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<SyncState> =
        syncStore.state.stateIn(scope, SharingStarted.Eagerly, SyncState())

    /**
     * Requests from all children that the parent hasn't resolved yet.
     *
     * Expired ones drop out here too, not only on the child that sent them: an older child
     * build keeps re-sending a request forever, and a question from last week has no business
     * sitting above today's on the parent's home. Judged when the store changes rather than on
     * a timer — a child publishes at least every half hour, so the list is never stale for long.
     */
    val pendingRequests: StateFlow<List<PendingRequest>> = syncStore.state.map { s ->
        val now = System.currentTimeMillis()
        val resolved = s.resolutions.map { it.requestId }.toSet()
        s.children.flatMap { child ->
            child.requests.map { request ->
                PendingRequest(
                    childName = child.displayName,
                    request = request,
                    childId = child.childId,
                    usage = child.usage,
                    epochDay = child.epochDay,
                    tzOffsetMinutes = child.tzOffsetMinutes,
                )
            }
        }.filter {
            it.request.requestId !in resolved &&
                !SyncEngine.requestExpired(it.request.createdAtEpochMs, now)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * A request, plus what the asking child had spent when they sent it. Carried here rather
     * than looked up at the card: the parent's screens hold requests and snapshots in separate
     * lists keyed differently, and the answer to "should I say yes" is the usage, so it travels
     * with the question (see [dev.walcott.data.ChildStats.usedTodayOn]).
     */
    data class PendingRequest(
        val childName: String,
        val request: ExtraTimeRequest,
        val childId: String = "",
        val usage: List<UsageEntry> = emptyList(),
        val epochDay: Long = 0,
        val tzOffsetMinutes: Int? = null,
    )

    /** Generic asks (apps, anything) from all children that the parent hasn't resolved yet. */
    val pendingAsks: StateFlow<List<PendingAsk>> = syncStore.state.map { s ->
        val now = System.currentTimeMillis()
        val resolved = s.resolutions.map { it.requestId }.toSet()
        s.children.flatMap { child -> child.asks.map { PendingAsk(child.displayName, it) } }
            .filter {
                it.ask.requestId !in resolved && !SyncEngine.requestExpired(it.ask.createdAtEpochMs, now)
            }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    data class PendingAsk(val childName: String, val ask: ChildRequest)

    /**
     * Domain selections from children that have arrived whole and the parent hasn't answered.
     * Incomplete batches are held back on purpose: acting on the slices that made it through
     * would block a list nobody chose (see [DomainInbox]).
     */
    val pendingDomainBatches: StateFlow<List<DomainInboxEntry>> = syncStore.state.map { s ->
        s.domainInbox.filter { it.complete && it.batchId !in s.domainsHandled }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Until when app installs are temporarily allowed on this device. */
    val installExemption: StateFlow<Long> =
        syncStore.state.map { it.installExemptionUntilMs }.stateIn(scope, SharingStarted.Eagerly, 0L)

    // --- Child-side visibility of its own request lifecycle ---

    /** This device's own unanswered time requests (child home "waiting" section). */
    val myPendingRequests: StateFlow<List<ExtraTimeRequest>> =
        syncStore.state.map { it.pendingRequests }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** This device's own unanswered asks (apps, anything). */
    val myPendingAsks: StateFlow<List<ChildRequest>> =
        syncStore.state.map { it.pendingAsks }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The domain selection this device last sent, and how far it got (see [sendDomains]). */
    val domainDelivery: StateFlow<DomainBatch?> =
        syncStore.state.map { it.domainBatch }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The parents' latest answer (approval/denial/bonus), until the child dismisses it. */
    val notice: StateFlow<NoticeEntry?> =
        syncStore.state.map { it.lastNotice }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun dismissNotice() {
        syncStore.update { it.copy(lastNotice = null) }
    }

    // --- Lifecycle ---

    fun start() {
        scope.launch {
            val id = identityStore.current()
            connect(id)
            if (id.isPaired) publishSelf()
        }
    }

    private suspend fun connect(id: FamilyIdentity) {
        transport?.close()
        if (!id.isPaired) return
        sinceCache = maxOf(sinceCache, syncStore.current().ntfySinceSec)
        connectedAtMs = System.currentTimeMillis()
        transport = NtfyTransport(
            id.ntfyServer,
            id.topic,
            // A short keepalive while someone is watching, a long one the rest of the day
            // (see Http.activeWebSocketClient). The interval is fixed when the client is
            // built, so this is read at connect time and a change means a reconnect.
            client = if (interactive) dev.walcott.net.Http.activeWebSocketClient
            else dev.walcott.net.Http.webSocketClient,
            sinceProvider = { sinceCache },
        ).also { t ->
            t.connect { raw, timeSec ->
                scope.launch {
                    // Advance the cursor even if handling throws: a single message that always
                    // fails to process must not wedge the `since=` replay and re-deliver itself
                    // forever. Losing its content is fine — the sender re-emits every cycle.
                    runCatching { handleIncoming(raw, id, timeSec) }
                        .onFailure { dev.walcott.debug.DebugLog.e(TAG, "handleIncoming failed", it) }
                    advanceCursor(timeSec)
                }
            }
        }
        // Parent republishes whenever the rules change. Cancel any previous collector
        // first: connect() runs again on re-pairing, and a leaked collector would double
        // every publish for the rest of the process's life.
        settingsWatchJob?.cancel()
        settingsWatchJob = if (id.role == Role.PARENT) {
            scope.launch {
                // Guard each emission: a transient failure while republishing a rule edit must
                // not tear down the collector and leave the parent silently no longer syncing
                // its edits for the rest of the process.
                settingsStore.settings.drop(1).collect {
                    runCatching { publishConfigChanged() }
                        .onFailure { dev.walcott.debug.DebugLog.e(TAG, "publish on settings change failed", it) }
                    // What the reminder ladder measures a saved backup against: a file older
                    // than the last edit is stale and worth nagging about.
                    syncStore.update { s -> s.copy(lastPolicyEditAtMs = System.currentTimeMillis()) }
                }
            }
        } else {
            null
        }
        // Re-emit heals lost messages from the moment a device is paired — including devices
        // paired during this process's lifetime (pairing used to publish exactly once).
        periodicReEmit()
    }

    /** Whether someone is currently looking at the app; picks the keepalive (see [connect]). */
    @Volatile private var interactive = false

    /** The mode last asked for, which a deferred switch applies once the socket has settled. */
    @Volatile private var desiredInteractive = false
    private var modeSwitchJob: kotlinx.coroutines.Job? = null

    /**
     * Follows whether the app is in the foreground, so the socket's keepalive matches what is
     * being asked of it.
     *
     * The rebuild is the point as much as the interval is: it replaces a socket that may have
     * died silently at exactly the moment a person starts waiting on it, and the new one replays
     * from the `since=` cursor, so nothing is lost.
     *
     * A switch on a socket younger than [MODE_SWITCH_MIN_SOCKET_AGE_MS] is DEFERRED rather than
     * dropped, and that matters in both directions. Dropping it would leave the app on the long
     * keepalive for a whole session whenever the socket happened to be built moments before the
     * screen appeared — the ordinary case at start-up — and, worse, leave it on the SHORT one for
     * ever if the drop happened on the way to the background. Deferring converges either way, and
     * cancelling any pending switch means someone flicking between apps pays for one reconnect at
     * the end rather than one per flick.
     */
    suspend fun setInteractive(nowInteractive: Boolean) {
        desiredInteractive = nowInteractive
        modeSwitchJob?.cancel()
        val id = identityStore.current()
        // Nothing to rebuild yet: record it, and the next connect() picks the right client.
        if (!id.isPaired || transport == null) {
            interactive = nowInteractive
            return
        }
        if (nowInteractive == interactive) return
        val settledFor = System.currentTimeMillis() - connectedAtMs
        if (settledFor >= MODE_SWITCH_MIN_SOCKET_AGE_MS) {
            applyKeepalive(id, nowInteractive)
        } else {
            modeSwitchJob = scope.launch {
                delay(MODE_SWITCH_MIN_SOCKET_AGE_MS - settledFor)
                val want = desiredInteractive
                if (want != interactive) {
                    runCatching { applyKeepalive(identityStore.current(), want) }
                        .onFailure { dev.walcott.debug.DebugLog.w(TAG, "deferred keepalive switch failed", it) }
                }
            }
        }
    }

    private suspend fun applyKeepalive(id: FamilyIdentity, nowInteractive: Boolean) {
        interactive = nowInteractive
        dev.walcott.debug.DebugLog.i(TAG, "keepalive now ${if (nowInteractive) "active" else "idle"}")
        connect(id)
    }

    /**
     * Rebuilds the socket when nothing has arrived over it for too long (see
     * [ChannelHealth.needsReconnect]). Called from the heartbeat, the one wakeup Doze always
     * honours.
     *
     * The inbound socket is the only way a child hears anything — new rules, granted time, every
     * remote command, the refusal of an emergency release — and publishing is a separate HTTP
     * call that keeps working regardless, so a dead socket looks like a perfectly healthy child
     * from the parent's side. Ping frames make most deaths visible to OkHttp itself; this is the
     * backstop for the ones that aren't, and it costs one comparison per half-hour.
     */
    suspend fun reconnectIfChannelStale() {
        val id = identityStore.current()
        if (!id.isPaired || transport == null) return
        val lastProof = maxOf(syncStore.current().lastChannelOkMs, connectedAtMs)
        if (!ChannelHealth.needsReconnect(lastProof, System.currentTimeMillis())) return
        dev.walcott.debug.DebugLog.w(TAG, "no message for ${ChannelHealth.RECONNECT_AFTER_MS / 60_000} min; reconnecting")
        connect(id)
        // The new socket replays from the cursor; this says "we are here" to anyone who missed us.
        runCatching { publishSelf() }
    }

    private fun periodicReEmit() {
        if (reEmitJob?.isActive == true) return
        reEmitJob = scope.launch {
            while (true) {
                delay(RE_EMIT_MILLIS)
                publishSelf()
            }
        }
    }

    /** Applies one raw transport message and advances the replay cursor. Poll-worker entry point. */
    suspend fun applyIncoming(raw: String, timeSec: Long = 0) {
        val id = identityStore.current()
        // Guarded like the live path: a message that can't be processed still advances the
        // cursor, so the background poll can't get stuck re-fetching the same poison message.
        if (id.isPaired) {
            runCatching { handleIncoming(raw, id, timeSec) }
                .onFailure { dev.walcott.debug.DebugLog.e(TAG, "applyIncoming failed", it) }
        }
        advanceCursor(timeSec)
    }

    /**
     * Moves the `since=` cursor forward. Advances on EVERY message — including our own echoes
     * and undecodable ones — or reconnects would replay them forever. A received message is
     * also end-to-end proof the channel works right now, so the channel-health stamp rides
     * on the same write.
     */
    private suspend fun advanceCursor(timeSec: Long) {
        if (timeSec <= sinceCache) return
        sinceCache = timeSec
        val now = System.currentTimeMillis()
        syncStore.update {
            val next = if (timeSec > it.ntfySinceSec) it.copy(ntfySinceSec = timeSec) else it
            next.copy(lastChannelOkMs = now)
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

    /** Toggle the parent's backup-nudge notifications (see BackupReminder). */
    suspend fun setBackupReminders(enabled: Boolean) {
        identityStore.save(identityStore.current().copy(backupReminders = enabled))
    }

    /** Unlink from the family and forget the mode choice; local policy and usage stay. */
    suspend fun resetDeviceMode() = unlink(FamilyIdentity())

    /**
     * This parent stops managing this family for good (see [dev.walcott.FamilyHub.removeFamily]):
     * the transport goes down and every trace of the family is erased from this device.
     *
     * The identity that stays behind keeps [DeviceMode.PARENT]. It is not cosmetic: an UNSET
     * identity enforces by default (see [FamilyIdentity.enforcesLocally]), so a blank one would
     * have the boot receiver start blocking apps on the parent's own phone.
     */
    suspend fun forgetFamily() {
        val before = identityStore.current()
        unlink(
            FamilyIdentity(
                mode = DeviceMode.PARENT,
                // Device-level preferences, not family ones: they happen to be stored per family
                // (every family needs its own copy), so dropping them here would silently turn
                // the app lock off on a parent who removed the family it was recorded in.
                appLock = before.appLock,
                appLockBiometric = before.appLockBiometric,
                backupReminders = before.backupReminders,
            ),
        )
        settingsStore.update { PolicySettings() }
        syncStore.update { SyncState() }
    }

    /**
     * Emergency release ([dev.walcott.enforcement.PanicRelease]): unlink AND remember that this
     * device must not enforce again, since the wiped identity alone would look like a fresh
     * install and start enforcing an empty policy.
     */
    suspend fun markReleased() = unlink(FamilyIdentity(released = true))

    /**
     * Forgets everything the sync layer recorded (emergency release): requests, notices,
     * the activity feed, per-child history. Separate from [markReleased] so the identity is
     * dropped — and the transport closed — before the record is erased.
     */
    suspend fun wipeSyncState() {
        syncStore.update { SyncState() }
    }

    private suspend fun unlink(identity: FamilyIdentity) {
        transport?.close()
        transport = null
        reEmitJob?.cancel()
        reEmitJob = null
        settingsWatchJob?.cancel()
        settingsWatchJob = null
        identityStore.save(identity)
    }

    /**
     * Make this device the parent of a new family. The signing key is generated in software
     * (not the Keystore) so the family backup can export it: it sits beside the family key,
     * which was always in the DataStore, so the at-rest exposure doesn't change class —
     * though unlike the Keystore the key becomes exportable under root/forensic access,
     * the accepted cost of restorability. Pre-v0.11 families keep their Keystore key
     * (see [signingKey]).
     */
    suspend fun becomeParent(familyName: String) {
        val signingPair = FamilyCrypto.generateSigningKeyPair()
        val familyKey = FamilyCrypto.generateFamilyKey()
        val topic = "walcott-" + FamilyCrypto.toB64(UUID.randomUUID().toString().toByteArray()).take(24)
        val identity = FamilyIdentity(
            role = Role.PARENT,
            mode = DeviceMode.PARENT,
            deviceId = "parent",
            topic = topic,
            familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
            parentPublicKeyB64 = FamilyCrypto.toB64(signingPair.public.encoded),
            parentPrivateKeyB64 = FamilyCrypto.toB64(signingPair.private.encoded),
        )
        identityStore.save(identity)
        // Anchors the "you still have no backup" reminder ladder (see BackupReminder).
        syncStore.update { it.copy(parentSetupAtMs = System.currentTimeMillis()) }
        settingsStore.update { it.copy(familyName = familyName) }
        repository.seedHardeningIfNeeded()
        // The other half of dropping the ordering dependency: if the PIN was set first, the key
        // is already here and the copies should exist from the moment the family does, not from
        // whenever the nightly worker next happens to run.
        runCatching { writeDueLocalBackups(java.time.LocalDate.now()) }
            .onFailure { dev.walcott.debug.DebugLog.w(TAG, "first local backup failed", it) }
        connect(identity)
        publishSelf()
    }

    /** Outcome of trying to move a family to a different relay (see [setRelayServer]). */
    enum class RelayChangeResult { OK, INVALID, HAS_CHILDREN }

    /**
     * Moves this family to a different relay.
     *
     * Refused the moment the family has enrolled a child, and that limit is deliberate rather
     * than laziness: a child learns the relay from its pairing QR and from nowhere else, so
     * switching it under a paired child would leave that phone listening to a server nobody
     * publishes on — no rules, no granted time, no remote commands, and no way to tell it where
     * everyone went. Recovering means re-enrolling the device, which on a Device Owner child is
     * a factory reset. A migration that carried children across is a real feature; silently
     * risking a family's phones on a settings screen is not the way to ship it.
     *
     * Before the first child, none of that applies: nobody has been told anything yet.
     */
    suspend fun setRelayServer(server: String): RelayChangeResult {
        val id = identityStore.current()
        if (id.role != Role.PARENT) return RelayChangeResult.HAS_CHILDREN
        val normalized = RelayServer.normalize(server) ?: return RelayChangeResult.INVALID
        // Both registers count: a child enrolled in the registry may not have checked in yet,
        // and a legacy child may have checked in without ever being in the registry.
        val enrolled = settingsStore.current().children.isNotEmpty() ||
            syncStore.current().children.isNotEmpty()
        if (enrolled) return RelayChangeResult.HAS_CHILDREN
        if (normalized == id.ntfyServer) return RelayChangeResult.OK
        identityStore.save(id.copy(ntfyServer = normalized))
        dev.walcott.debug.DebugLog.i(TAG, "relay changed to $normalized")
        // The cursor belongs to the old server's message stream; carrying it over would ask the
        // new one to replay from a timestamp that means nothing there.
        syncStore.update { it.copy(ntfySinceSec = 0) }
        sinceCache = 0
        connect(identityStore.current())
        publishSelf()
        return RelayChangeResult.OK
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
        // A fresh pairing is a new trust bootstrap (the QR in hand IS the family): drop the
        // replay baseline so a new family's lower version counter isn't mistaken for replay.
        syncStore.update { it.copy(appliedParentVersion = 0) }
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

    enum class InstallRequestResult { SENT, DUPLICATE, ALREADY_INSTALLED }

    /**
     * Child: ask the parents for one concrete app, shared from its Play page. Approval pushes
     * an install of exactly this package (see [ChildRequest.KIND_INSTALL]) — never a blanket
     * window. Deduplicated so mashing "share" doesn't stack copies on the parent's home.
     */
    suspend fun sendInstallRequest(pkg: String, label: String): InstallRequestResult {
        if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess) {
            return InstallRequestResult.ALREADY_INSTALLED
        }
        if (syncStore.current().pendingAsks.any { it.kind == ChildRequest.KIND_INSTALL && it.pkg == pkg }) {
            return InstallRequestResult.DUPLICATE
        }
        syncStore.update { s ->
            s.copy(
                childVersion = s.childVersion + 1,
                pendingAsks = s.pendingAsks + ChildRequest(
                    requestId = UUID.randomUUID().toString(),
                    kind = ChildRequest.KIND_INSTALL,
                    text = label.ifBlank { pkg },
                    pkg = pkg,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        publishSelf()
        return InstallRequestResult.SENT
    }

    /**
     * Child: hand the parent a selection of domains the monitor saw (see [DomainDelivery]).
     *
     * Deliberately the only path off this device for anything the monitor recorded. The selection
     * is sliced and then resent until each slice is confirmed, because a domain list is the one
     * payload here big enough to outgrow a message, and losing part of it silently would have the
     * parent blocking a list nobody chose.
     */
    suspend fun sendDomains(packageName: String, label: String, domains: List<String>) {
        val batch = DomainDelivery.start(UUID.randomUUID().toString(), packageName, label, domains) ?: return
        syncStore.update { it.copy(childVersion = it.childVersion + 1, domainBatch = batch) }
        publishSelf()
        nudgeDomainBatch()
    }

    /**
     * Resend the unconfirmed slices every [DOMAIN_NUDGE_MS] until the parent has them all, or
     * until the batch runs out of retries.
     *
     * Far tighter than the regular re-emit, and deliberately so: the parent handed the phone back
     * and is watching their own device right now, so the cost of a few extra messages buys the
     * only minute in which this request is interesting. The loop ends by itself — [DomainDelivery]
     * stops offering slices once the batch is delivered or abandoned.
     */
    private fun nudgeDomainBatch() {
        if (domainNudgeJob?.isActive == true) return
        domainNudgeJob = scope.launch {
            while (true) {
                delay(DOMAIN_NUDGE_MS)
                val batch = syncStore.current().domainBatch
                if (batch == null || batch.delivered || batch.abandoned) return@launch
                publishSelf()
            }
        }
    }

    // --- Emergency release, child-initiated (see PanicProtocol) ---

    /** Everything the child's emergency-release screen needs, in one reactive shape. */
    data class PanicStatus(
        val request: PanicRequest? = null,
        /** Newest server second this device has seen — the clock the request runs on. */
        val serverNowSec: Long = 0,
        /** Server second until which a parent's refusal blocks a new request. */
        val blockedUntilSec: Long = 0,
        /** Wall-clock ms of the last proof the channel works. */
        val lastChannelOkMs: Long = 0,
        /**
         * Whether the parent's build understands the request. An older parent silently ignores
         * the field, which would turn a loud, refusable request into a quiet escape hatch —
         * so the child isn't allowed to start one at all.
         */
        val parentSupported: Boolean = false,
    ) {
        fun channelProven(nowMs: Long): Boolean = PanicProtocol.channelProven(nowMs - lastChannelOkMs)

        /** Whether the child may start a request right now (the rule itself is in [PanicProtocol]). */
        fun canStart(nowMs: Long): Boolean = PanicProtocol.mayStart(
            hasActiveRequest = request != null,
            parentSupported = parentSupported,
            msSinceChannelOk = nowMs - lastChannelOkMs,
            blockedUntilSec = blockedUntilSec,
            serverNowSec = serverNowSec,
        )

        /** Seconds of lockout left after a refusal (0 = none), for the "try again in…" line. */
        val cooldownRemainingSec: Long get() = (blockedUntilSec - serverNowSec).coerceAtLeast(0)
    }

    val panicStatus: StateFlow<PanicStatus> = syncStore.state.map { s ->
        PanicStatus(
            request = s.panic,
            serverNowSec = s.ntfySinceSec,
            blockedUntilSec = s.panicBlockedUntilSec,
            lastChannelOkMs = s.lastChannelOkMs,
            parentSupported = s.parentAppVersionCode >= PANIC_MIN_PARENT_VERSION,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PanicStatus())

    /**
     * Starts the 24-hour emergency release. Returns false when the gates say no (no channel,
     * a standing refusal, a parent that couldn't be told, or a request already running) —
     * re-checked here and not only in the UI, since this is the one door out of enforcement.
     */
    suspend fun startPanic(): Boolean {
        val status = panicStatus.value
        if (!status.canStart(System.currentTimeMillis())) return false
        val anchor = status.serverNowSec
        syncStore.update {
            it.copy(
                panic = PanicRequest(
                    id = UUID.randomUUID().toString(),
                    startedAtSec = anchor,
                    lastCheckpointSec = anchor,
                ),
                childVersion = it.childVersion + 1,
            )
        }
        dev.walcott.debug.DebugLog.w(TAG, "emergency release requested by the child")
        publishSelf()
        return true
    }

    /** The child withdraws their own request (starting again restarts the 24 hours). */
    suspend fun cancelPanic() {
        if (syncStore.current().panic == null) return
        syncStore.update { it.copy(panic = null, childVersion = it.childVersion + 1) }
        dev.walcott.debug.DebugLog.w(TAG, "emergency release withdrawn by the child")
        publishSelf()
    }

    /**
     * The parent refuses the pending request ([RemoteAction.DENY_PANIC]): it dies and the child
     * can't ask again for three days. [requestId] must match the live request — a refusal that
     * arrives after the child already withdrew must not punish a later, unrelated one.
     */
    private suspend fun denyPanic(requestId: String): Pair<Boolean, String> = panicMutex.withLock {
        // Under the same lock as the checkpoints: a refusal landing at the same moment as the
        // final notice must not lose the race to the release it is trying to prevent.
        val s = syncStore.current()
        val request = s.panic ?: return@withLock false to "no_request"
        if (requestId.isNotBlank() && requestId != request.id) return@withLock false to "stale_request"
        syncStore.update {
            it.copy(
                panic = null,
                panicBlockedUntilSec = PanicProtocol.cooldownUntilSec(it.ntfySinceSec),
                childVersion = it.childVersion + 1,
            )
        }
        dev.walcott.debug.DebugLog.w(TAG, "emergency release refused by the parent")
        PanicNotifications.notifyDenied(context)
        publishSelf()
        true to "denied"
    }

    /**
     * Moves an active request forward against [serverNowSec] — the timestamp of a live message,
     * which is itself the proof that the channel works. Called on every incoming message, so the
     * two-hourly notice rides on traffic that happens anyway.
     *
     * Serialized like [applyCommands], and for the same reason: every message is handled in its
     * own coroutine, so a burst (our own echo landing beside a parent snapshot) would otherwise
     * read the same request twice and bank the same notice twice.
     */
    private suspend fun evaluatePanic(serverNowSec: Long) = panicMutex.withLock {
        val request = syncStore.current().panic ?: return@withLock
        when (PanicProtocol.evaluate(request, serverNowSec)) {
            PanicProtocol.Step.WAIT -> return@withLock
            PanicProtocol.Step.CHECKPOINT -> {
                val next = PanicProtocol.withCheckpoint(request, serverNowSec)
                syncStore.update { it.copy(panic = next, childVersion = it.childVersion + 1) }
                dev.walcott.debug.DebugLog.i(
                    TAG, "emergency release notice ${next.checkpoints}/${PanicProtocol.REQUIRED_CHECKPOINTS}",
                )
                PanicNotifications.notifyProgress(context, PanicProtocol.remainingCheckpoints(next))
                publishSelf()
            }
            PanicProtocol.Step.RELEASE -> completeRelease(request, serverNowSec)
            PanicProtocol.Step.EXPIRED -> expirePanic()
        }
    }

    /**
     * Banks the last notice and hands the device back. The request is recorded and published
     * BEFORE the teardown: it is the parent's only record that the device let itself go, and a
     * moment later there is no channel left to say so. That order is also what makes an
     * interrupted release recoverable — the banked request reads as [PanicProtocol.earned] on
     * the next pass, which is a RELEASE however long the interruption lasted.
     */
    private suspend fun completeRelease(request: PanicRequest, serverNowSec: Long) {
        if (!PanicProtocol.earned(request)) {
            syncStore.update {
                it.copy(
                    panic = PanicProtocol.withCheckpoint(request, serverNowSec),
                    childVersion = it.childVersion + 1,
                )
            }
            runCatching { publishSelf() }
        }
        PanicNotifications.notifyReleased(context)
        dev.walcott.enforcement.PanicRelease.releaseDevice(context)
    }

    /**
     * Ends a request whose device went quiet when a notice was due. Also called from the
     * heartbeat: while the channel is down no message arrives to notice it, and a request
     * that survived an offline stretch would be exactly the connectivity gap this must catch.
     *
     * A request that already served its full 24 hours is the exception, and it is why this
     * runs on the heartbeat at all rather than only on incoming messages: an interrupted
     * release leaves exactly that behind, and the device has no channel to be judged by any
     * more. Finish it instead of voiding it.
     */
    suspend fun expirePanicIfOffline() = panicMutex.withLock {
        val s = syncStore.current()
        val request = s.panic ?: return@withLock
        if (PanicProtocol.earned(request)) return@withLock completeRelease(request, s.ntfySinceSec)
        if (!PanicProtocol.expiredOffline(System.currentTimeMillis() - s.lastChannelOkMs)) return@withLock
        expirePanic()
    }

    private suspend fun expirePanic() {
        syncStore.update { it.copy(panic = null, childVersion = it.childVersion + 1) }
        dev.walcott.debug.DebugLog.w(TAG, "emergency release cancelled: the channel failed when a notice was due")
        PanicNotifications.notifyExpired(context)
        runCatching { publishSelf() }
    }

    /** PIN-gated manual exemption: allow installs on this device for [durationMs] (blanket). */
    suspend fun allowInstallsFor(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        syncStore.update { it.copy(installExemptionUntilMs = until) }
        // Synchronous lift, like openInstallForPush: the parent is standing at the device with
        // Play already open — don't depend on the exemption collector being alive and prompt.
        runCatching {
            DeviceRestrictions.apply(context, settingsStore.current().deviceRestrictions, installExemptUntilMs = until)
        }
        // The window is parent-visible state (and drives the reminder ladder on their phone).
        runCatching { publishSelf() }
    }

    /** Ends any open install window now and re-arms the block (the "re-block now" action). */
    suspend fun endInstallExemption() {
        val s = syncStore.current()
        if (s.pendingInstallPackage.isNotEmpty()) {
            // A pushed install's tight window is open: close it through its own path so the
            // pending fields and the prompt notification are cleaned up with it.
            closeInstallWindow()
            return
        }
        syncStore.update { it.copy(installExemptionUntilMs = 0) }
        runCatching {
            DeviceRestrictions.apply(context, settingsStore.current().deviceRestrictions, installExemptUntilMs = 0)
        }
        runCatching { publishSelf() }
    }

    /** True while a parent-pushed install's tight window is open (drives the close-on-install). */
    val pendingInstall: StateFlow<String> =
        syncStore.state.map { it.pendingInstallPackage }.stateIn(scope, SharingStarted.Eagerly, "")

    /**
     * Opens the tight, self-closing window for a parent-pushed install of [pkg]. The safety
     * cap is short: [closeInstallWindow] normally slams it shut on the first install, so this
     * ceiling only matters if nothing installs at all. [reopenInstallWindow] re-extends it
     * whenever the child actually engages, so this first window expiring costs nothing.
     */
    suspend fun openInstallForPush(pkg: String, commandId: String) {
        val until = System.currentTimeMillis() + INSTALL_PUSH_EXEMPTION_MS
        syncStore.update {
            it.copy(
                installExemptionUntilMs = until,
                pendingInstallPackage = pkg,
                pendingInstallCommandId = commandId,
            )
        }
        // Synchronous lift, mirroring closeInstallWindow's re-arm: the child may be looking
        // at Play seconds from now, so don't depend on the exemption collector's timing.
        runCatching {
            DeviceRestrictions.apply(context, settingsStore.current().deviceRestrictions, installExemptUntilMs = until)
        }
    }

    /**
     * Re-extends the pushed-install window at the moment the child engages (notification or
     * in-app card tap). The original window opens when the command ARRIVES, which can be long
     * before anyone looks at the device; without this, tapping after it expired would open
     * Play only for the install to be blocked by [DeviceRestrictions.KEY_INSTALLS].
     */
    suspend fun reopenInstallWindow() {
        val s = syncStore.current()
        if (s.pendingInstallPackage.isEmpty()) return
        openInstallForPush(s.pendingInstallPackage, s.pendingInstallCommandId)
    }

    /**
     * Closes the pushed-install window and re-arms [DeviceRestrictions.KEY_INSTALLS]
     * immediately (not just via the collector), so the child can't slip a second install in.
     * When [installedPkg] is the pushed package itself, the "opened" acknowledgement is
     * upgraded to "installed" so the parent sees the install actually completed.
     */
    suspend fun closeInstallWindow(installedPkg: String? = null) {
        val s = syncStore.current()
        if (s.pendingInstallPackage.isEmpty()) return
        val pushedLanded = installedPkg == s.pendingInstallPackage && s.pendingInstallCommandId.isNotEmpty()
        // The window was opened for ONE approved app; Play can't be told to install only that
        // one, so the guarantee is enforced after the fact: anything else that lands during
        // the window is removed on the spot (Device Owner uninstalls silently) and the parent
        // sees which app was tried instead of the approved one.
        val sneaked = installedPkg != null && !pushedLanded && s.pendingInstallCommandId.isNotEmpty()
        syncStore.update {
            it.copy(
                installExemptionUntilMs = 0,
                pendingInstallPackage = "",
                pendingInstallCommandId = "",
                lastCommandAck = when {
                    pushedLanded -> CommandAck(
                        id = s.pendingInstallCommandId,
                        action = RemoteAction.INSTALL_APP,
                        ok = true,
                        detail = RemoteAction.DETAIL_INSTALLED,
                        completedAtMs = System.currentTimeMillis(),
                        arg = s.pendingInstallPackage,
                    )
                    sneaked -> CommandAck(
                        id = s.pendingInstallCommandId,
                        action = RemoteAction.INSTALL_APP,
                        ok = false,
                        detail = RemoteAction.DETAIL_WRONG_APP_REMOVED,
                        completedAtMs = System.currentTimeMillis(),
                        arg = installedPkg,
                    )
                    else -> it.lastCommandAck
                },
                childVersion = if (pushedLanded || sneaked) it.childVersion + 1 else it.childVersion,
            )
        }
        InstallPromptNotifications.cancel(context, s.pendingInstallPackage)
        // Synchronous re-arm: don't wait for the settings/exemption collector to react.
        runCatching {
            DeviceRestrictions.apply(context, settingsStore.current().deviceRestrictions, installExemptUntilMs = 0)
        }
        if (sneaked) {
            dev.walcott.debug.DebugLog.w(TAG, "unauthorized install during window: $installedPkg — removing")
            runCatching { silentUninstall(installedPkg!!) }
        }
        if (pushedLanded || sneaked) publishSelf()
    }

    /** Device Owner silent uninstall (no-op elsewhere); the result lands in the debug log only. */
    private fun silentUninstall(pkg: String) {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(context.packageName) != true) return
        val sender = android.app.PendingIntent.getBroadcast(
            context, pkg.hashCode(),
            android.content.Intent("dev.walcott.action.UNINSTALL_RESULT").setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        ).intentSender
        context.packageManager.packageInstaller.uninstall(pkg, sender)
    }

    suspend fun requestExtraTime(categoryId: String, minutes: Int, reason: String, targetLabel: String = "") {
        syncStore.update { s ->
            s.copy(
                childVersion = s.childVersion + 1,
                pendingRequests = s.pendingRequests + ExtraTimeRequest(
                    requestId = UUID.randomUUID().toString(),
                    categoryId = categoryId,
                    minutes = minutes,
                    reason = reason,
                    createdAtEpochMs = System.currentTimeMillis(),
                    targetLabel = targetLabel,
                ),
            )
        }
        publishSelf()
    }

    /**
     * Retires the child's own requests that nobody answered in time (see
     * [SyncEngine.REQUEST_TTL_MS]). Called from the heartbeat, so it happens on a phone whose
     * owner never opens the app.
     *
     * The point is not tidiness: the home refuses to send a second request for something that
     * already has one pending, so an unanswered one left that button dead for good.
     */
    suspend fun expireStaleRequests() {
        val s = syncStore.current()
        val now = System.currentTimeMillis()
        val deadRequests = s.pendingRequests.filter { SyncEngine.requestExpired(it.createdAtEpochMs, now) }
        val deadAsks = s.pendingAsks.filter { SyncEngine.requestExpired(it.createdAtEpochMs, now) }
        if (deadRequests.isEmpty() && deadAsks.isEmpty()) return
        val newest = deadRequests.maxByOrNull { it.createdAtEpochMs }
        val expiredNotice = NoticeEntry(
            kind = NOTICE_EXPIRED,
            approved = false,
            text = newest?.targetLabel ?: deadAsks.maxByOrNull { it.createdAtEpochMs }?.text.orEmpty(),
            atMs = now,
        )
        syncStore.update { state ->
            state.copy(
                pendingRequests = state.pendingRequests - deadRequests.toSet(),
                pendingAsks = state.pendingAsks - deadAsks.toSet(),
                // Never over an answer the child hasn't read yet: an approval from a minute ago
                // matters more than a request that ran out, and this is the only copy of it.
                lastNotice = state.lastNotice ?: expiredNotice,
                childVersion = state.childVersion + 1,
            )
        }
        dev.walcott.debug.DebugLog.i(
            TAG, "retired ${deadRequests.size + deadAsks.size} unanswered request(s)",
        )
        runCatching { publishSelf() }
    }

    // --- Parent actions ---

    suspend fun resolveRequest(requestId: String, approved: Boolean, grantedMinutes: Int) {
        // Feed context: which child asked (the request may be a time request or a generic ask).
        val owner = syncStore.current().children.firstOrNull { c ->
            c.requests.any { it.requestId == requestId } || c.asks.any { it.requestId == requestId }
        }
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                resolutions = s.resolutions.filterNot { it.requestId == requestId } + Resolution(
                    requestId = requestId,
                    approved = approved,
                    grantedMinutes = grantedMinutes,
                    resolvedAtEpochMs = System.currentTimeMillis(),
                ),
            ).let { next ->
                if (owner == null) {
                    next
                } else {
                    next.plusEvent(
                        event(
                            if (approved) ParentEvent.TYPE_REQUEST_APPROVED else ParentEvent.TYPE_REQUEST_DENIED,
                            owner,
                            count = grantedMinutes,
                        ),
                    )
                }
            }
        }
        publishSelf()
    }

    /**
     * Answers a request straight from its notification, without opening the app.
     *
     * Approving grants exactly what was asked for: the shade has room for two buttons and no
     * picker, and "some other amount" is a considered answer that belongs on the card
     * ([dev.walcott.ui.parent.ExtraTimeRequestCard]). This is for the answer a parent already
     * knows, which is most of them.
     *
     * Returns false when there is nothing to answer — the request was resolved in the app, or
     * belongs to another family. The receiver offers the id to every family this phone holds and
     * lets the one that owns it act, so a stale notification (or a second tap on the same one)
     * quietly does nothing instead of granting twice.
     */
    suspend fun resolveFromNotification(requestId: String, approved: Boolean): Boolean {
        val s = syncStore.current()
        if (s.resolutions.any { it.requestId == requestId }) return false
        val timeRequest = s.children.flatMap { it.requests }.firstOrNull { it.requestId == requestId }
        if (timeRequest != null) {
            resolveRequest(requestId, approved, if (approved) timeRequest.minutes else 0)
            return true
        }
        val ask = s.children.flatMap { it.asks }.firstOrNull { it.requestId == requestId } ?: return false
        // An approved install ask is more than a resolution: it also pushes the single-app
        // install window. Same call the card makes, so the two paths can't drift.
        if (approved && ask.kind == ChildRequest.KIND_INSTALL && ask.pkg.isNotBlank()) {
            approveInstallAsk(requestId)
        } else {
            resolveRequest(requestId, approved, 0)
        }
        return true
    }

    /** Parent: hide a delivered-but-unfinished op from the home (see SyncState.dismissedOpIds). */
    suspend fun dismissPendingOp(id: String) {
        syncStore.update { it.copy(dismissedOpIds = (it.dismissedOpIds + id).takeLast(50)) }
    }

    /**
     * Approves a child's install request ([ChildRequest.KIND_INSTALL]): resolves the ask and
     * pushes the tight single-app install to the device that asked — installs of anything else
     * stay blocked throughout. The app arrives with no limit, like any other new app; setting
     * one is a separate, deliberate act.
     */
    suspend fun approveInstallAsk(requestId: String) {
        val owner = syncStore.current().children.firstOrNull { c -> c.asks.any { it.requestId == requestId } }
        val ask = owner?.asks?.firstOrNull { it.requestId == requestId }
        if (owner == null || ask == null || ask.pkg.isBlank()) return
        resolveRequest(requestId, approved = true, grantedMinutes = 0)
        sendCommand(owner.deviceId, RemoteAction.INSTALL_APP, arg = ask.pkg)
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

    /**
     * Parent queues a remote fix for a child device (see [RemoteAction]). Applied on the
     * child's next check-in and acknowledged back in its snapshot.
     */
    suspend fun sendCommand(targetDeviceId: String, action: String, arg: String = "") {
        val now = System.currentTimeMillis()
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                commands = SyncEngine.withCommand(
                    s.commands,
                    RemoteCommand(UUID.randomUUID().toString(), targetDeviceId, action, now, arg),
                    now,
                ),
            )
        }
        publishSelf()
    }

    /**
     * Withdraws a queued command before the child fetches it. Best-effort: a command the
     * child's transport already delivered will still run (and ack), which is the honest
     * outcome — cancellation is for commands still sitting in the queue.
     */
    suspend fun cancelCommand(commandId: String) {
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                commands = s.commands.filterNot { it.id == commandId },
            )
        }
        publishSelf()
    }

    /**
     * Parent refuses a child's emergency release: queues the refusal and records it on the feed
     * right away, so the wall shows the decision even before the child acknowledges it.
     */
    suspend fun denyPanicRequest(targetDeviceId: String, requestId: String) {
        val target = syncStore.current().children.firstOrNull { it.deviceId == targetDeviceId }
        sendCommand(targetDeviceId, RemoteAction.DENY_PANIC, arg = requestId)
        if (target != null) {
            syncStore.update { it.plusEvent(event(ParentEvent.TYPE_PANIC_DENIED, target, detail = requestId)) }
        }
    }

    /** Withdraws a pending "locate now" for a device. Best-effort, like [cancelCommand]. */
    suspend fun cancelLocationRequest(targetDeviceId: String) {
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                locationRequests = s.locationRequests.filterNot { it.deviceId == targetDeviceId },
            )
        }
        publishSelf()
    }

    /** Records how the last self-update went, so the parent can see why a child is stuck. */
    suspend fun recordUpdateError(error: String) {
        if (syncStore.current().updateError == error) return
        syncStore.update { it.copy(updateError = error) }
        runCatching { publishSelf() }
    }

    /**
     * Records what the rules just did here, for the parent's activity wall (see [ChildEvent]).
     * Published promptly but throttled: a wall entry is worth a message, a burst of them is not.
     */
    suspend fun recordRuleEvents(events: List<ChildEvent>) {
        if (events.isEmpty()) return
        val now = System.currentTimeMillis()
        syncStore.update {
            it.copy(
                ruleEvents = ChildEventLog.plus(it.ruleEvents, events, now),
                childVersion = it.childVersion + 1,
            )
        }
        runCatching { publishHeartbeatIfStale(RULE_EVENT_PUBLISH_MIN_MS) }
    }

    /** Records the heartbeat self-test's result; publishes on change so the parent hears promptly. */
    /** Persists the child's self-repair nudge throttle (see [ChildHealthCheck]). */
    suspend fun recordChildFixNudges(notifiedAt: Map<String, Long>) {
        if (notifiedAt == syncStore.current().childFixNotifiedAt) return
        syncStore.update { it.copy(childFixNotifiedAt = notifiedAt) }
    }

    suspend fun recordEnforcementGap(packages: List<String>) {
        if (syncStore.current().enforcementGaps == packages) return
        syncStore.update { it.copy(enforcementGaps = packages, childVersion = it.childVersion + 1) }
        runCatching { publishSelf() }
    }

    /** The parent app's build as last published in its snapshot (0 = unknown/legacy parent). */
    suspend fun parentAppVersionCode(): Int = syncStore.current().parentAppVersionCode

    /**
     * Records a clock-skew measurement (see [ClockGuard]). Persisted only on a meaningful
     * change so per-message jitter (network delay) doesn't churn DataStore; published
     * immediately when the tampered/clean verdict flips so the parent hears promptly.
     */
    private suspend fun recordClockSkew(skewMs: Long) {
        val previous = syncStore.current().clockSkewMs
        val verdictFlipped = ClockGuard.isTampered(skewMs) != ClockGuard.isTampered(previous)
        if (!verdictFlipped && kotlin.math.abs(skewMs - previous) < CLOCK_SKEW_RECORD_DELTA_MS) return
        syncStore.update { it.copy(clockSkewMs = skewMs, childVersion = it.childVersion + 1) }
        if (verdictFlipped) {
            dev.walcott.debug.DebugLog.w(TAG, "clock skew now ${skewMs / 1000}s (tampered=${ClockGuard.isTampered(skewMs)})")
            runCatching { publishSelf() }
        }
    }

    /**
     * Forget a device the parent no longer tracks (orphaned test devices, re-paired phones).
     * Purely local: if the device is still alive and paired it will re-appear on its next
     * publish, which is the honest behavior — removal is for devices that are actually gone.
     */
    suspend fun removeChildDevice(deviceId: String) {
        syncStore.update { s ->
            s.copy(
                children = s.children.filterNot { it.deviceId == deviceId },
                lastSeen = s.lastSeen - deviceId,
                staleNotifiedLastSeen = s.staleNotifiedLastSeen - deviceId,
                enforcementNotified = s.enforcementNotified - deviceId,
                usageAccessNotified = s.usageAccessNotified - deviceId,
                mockLocationNotified = s.mockLocationNotified - deviceId,
                lowBatteryNotified = s.lowBatteryNotified - deviceId,
                networkLocationNotified = s.networkLocationNotified - deviceId,
                pinAlertedTotal = s.pinAlertedTotal - deviceId,
                selfTestNotified = s.selfTestNotified - deviceId,
                clockTamperNotified = s.clockTamperNotified - deviceId,
                diagReports = s.diagReports - deviceId,
                diagHistory = s.diagHistory - deviceId,
                // Only legacy devices ledger under their deviceId; child-keyed history stays.
                usageHistory = s.usageHistory - deviceId,
            )
        }
    }

    /** Parent grants an unsolicited bonus (chores, good behaviour) to a child device. */
    suspend fun giveBonus(targetDeviceId: String, categoryId: String, minutes: Int) {
        val target = syncStore.current().children.firstOrNull { it.deviceId == targetDeviceId }
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
            ).let { next ->
                if (target == null) next else next.plusEvent(event(ParentEvent.TYPE_BONUS, target, count = minutes))
            }
        }
        publishSelf()
    }

    private suspend fun publishConfigChanged() {
        syncStore.update { it.copy(parentVersion = it.parentVersion + 1) }
        publishSelf()
    }

    // --- Family backup / restore (TODO #1, option (a)) ---

    /**
     * Builds the passphrase-encrypted family backup file. For a legacy family whose signing
     * key is locked in the Keystore (non-exportable), each backup carries a FRESH recovery
     * keypair plus a [RotationCert] minted by the Keystore key — nothing on the wire changes
     * until a restore actually happens. Newer families export their software key directly.
     */
    suspend fun createBackup(passphrase: CharArray): String {
        // PBKDF2 at 600k iterations takes a moment by design; keep it off the caller's thread.
        return withContext(Dispatchers.Default) { FamilyBackup.encrypt(buildBackupPayload(), passphrase) }
    }

    private suspend fun buildBackupPayload(): FamilyBackupPayload {
        val id = identityStore.current()
        check(id.role == Role.PARENT) { "only a parent device can create a family backup" }
        val (publicB64, privateB64, certB64) = if (id.parentPrivateKeyB64.isNotBlank()) {
            // Software key. Any rotation cert rides along: a child that slept through a past
            // restore still trusts the pre-rotation key and needs the hand-over proof.
            Triple(id.parentPublicKeyB64, id.parentPrivateKeyB64, id.rotationCertB64)
        } else {
            recoveryTrio(id)
        }
        val settings = settingsStore.current()
        return FamilyBackupPayload(
            familyName = settings.familyName,
            topic = id.topic,
            ntfyServer = id.ntfyServer,
            familyKeyB64 = id.familyKeyB64,
            signingPublicKeyB64 = publicB64,
            signingPrivateKeyB64 = privateB64,
            rotationCertB64 = certB64,
            policyJson = json.encodeToString(PolicySettings.serializer(), settings),
            parentVersion = syncStore.current().parentVersion,
            createdAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Legacy family (Keystore signing key): the recovery keypair + cert embedded in backups,
     * minted on the first backup and REUSED for every later one. If each backup minted its
     * own pair, two old files would rotate children to different keys, and restoring from
     * the second would orphan every child that had followed the first.
     */
    private suspend fun recoveryTrio(id: FamilyIdentity): Triple<String, String, String> {
        if (id.recoveryPrivateKeyB64.isNotBlank()) {
            return Triple(id.recoveryPublicKeyB64, id.recoveryPrivateKeyB64, id.recoveryCertB64)
        }
        val recovery = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(recovery.public, ParentKeystore.privateKey())
        val trio = Triple(
            FamilyCrypto.toB64(recovery.public.encoded),
            FamilyCrypto.toB64(recovery.private.encoded),
            KeyRotation.encode(cert),
        )
        identityStore.save(
            identityStore.current().copy(
                recoveryPublicKeyB64 = trio.first,
                recoveryPrivateKeyB64 = trio.second,
                recoveryCertB64 = trio.third,
            ),
        )
        return trio
    }

    /** Call once the backup file actually reached its destination, so the card can say so. */
    suspend fun recordBackupSaved() {
        syncStore.update { it.copy(lastBackupAtMs = System.currentTimeMillis()) }
    }

    /**
     * Caches the key for the on-device copies, stretched from the parent PIN. Called whenever the
     * PIN is set or successfully entered, which is what lets the nightly write be silent: the PIN
     * itself is never stored, and an existing parent picks this up the next time they unlock
     * without being asked for anything.
     *
     * The PIN is short, so this file is far weaker than a passphrase backup and is not a
     * replacement for one — see [LocalBackupStore] for what it is and isn't for.
     */
    /**
     * Keeps a readable copy of the PIN — on a PARENT device only (see
     * [FamilyIdentity.pinPlain]). The role check is the whole security property, so it is here,
     * once, rather than at each call site: a child device runs this same code on every extra-time
     * and release dialog, and must come out of it holding nothing.
     */
    suspend fun rememberPinIfParent(pin: String) {
        val id = identityStore.current()
        if (id.effectiveMode != DeviceMode.PARENT) return
        if (id.pinPlain == pin) return
        identityStore.save(id.copy(pinPlain = pin))
    }

    /** The readable PIN, or "" when this device has never held it (see [FamilyIdentity.pinPlain]). */
    val readablePin: StateFlow<String> =
        identityStore.identity.map { it.pinPlain }.stateIn(scope, SharingStarted.Eagerly, "")

    suspend fun cacheLocalBackupKey(pin: String) {
        // Deliberately NOT gated on being a parent yet. Gating it made the whole feature depend on
        // the PIN being set after the family exists, and if a setup journey ever did it the other
        // way round the key would silently never be derived — no backup, no signal, exactly the
        // failure this is here to prevent. Deriving early is harmless: writing still requires a
        // parent, and a child never reaches the code that uses it.
        val current = syncStore.current()
        val salt = current.localBackupSaltB64.takeIf { it.isNotBlank() } ?: FamilyBackup.newSaltB64()
        val key = withContext(Dispatchers.Default) { FamilyBackup.deriveKeyB64(pin.toCharArray(), salt) }
        if (key == current.localBackupKeyB64) return
        // A changed PIN has to open ALL three copies, not just whichever the rotation happens to
        // refresh next — otherwise the monthly one keeps needing a PIN the parent has forgotten.
        // Forgetting the written days makes every slot due, and the rewrite happens now.
        syncStore.update { it.copy(localBackupKeyB64 = key, localBackupSaltB64 = salt, localBackupDays = emptyMap()) }
        writeDueLocalBackups(java.time.LocalDate.now())
    }

    /**
     * Rewrites whichever on-device copies are due tonight (see [BackupRotation]). No-op until the
     * PIN has been seen once, and on anything that isn't a parent. Never throws — a failed backup
     * must not take down the worker that called it.
     */
    suspend fun writeDueLocalBackups(today: java.time.LocalDate): Set<BackupRotation.Slot> {
        val s = syncStore.current()
        if (identityStore.current().role != Role.PARENT || s.localBackupKeyB64.isBlank()) return emptySet()
        val lastWritten = s.localBackupDays.mapNotNull { (name, day) ->
            BackupRotation.Slot.entries.firstOrNull { it.name == name }?.to(java.time.LocalDate.ofEpochDay(day))
        }.toMap()
        val due = BackupRotation.due(today, lastWritten)
        if (due.isEmpty()) return emptySet()

        val text = runCatching {
            withContext(Dispatchers.Default) {
                FamilyBackup.encryptWithDerivedKey(
                    buildBackupPayload(), s.localBackupKeyB64, s.localBackupSaltB64,
                    keySource = FamilyBackup.SOURCE_PIN,
                )
            }
        }.getOrElse {
            dev.walcott.debug.DebugLog.w(TAG, "local backup payload failed", it)
            syncStore.update { st -> st.copy(localBackupError = true) }
            return emptySet()
        }

        // One ciphertext for every slot due tonight: they are copies of the same state, and
        // re-sealing per slot would only burn CPU for three different nonces.
        val written = due.mapNotNull { slot ->
            LocalBackupStore.write(context, slot, text, s.localBackupUris[slot.name], familyId)
                ?.let { slot to it.toString() }
        }.toMap()
        // Deliberately does NOT touch lastBackupAtMs: that drives the reminder to set up a backup
        // that lives somewhere else, and a copy on the same phone is no answer to losing the
        // phone. Silencing that nudge here would trade one disaster for another.
        syncStore.update { st ->
            st.copy(
                localBackupDays = st.localBackupDays + written.keys.associate { it.name to today.toEpochDay() },
                localBackupUris = st.localBackupUris + written.mapKeys { it.key.name },
                localBackupError = written.size != due.size,
            )
        }
        return written.keys
    }

    /** Debug harness only: puts this parent back in the state an un-upgraded one is in. */
    suspend fun clearLocalBackupKeyForDebug() {
        syncStore.update { it.copy(localBackupKeyB64 = "", localBackupSaltB64 = "", localBackupDays = emptyMap()) }
    }

    /**
     * Resurrects a family from a backup on this (fresh) device: identity, keys, rules and
     * children registry come back, and the first publish re-asserts the rules — children
     * never need to be touched. False when the passphrase is wrong or the file is invalid.
     */
    suspend fun restoreBackup(fileJson: String, passphrase: CharArray): Boolean {
        val payload = withContext(Dispatchers.Default) { FamilyBackup.decrypt(fileJson, passphrase) }
            ?: return false
        val policy = runCatching { json.decodeFromString(PolicySettings.serializer(), payload.policyJson) }
            .getOrNull() ?: return false
        // A crafted file must not silently point this device's transport at an arbitrary
        // scheme/host. http stays allowed: self-hosted LAN ntfy servers are legitimate.
        val server = runCatching { java.net.URI(payload.ntfyServer) }.getOrNull()
        if (server?.scheme !in setOf("http", "https") || server?.host.isNullOrBlank()) return false
        // The key material must actually parse before this device stakes its identity on it.
        // Authenticated encryption rules out tampering, but not a buggy or future writer.
        val materialOk = runCatching {
            check(payload.topic.isNotBlank())
            FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(payload.familyKeyB64))
            FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(payload.signingPublicKeyB64))
            FamilyCrypto.privateKeyFromBytes(FamilyCrypto.fromB64(payload.signingPrivateKeyB64))
            if (payload.rotationCertB64.isNotBlank()) checkNotNull(KeyRotation.decode(payload.rotationCertB64))
        }.isSuccess
        if (!materialOk) return false
        val identity = FamilyIdentity(
            role = Role.PARENT,
            mode = DeviceMode.PARENT,
            deviceId = "parent",
            topic = payload.topic,
            familyKeyB64 = payload.familyKeyB64,
            parentPublicKeyB64 = payload.signingPublicKeyB64,
            parentPrivateKeyB64 = payload.signingPrivateKeyB64,
            rotationCertB64 = payload.rotationCertB64,
            ntfyServer = payload.ntfyServer,
        )
        settingsStore.update { policy }
        // A fresh slate for everything device-local: a stale auto-backup pointer would
        // clobber a previous family's file with this one's data, and ghost children or
        // reminder bookkeeping from a pre-restore life would mislead. Only the version
        // counter carries over, leaping far ABOVE the backup's: children gate rules on
        // version monotonicity (SyncEngine.adoptsPolicy) and the lost phone may have
        // published edits after this backup was taken — a same-key restore carries no
        // rotation to rebase their counter, so the leap must dwarf any realistic edit count.
        syncStore.update {
            SyncState(
                parentVersion = maxOf(it.parentVersion, payload.parentVersion) + RESTORE_VERSION_LEAP,
                parentSetupAtMs = System.currentTimeMillis(),
            )
        }
        identityStore.save(identity)
        dev.walcott.debug.DebugLog.w(TAG, "family restored from backup (created ${payload.createdAtMs})")
        connect(identity)
        publishSelf()
        return true
    }

    /** Publish this child's snapshot now (used by the periodic location sampler). */
    suspend fun publishLocationUpdate() = publishSelf()

    // --- Idle-earn (token-window model) ---

    /** Minutes earned today, for the child's "earned" display. Reactive to the grant ledger. */
    val earnedTodayMinutes: StateFlow<Int> = syncStore.state.map { s ->
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        IdleEarnEngine.earnedOnDay(
            s.earnGrants.map { EarnGrant(it.epochMs, it.minutes) }, dayStart, dayStart + 86_400_000L,
        )
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Banks [seconds] of idle time, then converts as much as the caps allow into earned extra
     * for the target category. Called by the enforcement loop (child only). Bounded so a child
     * can't stockpile more than a week's worth of idle while a cap is saturated.
     */
    suspend fun accrueAndConvertIdle(seconds: Long, config: IdleEarnConfig): Int {
        if (seconds > 0) {
            val bankCapSeconds = idleBankCapSeconds(config)
            syncStore.update {
                it.copy(idleEarnBankSeconds = (it.idleEarnBankSeconds + seconds).coerceIn(0, bankCapSeconds))
            }
        }
        val now = System.currentTimeMillis()
        val s = syncStore.current()
        val ledger = s.earnGrants.map { EarnGrant(it.epochMs, it.minutes) }
        val grant = IdleEarnEngine.grantableMinutes(config, ledger, s.idleEarnBankSeconds / 60, now)
        if (grant <= 0) return 0

        // Earned minutes widen every app's allowance (see RuleEngine's ALL_APPS handling):
        // with no categories left there is no single target to send them to, and "you earned
        // more screen time" is what a child was promised anyway.
        repository.grantExtraMinutes(dev.walcott.rules.ExtraTime.ALL_APPS, grant.toLong())
        val consumedSeconds = IdleEarnEngine.idleConsumedFor(config, grant) * 60
        val pruned = IdleEarnEngine.prune(ledger + EarnGrant(now, grant), now)
            .map { EarnGrantEntry(it.epochMs, it.minutes) }
        syncStore.update {
            it.copy(
                idleEarnBankSeconds = (it.idleEarnBankSeconds - consumedSeconds).coerceAtLeast(0),
                earnGrants = pruned,
            )
        }
        dev.walcott.debug.DebugLog.i(TAG, "idle-earn granted $grant min to every app")
        return grant
    }

    /** Idle needed to reach the weekly cap; the bank never exceeds this, so nothing stockpiles. */
    private fun idleBankCapSeconds(config: IdleEarnConfig): Long {
        val reward = config.rewardMinutes.coerceAtLeast(1)
        return config.weeklyCapMinutes.toLong() / reward * config.minutesIdlePerReward * 60L
    }

    /** Publish now because a health signal changed (e.g. usage access toggled). */
    suspend fun publishHealthUpdate() = publishSelf()

    /**
     * Publishes unless something else already published within [minIntervalMs]. This is the
     * Doze-resilient heartbeat: the in-process 15-min re-emit can't fire while the device
     * sleeps, so the watchdog worker (batched by Doze into maintenance windows) and the
     * screen-off checkpoint call this instead — reusing wakeups that happen anyway, and the
     * throttle keeps awake periods from double-publishing.
     */
    suspend fun publishHeartbeatIfStale(minIntervalMs: Long) {
        if (System.currentTimeMillis() - lastPublishAtMs < minIntervalMs) return
        publishSelf()
    }

    /** PIN check with escalating brute-force lockout (device-local state). */
    suspend fun verifyPinGuarded(pin: String): PinResult {
        val s = syncStore.current()
        val now = System.currentTimeMillis()
        // Before anything else: a family with no PIN can't fail a check, it can only fail to
        // have one. Counting these as wrong guesses locked the child out of a door that was
        // never going to open, and reported them to the parent as an attempted break-in.
        if (!repository.hasPin()) return PinResult.NotSet
        val remaining = PinLockout.remainingMs(s.pinLockedUntilMs, now)
        if (remaining > 0) return PinResult.Locked(remaining)

        if (repository.verifyPin(pin)) {
            if (s.pinFailedAttempts != 0 || s.pinLockedUntilMs != 0L) {
                syncStore.update { it.copy(pinFailedAttempts = 0, pinLockedUntilMs = 0) }
            }
            // A correct PIN is the only moment we ever hold it, so it is the only moment the
            // on-device backup key can be derived. Parents who set their PIN before this existed
            // get it on their next unlock, without being asked for anything.
            if (s.localBackupKeyB64.isBlank()) cacheLocalBackupKey(pin)
            // Same trick for the readable reminder: a family whose PIN predates the feature
            // gets it back the next time they type it correctly, with nothing to answer.
            rememberPinIfParent(pin)
            return PinResult.Ok
        }

        val attempts = s.pinFailedAttempts + 1
        val lockMs = PinLockout.lockoutMs(attempts)
        syncStore.update {
            it.copy(
                pinFailedAttempts = attempts,
                pinLockedUntilMs = if (lockMs > 0) now + lockMs else it.pinLockedUntilMs,
                // Monotonic tally reported to the parent so a brute-force attempt is visible remotely.
                pinWrongTotal = it.pinWrongTotal + 1,
                lastWrongPinMs = now,
            )
        }
        // Surface the failed attempt to the parent promptly; the escalating lockout rate-limits this.
        runCatching { publishSelf() }
        return if (lockMs > 0) PinResult.Locked(lockMs) else PinResult.Wrong
    }

    // --- Publish / receive ---

    /**
     * Publishes this device's snapshot. Never throws: it is called from many fire-and-forget
     * spots (resolve, bonus, command, PIN failure), and a Keystore or encoding hiccup killing
     * those coroutines silently would drop the user's action with no trace. The periodic
     * re-emit retries anything a failed publish missed.
     */
    private suspend fun publishSelf() {
        try {
            publishSelfOrThrow()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            dev.walcott.debug.DebugLog.e(TAG, "publish failed", t)
        }
    }

    private suspend fun publishSelfOrThrow() {
        val id = identityStore.current()
        val transport = transport ?: return
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        when (id.role) {
            Role.PARENT -> {
                val state = syncStore.current()
                // The PIN hash/salt travel with the policy so the parent's PIN also guards
                // enrolled child devices (gate + leaving child mode).
                val settings = settingsStore.current()
                // Ask for icons of apps shown in the list that aren't cached yet; empties out.
                // The rotation slides the bounded request window over time, so a package no
                // child can serve can't starve the ones behind it.
                val shownApps = state.children.flatMap { c -> c.apps.map { it.packageName } }
                val iconRequests = IconSync.toRequest(
                    shownApps, iconStore.cachedAmong(shownApps),
                    rotation = (System.currentTimeMillis() / ICON_REQUEST_ROTATE_MS).toInt(),
                )
                val snapshot = ParentSnapshot(
                    version = state.parentVersion,
                    policyJson = json.encodeToString(PolicySettings.serializer(), settings),
                    resolutions = state.resolutions,
                    bonuses = state.bonuses,
                    locationRequests = state.locationRequests,
                    commands = state.commands,
                    iconRequests = iconRequests,
                    domainAcks = state.domainAcks,
                    // The parent is the fleet's update canary: children only follow up to this.
                    parentVersionCode = BuildConfig.VERSION_CODE,
                )
                val rotation = id.rotationCertB64.takeIf { it.isNotBlank() }?.let { KeyRotation.decode(it) }
                transport.publish(SyncProtocol.encodeParent(snapshot, familyKey, signingKey(id), rotation))
            }
            Role.CHILD -> {
                val s = syncStore.current()
                val today = LocalDate.now().toEpochDay()
                // Stamped now and remembered with the local clock as it reads at this instant:
                // when this message comes back, those two are what measure the clock.
                val nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong()
                // Capped, with the tail folded into one bucket so the daily totals the parent
                // sums stay exact (see UsageReport). Seven days of per-app rows would otherwise
                // be the biggest thing in the message.
                val history = repository.weeklyUsage().map { (day, usage) ->
                    DayUsage(
                        day,
                        UsageReport.cap(
                            usage.map { UsageEntry(it.key, it.value.seconds) },
                            UsageReport.MAX_PER_HISTORY_DAY,
                        ),
                    )
                }
                // PackageManager enumeration is blocking; keep it off the caller's thread.
                val apps = withContext(Dispatchers.IO) {
                    repository.inventory.launchableApps()
                        .filterNot { it.isSystem }
                        .map { InstalledAppInfo(it.packageName, it.label) }
                }
                val settings = settingsStore.current()
                // History off (the default) reports only the current position; on, the 48h
                // trail is decimated so it can't push the snapshot past ntfy's message cap.
                val historyOn = settings.resolveForChild(id.childId).locationHistoryEnabled
                val crashes = dev.walcott.debug.CrashCounter.current()
                val locations = if (historyOn) {
                    LocationTrail.compress(repository.recentLocations(), System.currentTimeMillis())
                } else {
                    repository.latestLocation()
                }
                val snapshot = ChildSnapshot(
                    deviceId = id.deviceId,
                    displayName = id.displayName,
                    childId = id.childId,
                    version = s.childVersion,
                    epochDay = today,
                    usage = UsageReport.cap(
                        repository.reportedUsageNow().map { UsageEntry(it.key, it.value.seconds) },
                        UsageReport.MAX_TODAY,
                    ),
                    extra = repository.extraNow().map { UsageEntry(it.key, it.value.seconds) },
                    requests = s.pendingRequests,
                    history = history,
                    asks = s.pendingAsks,
                    apps = apps,
                    locations = locations,
                    networkLocationOn = LocationSampler(context).networkProviderEnabled(),
                    usageAccessOn = UsageAccess.granted(context),
                    appVersionCode = BuildConfig.VERSION_CODE,
                    appVersionName = BuildConfig.VERSION_NAME,
                    enforcement = EnforcementBackends.status(context),
                    pinWrongTotal = s.pinWrongTotal,
                    lastWrongPinMs = s.lastWrongPinMs,
                    lastCommand = s.lastCommandAck,
                    answeredLocationRequestMs = s.appliedLocationRequestMs,
                    appliedPolicyVersion = s.appliedParentVersion,
                    batteryPercent = batteryPercent(),
                    charging = batteryCharging(),
                    updateError = s.updateError,
                    enforcementGaps = s.enforcementGaps,
                    clockSkewMs = s.clockSkewMs,
                    panic = s.panic,
                    installExemptionUntilMs = s.installExemptionUntilMs,
                    domainChunks = DomainDelivery.forPublish(s.domainBatch),
                    // Which clock `epochDay` and the counters beside it were read by, so the
                    // parent doesn't date them with its own while one of them is travelling.
                    tzOffsetMinutes = java.time.OffsetDateTime.now().offset.totalSeconds / 60,
                    publishNonce = nonce,
                    ruleEvents = ChildEventLog.plus(s.ruleEvents, emptyList(), System.currentTimeMillis()),
                    // Asked for versus actually up: the tunnel can be refused, revoked or stolen
                    // by another VPN app, and none of that was visible from the parent's side.
                    webFilterExpected = settings.hasWebFilter(),
                    // Grace-guarded: every process start has the tunnel down for a few seconds
                    // while the service establishes it, and reporting that would alert the parent
                    // after every reboot and every self-update (see VpnStatus.GRACE_MS).
                    webFilterOn = !dev.walcott.net.VpnStatus.settledDown(),
                    crashTotal = crashes.total,
                    lastCrashMs = crashes.lastAtMs,
                )
                // Fit-or-degrade: an oversized message would be rejected (HTTP 413) and the
                // child would silently vanish from the parent, which is far worse than a
                // temporarily thinner snapshot.
                val fitted = SnapshotFit.encodeChild(snapshot, familyKey)
                if (fitted.degraded != null) {
                    dev.walcott.debug.DebugLog.w(TAG, "snapshot over size budget; degraded: ${fitted.degraded}")
                }
                awaitedEcho = nonce to System.currentTimeMillis()
                transport.publish(fitted.encoded)
                // Count the round only when slices actually went out, and only after the publish
                // succeeded: charging a retry to a message that was never sent would burn the
                // give-up budget on this device's own connectivity rather than on the parent.
                if (snapshot.domainChunks.isNotEmpty()) {
                    syncStore.update { st ->
                        st.copy(domainBatch = st.domainBatch?.let { DomainDelivery.published(it) })
                    }
                }
            }
            Role.UNPAIRED -> Unit
        }
        if (id.role != Role.UNPAIRED) lastPublishAtMs = System.currentTimeMillis()
    }

    /**
     * Child: render and send a batch of the icons the parent asked for — only apps this child
     * actually has, bounded so rendering stays cheap and one message stays under the size cap.
     * The parent re-requests what's still missing, so the backlog drains over a few messages.
     */
    private suspend fun answerIconRequests(requests: List<String>, id: FamilyIdentity) {
        val transport = transport ?: return
        val candidates = withContext(Dispatchers.IO) {
            requests.asSequence()
                .filter { runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess }
                .take(ICON_RENDER_LIMIT)
                .mapNotNull { pkg ->
                    val drawable = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
                    drawable?.let { IconStore.encode(it) }?.let { AppIconData(pkg, it) }
                }
                .toList()
        }
        val packed = IconSync.pack(candidates)
        if (packed.isEmpty()) return
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        // Fit-or-drop: the pack budget is measured pre-envelope, so verify the real wire size
        // like the snapshot does — an oversized publish would be 413-rejected every cycle and
        // silently jam this icon (and everything queued behind it) forever.
        val message = IconFit.encode(IconPayload(id.deviceId, packed), familyKey)
        if (message == null) {
            dev.walcott.debug.DebugLog.w(TAG, "icon message over size budget even with one icon; dropped")
            return
        }
        transport.publish(message)
    }

    /**
     * Child: gather and publish the health report a [RemoteAction.DIAGNOSE] asked for. Its
     * own message kind (like icons) so the log tail never bloats the regular snapshot;
     * DiagFit trims the log to keep the message under the ntfy size cap.
     */
    suspend fun publishDiagnostics() {
        val id = identityStore.current()
        val transport = transport ?: return
        val s = syncStore.current()
        val locationManager = context.getSystemService(android.location.LocationManager::class.java)
        val payload = DiagPayload(
            deviceId = id.deviceId,
            atMs = System.currentTimeMillis(),
            enforcement = EnforcementBackends.status(context),
            deviceOwner = dev.walcott.enforcement.Enforcer(context).isDeviceOwner(),
            usageAccess = UsageAccess.granted(context),
            gpsOn = runCatching {
                locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
            }.getOrDefault(false),
            networkLocationOn = LocationSampler(context).networkProviderEnabled(),
            locationPermission = dev.walcott.location.LocationPolicy.hasFineLocation(context),
            batteryPercent = batteryPercent(),
            charging = batteryCharging(),
            updateError = s.updateError,
            suspendFailures = dev.walcott.enforcement.Enforcer.recentSuspendFailures,
            appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME,
            logLines = dev.walcott.debug.DebugLog.tail(DIAG_LOG_LINES),
        )
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        transport.publish(DiagFit.encode(payload, familyKey))
    }

    /** Current battery percentage (0–100), or -1 if the platform won't say. */
    private fun batteryPercent(): Int =
        runCatching {
            context.getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1)

    private fun batteryCharging(): Boolean =
        runCatching {
            context.getSystemService(android.os.BatteryManager::class.java)?.isCharging ?: false
        }.getOrDefault(false)

    /** The active parent signing key: software (new/restored families) or the legacy Keystore. */
    private fun signingKey(id: FamilyIdentity): java.security.PrivateKey =
        if (id.parentPrivateKeyB64.isNotBlank()) {
            FamilyCrypto.privateKeyFromBytes(FamilyCrypto.fromB64(id.parentPrivateKeyB64))
        } else {
            ParentKeystore.privateKey()
        }

    private suspend fun handleIncoming(raw: String, id: FamilyIdentity, timeSec: Long) {
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        val parentPublic = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(id.parentPublicKeyB64))
        val decoded = SyncProtocol.decodeVerbose(raw, familyKey, parentPublic) ?: return
        val message = decoded.message

        // A restored parent proved a key rotation (see KeyRotation): make the new key this
        // child's trust root. The old key died with the old phone, so this is permanent.
        val rotatedKey = decoded.rotatedParentPublicKeyB64
        if (rotatedKey != null && id.role == Role.CHILD && rotatedKey != id.parentPublicKeyB64) {
            identityStore.save(identityStore.current().copy(parentPublicKeyB64 = rotatedKey))
            dev.walcott.debug.DebugLog.w(TAG, "adopted rotated parent signing key (parent restored from backup)")
        }

        // Clock-tamper watch (child only): every message carries the server's clock.
        if (id.role == Role.CHILD && timeSec > 0) {
            val ownSnapshot = (message as? IncomingMessage.FromChild)
                ?.snapshot?.takeIf { it.deviceId == id.deviceId }
            val skew = if (ownSnapshot != null) {
                // Only the echo of the publish we are still waiting for says anything: an older
                // publish of ours, replayed after a reconnect, carries a server stamp from
                // before the outage and would read as a clock hours ahead.
                awaitedEcho?.let { (nonce, publishedAt) ->
                    ClockGuard.skewFromOwnEcho(nonce, publishedAt, ownSnapshot.publishNonce, timeSec)
                        ?.also { awaitedEcho = null }
                }
            } else {
                ClockGuard.measuredSkew(ClockGuard.skewMs(System.currentTimeMillis(), timeSec))
            }
            skew?.let { recordClockSkew(it) }
        }

        when {
            id.role == Role.CHILD && message is IncomingMessage.FromParent ->
                applyParentSnapshot(message.snapshot, rotationAdopted = rotatedKey != null)
            id.role == Role.PARENT && message is IncomingMessage.FromChild -> applyChildSnapshot(message.snapshot)
            id.role == Role.PARENT && message is IncomingMessage.FromChildIcons -> applyIconPayload(message.payload)
            id.role == Role.PARENT && message is IncomingMessage.FromChildDiag -> applyDiagPayload(message.payload)
        }

        // The emergency release runs on the server's clock, and a LIVE message proves both that
        // the channel works and what time the server thinks it is (see PanicProtocol.provesChannel
        // for why a replayed one proves neither). Deliberately AFTER the parent snapshot above: a
        // refusal arriving in the same message must beat the notice — or the release — it refuses.
        if (id.role == Role.CHILD && PanicProtocol.provesChannel(System.currentTimeMillis(), timeSec)) {
            evaluatePanic(maxOf(timeSec, syncStore.current().ntfySinceSec))
        }
    }

    /** Parent: file the health report, newest first, for the child's health-reports screen. */
    private suspend fun applyDiagPayload(payload: DiagPayload) {
        syncStore.update { s ->
            // A report stored by a parent build that only kept the last one still counts as the
            // oldest entry of the new history; filing over it is what retires that field.
            val legacy = s.diagReports[payload.deviceId]?.let { listOf(StoredDiag(it)) }.orEmpty()
            val previous = s.diagHistory[payload.deviceId] ?: legacy
            // The same report twice is one report. ntfy replays its backlog on every reconnect,
            // so a message we already filed comes back around: without this the archive grew a
            // second copy of it, and the screen — which keys its rows by that instant — died on
            // the duplicate key the moment the parent opened it.
            if (previous.any { it.report.atMs == payload.atMs }) return@update s
            // Stamped on arrival — see StoredDiag for why the version row needs the date.
            val filed = StoredDiag(payload, BuildConfig.VERSION_CODE)
            s.copy(
                diagHistory = s.diagHistory +
                    (payload.deviceId to (listOf(filed) + previous).take(MAX_DIAG_HISTORY)),
                diagReports = s.diagReports - payload.deviceId,
            )
        }
    }

    /**
     * Parent: cache the icons a child just sent. If apps still lack icons, re-publish so the
     * next request goes out promptly — that request→answer→request loop drains the enrollment
     * burst quickly and then falls silent (empty requests cost nothing).
     */
    private suspend fun applyIconPayload(payload: IconPayload) {
        var stored = 0
        for (icon in payload.icons) {
            if (iconStore.has(icon.packageName)) continue
            val bytes = IconStore.decodeBase64(icon.webpB64) ?: continue
            // A stored-but-undecodable file would count as "cached" forever (never re-requested,
            // never rendered), so only bytes that actually decode to a bitmap are kept.
            if (IconStore.toBitmap(bytes) == null) continue
            if (iconStore.store(icon.packageName, bytes)) stored++
        }
        if (stored == 0) return
        iconsCached.value = iconsCached.value + 1 // nudge the UI to re-read the cache
        val shown = syncStore.current().children.flatMap { c -> c.apps.map { it.packageName } }
        if (IconSync.toRequest(shown, iconStore.cachedAmong(shown)).isNotEmpty()) publishSelf()
    }

    /** Bumps whenever new icons land, so the app list recomposes and re-reads the disk cache. */
    val iconsCached = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Cached icon bytes for [pkg], or null if not fetched yet (parent-side render). */
    fun iconBytes(pkg: String): ByteArray? = iconStore.read(pkg)

    private suspend fun applyParentSnapshot(snapshot: ParentSnapshot, rotationAdopted: Boolean) {
        val id = identityStore.current()
        // Replay gate: an old captured envelope is still validly signed, so freshness must
        // come from the version counter (see SyncEngine.adoptsPolicy). Everything below the
        // rules — commands, resolutions, bonuses, icon/locate requests — stays idempotent by
        // its own ids and keeps processing regardless, since re-emits reuse a version.
        // A policy this device couldn't read is an emergency: everything is unclassified, so
        // everything is blocked, and the version gate would reject every re-emit (they reuse
        // the version) until the parent happened to edit a rule. Adopt the next snapshot
        // whatever its version — the parent's copy is the truth we lost.
        val recoveringPolicy = settingsStore.consumeCorruption()
        if (recoveringPolicy) {
            dev.walcott.debug.DebugLog.w(TAG, "local policy was unreadable; re-adopting the parent's")
        }
        val newRulesAdopted = recoveringPolicy || SyncEngine.adoptsPolicy(
            snapshot.version, syncStore.current().appliedParentVersion, rotationAdopted,
        )
        // Adopt the parent's rules, flattened to this child's slice. Prefer the parent's
        // PIN; keep the local one while none has synced yet (old parent, or first snapshot
        // not arrived — until then a locally created PIN still guards the gate).
        val incoming = if (newRulesAdopted) {
            runCatching { json.decodeFromString(PolicySettings.serializer(), snapshot.policyJson) }.getOrNull()
        } else {
            null
        }
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

        // Track the parent app's build for the update canary. Monotonic max, so a replayed
        // older parent snapshot can't yank a child's already-allowed target back down.
        if (snapshot.parentVersionCode > s.parentAppVersionCode) {
            syncStore.update {
                it.copy(parentAppVersionCode = maxOf(it.parentAppVersionCode, snapshot.parentVersionCode))
            }
        }

        // Mark off the domain slices the parent confirmed. Doing this before the publishes below
        // means a batch that just completed stops riding the very next message.
        if (snapshot.domainAcks.isNotEmpty() && s.domainBatch != null) {
            syncStore.update { st ->
                st.copy(domainBatch = st.domainBatch?.let { DomainDelivery.acked(it, snapshot.domainAcks) })
            }
            // Send the next slices on the back of the confirmation rather than waiting out the
            // nudge interval: each round moves the batch forward, so a long selection lands in
            // seconds instead of a minute, which is the difference between the parent seeing it
            // while they are still looking and finding it later.
            val next = syncStore.current().domainBatch
            val progressed = (next?.ackedIndexes?.size ?: 0) > s.domainBatch.ackedIndexes.size
            if (progressed && next != null && !next.delivered && !next.abandoned) publishSelf()
        }

        // On-demand: answer a fresh "locate now" addressed to this device (one attempt each).
        val locReq = SyncEngine.freshLocationRequest(snapshot, deviceId, s.appliedLocationRequestMs)
        if (locReq != null) {
            LocationSampler(context).currentFix()?.let { repository.recordLocation(it) }
            syncStore.update { it.copy(appliedLocationRequestMs = locReq.requestedAtMs) }
            publishSelf()
        }

        // Remote fixes from the parent (update now, re-apply policy, nudge for permissions).
        // Run before the grants below so a device whose enforcement had lapsed is repaired
        // first; each command publishes its own acknowledgement.
        applyCommands(snapshot, deviceId)

        // Answer the parent's app-icon requests for apps this child has (a bounded trickle).
        if (snapshot.iconRequests.isNotEmpty()) runCatching { answerIconRequests(snapshot.iconRequests, id) }

        // Record which rules version this child now runs, and echo it promptly so the
        // parent's "updating rules…" indicator clears (a re-emit would take minutes).
        if (newRulesAdopted) {
            syncStore.update {
                it.copy(
                    appliedParentVersion =
                        SyncEngine.rebasedPolicyVersion(snapshot.version, it.appliedParentVersion, rotationAdopted),
                )
            }
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

        if (freshResolutions.isEmpty() && freshBonuses.isEmpty()) {
            if (newRulesAdopted) publishSelf()
            return
        }
        // The child home tells the child what was answered — a denial or a surprise bonus
        // must not just silently vanish or appear.
        val summary = SyncEngine.latestResolutionSummary(freshResolutions, s.pendingRequests, s.pendingAsks)
        val noticeFromResolution = summary?.let {
            NoticeEntry(
                kind = if (it.categoryId.isNotEmpty()) "time" else it.kind,
                approved = it.approved,
                minutes = it.grantedMinutes,
                categoryId = it.categoryId,
                text = it.text,
                atMs = System.currentTimeMillis(),
            )
        }
        val noticeFromBonus = freshBonuses.lastOrNull { it.minutes > 0 }?.let {
            NoticeEntry(
                kind = "bonus", approved = true, minutes = it.minutes,
                categoryId = it.categoryId, atMs = System.currentTimeMillis(),
            )
        }

        val resolvedIds = freshResolutions.map { it.requestId }.toSet()
        val bonusIds = freshBonuses.map { it.id }.toSet()
        syncStore.update {
            it.copy(
                pendingRequests = it.pendingRequests.filterNot { r -> r.requestId in resolvedIds },
                pendingAsks = it.pendingAsks.filterNot { a -> a.requestId in resolvedIds },
                appliedResolutionIds = it.appliedResolutionIds + resolvedIds,
                appliedBonusIds = it.appliedBonusIds + bonusIds,
                lastNotice = noticeFromResolution ?: noticeFromBonus ?: it.lastNotice,
                installExemptionUntilMs = if (approvedAppAsk) {
                    System.currentTimeMillis() + DeviceRestrictions.INSTALL_EXEMPTION_MS
                } else {
                    it.installExemptionUntilMs
                },
            )
        }
        // The pending list shrank (and possibly the rules changed): tell the parent now.
        publishSelf()
    }

    /**
     * Runs any remote commands addressed to this device. Each is marked applied *before* it
     * runs, so a command that kills the process midway (an update install restarts us) can't
     * loop forever; the parent sees the missing acknowledgement instead.
     *
     * Serialized under [commandMutex] and re-reading the applied set inside it, because every
     * incoming message is handled in its own coroutine: when ntfy replays the backlog after a
     * reconnect, the same parent snapshot can be in flight twice, and a check-then-act on a
     * stale set would run an APK install concurrently with itself.
     */
    private suspend fun applyCommands(snapshot: ParentSnapshot, deviceId: String) = commandMutex.withLock {
        val runner by lazy {
            RemoteCommandRunner(
                context,
                repository,
                openInstallForPush = { pkg, id -> openInstallForPush(pkg, id) },
                publishDiagnostics = { publishDiagnostics() },
                denyPanic = { requestId -> denyPanic(requestId) },
            )
        }
        for (command in SyncEngine.newCommands(snapshot, deviceId, syncStore.current().appliedCommandIds)) {
            // Re-check under the lock: a concurrent handler may have claimed it since.
            if (command.id in syncStore.current().appliedCommandIds) continue
            syncStore.update { it.copy(appliedCommandIds = it.appliedCommandIds + command.id) }
            val ack = runner.run(command)
            syncStore.update { it.copy(lastCommandAck = ack, childVersion = it.childVersion + 1) }
            publishSelf()
        }
    }

    /** A feed entry for [snapshot]'s child (see [ParentEvent]); recorded beside each alert. */
    private fun event(type: String, snapshot: ChildSnapshot, detail: String = "", count: Int = 0) = ParentEvent(
        id = UUID.randomUUID().toString(),
        atMs = System.currentTimeMillis(),
        type = type,
        childId = snapshot.childId,
        childName = snapshot.displayName,
        detail = detail,
        count = count,
    )

    private suspend fun applyChildSnapshot(snapshot: ChildSnapshot) = childSnapshotMutex.withLock {
        applyChildSnapshotLocked(snapshot)
    }

    private suspend fun applyChildSnapshotLocked(snapshot: ChildSnapshot) {
        val before = syncStore.current()
        // Alerts are the only thing that leaves this family's screens, so on a device holding
        // several families they say which one they came from. The feed doesn't need it — each
        // family has its own — and neither does a device with a single family, where the suffix
        // would be pure noise (familyLabel returns null there).
        val family = familyLabel()
        val who = SyncNotifications.who(snapshot.displayName, family)
        val prevRequestIds = before.children.flatMap { it.requests }.map { it.requestId }.toSet()
        val prevAskIds = before.children.flatMap { it.asks }.map { it.requestId }.toSet()
        val merged = SyncEngine.mergeChild(before.children.associateBy { it.deviceId }, snapshot).values.toList()
        // A child that acknowledged a command has run it: drop it from the queue so it isn't
        // carried in every subsequent parent snapshot.
        val ackedId = snapshot.lastCommand?.id
        // The ack of a command still in the queue is its completion — feed-worthy exactly once.
        val ackCompleted = snapshot.lastCommand?.takeIf { ack -> before.commands.any { it.id == ack.id } }
        // Fold this snapshot's usage into the per-child daily ledger (see UsageLedger).
        val ledgerKey = UsageLedger.keyOf(snapshot.childId, snapshot.deviceId)
        val ledger = UsageLedger.merge(
            before.usageHistory[ledgerKey].orEmpty(),
            snapshot.history,
            snapshot.epochDay,
            snapshot.usage.sumOf { it.seconds },
        )
        // Track when this device's install window was first seen open, so the hourly reminder
        // can count "open for an hour" from reality rather than from worker cadence.
        val installWindowOpen = snapshot.installExemptionUntilMs > System.currentTimeMillis()
        syncStore.update {
            it.copy(
                children = merged,
                lastSeen = it.lastSeen + (snapshot.deviceId to System.currentTimeMillis()),
                commands = if (ackedId != null) it.commands.filterNot { c -> c.id == ackedId } else it.commands,
                usageHistory = it.usageHistory + (ledgerKey to ledger),
                installWindowSeen = when {
                    installWindowOpen && snapshot.deviceId !in it.installWindowSeen ->
                        it.installWindowSeen + (snapshot.deviceId to System.currentTimeMillis())
                    !installWindowOpen -> it.installWindowSeen - snapshot.deviceId
                    else -> it.installWindowSeen
                },
                installWindowRemindedAt = if (installWindowOpen) {
                    it.installWindowRemindedAt
                } else {
                    it.installWindowRemindedAt - snapshot.deviceId
                },
            ).let { s ->
                if (ackCompleted == null) {
                    s
                } else {
                    s.plusEvent(
                        event(
                            ParentEvent.TYPE_REMOTE_DONE, snapshot,
                            detail = ackCompleted.action, count = if (ackCompleted.ok) 1 else 0,
                        ),
                    )
                }
            }
        }

        // The open-window nag is stateful on screen too: drop it the moment the window closes.
        if (!installWindowOpen && snapshot.deviceId in before.installWindowSeen) {
            SyncNotifications.cancelInstallWindowOpen(context, snapshot.deviceId)
        }

        // Alert once when a child reports enforcement is inactive (not Device Owner and no
        // accessibility blocker); clear the flag when it recovers so a later lapse re-alerts.
        val nowInactive = snapshot.enforcement == EnforcementStatus.NONE
        if (nowInactive && snapshot.deviceId !in before.enforcementNotified) {
            SyncNotifications.notifyEnforcementInactive(context, who, snapshot.deviceId, snapshot.childId)
            syncStore.update {
                it.copy(enforcementNotified = it.enforcementNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_UNPROTECTED, snapshot))
            }
        } else if (!nowInactive && snapshot.enforcement != EnforcementStatus.UNKNOWN &&
            snapshot.deviceId in before.enforcementNotified
        ) {
            syncStore.update { it.copy(enforcementNotified = it.enforcementNotified - snapshot.deviceId) }
        }

        // Alert when a child loses full Device Owner protection but a weaker backend remains
        // (the NONE alert above misses that downgrade). The version guard mirrors mergeChild's
        // accept rule so a replayed older snapshot can't fake a transition.
        val prevChild = before.children.firstOrNull { it.deviceId == snapshot.deviceId }

        // A different app than the approved one landed during an install window and was
        // removed on the spot. Loud and specific: the parent sees exactly WHICH app was tried.
        val wrongAppAck = snapshot.lastCommand
            ?.takeIf { it.detail == RemoteAction.DETAIL_WRONG_APP_REMOVED && it.arg.isNotBlank() }
        if (wrongAppAck != null &&
            (prevChild?.lastCommand?.completedAtMs ?: 0L) != wrongAppAck.completedAtMs
        ) {
            SyncNotifications.notifyWrongApp(
                context, who, wrongAppAck.arg, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.plusEvent(event(ParentEvent.TYPE_WRONG_APP, snapshot, detail = wrongAppAck.arg))
            }
        }
        if (prevChild?.enforcement == EnforcementStatus.DEVICE_OWNER &&
            snapshot.enforcement != EnforcementStatus.DEVICE_OWNER &&
            snapshot.enforcement != EnforcementStatus.UNKNOWN &&
            snapshot.version >= prevChild.version
        ) {
            SyncNotifications.notifyEnforcementDegraded(context, who, snapshot.deviceId, snapshot.childId)
            syncStore.update { it.plusEvent(event(ParentEvent.TYPE_PROTECTION_DEGRADED, snapshot)) }
        }

        // The child asked to be released from Walcott (see PanicProtocol). The loudest thing a
        // family can be told: one alert per two-hourly notice — that drum-beat IS the protocol's
        // guarantee that a living parent finds out — each carrying a one-tap refusal. Keyed by
        // request id AND checkpoint so a re-started request alerts again and a re-emitted
        // snapshot doesn't.
        val panic = snapshot.panic
        val panicKey = panic?.let { "${it.id}@${it.checkpoints}" }
        if (panic != null && before.panicAlerted[snapshot.deviceId] != panicKey) {
            val released = panic.checkpoints >= PanicProtocol.REQUIRED_CHECKPOINTS
            SyncNotifications.notifyPanicRequest(
                context, who, panic, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.copy(panicAlerted = it.panicAlerted + (snapshot.deviceId to panicKey!!))
                    .plusEvent(
                        event(
                            if (released) ParentEvent.TYPE_PANIC_RELEASED else ParentEvent.TYPE_PANIC_REQUEST,
                            snapshot, detail = panic.id, count = PanicProtocol.remainingCheckpoints(panic),
                        ),
                    )
            }
        } else if (panic == null && snapshot.deviceId in before.panicAlerted) {
            // Withdrawn by the child, refused, or killed by the connectivity rule. Either way
            // the parent deserves the closing line as much as the alarm.
            syncStore.update {
                it.copy(panicAlerted = it.panicAlerted - snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_PANIC_CANCELLED, snapshot))
            }
        }

        // Alert once when usage access is off (budgets silently stop counting); re-alert on relapse.
        val usageOff = !snapshot.usageAccessOn
        if (usageOff && snapshot.deviceId !in before.usageAccessNotified) {
            SyncNotifications.notifyUsageAccessLost(context, who, snapshot.deviceId, snapshot.childId)
            syncStore.update {
                it.copy(usageAccessNotified = it.usageAccessNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_USAGE_ACCESS_OFF, snapshot))
            }
        } else if (!usageOff && snapshot.deviceId in before.usageAccessNotified) {
            syncStore.update { it.copy(usageAccessNotified = it.usageAccessNotified - snapshot.deviceId) }
        }

        // The rules ask for a DNS filter and the tunnel isn't up: the blocked domains are
        // resolving normally and nothing else says so — publishing keeps working, the child
        // looks healthy. One alert per outage; the recovery clears it so a relapse re-alerts.
        val filterDown = snapshot.webFilterExpected && !snapshot.webFilterOn
        if (filterDown && snapshot.deviceId !in before.webFilterNotified) {
            SyncNotifications.notifyWebFilterDown(context, who, snapshot.deviceId, snapshot.childId)
            syncStore.update {
                it.copy(webFilterNotified = it.webFilterNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_WEB_FILTER_DOWN, snapshot))
            }
        } else if (!filterDown && snapshot.deviceId in before.webFilterNotified) {
            // Worth a line of its own: "the filter is running again" is the answer to the
            // question the alert asked, and without it the feed only ever records failures.
            syncStore.update {
                it.copy(webFilterNotified = it.webFilterNotified - snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_WEB_FILTER_BACK, snapshot))
            }
        }

        // The child app died of an uncaught exception since the last snapshot. Counted
        // cumulatively by the child, so the news is the GROWTH — that needs no reset handshake
        // and a re-emitted snapshot can't raise it twice. The version guard mirrors mergeChild's
        // accept rule so a replayed older snapshot can't manufacture one either. A child seen
        // for the first time raises nothing: its tally is history this parent never lived.
        val newCrashes = if (prevChild == null || snapshot.version < prevChild.version) {
            0
        } else {
            snapshot.crashTotal - prevChild.crashTotal
        }
        if (newCrashes > 0) {
            SyncNotifications.notifyChildCrashed(
                context, who, newCrashes, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.plusEvent(event(ParentEvent.TYPE_CHILD_CRASHED, snapshot, count = newCrashes))
            }
        }

        // Alert once when mock (spoofed) fixes appear in the trail; clear when it's clean again.
        val hasMock = snapshot.locations.any { it.mock }
        if (hasMock && snapshot.deviceId !in before.mockLocationNotified) {
            SyncNotifications.notifyMockLocation(context, who, snapshot.deviceId, snapshot.childId)
            syncStore.update {
                it.copy(mockLocationNotified = it.mockLocationNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_MOCK_LOCATION, snapshot))
            }
        } else if (!hasMock && snapshot.deviceId in before.mockLocationNotified) {
            syncStore.update { it.copy(mockLocationNotified = it.mockLocationNotified - snapshot.deviceId) }
        }

        // Low battery: warn once when a child drops below 20% unplugged (it may die and go
        // silent); clear only past the recover mark or once charging, so it can't flap.
        val alreadyLow = snapshot.deviceId in before.lowBatteryNotified
        if (HealthAlerts.shouldAlertLowBattery(snapshot.batteryPercent, snapshot.charging, alreadyLow)) {
            SyncNotifications.notifyLowBattery(
                context, who, snapshot.batteryPercent, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.copy(lowBatteryNotified = it.lowBatteryNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_LOW_BATTERY, snapshot, count = snapshot.batteryPercent))
            }
        } else if (alreadyLow && HealthAlerts.clearsLowBattery(snapshot.batteryPercent, snapshot.charging)) {
            syncStore.update { it.copy(lowBatteryNotified = it.lowBatteryNotified - snapshot.deviceId) }
        }

        // Enforcement self-test gap: the child looked healthy but the OS wasn't actually
        // suspending what the rules block. One alert per outage; clears when a later
        // self-test passes so a relapse re-alerts.
        val hasGap = snapshot.enforcementGaps.isNotEmpty()
        if (hasGap && snapshot.deviceId !in before.selfTestNotified) {
            SyncNotifications.notifyEnforcementGap(
                context, who, snapshot.enforcementGaps.size, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.copy(selfTestNotified = it.selfTestNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_ENFORCEMENT_GAP, snapshot, count = snapshot.enforcementGaps.size))
            }
        } else if (!hasGap && snapshot.deviceId in before.selfTestNotified) {
            // The recovery matters as much as the failure on the feed: "blocking works again".
            syncStore.update {
                it.copy(selfTestNotified = it.selfTestNotified - snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_ENFORCEMENT_GAP_CLEARED, snapshot))
            }
        }

        // Clock tamper: the child's clock disagrees with the sync server far beyond drift —
        // the bedtime/budget bypass when the date-time restriction isn't on. One-shot with
        // hysteresis (ClockGuard) so a skew hovering at the threshold can't flap.
        val alreadyClockAlerted = snapshot.deviceId in before.clockTamperNotified
        if (ClockGuard.shouldAlert(snapshot.clockSkewMs, alreadyClockAlerted)) {
            SyncNotifications.notifyClockTamper(
                context, who, snapshot.clockSkewMs, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.copy(clockTamperNotified = it.clockTamperNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_CLOCK_TAMPER, snapshot, detail = snapshot.clockSkewMs.toString()))
            }
        } else if (alreadyClockAlerted && ClockGuard.clears(snapshot.clockSkewMs)) {
            syncStore.update { it.copy(clockTamperNotified = it.clockTamperNotified - snapshot.deviceId) }
        }

        // Network (Wi-Fi/cell) location off: indoor tracking silently stops. Alert once,
        // clear when it comes back. Defaults true, so legacy children never false-alarm.
        val netLocOff = !snapshot.networkLocationOn
        if (netLocOff && snapshot.deviceId !in before.networkLocationNotified) {
            SyncNotifications.notifyNetworkLocationOff(context, who, snapshot.deviceId, snapshot.childId)
            syncStore.update {
                it.copy(networkLocationNotified = it.networkLocationNotified + snapshot.deviceId)
                    .plusEvent(event(ParentEvent.TYPE_INDOOR_LOCATION_OFF, snapshot))
            }
        } else if (!netLocOff && snapshot.deviceId in before.networkLocationNotified) {
            syncStore.update { it.copy(networkLocationNotified = it.networkLocationNotified - snapshot.deviceId) }
        }

        // Notify about newly installed (still unclassified => blocked) apps. The first pass only
        // seeds the seen-set from existing data so updating the app doesn't flood the parent.
        val assignedPackages = settingsStore.current().assignments.keys
        if (!before.seenAppsSeeded) {
            val known = merged.flatMap { it.apps }.map { it.packageName }.toSet() + assignedPackages
            syncStore.update { it.copy(seenAppPackages = it.seenAppPackages + known, seenAppsSeeded = true) }
        } else {
            val newApps = snapshot.apps.filter {
                it.packageName !in before.seenAppPackages && it.packageName !in assignedPackages
            }
            if (newApps.isNotEmpty()) {
                // Always advance the seen-set (so turning the alert on later doesn't flood);
                // only post the notification when the parent opted to be told. The feed entry
                // is unconditional — a new, still-blocked app is always worth a durable trace.
                if (settingsStore.current().newAppAlerts) {
                    SyncNotifications.notifyNewApp(
                        context, who, newApps.first().label, newApps.size - 1, snapshot.deviceId,
                    )
                }
                syncStore.update {
                    it.copy(seenAppPackages = it.seenAppPackages + newApps.map { a -> a.packageName })
                        .plusEvent(
                            event(
                                ParentEvent.TYPE_NEW_APP, snapshot,
                                detail = newApps.first().label, count = newApps.size - 1,
                            ),
                        )
                }
            }
        }

        // The child's own account of what the rules did (bedtime starting, an app running out).
        // Folded in by id, so a re-emitted snapshot repeats nothing; kinds this build doesn't
        // know are skipped rather than shown as a blank line.
        val knownEventIds = before.events.map { it.id }.toSet()
        val freshRuleEvents = snapshot.ruleEvents.filter { it.id.isNotBlank() && it.id !in knownEventIds }
        if (freshRuleEvents.isNotEmpty()) {
            syncStore.update { state ->
                freshRuleEvents.fold(state) { acc, ruleEvent ->
                    val entry = ParentEvent.fromChildEvent(ruleEvent, snapshot.childId, snapshot.displayName)
                    if (entry == null) acc else acc.plusEvent(entry)
                }
            }
        }

        // Alert whenever the child's cumulative wrong-PIN count grows (someone is guessing the PIN).
        val prevPinTotal = before.pinAlertedTotal[snapshot.deviceId] ?: 0
        if (snapshot.pinWrongTotal > prevPinTotal) {
            SyncNotifications.notifyWrongPin(
                context, who, snapshot.pinWrongTotal, snapshot.deviceId, snapshot.childId,
            )
            syncStore.update {
                it.copy(pinAlertedTotal = it.pinAlertedTotal + (snapshot.deviceId to snapshot.pinWrongTotal))
                    .plusEvent(event(ParentEvent.TYPE_WRONG_PIN, snapshot, count = snapshot.pinWrongTotal))
            }
        }

        // Answering from the shade skips the app lock, which is the one thing that stops a child
        // holding the unlocked parent phone from approving their own request. A locked parent app
        // therefore gets a notification that only opens the app, as before.
        val quickAnswer = !identityStore.current().appLock

        val resolved = before.resolutions.map { it.requestId }.toSet()
        val newlyPending = snapshot.requests.map { it.requestId }.toSet() - prevRequestIds - resolved
        if (newlyPending.isNotEmpty()) {
            val req = snapshot.requests.first { it.requestId in newlyPending }
            SyncNotifications.notifyRequest(context, who, req.minutes, req.requestId, quickAnswer)
        }
        for (req in snapshot.requests.filter { it.requestId in newlyPending }) {
            syncStore.update {
                it.plusEvent(event(ParentEvent.TYPE_TIME_REQUEST, snapshot, detail = req.targetLabel, count = req.minutes))
            }
        }

        // Generic asks (app installs, free-form) notify too — they used to be UI-only.
        val newlyAsked = snapshot.asks.map { it.requestId }.toSet() - prevAskIds - resolved
        for (ask in snapshot.asks.filter { it.requestId in newlyAsked }) {
            // Install requests get their own wording — "asks for something" undersells the
            // one ask the parent can answer entirely from the notification shade's tap.
            if (ask.kind == ChildRequest.KIND_INSTALL) {
                SyncNotifications.notifyInstallAsk(context, who, ask.text, ask.requestId, quickAnswer)
            } else {
                SyncNotifications.notifyAsk(context, who, ask.text, ask.requestId, quickAnswer)
            }
            syncStore.update { it.plusEvent(event(ParentEvent.TYPE_ASK, snapshot, detail = ask.text)) }
        }

        // Domain slices from the child's monitor. Acknowledge every slice — including ones for a
        // batch already answered, which is the only thing that lets the child stop resending —
        // and alert once, when the batch is whole and therefore actionable.
        if (snapshot.domainChunks.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            val handledBefore = before.domainsHandled
            val wasComplete = before.domainInbox.filter { it.complete }.map { it.batchId }.toSet()
            syncStore.update {
                it.copy(
                    domainInbox = DomainInbox.merge(
                        inbox = it.domainInbox,
                        incoming = snapshot.domainChunks,
                        deviceId = snapshot.deviceId,
                        childId = snapshot.childId,
                        childName = snapshot.displayName,
                        handled = handledBefore,
                        nowMs = nowMs,
                    ),
                    domainAcks = DomainInbox.withAcks(it.domainAcks, snapshot.domainChunks),
                )
            }
            // Ack promptly: a re-emit is minutes away and the child is nudging every few seconds.
            publishSelf()
            for (entry in syncStore.current().domainInbox) {
                if (!entry.complete || entry.batchId in wasComplete || entry.batchId in handledBefore) continue
                val count = entry.domains()?.size ?: continue
                SyncNotifications.notifyDomainRequest(context, SyncNotifications.who(entry.childName, family), entry.label, count, entry.childId)
                syncStore.update {
                    it.plusEvent(
                        event(ParentEvent.TYPE_DOMAINS, snapshot, detail = entry.label, count = count),
                    )
                }
            }
        }
    }

    // --- Parent: answering a domain request (see DomainInbox) ---

    /**
     * Turn a domain batch into web-filter rules and mark it answered.
     *
     * [familyWide] false scopes the rules to this child alone; [anyApp] false keeps them to the app
     * that resolved them, which is the precise answer the monitor makes possible — the parent knows
     * exactly who asked. Marking it answered is part of the same call: a batch whose rules exist but
     * that still shows as pending would be blocked twice by the next tap.
     */
    suspend fun applyDomainRules(batchId: String, domains: List<String>, familyWide: Boolean, anyApp: Boolean) {
        val entry = syncStore.current().domainInbox.firstOrNull { it.batchId == batchId } ?: return
        val clean = domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isNotEmpty()) {
            // Through the repository, not the store: a rule written without bumping the policy
            // version is a rule no child ever hears about.
            val scopeToApp = entry.packageName.takeUnless { anyApp }
            repository.updateSettings { settings ->
                if (familyWide) settings.withFamilyDomainRules(clean, scopeToApp)
                else settings.withChildDomainRules(entry.childId, clean, scopeToApp)
            }
        }
        markDomainBatchHandled(batchId)
    }

    /** Parent: the request is not worth acting on. Gone from the home, and it does not come back. */
    suspend fun discardDomainBatch(batchId: String) = markDomainBatchHandled(batchId)

    private suspend fun markDomainBatchHandled(batchId: String) {
        syncStore.update {
            it.copy(
                domainsHandled = DomainInbox.withHandled(it.domainsHandled, batchId),
                domainInbox = it.domainInbox.filterNot { e -> e.batchId == batchId },
            )
        }
    }

    companion object {
        private const val TAG = "WalcottSync"
        /**
         * Safety cap on the pushed-install window: the window normally closes on the first
         * install (see [closeInstallWindow]), so this only bounds the "nothing installed" case.
         * Kept short to minimize the opportunity to sneak in an alternative app.
         */
        private const val INSTALL_PUSH_EXEMPTION_MS = 5 * 60 * 1000L
        // Re-emits only heal lost messages: real changes (settings edits, requests,
        // resolutions) publish immediately, so a long interval costs little freshness
        // and saves a lot of radio/battery.
        private const val RE_EMIT_MILLIS = 15 * 60 * 1000L

        /**
         * How settled a socket must be before a foreground/background change is allowed to
         * replace it. Someone bouncing between Walcott and another app would otherwise pay a
         * TLS handshake each way, which costs more than the pings the switch is about.
         */
        private const val MODE_SWITCH_MIN_SOCKET_AGE_MS = 30_000L
        /** How many app icons a child renders+sends per parent request (the rest trickle next cycle). */
        private const val ICON_RENDER_LIMIT = 8
        /** How often the bounded icon-request window rotates when more icons are missing than fit it. */
        private const val ICON_REQUEST_ROTATE_MS = 5 * 60 * 1000L
        /** Ignore skew changes smaller than this (network-delay jitter) to spare DataStore. */
        private const val CLOCK_SKEW_RECORD_DELTA_MS = 60_000L
        /** Log lines offered to the diagnostics report before DiagFit trims to the size cap. */
        private const val DIAG_LOG_LINES = 80
        /** One backup rewrite per burst of edits (a wizard changes many settings in seconds). */

        /**
         * How often a child re-offers the unconfirmed slices of a domain batch. Aggressive next to
         * [RE_EMIT_MILLIS] because the parent has just handed the phone back and is looking at
         * their own screen: this is the one minute in which the request matters.
         */
        private const val DOMAIN_NUDGE_MS = 20_000L
        /** At most one message a minute for wall entries, however busy the rules get. */
        private const val RULE_EVENT_PUBLISH_MIN_MS = 60_000L
        /** How far a restore jumps the version counter past the backup's (see restoreBackup). */
        private const val RESTORE_VERSION_LEAP = 1_000_000L

        /**
         * The first build whose parent side understands an emergency release. A child refuses
         * to start one against an older parent: that parent would ignore the field entirely,
         * turning a request the family is supposed to see and be able to refuse into a silent
         * 24-hour countdown. Children only self-update up to the parent's build (the canary),
         * so in practice a child new enough to offer this already has a parent new enough.
         */
        const val PANIC_MIN_PARENT_VERSION = 53

        /** [NoticeEntry.kind] for a request that ran out of time unanswered. */
        const val NOTICE_EXPIRED = "expired"
    }
}
