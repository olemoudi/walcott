package dev.walcott.sync

import android.content.Context
import android.os.Build
import dev.walcott.data.PinLockout
import dev.walcott.data.PinResult
import dev.walcott.data.PolicySettings
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottRepository
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
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var transport: SyncTransport? = null
    private var reEmitJob: Job? = null
    /** Parent-only republish-on-edit collector; tracked so reconnects don't stack duplicates. */
    private var settingsWatchJob: Job? = null
    /** In-memory mirror of [SyncState.ntfySinceSec] so the transport's sinceProvider never blocks. */
    @Volatile private var sinceCache: Long = 0
    /** Wall clock of the last successful publish, so heartbeats can skip redundant ones. */
    @Volatile private var lastPublishAtMs: Long = 0
    /** Serializes remote-command execution across concurrently handled parent snapshots. */
    private val commandMutex = Mutex()

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

    // --- Child-side visibility of its own request lifecycle ---

    /** This device's own unanswered time requests (child home "waiting" section). */
    val myPendingRequests: StateFlow<List<ExtraTimeRequest>> =
        syncStore.state.map { it.pendingRequests }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** This device's own unanswered asks (apps, anything). */
    val myPendingAsks: StateFlow<List<ChildRequest>> =
        syncStore.state.map { it.pendingAsks }.stateIn(scope, SharingStarted.Eagerly, emptyList())

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
        transport = NtfyTransport(id.ntfyServer, id.topic, sinceProvider = { sinceCache }).also { t ->
            t.connect { raw, timeSec ->
                scope.launch {
                    handleIncoming(raw, id)
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
                settingsStore.settings.drop(1).collect { publishConfigChanged() }
            }
        } else {
            null
        }
        // Re-emit heals lost messages from the moment a device is paired — including devices
        // paired during this process's lifetime (pairing used to publish exactly once).
        periodicReEmit()
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
        if (id.isPaired) handleIncoming(raw, id)
        advanceCursor(timeSec)
    }

    /**
     * Moves the `since=` cursor forward. Advances on EVERY message — including our own echoes
     * and undecodable ones — or reconnects would replay them forever.
     */
    private suspend fun advanceCursor(timeSec: Long) {
        if (timeSec <= sinceCache) return
        sinceCache = timeSec
        syncStore.update { if (timeSec > it.ntfySinceSec) it.copy(ntfySinceSec = timeSec) else it }
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
        reEmitJob?.cancel()
        reEmitJob = null
        settingsWatchJob?.cancel()
        settingsWatchJob = null
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
        repository.seedHardeningIfNeeded()
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

    /** PIN-gated manual exemption: allow installs on this device for a while (blanket). */
    suspend fun allowInstallsTemporarily() {
        syncStore.update {
            it.copy(installExemptionUntilMs = System.currentTimeMillis() + DeviceRestrictions.INSTALL_EXEMPTION_MS)
        }
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
        syncStore.update {
            it.copy(
                installExemptionUntilMs = 0,
                pendingInstallPackage = "",
                pendingInstallCommandId = "",
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
                pinAlertedTotal = s.pinAlertedTotal - deviceId,
            )
        }
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

        repository.grantExtraMinutes(config.targetCategoryId, grant.toLong())
        val consumedSeconds = IdleEarnEngine.idleConsumedFor(config, grant) * 60
        val pruned = IdleEarnEngine.prune(ledger + EarnGrant(now, grant), now)
            .map { EarnGrantEntry(it.epochMs, it.minutes) }
        syncStore.update {
            it.copy(
                idleEarnBankSeconds = (it.idleEarnBankSeconds - consumedSeconds).coerceAtLeast(0),
                earnGrants = pruned,
            )
        }
        dev.walcott.debug.DebugLog.i(TAG, "idle-earn granted $grant min to ${config.targetCategoryId}")
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
        val remaining = PinLockout.remainingMs(s.pinLockedUntilMs, now)
        if (remaining > 0) return PinResult.Locked(remaining)

        if (repository.verifyPin(pin)) {
            if (s.pinFailedAttempts != 0 || s.pinLockedUntilMs != 0L) {
                syncStore.update { it.copy(pinFailedAttempts = 0, pinLockedUntilMs = 0) }
            }
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
                val snapshot = ParentSnapshot(
                    version = state.parentVersion,
                    policyJson = json.encodeToString(PolicySettings.serializer(), settings),
                    resolutions = state.resolutions,
                    bonuses = state.bonuses,
                    locationRequests = state.locationRequests,
                    commands = state.commands,
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
                // History off (the default) reports only the current position; on, the 48h
                // trail is decimated so it can't push the snapshot past ntfy's message cap.
                val historyOn = settingsStore.current().resolveForChild(id.childId).locationHistoryEnabled
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
                    usage = repository.reportedUsageNow().map { UsageEntry(it.key, it.value.seconds) },
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
                    updateError = s.updateError,
                )
                // Fit-or-degrade: an oversized message would be rejected (HTTP 413) and the
                // child would silently vanish from the parent, which is far worse than a
                // temporarily thinner snapshot.
                val fitted = SnapshotFit.encodeChild(snapshot, familyKey)
                if (fitted.degraded != null) {
                    dev.walcott.debug.DebugLog.w(TAG, "snapshot over size budget; degraded: ${fitted.degraded}")
                }
                transport.publish(fitted.encoded)
            }
            Role.UNPAIRED -> Unit
        }
        if (id.role != Role.UNPAIRED) lastPublishAtMs = System.currentTimeMillis()
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

        // Remote fixes from the parent (update now, re-apply policy, nudge for permissions).
        // Run before the grants below so a device whose enforcement had lapsed is repaired
        // first; each command publishes its own acknowledgement.
        applyCommands(snapshot, deviceId)

        // Record which rules version this child now runs, and echo it promptly so the
        // parent's "updating rules…" indicator clears (a re-emit would take minutes).
        val newRulesAdopted = snapshot.version > s.appliedParentVersion
        if (newRulesAdopted) {
            syncStore.update {
                it.copy(appliedParentVersion = maxOf(it.appliedParentVersion, snapshot.version))
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
            RemoteCommandRunner(context, repository, openInstallForPush = { pkg, id -> openInstallForPush(pkg, id) })
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

    private suspend fun applyChildSnapshot(snapshot: ChildSnapshot) {
        val before = syncStore.current()
        val prevRequestIds = before.children.flatMap { it.requests }.map { it.requestId }.toSet()
        val prevAskIds = before.children.flatMap { it.asks }.map { it.requestId }.toSet()
        val merged = SyncEngine.mergeChild(before.children.associateBy { it.deviceId }, snapshot).values.toList()
        // A child that acknowledged a command has run it: drop it from the queue so it isn't
        // carried in every subsequent parent snapshot.
        val ackedId = snapshot.lastCommand?.id
        syncStore.update {
            it.copy(
                children = merged,
                lastSeen = it.lastSeen + (snapshot.deviceId to System.currentTimeMillis()),
                commands = if (ackedId != null) it.commands.filterNot { c -> c.id == ackedId } else it.commands,
            )
        }

        // Alert once when a child reports enforcement is inactive (not Device Owner and no
        // accessibility blocker); clear the flag when it recovers so a later lapse re-alerts.
        val nowInactive = snapshot.enforcement == EnforcementStatus.NONE
        if (nowInactive && snapshot.deviceId !in before.enforcementNotified) {
            SyncNotifications.notifyEnforcementInactive(context, snapshot.displayName, snapshot.deviceId)
            syncStore.update { it.copy(enforcementNotified = it.enforcementNotified + snapshot.deviceId) }
        } else if (!nowInactive && snapshot.enforcement != EnforcementStatus.UNKNOWN &&
            snapshot.deviceId in before.enforcementNotified
        ) {
            syncStore.update { it.copy(enforcementNotified = it.enforcementNotified - snapshot.deviceId) }
        }

        // Alert when a child loses full Device Owner protection but a weaker backend remains
        // (the NONE alert above misses that downgrade). The version guard mirrors mergeChild's
        // accept rule so a replayed older snapshot can't fake a transition.
        val prevChild = before.children.firstOrNull { it.deviceId == snapshot.deviceId }
        if (prevChild?.enforcement == EnforcementStatus.DEVICE_OWNER &&
            snapshot.enforcement != EnforcementStatus.DEVICE_OWNER &&
            snapshot.enforcement != EnforcementStatus.UNKNOWN &&
            snapshot.version >= prevChild.version
        ) {
            SyncNotifications.notifyEnforcementDegraded(context, snapshot.displayName, snapshot.deviceId)
        }

        // Alert once when usage access is off (budgets silently stop counting); re-alert on relapse.
        val usageOff = !snapshot.usageAccessOn
        if (usageOff && snapshot.deviceId !in before.usageAccessNotified) {
            SyncNotifications.notifyUsageAccessLost(context, snapshot.displayName, snapshot.deviceId)
            syncStore.update { it.copy(usageAccessNotified = it.usageAccessNotified + snapshot.deviceId) }
        } else if (!usageOff && snapshot.deviceId in before.usageAccessNotified) {
            syncStore.update { it.copy(usageAccessNotified = it.usageAccessNotified - snapshot.deviceId) }
        }

        // Alert once when mock (spoofed) fixes appear in the trail; clear when it's clean again.
        val hasMock = snapshot.locations.any { it.mock }
        if (hasMock && snapshot.deviceId !in before.mockLocationNotified) {
            SyncNotifications.notifyMockLocation(context, snapshot.displayName, snapshot.deviceId)
            syncStore.update { it.copy(mockLocationNotified = it.mockLocationNotified + snapshot.deviceId) }
        } else if (!hasMock && snapshot.deviceId in before.mockLocationNotified) {
            syncStore.update { it.copy(mockLocationNotified = it.mockLocationNotified - snapshot.deviceId) }
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
                SyncNotifications.notifyNewApp(
                    context, snapshot.displayName, newApps.first().label, newApps.size - 1, snapshot.deviceId,
                )
                syncStore.update {
                    it.copy(seenAppPackages = it.seenAppPackages + newApps.map { a -> a.packageName })
                }
            }
        }

        // Alert whenever the child's cumulative wrong-PIN count grows (someone is guessing the PIN).
        val prevPinTotal = before.pinAlertedTotal[snapshot.deviceId] ?: 0
        if (snapshot.pinWrongTotal > prevPinTotal) {
            SyncNotifications.notifyWrongPin(context, snapshot.displayName, snapshot.pinWrongTotal, snapshot.deviceId)
            syncStore.update {
                it.copy(pinAlertedTotal = it.pinAlertedTotal + (snapshot.deviceId to snapshot.pinWrongTotal))
            }
        }

        val resolved = before.resolutions.map { it.requestId }.toSet()
        val newlyPending = snapshot.requests.map { it.requestId }.toSet() - prevRequestIds - resolved
        if (newlyPending.isNotEmpty()) {
            val req = snapshot.requests.first { it.requestId in newlyPending }
            SyncNotifications.notifyRequest(context, snapshot.displayName, req.minutes)
        }

        // Generic asks (app installs, free-form) notify too — they used to be UI-only.
        val newlyAsked = snapshot.asks.map { it.requestId }.toSet() - prevAskIds - resolved
        for (ask in snapshot.asks.filter { it.requestId in newlyAsked }) {
            SyncNotifications.notifyAsk(context, snapshot.displayName, ask.text, ask.requestId)
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
    }
}
