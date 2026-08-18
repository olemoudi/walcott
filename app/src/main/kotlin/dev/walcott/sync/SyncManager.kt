package dev.walcott.sync

import android.content.Context
import android.os.Build
import dev.walcott.data.BlockKinds
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
import dev.walcott.notifications.NotificationLog
import dev.walcott.location.LocationSampler
import dev.walcott.rules.EarnGrant
import dev.walcott.rules.IdleEarnConfig
import dev.walcott.rules.IdleEarnEngine
import dev.walcott.ui.format.humanize
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

    /**
     * The relay the family is moving AWAY from, kept connected during a migration.
     *
     * The parent publishes to both while any device might still be listening to the old one, and
     * listens on both so a straggler's acknowledgement arrives wherever it is sent from. Closed as
     * soon as every known device has confirmed the move, or when the window runs out (see
     * [RemoteAction.RELAY_MIGRATION_WINDOW_MS]).
     */
    private var legacyTransport: SyncTransport? = null
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
     *
     * Repeats collapse the same way ([SyncEngine.newestPerTarget]): an impatient child asking
     * three times about one app is one card, not three — three of which would let the parent
     * grant the same request three times over.
     */
    val pendingRequests: StateFlow<List<PendingRequest>> = syncStore.state.map { s ->
        val now = System.currentTimeMillis()
        val resolved = s.resolutions.map { it.requestId }.toSet()
        s.children.flatMap { child ->
            SyncEngine.newestPerTarget(child.requests).map { request ->
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
        connectLegacy(id)
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
                    runCatching { onPolicyEdited() }
                        .onFailure { dev.walcott.debug.DebugLog.e(TAG, "scheduling the policy push failed", it) }
                }
            }
        } else {
            null
        }
        // A burst of edits that was still being held when the process died: the child would
        // reject it for ever after (its version never went up), so push it now.
        if (id.role == Role.PARENT && syncStore.current().pendingPolicyPush) {
            dev.walcott.debug.DebugLog.i(TAG, "publishing a rule edit held across a restart")
            runCatching { flushPolicyPush() }
                .onFailure { dev.walcott.debug.DebugLog.e(TAG, "flushing the held policy failed", it) }
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
        // Putting the app away is the clearest possible "I have finished editing": there is
        // nothing left to coalesce with, so the held policy goes now instead of making a child
        // wait out the rest of a window nobody is going to add to.
        if (!nowInteractive) {
            runCatching { flushPolicyPush() }
                .onFailure { dev.walcott.debug.DebugLog.e(TAG, "flushing on background failed", it) }
        }
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

    /**
     * Opens (or closes) the connection to the relay the family is moving away from.
     *
     * Idempotent and called from [connect], so a process restart mid-migration puts the second
     * socket back rather than silently finishing the migration early for the phones that have not
     * moved yet.
     */
    private suspend fun connectLegacy(id: FamilyIdentity) {
        legacyTransport?.close()
        legacyTransport = null
        if (!migrationOpen(id)) return
        dev.walcott.debug.DebugLog.i(TAG, "keeping ${id.previousNtfyServer} alive for phones still on it")
        legacyTransport = NtfyTransport(
            id.previousNtfyServer,
            id.topic,
            client = dev.walcott.net.Http.webSocketClient,
            // Its own cursor would need its own storage; a migration is short and the messages
            // that matter on this socket are acknowledgements, which arrive live.
            sinceProvider = { 0 },
        ).also { t ->
            t.connect { raw, timeSec ->
                scope.launch {
                    runCatching { handleIncoming(raw, id, timeSec) }
                        .onFailure { dev.walcott.debug.DebugLog.e(TAG, "handleIncoming (legacy) failed", it) }
                }
            }
        }
    }

    /** Whether a migration is still running: a previous relay, inside its window. */
    private fun migrationOpen(id: FamilyIdentity): Boolean =
        id.role == Role.PARENT && id.previousNtfyServer.isNotBlank() &&
            System.currentTimeMillis() - id.relayMigratedAtMs < RemoteAction.RELAY_MIGRATION_WINDOW_MS

    /**
     * Ends the migration once nobody is left behind — every device the parent knows about has
     * acknowledged the move — or once the window has run out.
     *
     * Called wherever a device's acknowledgement lands. Closing early matters: until it happens
     * every message this family sends is sent twice.
     */
    private suspend fun closeMigrationIfDone() {
        val id = identityStore.current()
        if (id.previousNtfyServer.isBlank()) return
        val devices = syncStore.current().children
        val allMoved = devices.isNotEmpty() && devices.all {
            it.lastCommand?.action == RemoteAction.SET_RELAY && it.lastCommand?.ok == true
        }
        val expired = System.currentTimeMillis() - id.relayMigratedAtMs >= RemoteAction.RELAY_MIGRATION_WINDOW_MS
        if (!allMoved && !expired) return
        dev.walcott.debug.DebugLog.w(
            TAG,
            if (allMoved) "every phone has moved relay; letting the old one go" else "relay migration window closed",
        )
        legacyTransport?.close()
        legacyTransport = null
        identityStore.save(identityStore.current().copy(previousNtfyServer = "", relayMigratedAtMs = 0))
    }

    /**
     * Moves this family — parent AND children — to a different relay.
     *
     * The order is the whole of it. The instruction is queued and published while the parent is
     * still on the OLD relay, because that is where every child is listening; only then does the
     * parent move, and it keeps the old relay connected until the last device has said it moved
     * too. A child that is off for a week comes back to the address it knows and finds the
     * instruction waiting.
     *
     * Refused while another migration is still in flight: two overlapping moves would leave the
     * fleet spread across three relays with only one of them being listened to.
     */
    suspend fun migrateRelay(server: String): RelayChangeResult {
        val id = identityStore.current()
        if (id.role != Role.PARENT) return RelayChangeResult.HAS_CHILDREN
        val normalized = RelayServer.normalize(server) ?: return RelayChangeResult.INVALID
        if (normalized == id.ntfyServer) return RelayChangeResult.OK
        if (migrationOpen(id)) return RelayChangeResult.MIGRATION_RUNNING

        val devices = syncStore.current().children.map { it.deviceId }
        dev.walcott.debug.DebugLog.w(TAG, "moving this family to $normalized (${devices.size} device(s) to tell)")
        // Told first, on the relay they are listening to. sendCommand publishes as it queues.
        for (deviceId in devices) {
            runCatching { sendCommand(deviceId, RemoteAction.SET_RELAY, arg = normalized) }
                .onFailure { dev.walcott.debug.DebugLog.e(TAG, "could not queue the move for $deviceId", it) }
        }
        identityStore.save(
            id.copy(
                ntfyServer = normalized,
                previousNtfyServer = id.ntfyServer,
                relayMigratedAtMs = System.currentTimeMillis(),
            ),
        )
        // The cursor belongs to the old server's stream; it means nothing on the new one.
        syncStore.update { it.copy(ntfySinceSec = 0) }
        sinceCache = 0
        connect(identityStore.current())
        publishSelf()
        // A family with no devices yet has nobody to wait for.
        closeMigrationIfDone()
        return RelayChangeResult.OK
    }

    /** Child: adopts the relay the parent named, and reconnects there. */
    private suspend fun adoptRelay(server: String) {
        val normalized = RelayServer.normalize(server) ?: return
        val id = identityStore.current()
        if (normalized == id.ntfyServer) return
        dev.walcott.debug.DebugLog.w(TAG, "the parent moved this family to $normalized")
        identityStore.save(id.copy(ntfyServer = normalized))
        syncStore.update { it.copy(ntfySinceSec = 0) }
        sinceCache = 0
        connect(identityStore.current())
        publishSelf()
    }

    /**
     * What the parent's screen shows while a migration runs: where the family is going, and which
     * phones have not been heard from since. Null when nothing is in flight.
     */
    data class RelayMigration(val from: String, val to: String, val moved: Int, val total: Int)

    val relayMigration: StateFlow<RelayMigration?> =
        kotlinx.coroutines.flow.combine(identityStore.identity, syncStore.state) { id, sync ->
            if (id.previousNtfyServer.isBlank()) {
                null
            } else {
                RelayMigration(
                    from = id.previousNtfyServer,
                    to = id.ntfyServer,
                    moved = sync.children.count {
                        it.lastCommand?.action == RemoteAction.SET_RELAY && it.lastCommand?.ok == true
                    },
                    total = sync.children.size,
                )
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

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
        val topic = newTopic()
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

    /**
     * A fresh family topic: 128 random bits, base64url, and nothing that says what wrote it.
     *
     * Two things it must be. **Unguessable**, because the topic IS the family's address on a
     * public relay and anyone who knows it can read the (encrypted) traffic and publish noise into
     * it — this is 128 bits where the old form carried about 60, taken from the printed form of a
     * UUID. And **anonymous**: it used to start with "walcott-", which made every family's traffic
     * identifiable as this app's at a glance, and a relay operator's only means of acting on all
     * of it at once. There is no reason for a topic to say anything at all.
     *
     * Existing families keep the topic they were created with — it is written into every child's
     * pairing and every backup file — and can shed it by migrating (see [migrateRelay]).
     */
    private fun newTopic(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return FamilyCrypto.toB64(bytes)
    }

    /** Outcome of trying to move a family to a different relay (see [setRelayServer]). */
    enum class RelayChangeResult { OK, INVALID, HAS_CHILDREN, MIGRATION_RUNNING }

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
        // Offer the guided setup again: a new family means new rules — possibly a web filter or
        // tracking the last one never asked for — and a different adult holding this phone.
        runCatching { dev.walcott.setup.DeviceSetupStore(context).resetJourney() }
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

    /** Its human name, as the parent's device sent it; "" before it is known. */
    val pendingInstallLabel: StateFlow<String> =
        syncStore.state.map { it.pendingInstallLabel }.stateIn(scope, SharingStarted.Eagerly, "")

    /**
     * Opens the tight, self-closing window for a parent-pushed install of [pkg]. The safety
     * cap is short: [closeInstallWindow] normally slams it shut on the first install, so this
     * ceiling only matters if nothing installs at all. [reopenInstallWindow] re-extends it
     * whenever the child actually engages, so this first window expiring costs nothing.
     */
    suspend fun openInstallForPush(pkg: String, commandId: String, label: String = "") {
        val until = System.currentTimeMillis() + INSTALL_PUSH_EXEMPTION_MS
        syncStore.update { state ->
            state.copy(
                installExemptionUntilMs = until,
                pendingInstallPackage = pkg,
                pendingInstallCommandId = commandId,
                // Kept across a reopen: the second call comes from a tap, which knows no label.
                pendingInstallLabel = label.ifBlank { state.pendingInstallLabel },
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
     *
     * Closing is all this does. What landed — the approved app, something else, or both — is
     * judged by [reconcileInstalls], which runs right after and, unlike a window, is still
     * there when the second app arrives three seconds later.
     */
    suspend fun closeInstallWindow(installedPkg: String? = null) {
        val s = syncStore.current()
        if (s.pendingInstallPackage.isEmpty()) return
        val pushedLanded = installedPkg == s.pendingInstallPackage && s.pendingInstallCommandId.isNotEmpty()
        syncStore.update {
            it.copy(
                installExemptionUntilMs = 0,
                pendingInstallPackage = "",
                pendingInstallCommandId = "",
                pendingInstallLabel = "",
                // Remembered so an approved app that lands late is still recognised as approved
                // (see InstallGuard.LATE_LANDING_GRACE_MS).
                lastWindowPackage = s.pendingInstallPackage,
                lastWindowClosedAtMs = System.currentTimeMillis(),
                lastCommandAck = if (pushedLanded) {
                    CommandAck(
                        id = s.pendingInstallCommandId,
                        action = RemoteAction.INSTALL_APP,
                        ok = true,
                        detail = RemoteAction.DETAIL_INSTALLED,
                        completedAtMs = System.currentTimeMillis(),
                        arg = s.pendingInstallPackage,
                    )
                } else {
                    it.lastCommandAck
                },
                childVersion = if (pushedLanded) it.childVersion + 1 else it.childVersion,
            )
        }
        InstallPromptNotifications.cancel(context, s.pendingInstallPackage)
        // Synchronous re-arm: don't wait for the settings/exemption collector to react.
        runCatching {
            DeviceRestrictions.apply(context, settingsStore.current().deviceRestrictions, installExemptUntilMs = 0)
        }
        if (pushedLanded) publishSelf()
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

    // --- Install guard: what turned up that nobody approved (see InstallGuard) ---

    /** Apps quarantined right now; the enforcement loop keeps exactly these suspended. */
    val quarantined: StateFlow<Set<String>> = syncStore.state
        .map { s -> s.unauthorizedApps.map { it.pkg }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val enforcer by lazy { dev.walcott.enforcement.Enforcer(context) }
    private val installGuardMutex = Mutex()

    /** A package appeared or disappeared: close any window it answers, then judge what is here. */
    suspend fun onPackageChanged(added: String?) {
        if (added != null) closeInstallWindow(added)
        reconcileInstalls()
    }

    /**
     * Compares what is installed against what was, and answers for the difference.
     *
     * This is the guarantee behind "approving one app installs one app". The install block is
     * all-or-nothing, so during any window the child could tap Install on something else, queue
     * three downloads at once, or let Play flush a queue it had been sitting on — and the old
     * close-on-first-install only ever caught the first of them. Here, everything that appeared
     * unapproved is suspended on the spot and reported to the parent, and the case stays open
     * (retried on every package event and every heartbeat) until the app is really gone or the
     * parent says it may stay.
     *
     * Runs on package events, on service start and on the heartbeat, so a device that was off,
     * killed by an OEM battery saver, or simply not listening still reconciles: the check does
     * not depend on having witnessed the install.
     *
     * The publish is deliberately outside the lock: it is a network call, and the package
     * receiver that fires during it has better things to do than wait for a socket.
     */
    suspend fun reconcileInstalls() {
        if (installGuardMutex.withLock { withContext(Dispatchers.IO) { reconcileLocked() } }) {
            publishSelf()
        }
    }

    /** The reconciliation itself; returns whether the parent needs to hear about it. */
    private suspend fun reconcileLocked(): Boolean {
        // Null, not empty: a device with no user apps at all is a normal state to record as the
        // baseline. Only a PackageManager that could not answer is a reason to judge nothing.
        val installed = repository.inventory.userPackages() ?: return false
        val s = syncStore.current()
        val now = System.currentTimeMillis()
        val blanketWindow = s.installExemptionUntilMs > now && s.pendingInstallPackage.isEmpty()
        // Device Owner is part of the question, not just of the answer: the install block is a
        // user restriction only a Device Owner can set, so anywhere else there is no block to
        // violate and every install is legitimate. Without this, the same policy read on a
        // PARENT's phone — which holds the same settings — would quarantine the parent's own
        // apps, and an accessibility-only child would be punished for a block it never had.
        val installsBlocked = enforcer.isDeviceOwner() &&
            DeviceRestrictions.KEY_INSTALLS in settingsStore.current().deviceRestrictions

        // Seed on first sight, and keep the baseline current whenever there is nothing to judge:
        // neither can be allowed to INDICT what it finds. Closing cases is a different matter —
        // a pass that judges nobody still has to let people go (see InstallGuard.retained), or a
        // withdrawn rule and a finished case both go on suspending apps with nothing left to
        // clear them.
        if (!s.installBaselineSeeded || !InstallGuard.guarding(installsBlocked, blanketWindow)) {
            val keep = InstallGuard.retained(s.unauthorizedApps, installed, installsBlocked)
            val freed = s.unauthorizedApps.filterNot { entry -> keep.any { it.pkg == entry.pkg } }
            syncStore.update {
                it.copy(
                    knownPackages = installed,
                    installBaselineSeeded = true,
                    unauthorizedApps = keep,
                    childVersion = if (freed.isEmpty()) it.childVersion else it.childVersion + 1,
                )
            }
            if (freed.isEmpty()) return false
            dev.walcott.debug.DebugLog.i(TAG, "quarantine released: ${freed.joinToString { it.pkg }}")
            // Only the ones still here can be un-suspended; the rest are gone, which is why
            // their case closed in the first place.
            runCatching { enforcer.release(freed.map { it.pkg }.filter { it in installed }) }
            return true
        }

        val approved = InstallGuard.approved(
            s.pendingInstallPackage, s.lastWindowPackage, s.lastWindowClosedAtMs, now,
        )
        val fresh = InstallGuard
            .fresh(installed, s.knownPackages, approved, s.unauthorizedApps.map { it.pkg }.toSet())
            .map { UnauthorizedApp(pkg = it, label = repository.inventory.label(it) ?: it, atMs = now) }
        val dropped = InstallGuard.overflow(s.unauthorizedApps, fresh, installed)
        if (dropped > 0) {
            dev.walcott.debug.DebugLog.w(TAG, "quarantine at capacity: $dropped case(s) not tracked")
        }
        val open = InstallGuard.nextQuarantine(s.unauthorizedApps, fresh, installed)
        if (open.isEmpty() && s.unauthorizedApps.isEmpty()) {
            syncStore.update { it.copy(knownPackages = installed) }
            return false
        }
        if (fresh.isNotEmpty()) {
            dev.walcott.debug.DebugLog.w(
                TAG, "unauthorized install(s): ${fresh.joinToString { it.pkg }} — quarantining",
            )
        }

        // Suspend before removing: it takes effect immediately, it survives a refused or
        // interrupted uninstall, and it is the part that can be taken back if the parent
        // decides the app may stay.
        val suspended = enforcer.quarantine(open.map { it.pkg })
        val updated = open.map { entry ->
            runCatching { silentUninstall(entry.pkg) }
            val attempts = entry.removalAttempts + 1
            if (attempts == STUCK_REMOVAL_ATTEMPTS) {
                dev.walcott.debug.DebugLog.w(TAG, "${entry.pkg} survived $attempts removal attempts")
            }
            entry.copy(suspended = entry.pkg in suspended, removalAttempts = attempts)
        }
        val resolved = s.unauthorizedApps.filter { it.pkg !in installed }
        if (resolved.isNotEmpty()) {
            dev.walcott.debug.DebugLog.i(TAG, "quarantine cleared: ${resolved.joinToString { it.pkg }}")
        }
        syncStore.update { it.copy(knownPackages = installed, unauthorizedApps = updated) }

        // Only tell the parent when the answer changed. A retry that failed the same way it
        // failed fifteen minutes ago is not news, and a snapshot per heartbeat for the lifetime
        // of a stuck case would be the loudest thing on the channel.
        val changed = fresh.isNotEmpty() || resolved.isNotEmpty() ||
            updated.any { entry -> s.unauthorizedApps.any { it.pkg == entry.pkg && it.suspended != entry.suspended } }
        if (changed) syncStore.update { it.copy(childVersion = it.childVersion + 1) }
        return changed
    }

    /**
     * Remote fix: remove an app now (the parent's "remove" answer). Works on any user app, not
     * only a quarantined one — "get that off their phone" is the same request either way.
     */
    suspend fun removeAppNow(pkg: String): Boolean = installGuardMutex.withLock {
        withContext(Dispatchers.IO) { removeAppLocked(pkg) }
    }

    private suspend fun removeAppLocked(pkg: String): Boolean {
        val installed = repository.inventory.userPackages() ?: return false
        if (pkg.isBlank() || pkg !in installed) return false
        // Suspended first so the app is unusable from this instant, whatever the uninstall does.
        val suspended = runCatching { enforcer.quarantine(listOf(pkg)) }.getOrDefault(emptySet())
        runCatching { silentUninstall(pkg) }
        // Tracked from here on even if it was never quarantined, so a removal the OS refuses is
        // retried on the next pass and visible, instead of a button that silently did nothing.
        val entry = syncStore.current().unauthorizedApps.firstOrNull { it.pkg == pkg }
        syncStore.update {
            it.copy(
                unauthorizedApps = InstallGuard.nextQuarantine(
                    it.unauthorizedApps.filterNot { open -> open.pkg == pkg },
                    listOf(
                        UnauthorizedApp(
                            pkg = pkg,
                            label = entry?.label ?: repository.inventory.label(pkg) ?: pkg,
                            atMs = entry?.atMs ?: System.currentTimeMillis(),
                            suspended = pkg in suspended,
                            removalAttempts = (entry?.removalAttempts ?: 0) + 1,
                        ),
                    ),
                    installed,
                ),
                childVersion = it.childVersion + 1,
            )
        }
        return true
    }

    /** Remote fix: let a quarantined app stay (the parent's "allow" answer). */
    suspend fun allowAppNow(pkg: String): Boolean = installGuardMutex.withLock {
        val s = syncStore.current()
        if (s.unauthorizedApps.none { it.pkg == pkg }) return@withLock false
        syncStore.update {
            it.copy(
                unauthorizedApps = it.unauthorizedApps.filterNot { entry -> entry.pkg == pkg },
                // Into the baseline, or the next reconciliation would quarantine it all over again.
                knownPackages = it.knownPackages + pkg,
                childVersion = it.childVersion + 1,
            )
        }
        runCatching { enforcer.release(listOf(pkg)) }
        true
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
        // Yesterday's answer, over minutes that expired with yesterday (see
        // [SyncEngine.noticeExpired]). Dropped here rather than only hidden on the screen, so it
        // stops travelling in the state at all — including to a child who never opens the app.
        if (s.lastNotice?.let { SyncEngine.noticeExpired(it.atMs, now) } == true) {
            syncStore.update { it.copy(lastNotice = null) }
        }
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
        // The card and the notification are two views of one question: answering either must
        // retire both. Without this, a parent who answered in the app kept a live Approve button
        // in their own shade — harmless to tap (the resolution is idempotent) but an invitation
        // to wonder whether the first answer took.
        SyncNotifications.cancelRequest(context, requestId)
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
    /**
     * What became of [requestId] in THIS family, for a notification tap that finds no card to
     * answer. The decision itself is [SyncEngine.requestState]; this only reads the store.
     */
    suspend fun requestState(requestId: String): SyncEngine.RequestState {
        val state = syncStore.current()
        val createdAt = buildMap {
            state.children.forEach { child ->
                child.requests.forEach { put(it.requestId, it.createdAtEpochMs) }
                child.asks.forEach { put(it.requestId, it.createdAtEpochMs) }
            }
        }
        return SyncEngine.requestState(
            requestId = requestId,
            resolvedIds = state.resolutions.mapTo(mutableSetOf()) { it.requestId },
            createdAtByRequestId = createdAt,
            nowMs = System.currentTimeMillis(),
        )
    }

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
        // The label travels with it: the app isn't installed on the child yet, so its own
        // package manager has nothing to show but the package name.
        sendCommand(owner.deviceId, RemoteAction.INSTALL_APP, arg = ask.pkg, label = ask.text)
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
    suspend fun sendCommand(targetDeviceId: String, action: String, arg: String = "", label: String = "") {
        val now = System.currentTimeMillis()
        syncStore.update { s ->
            s.copy(
                parentVersion = s.parentVersion + 1,
                commands = SyncEngine.withCommand(
                    s.commands,
                    RemoteCommand(UUID.randomUUID().toString(), targetDeviceId, action, now, arg, label),
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
                setupPendingSince = s.setupPendingSince - deviceId,
                setupRemindedAt = s.setupRemindedAt - deviceId,
                diagReports = s.diagReports - deviceId,
                diagHistory = s.diagHistory - deviceId,
                // Only legacy devices ledger under their deviceId; child-keyed history stays.
                usageHistory = s.usageHistory - deviceId,
                usageByApp = s.usageByApp - deviceId,
                blockLedgers = s.blockLedgers - deviceId,
            )
        }
    }

    /**
     * Parent frees a supervised phone for good (see [RemoteAction.RELEASE_DEVICE]).
     *
     * Queued like any other command, so a phone that is off or out of coverage is freed when it
     * next comes back rather than never — which matters, because until it does that phone is
     * still enforcing rules nobody is managing any more. The device row is deliberately kept
     * until the child acknowledges: the parent has to be able to see that it is still pending,
     * and the acknowledgement is what removes it (see [applyChildSnapshotLocked]).
     */
    suspend fun releaseChildDevice(targetDeviceId: String) {
        dev.walcott.debug.DebugLog.w(TAG, "asking $targetDeviceId to release itself")
        sendCommand(targetDeviceId, RemoteAction.RELEASE_DEVICE)
    }

    /** The devices this family has heard from for [childId] — who a release has to be sent to. */
    suspend fun devicesOfChild(childId: String): List<String> =
        syncStore.current().children.filter { it.childId == childId }.map { it.deviceId }

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

    private var policyPushJob: kotlinx.coroutines.Job? = null

    /**
     * A rule was edited: mark it pending and (re)start the hold (see [dev.walcott.data.PolicyPush]).
     *
     * The edit is already saved locally — the screens show it, and the child's copy is what lags.
     * Everything here is about WHEN it goes on the wire.
     */
    private suspend fun onPolicyEdited() {
        val now = System.currentTimeMillis()
        val current = syncStore.current()
        // The burst keeps its own start: the ceiling is measured from the oldest edit still
        // waiting, not from this one, or a parent who keeps adjusting would keep moving it.
        val startedAt = if (current.pendingPolicyPush && current.policyHoldStartedAtMs > 0) {
            current.policyHoldStartedAtMs
        } else {
            now
        }
        syncStore.update {
            it.copy(
                pendingPolicyPush = true,
                policyHoldStartedAtMs = startedAt,
                // What the reminder ladder measures a saved backup against: a file older than
                // the last edit is stale and worth nagging about.
                lastPolicyEditAtMs = now,
            )
        }
        val hold = dev.walcott.data.PolicyPush.remainingMs(startedAt, now, now)
        dev.walcott.debug.DebugLog.i(TAG, "rule edit held for ${hold / 1000}s")
        policyPushJob?.cancel()
        policyPushJob = scope.launch {
            delay(hold)
            // [publishHeldPolicy], not [flushPolicyPush]: the latter cancels this very job
            // first, and cancelling the coroutine you are running in kills it at its next
            // suspension point — which is the store read on the line after. So the timer
            // aborted before writing anything, every single time, and a parent's rule edit
            // only ever went out when they left the app (setInteractive) or relaunched it.
            // It looked exactly like a slow window; it was a window that never fired.
            runCatching { publishHeldPolicy() }
                .onFailure { dev.walcott.debug.DebugLog.e(TAG, "publishing the held policy failed", it) }
        }
    }

    /**
     * Stops the timer and publishes whatever is being held, now. For the callers that are not
     * the timer: the parent putting the app away (they have plainly stopped editing), and
     * start-up for a burst interrupted by a process death.
     */
    suspend fun flushPolicyPush() {
        policyPushJob?.cancel()
        policyPushJob = null
        publishHeldPolicy()
    }

    /** The publish itself, with no opinion about the timer that may or may not be running. */
    private suspend fun publishHeldPolicy() {
        val held = syncStore.current()
        if (!held.pendingPolicyPush) return
        // How long the oldest edit in this burst actually waited. The hold is invisible when it
        // works, so "it feels slow" has no evidence behind it either way without this line.
        held.policyHoldStartedAtMs.takeIf { it > 0 }?.let { startedAt ->
            val waited = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            dev.walcott.debug.DebugLog.i(TAG, "publishing rule edits held for ${waited / 1000}s")
        }
        // Bump the version and clear the flag in ONE write, then publish.
        //
        // The bump is what makes the edit adoptable at all — a child refuses a policy whose
        // version hasn't moved — so once it has happened, even a publish that never leaves the
        // phone is repaired by the next re-emit. That is what makes it safe to stop calling this
        // pending, and stopping is necessary: the publish below is what records the deployed
        // policy, and it only does so once nothing is being held.
        syncStore.update {
            it.copy(
                parentVersion = it.parentVersion + 1,
                pendingPolicyPush = false,
                policyHoldStartedAtMs = 0,
            )
        }
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

    /**
     * Drops the resolutions and bonuses that have stopped meaning anything (see [ParentFit]).
     *
     * Runs on every parent publish, which is the only place that needs them to be small and the
     * only moment guaranteed to happen. Writes only when something actually goes, so a quiet
     * family never touches DataStore for it.
     */
    private suspend fun pruneAnswers() {
        val state = syncStore.current()
        if (state.resolutions.isEmpty() && state.bonuses.isEmpty()) return
        val keptResolutions = ParentFit.keptResolutions(state.resolutions, System.currentTimeMillis())
        val liveBonuses = ParentFit.liveBonuses(state.bonuses, LocalDate.now().toEpochDay())
        if (keptResolutions.size == state.resolutions.size && liveBonuses.size == state.bonuses.size) return
        dev.walcott.debug.DebugLog.i(
            TAG,
            "forgot ${state.resolutions.size - keptResolutions.size} old answer(s) and " +
                "${state.bonuses.size - liveBonuses.size} bonus(es) nobody can still apply",
        )
        syncStore.update { it.copy(resolutions = keptResolutions, bonuses = liveBonuses) }
    }

    private suspend fun publishSelfOrThrow() {
        val id = identityStore.current()
        val transport = transport ?: return
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        when (id.role) {
            Role.PARENT -> {
                // Retire the answers that can no longer do anything BEFORE reading the state to
                // publish, so the message and the store shrink together (see ParentFit). Without
                // this both grew for the lifetime of the install, and the message hit the relay's
                // cap — which is not a degraded family, it is a family whose rules stop moving.
                pruneAnswers()
                val state = syncStore.current()
                // The PIN hash/salt travel with the policy so the parent's PIN also guards
                // enrolled child devices (gate + leaving child mode).
                val settings = settingsStore.current()
                // Ask for icons of apps shown in the list that aren't cached yet; empties out.
                // The rotation slides the bounded request window over time, so a package no
                // child can serve can't starve the ones behind it.
                val shownApps = state.children.flatMap { c -> c.apps.map { it.packageName } }
                val nowMs = System.currentTimeMillis()
                val iconRequests = IconSync.toRequest(
                    shownApps,
                    iconStore.cachedAmong(shownApps) + IconSync.suppressed(state.iconsUnrenderable, nowMs),
                    rotation = (nowMs / ICON_REQUEST_ROTATE_MS).toInt(),
                )
                val snapshot = ParentSnapshot(
                    version = state.parentVersion,
                    policyJson = json.encodeToString(PolicySettings.serializer(), settings),
                    // What travels is only what a child could still apply; the parent goes on
                    // REMEMBERING answers for much longer, so its own screens can still say who
                    // answered what (see ParentFit.RESOLUTION_KEEP_MS).
                    resolutions = ParentFit.liveResolutions(state.resolutions, nowMs),
                    bonuses = state.bonuses,
                    locationRequests = state.locationRequests,
                    commands = state.commands,
                    iconRequests = iconRequests,
                    domainAcks = state.domainAcks,
                    // The parent is the fleet's update canary: children only follow up to this.
                    parentVersionCode = BuildConfig.VERSION_CODE,
                )
                val rotation = id.rotationCertB64.takeIf { it.isNotBlank() }?.let { KeyRotation.decode(it) }
                // Measured rather than hoped: an oversized parent message is refused by the relay
                // every single time, so the rules would stop reaching every child at once and
                // nothing would ever say why (see ParentFit).
                val fitted = ParentFit.encode(snapshot, familyKey, signingKey(id), rotation)
                fitted.degraded?.let {
                    dev.walcott.debug.DebugLog.w(TAG, "parent snapshot did not fit; dropped $it")
                }
                if (fitted.oversize != state.policyTooLarge) {
                    syncStore.update { st -> st.copy(policyTooLarge = fitted.oversize) }
                }
                if (fitted.oversize) {
                    dev.walcott.debug.DebugLog.e(
                        TAG,
                        "these rules are too large for one relay message (${fitted.encoded.length} bytes)",
                    )
                }
                transport.publish(fitted.encoded)
                // And to the relay the family is leaving, while anyone might still be listening
                // there: a phone that was off during the move comes back to the old address and
                // has to find the rules — and the instruction to move — waiting for it.
                legacyTransport?.publish(fitted.encoded)
                // What is now on the wire, so the screens can tell an edit that has gone out from
                // one still sitting on this phone (see PolicyDiff). Recorded at publish, not at
                // confirmation: "not yet sent" and "sent but not yet confirmed" are different
                // states with different chips, and this is the boundary between them.
                //
                // Never while a push is being held. The periodic re-emit publishes the current
                // policy WITHOUT bumping the version, so mid-hold it puts an edit on the wire
                // that every child will reject — counting that as deployed would clear the
                // pending chips for a change that has not actually taken anywhere.
                if (!state.pendingPolicyPush && state.deployedPolicyJson != snapshot.policyJson) {
                    syncStore.update { it.copy(deployedPolicyJson = snapshot.policyJson) }
                }
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
                // Everything still ungranted on this phone, by the same list its own home screen
                // and its periodic self-check read (see DeviceSetup). The parent cannot fix any
                // of it remotely — that is the point of reporting it: an enrollment nobody
                // finished is invisible from the other side otherwise, because a device missing
                // every permission still pairs, still publishes and still looks alive.
                val setupUnmet = runCatching {
                    dev.walcott.setup.DeviceSetup.unmet(dev.walcott.setup.DeviceSetupProbe.read(context))
                        .map { it.key }
                }.getOrDefault(emptyList())
                // History off (the default) reports only the current position; on, the 48h
                // trail is decimated so it can't push the snapshot past ntfy's message cap.
                val historyOn = settings.resolveForChild(id.childId).locationHistoryEnabled
                val crashes = dev.walcott.debug.CrashCounter.current()
                // Counts and timestamps only — reading it does not touch the cached lists.
                val blocklistState = withContext(Dispatchers.IO) {
                    dev.walcott.net.BlocklistStore.get(context).state.value
                }
                val ringer = dev.walcott.enforcement.AudioGuard.read(context)
                // Read, never armed, here: this is the publish path, and arming belongs where the
                // rules are applied (see EnforcementService). A check-in must not be the thing that
                // quietly changes what a device can do.
                val lockState = dev.walcott.enforcement.LockScreen.state(
                    context,
                    token = s.lockTokenB64.takeIf { it.isNotBlank() }
                        ?.let { runCatching { FamilyCrypto.fromB64(it) }.getOrNull() },
                )
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
                    // And what the filter is made of: the public lists this device has actually
                    // downloaded, plus any it never has (see BlocklistStore). Read from the
                    // store's own state file, so it costs no disk read of the lists themselves.
                    filterListDomains = blocklistState.domainsFor(settings.enabledBlocklists),
                    filterListsPending = blocklistState.pending(settings.enabledBlocklists),
                    // Whether this phone can actually be heard, whether something is still muting
                    // it, and how often it has had to be put right (see AudioGuard). Reported
                    // whatever the rules say: a family that has not turned the guard on still wants
                    // to know that the phone they cannot reach is on silent.
                    ringerAudible = ringer.audible,
                    ringerDndSilencing = ringer.dndSilencing,
                    ringerRestores = s.ringerRestores,
                    // Whether the lock could be reset from the parent's phone RIGHT NOW. Reported
                    // before it is needed on purpose — see LockScreen.
                    lockResetReady = lockState.tokenRegistered && lockState.tokenActive,
                    notificationAccess = !settings.notificationLogEnabled ||
                        NotificationLog.accessGranted(context),
                    crashTotal = crashes.total,
                    lastCrashMs = crashes.lastAtMs,
                    unauthorized = s.unauthorizedApps,
                    setupUnmet = setupUnmet,
                    blocks = blockReport(today),
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
     * Child: what the filter and the rules blocked, for the snapshot.
     *
     * Flushed first, so the report includes the last minute rather than everything up to the
     * previous flush — a publish is exactly the moment the counts are about to be read. Null
     * when nothing has been blocked at all, which is the normal state of a family that has not
     * turned the filter on and costs the message nothing.
     */
    private suspend fun blockReport(today: Long): BlockReport? {
        runCatching { repository.flushBlockCounters() }
        val report = runCatching {
            val domains = repository.blockCounts(BlockKinds.DOMAIN, today)
            val netApps = repository.blockCounts(BlockKinds.NET_APP, today)
            val ruleApps = repository.blockCounts(BlockKinds.RULE_APP, today)
            BlockReport(
                epochDay = today,
                // Summed from the rows rather than kept as a separate counter: the tail is
                // folded, never dropped, so the rows still add up to the truth.
                netToday = domains.sumOf { it.count },
                ruleToday = ruleApps.sumOf { it.count },
                domains = BlockReports.cap(domains.map { BlockCount(it.key, it.count) }),
                netApps = BlockReports.cap(netApps.map { BlockCount(it.key, it.count) }),
                ruleApps = BlockReports.cap(ruleApps.map { BlockCount(it.key, it.count) }),
                recentDays = recentBlockDays(today),
            )
        }.getOrNull() ?: return null
        return report.takeUnless { it.isEmpty() }
    }

    /** Totals for the days before today this device still has, oldest first. */
    private suspend fun recentBlockDays(today: Long): List<DayBlockTotals> {
        val first = today - BlockReports.MAX_RECENT_DAYS
        val rows = repository.blockTotals(first, today - 1)
        return rows.groupBy { it.epochDay }.entries.sortedBy { it.key }.map { (day, kinds) ->
            DayBlockTotals(
                epochDay = day,
                net = kinds.firstOrNull { it.kind == BlockKinds.DOMAIN }?.total ?: 0L,
                rule = kinds.firstOrNull { it.kind == BlockKinds.RULE_APP }?.total ?: 0L,
            )
        }
    }

    /**
     * Child: render and send a batch of the icons the parent asked for — only apps this child
     * actually has, bounded so rendering stays cheap and one message stays under the size cap.
     * The parent re-requests what's still missing, so the backlog drains over a few messages.
     */
    private suspend fun answerIconRequests(requests: List<String>, id: FamilyIdentity) {
        val transport = transport ?: return
        // Rendered and un-renderable are gathered in one pass, and the limit counts only the
        // ones that WORKED. Taking the first eight requests and then encoding them meant a
        // package whose icon cannot be produced consumed a slot every single cycle — and, once
        // it was the only one left, produced an empty batch that was never sent at all. That
        // is the shape of an icon still missing after days.
        val rendered = mutableListOf<AppIconData>()
        val unavailable = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            for (pkg in requests) {
                if (rendered.size >= ICON_RENDER_LIMIT) break
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                // Not installed here is not this child's answer to give: another child may have
                // it, and this one's app list already says it does not.
                if (info == null) continue
                // Two sources, because they can disagree. The application icon is what an app
                // declares about itself, and an app whose install never registered one (a
                // sideload gone sideways, a package restored by an old installer) has nothing
                // to declare — while its launcher activity, the thing the child actually taps,
                // still carries the icon the launcher draws.
                val drawable = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                    ?: runCatching {
                        pm.getLaunchIntentForPackage(pkg)?.resolveActivityInfo(pm, 0)?.loadIcon(pm)
                    }.getOrNull()
                val encoded = drawable?.let { IconStore.encode(it) }
                if (encoded == null) unavailable += pkg else rendered += AppIconData(pkg, encoded)
            }
        }
        val packed = IconSync.pack(rendered)
        // An icon that packing had to skip is one this message cannot carry; it is not a
        // failure of the child, so it is left for the next cycle rather than declared missing.
        if (packed.isEmpty() && unavailable.isEmpty()) return
        if (unavailable.isNotEmpty()) {
            dev.walcott.debug.DebugLog.w(TAG, "cannot render icons for ${unavailable.joinToString()}")
        }
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))
        // Fit-or-drop: the pack budget is measured pre-envelope, so verify the real wire size
        // like the snapshot does — an oversized publish would be 413-rejected every cycle and
        // silently jam this icon (and everything queued behind it) forever.
        val message = IconFit.encode(IconPayload(id.deviceId, packed, unavailable), familyKey)
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

    /**
     * Child: publishes the notification log for the window the parent asked about, newest first.
     *
     * [beforeMs] is the page cursor (0 = start at now), and the window reaches back
     * [NotificationLog.RETAIN_HOURS] because that is all this device kept. Returns how many entries
     * were read, for the command's acknowledgement.
     *
     * The two "there is nothing here and this is why" cases are reported rather than answered with
     * an empty list: a family that switched the log on and never granted notification access would
     * otherwise read a silent phone as a quiet day.
     */
    /**
     * Parent: remembers the PIN this phone is about to set on [deviceId] (see [SyncState.lastLockPin]).
     * Written BEFORE the command goes out, so a phone that dies between the two still knows what it
     * asked for — the opposite order loses the number and leaves somebody locked out.
     */
    suspend fun rememberLockPin(deviceId: String, pin: String) {
        syncStore.update { s ->
            s.copy(
                lastLockPin = if (pin.isBlank()) s.lastLockPin - deviceId else s.lastLockPin + (deviceId to pin),
            )
        }
    }

    /**
     * Child: counts one ringer restore, so "this phone was on silent again" is a number the parent
     * can watch grow rather than a moment nobody was there for.
     */
    suspend fun recordRingerRestore() {
        syncStore.update { it.copy(ringerRestores = it.ringerRestores + 1) }
    }

    /**
     * Child: arms the lock-screen escape hatch, if this device can have one. Called where the rules
     * are applied, so a family that never asked for it never registers a token.
     */
    suspend fun armLockReset() {
        lockToken()
    }

    /**
     * This device's lock-screen reset token, minted and registered on first use (see [LockScreen]).
     *
     * Kept device-local and re-registered every time it is read, which is cheap and covers the one
     * failure that matters: the platform can forget a token, and a device that believes it is
     * rescuable when it is not is worse than one that says so. Whether the SYSTEM has activated it
     * is a separate question, answered in the snapshot ([ChildSnapshot.lockResetReady]).
     */
    private suspend fun lockToken(): ByteArray? {
        val stored = syncStore.current().lockTokenB64
        val token = if (stored.isNotBlank()) {
            runCatching { FamilyCrypto.fromB64(stored) }.getOrNull()
        } else {
            null
        } ?: dev.walcott.enforcement.LockScreen.newToken().also {
            syncStore.update { s -> s.copy(lockTokenB64 = FamilyCrypto.toB64(it)) }
        }
        return if (dev.walcott.enforcement.LockScreen.register(context, token)) token else null
    }

    suspend fun publishNotifications(arg: String): Int {
        val id = identityStore.current()
        val transport = transport ?: return 0
        val settings = settingsStore.current()
        val query = NotificationQuery.decode(arg)
        val now = System.currentTimeMillis()
        val before = if (query.beforeMs > 0) query.beforeMs else now + 1
        val since = now - java.util.concurrent.TimeUnit.HOURS.toMillis(NotificationLog.RETAIN_HOURS)
        val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(id.familyKeyB64))

        val enabled = NotificationLog.enabledBy(settings)
        val access = NotificationLog.accessGranted(context)
        val dao = repository.notifications
        val onePackage = query.pkg.isNotBlank()
        val entries = if (enabled && access) {
            runCatching {
                if (onePackage) dao.pageForApp(query.pkg, since, before, NOTIFICATION_PAGE_ROWS)
                else dao.page(since, before, NOTIFICATION_PAGE_ROWS)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val payload = NotificationPayload(
            deviceId = id.deviceId,
            atMs = now,
            entries = entries.map {
                NotificationEntry(atMs = it.postedAtMs, pkg = it.packageName, title = it.title, text = it.text)
            },
            total = if (enabled && access) {
                runCatching {
                    if (onePackage) dao.countForApp(query.pkg, since, before)
                    else dao.countBetween(since, before)
                }.getOrDefault(entries.size)
            } else {
                0
            },
            sinceMs = since,
            pkg = query.pkg,
            noAccess = enabled && !access,
            notEnabled = !enabled,
        )
        transport.publish(NotificationFit.encode(payload, familyKey))
        dev.walcott.debug.DebugLog.i(TAG, "notification log published: ${entries.size} of ${payload.total}")
        return entries.size
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
            id.role == Role.PARENT && message is IncomingMessage.FromChildNotifications ->
                applyNotificationPayload(message.payload)
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
     * Parent: file a page of notifications a device just sent.
     *
     * Pages accumulate rather than replace, because that is what "load older" means — the parent
     * asked for the 24 h window, got what fitted, and asked again for what came before it. Bounded
     * at [MAX_NOTIFICATION_PAGES] and de-duplicated by the instant the page was taken, since the
     * relay replays its backlog on reconnect and a page filed twice would put every notification on
     * the screen twice.
     */
    private suspend fun applyNotificationPayload(payload: NotificationPayload) {
        syncStore.update { s ->
            val previous = s.notificationPages[payload.deviceId].orEmpty()
            if (previous.any { it.atMs == payload.atMs }) return@update s
            s.copy(
                notificationPages = s.notificationPages +
                    (payload.deviceId to (listOf(payload) + previous).take(MAX_NOTIFICATION_PAGES)),
            )
        }
    }

    /**
     * Parent: cache the icons a child just sent. If apps still lack icons, re-publish so the
     * next request goes out promptly — that request→answer→request loop drains the enrollment
     * burst quickly and then falls silent (empty requests cost nothing).
     */
    private suspend fun applyIconPayload(payload: IconPayload) {
        if (payload.unavailable.isNotEmpty()) {
            // Stamped rather than remembered for good: the ask resumes a day later (see
            // IconSync.suppressed). Pruned to what the children still report having, so an app
            // that is uninstalled and put back also gets its chance immediately.
            val shown = syncStore.current().children.flatMap { c -> c.apps.map { it.packageName } }.toSet()
            val nowMs = System.currentTimeMillis()
            syncStore.update { state ->
                val stamped = state.iconsUnrenderable + payload.unavailable.associateWith { nowMs }
                state.copy(iconsUnrenderable = stamped.filterKeys { it in shown })
            }
            // Said out loud on the parent too: the child logs why it failed, but the parent is
            // where the blank icon is actually being looked at.
            dev.walcott.debug.DebugLog.w(
                TAG,
                "child cannot render icons for ${payload.unavailable.joinToString()}; asking again in a day",
            )
        }
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
        val after = syncStore.current()
        val shown = after.children.flatMap { c -> c.apps.map { it.packageName } }
        val stillMissing = IconSync.toRequest(
            shown,
            iconStore.cachedAmong(shown) + IconSync.suppressed(after.iconsUnrenderable, System.currentTimeMillis()),
        )
        if (stillMissing.isNotEmpty()) publishSelf()
    }

    /** Bumps whenever new icons land, so the app list recomposes and re-reads the disk cache. */
    val iconsCached = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Cached icon bytes for [pkg], or null if not fetched yet (parent-side render). */
    fun iconBytes(pkg: String): ByteArray? = iconStore.read(pkg)

    /**
     * Applied one at a time, because the replay gate below is a read-modify-write.
     *
     * Two parent snapshots arriving close together used to be applied concurrently: both read
     * the same `appliedParentVersion`, both decided they were newer than it, and both wrote —
     * so the LAST WRITE won rather than the highest version. The child could end up enforcing
     * the older policy while reporting it had applied the newer one, and nothing would correct
     * it until the parent next edited a rule.
     *
     * Not a rare shape, either. Every resolution, command and bonus publishes its own snapshot,
     * so a parent answering a request while a rule edit goes out produces two in the same
     * instant — and a child reconnecting with a `since=` cursor is handed the whole backlog at
     * once, which is the common case rather than the exotic one.
     */
    private val parentSnapshotMutex = Mutex()

    private suspend fun applyParentSnapshot(snapshot: ParentSnapshot, rotationAdopted: Boolean) =
        parentSnapshotMutex.withLock { applyParentSnapshotLocked(snapshot, rotationAdopted) }

    private suspend fun applyParentSnapshotLocked(snapshot: ParentSnapshot, rotationAdopted: Boolean) {
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
        // Nothing may follow a release: the stores it just wiped would be written back into
        // existence by the bookkeeping below, on a device that is no longer part of any family.
        if (applyCommands(snapshot, deviceId)) return

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
        val pendingIds = s.pendingRequests.map { it.requestId }.toSet() +
            s.pendingAsks.map { it.requestId }
        val freshResolutions = SyncEngine.newResolutions(snapshot, pendingIds, s.appliedResolutionIds)
        for (resolution in freshResolutions) {
            if (!resolution.approved) continue
            if (resolution.grantedMinutes > 0) {
                val req = s.pendingRequests.firstOrNull { it.requestId == resolution.requestId }
                if (req != null) repository.grantExtraMinutes(req.categoryId, resolution.grantedMinutes.toLong())
            }
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
                appliedResolutionIds = SyncState.rememberApplied(it.appliedResolutionIds, resolvedIds),
                appliedBonusIds = SyncState.rememberApplied(it.appliedBonusIds, bonusIds),
                lastNotice = noticeFromResolution ?: noticeFromBonus ?: it.lastNotice,
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
    /** Returns true when the last thing it did was hand this device back (nothing may follow). */
    private suspend fun applyCommands(snapshot: ParentSnapshot, deviceId: String): Boolean = commandMutex.withLock {
        val runner by lazy {
            RemoteCommandRunner(
                context,
                repository,
                openInstallForPush = { pkg, id, label -> openInstallForPush(pkg, id, label) },
                publishDiagnostics = { publishDiagnostics() },
                denyPanic = { requestId -> denyPanic(requestId) },
                removeApp = { pkg -> removeAppNow(pkg) },
                allowApp = { pkg -> allowAppNow(pkg) },
                setLockPin = { pin -> dev.walcott.enforcement.LockScreen.apply(context, pin, lockToken()) },
                publishNotifications = { arg -> publishNotifications(arg) },
            )
        }
        for (command in SyncEngine.newCommands(snapshot, deviceId, syncStore.current().appliedCommandIds)) {
            // Re-check under the lock: a concurrent handler may have claimed it since.
            if (command.id in syncStore.current().appliedCommandIds) continue
            syncStore.update {
                it.copy(appliedCommandIds = SyncState.rememberApplied(it.appliedCommandIds, listOf(command.id)))
            }
            val ack = runner.run(command)
            syncStore.update { it.copy(lastCommandAck = ack, childVersion = it.childVersion + 1) }
            publishSelf()
            // The release is run HERE, after its acknowledgement is on the wire, and it is the
            // last thing this device ever does on this channel: the teardown wipes the sync
            // state and closes the transport, so an ack published afterwards would go nowhere
            // and the parent would be left unable to tell a freed phone from a dead one.
            if (command.action == RemoteAction.RELEASE_DEVICE && ack.ok) {
                dev.walcott.enforcement.PanicRelease.releaseDevice(context)
                return@withLock true
            }
            // Same ordering discipline, milder consequence: the acknowledgement above went out on
            // the relay the parent is still listening to, and only now does this device stop
            // listening to it.
            if (command.action == RemoteAction.SET_RELAY && ack.ok) {
                adoptRelay(command.arg)
            }
        }
        false
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
        val byApp = UsageLedger.mergeByApp(
            before.usageByApp[ledgerKey].orEmpty(),
            snapshot.history,
            snapshot.epochDay,
            snapshot.usage,
        )
        // The same for what was blocked. Pruning is part of the merge, so the ledger is trimmed
        // by the act of being written to rather than by a job somebody has to remember to run.
        val blocks = snapshot.blocks?.let {
            BlockLedger.merge(before.blockLedgers[ledgerKey] ?: BlockLedger.Ledger(), it, snapshot.epochDay)
        }
        // A phone the parent was told had gone quiet has just spoken. Announced here rather than
        // from the hourly worker because this is the moment it happens, and a recovery reported an
        // hour late is one the parent has already gone looking for (see Staleness.recoveryKeys).
        val recoveryKeys = Staleness.recoveryKeys(snapshot.deviceId, snapshot.childId, before.staleNotifiedLastSeen)
        // Null when this child had never reported at all: there is no silence to measure, only a
        // first arrival.
        val silence = before.lastSeen[snapshot.deviceId]
            ?.let { java.time.Duration.ofMillis((System.currentTimeMillis() - it).coerceAtLeast(0)) }
        // A short gap is not news in either direction, so the return of one is not either — the
        // alert is retired quietly and nothing is posted (see Staleness.worthAnnouncingReturn).
        val announceReturn = recoveryKeys.isNotEmpty() && Staleness.worthAnnouncingReturn(silence?.toMillis())
        if (recoveryKeys.isNotEmpty()) {
            dev.walcott.debug.DebugLog.i(
                TAG,
                "${snapshot.deviceId} is back after ${silence ?: "never reporting"}" +
                    if (announceReturn) "" else " (too short to be worth saying)",
            )
            // Whether or not it is announced, the alarm it answers goes: the phone is here.
            SyncNotifications.cancelStale(context, snapshot.deviceId)
        }
        if (announceReturn) {
            SyncNotifications.notifyChildBack(
                context, who, silence?.humanize(), snapshot.deviceId, snapshot.childId,
            )
        }

        // Track when this device's install window was first seen open, so the hourly reminder
        // can count "open for an hour" from reality rather than from worker cadence.
        val installWindowOpen = snapshot.installExemptionUntilMs > System.currentTimeMillis()
        syncStore.update {
            it.copy(
                children = merged,
                lastSeen = it.lastSeen + (snapshot.deviceId to System.currentTimeMillis()),
                commands = if (ackedId != null) it.commands.filterNot { c -> c.id == ackedId } else it.commands,
                usageHistory = it.usageHistory + (ledgerKey to ledger),
                usageByApp = it.usageByApp + (ledgerKey to byApp),
                blockLedgers = if (blocks != null) it.blockLedgers + (ledgerKey to blocks) else it.blockLedgers,
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
                // Dropped here, so the NEXT outage alerts again rather than being deduped
                // against an alert the parent has already been told is over.
                staleNotifiedLastSeen = it.staleNotifiedLastSeen - recoveryKeys,
            ).let { s ->
                // The wall is the durable record behind an alert; an unannounced return has none
                // to be the record of.
                if (announceReturn) s.plusEvent(event(ParentEvent.TYPE_BACK, snapshot)) else s
            }.let { s ->
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

        // A device saying it has moved relay may have been the last one the parent was waiting for.
        if (snapshot.lastCommand?.action == RemoteAction.SET_RELAY && snapshot.lastCommand?.ok == true) {
            runCatching { closeMigrationIfDone() }
                .onFailure { dev.walcott.debug.DebugLog.w(TAG, "could not close the migration", it) }
        }

        // A device that acknowledged its release is already tearing itself down and will never
        // publish again (see RemoteAction.RELEASE_DEVICE). Let it go here, or the parent keeps a
        // row for a phone that was freed on purpose — and starts alerting, days later, that it
        // has not been heard from. The feed entry recorded above is what remains of it.
        val releaseAck = snapshot.lastCommand?.takeIf { it.action == RemoteAction.RELEASE_DEVICE && it.ok }
        if (releaseAck != null) {
            dev.walcott.debug.DebugLog.w(TAG, "${snapshot.deviceId} confirmed its release; letting it go")
            SyncNotifications.cancelForDevice(context, snapshot.deviceId)
            removeChildDevice(snapshot.deviceId)
            return
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
        // Apps that turned up on the child without approval (see InstallGuard). The child has
        // already suspended them and is already removing them, so this alert is not a request
        // for permission: it names the app, and offers the one call that is the parent's —
        // let it stay. One notification per app, so two offenders don't overwrite each other.
        //
        // Guarded by the same version rule mergeChild accepts on, or a replayed older snapshot
        // would resurrect a case the parent has already answered.
        if (prevChild == null || snapshot.version >= prevChild.version) {
            val quarantinedBefore = prevChild?.unauthorized.orEmpty().map { it.pkg }.toSet()
            for (entry in snapshot.unauthorized) {
                if (entry.pkg in quarantinedBefore) continue
                val name = entry.label.ifBlank { entry.pkg }
                SyncNotifications.notifyUnauthorizedApp(
                    context, who, name, entry.pkg, snapshot.deviceId, snapshot.childId,
                )
                syncStore.update {
                    it.plusEvent(event(ParentEvent.TYPE_WRONG_APP, snapshot, detail = name))
                }
            }
            // Case closed on the child (removed, or allowed): take the alert down with it.
            val stillQuarantined = snapshot.unauthorized.map { it.pkg }.toSet()
            for (pkg in quarantinedBefore - stillQuarantined) {
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(context)
                        .cancel(UnauthorizedAppReceiver.notificationId(snapshot.deviceId, pkg))
                }
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

        // An enrollment that stopped at the QR: the device is paired and publishing, and the
        // permissions its rules need were never granted. Only the clock is started here — the
        // reminder itself is the hourly worker's (see SetupReminder), because this runs on every
        // check-in and the parent must not be told every half hour. Recovering clears both, so a
        // phone set up properly is never mentioned again and a later lapse starts fresh.
        val setupPending = snapshot.setupUnmet.isNotEmpty()
        val pendingSince = before.setupPendingSince[snapshot.deviceId] ?: 0L
        if (setupPending && pendingSince == 0L) {
            syncStore.update {
                it.copy(
                    setupPendingSince = it.setupPendingSince + (snapshot.deviceId to System.currentTimeMillis()),
                )
            }
        } else if (!setupPending && pendingSince != 0L) {
            // Worth a line of its own: it is the answer to the reminder, and the only positive
            // confirmation a parent ever gets that the phone in someone else's hands is ready.
            syncStore.update {
                it.copy(
                    setupPendingSince = it.setupPendingSince - snapshot.deviceId,
                    setupRemindedAt = it.setupRemindedAt - snapshot.deviceId,
                ).plusEvent(event(ParentEvent.TYPE_SETUP_DONE, snapshot))
            }
        }

        // The child has caught up with the parent's rules. Recorded so the detail screen can say
        // "up to date, confirmed at X" instead of leaving the parent to read meaning into the
        // ABSENCE of a warning — which is also what a child that has never reported looks like.
        // Only a version that actually moved forward counts, so a re-emitted snapshot can't
        // restamp the time and make a stale confirmation look fresh.
        val confirmedBefore = before.policyConfirmedVersion[snapshot.deviceId] ?: 0L
        if (snapshot.appliedPolicyVersion > confirmedBefore) {
            val caughtUp = snapshot.appliedPolicyVersion >= before.parentVersion
            syncStore.update {
                it.copy(
                    policyConfirmedVersion =
                        it.policyConfirmedVersion + (snapshot.deviceId to snapshot.appliedPolicyVersion),
                    policyConfirmedAtMs =
                        it.policyConfirmedAtMs + (snapshot.deviceId to System.currentTimeMillis()),
                ).let { s ->
                    // Only worth a line on the wall once they are actually current: a child
                    // stepping through a backlog of versions is one event, not four.
                    if (caughtUp) s.plusEvent(event(ParentEvent.TYPE_RULES_APPLIED, snapshot)) else s
                }
            }
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
            } else if (ask.kind == ChildRequest.KIND_HELP) {
                // The one ask with no answer to give from the shade — it is a person, not a
                // permission (see notifyHelpAsk).
                SyncNotifications.notifyHelpAsk(context, who, ask.requestId)
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

        /**
         * After this many failed removals, the debug log says so once. A Device Owner uninstall
         * that keeps being refused is a real gap — the app is still suspended, but it is not
         * going away, and the only surface that can say that out loud is the log the health
         * report carries.
         */
        private const val STUCK_REMOVAL_ATTEMPTS = 4
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

        /**
         * Notifications read for one page before [NotificationFit] trims to the size cap.
         *
         * Generous on purpose: reading them costs a query, and the cap that matters is the message
         * size, which Fit measures rather than guesses. What this bounds is the query, so a phone
         * with a thousand rows does not load them all to send sixty.
         */
        private const val NOTIFICATION_PAGE_ROWS = 200
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
