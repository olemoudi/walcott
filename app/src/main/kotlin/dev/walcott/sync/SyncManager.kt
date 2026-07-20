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
    private val iconStore: IconStore = IconStore(context),
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
    /** In-flight debounced auto-backup rewrite; replaced on every new trigger. */
    private var autoBackupJob: Job? = null

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
                    // Stamp BEFORE the (debounced) auto-refresh: once that lands it re-stamps
                    // lastBackupAtMs above this, which is what keeps the reminders silent.
                    syncStore.update { s -> s.copy(lastPolicyEditAtMs = System.currentTimeMillis()) }
                    // Fire-and-forget backups: the same edits that republish also refresh the
                    // backup file, so it can never quietly go months stale.
                    scheduleAutoBackupRefresh()
                }
            }
        } else {
            null
        }
        // Catch up the auto-backup on process start too (edits can land between process lives).
        if (id.role == Role.PARENT) scheduleAutoBackupRefresh()
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
    suspend fun resetDeviceMode() {
        transport?.close()
        transport = null
        reEmitJob?.cancel()
        reEmitJob = null
        settingsWatchJob?.cancel()
        settingsWatchJob = null
        identityStore.save(FamilyIdentity())
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

    /** Records the heartbeat self-test's result; publishes on change so the parent hears promptly. */
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
     * Turns on fire-and-forget backups into [uri] (a SAF document with persisted write
     * permission): from now on every rule change rewrites the file, re-sealed with the KDF
     * output cached here — the passphrase itself is never stored, and the cached key adds
     * nothing an attacker with this phone doesn't already have (all keys live here anyway).
     */
    suspend fun enableAutoBackup(uri: String, passphrase: CharArray) {
        val saltB64 = FamilyBackup.newSaltB64()
        val keyB64 = withContext(Dispatchers.Default) { FamilyBackup.deriveKeyB64(passphrase, saltB64) }
        syncStore.update {
            it.copy(
                autoBackupUri = uri,
                autoBackupKeyB64 = keyB64,
                autoBackupSaltB64 = saltB64,
                autoBackupIterations = FamilyBackup.KDF_ITERATIONS,
                autoBackupError = false,
            )
        }
    }

    suspend fun disableAutoBackup() {
        syncStore.update {
            it.copy(autoBackupUri = "", autoBackupKeyB64 = "", autoBackupSaltB64 = "", autoBackupError = false)
        }
    }

    /** Debounced trigger: one rewrite shortly after a burst of rule edits, not one per edit. */
    private fun scheduleAutoBackupRefresh() {
        autoBackupJob?.cancel()
        autoBackupJob = scope.launch {
            delay(AUTO_BACKUP_DEBOUNCE_MS)
            refreshAutoBackup()
        }
    }

    /** Rewrites the auto-backup document with the current family state. Never throws. */
    private suspend fun refreshAutoBackup() {
        val s = syncStore.current()
        if (s.autoBackupUri.isBlank() || identityStore.current().role != Role.PARENT) return
        val ok = runCatching {
            val text = withContext(Dispatchers.Default) {
                FamilyBackup.encryptWithDerivedKey(
                    buildBackupPayload(), s.autoBackupKeyB64, s.autoBackupSaltB64, s.autoBackupIterations,
                )
            }
            withContext(Dispatchers.IO) {
                val uri = android.net.Uri.parse(s.autoBackupUri)
                // "wt" truncates the previous content; fall back for providers that reject it.
                val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: context.contentResolver.openOutputStream(uri)
                checkNotNull(stream) { "no output stream" }.use { it.write(text.toByteArray()) }
            }
        }.onFailure {
            dev.walcott.debug.DebugLog.w(TAG, "auto-backup refresh failed", it)
        }.isSuccess
        syncStore.update {
            if (ok) it.copy(lastBackupAtMs = System.currentTimeMillis(), autoBackupError = false)
            else it.copy(autoBackupError = true)
        }
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
                // Ask for icons of apps shown in the list that aren't cached yet; empties out.
                val shownApps = state.children.flatMap { c -> c.apps.map { it.packageName } }
                val iconRequests = IconSync.toRequest(shownApps, iconStore.cachedAmong(shownApps))
                val snapshot = ParentSnapshot(
                    version = state.parentVersion,
                    policyJson = json.encodeToString(PolicySettings.serializer(), settings),
                    resolutions = state.resolutions,
                    bonuses = state.bonuses,
                    locationRequests = state.locationRequests,
                    commands = state.commands,
                    iconRequests = iconRequests,
                    // The parent is the fleet's update canary: children only follow up to this.
                    parentVersionCode = BuildConfig.VERSION_CODE,
                )
                val rotation = id.rotationCertB64.takeIf { it.isNotBlank() }?.let { KeyRotation.decode(it) }
                transport.publish(SyncProtocol.encodeParent(snapshot, familyKey, signingKey(id), rotation))
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
                    batteryPercent = batteryPercent(),
                    charging = batteryCharging(),
                    updateError = s.updateError,
                    enforcementGaps = s.enforcementGaps,
                    clockSkewMs = s.clockSkewMs,
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
        transport.publish(SyncProtocol.encodeChildIcons(IconPayload(id.deviceId, packed), familyKey))
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

        // Clock-tamper watch (child only): every message carries the server's clock. Only this
        // device's own echo proves a forward-set clock; see ClockGuard for the replay caveat.
        if (id.role == Role.CHILD && timeSec > 0) {
            val ownEcho = message is IncomingMessage.FromChild && message.snapshot.deviceId == id.deviceId
            ClockGuard.measuredSkew(ClockGuard.skewMs(System.currentTimeMillis(), timeSec), ownEcho)
                ?.let { recordClockSkew(it) }
        }

        when {
            id.role == Role.CHILD && message is IncomingMessage.FromParent ->
                applyParentSnapshot(message.snapshot, rotationAdopted = rotatedKey != null)
            id.role == Role.PARENT && message is IncomingMessage.FromChild -> applyChildSnapshot(message.snapshot)
            id.role == Role.PARENT && message is IncomingMessage.FromChildIcons -> applyIconPayload(message.payload)
            id.role == Role.PARENT && message is IncomingMessage.FromChildDiag -> applyDiagPayload(message.payload)
        }
    }

    /** Parent: keep the latest health report per device, for the child-detail screen. */
    private suspend fun applyDiagPayload(payload: DiagPayload) {
        syncStore.update { it.copy(diagReports = it.diagReports + (payload.deviceId to payload)) }
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
            iconStore.store(icon.packageName, bytes)
            stored++
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
        val newRulesAdopted = SyncEngine.adoptsPolicy(
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

        // Low battery: warn once when a child drops below 20% unplugged (it may die and go
        // silent); clear only past the recover mark or once charging, so it can't flap.
        val alreadyLow = snapshot.deviceId in before.lowBatteryNotified
        if (HealthAlerts.shouldAlertLowBattery(snapshot.batteryPercent, snapshot.charging, alreadyLow)) {
            SyncNotifications.notifyLowBattery(context, snapshot.displayName, snapshot.batteryPercent, snapshot.deviceId)
            syncStore.update { it.copy(lowBatteryNotified = it.lowBatteryNotified + snapshot.deviceId) }
        } else if (alreadyLow && HealthAlerts.clearsLowBattery(snapshot.batteryPercent, snapshot.charging)) {
            syncStore.update { it.copy(lowBatteryNotified = it.lowBatteryNotified - snapshot.deviceId) }
        }

        // Enforcement self-test gap: the child looked healthy but the OS wasn't actually
        // suspending what the rules block. One alert per outage; clears when a later
        // self-test passes so a relapse re-alerts.
        val hasGap = snapshot.enforcementGaps.isNotEmpty()
        if (hasGap && snapshot.deviceId !in before.selfTestNotified) {
            SyncNotifications.notifyEnforcementGap(
                context, snapshot.displayName, snapshot.enforcementGaps.size, snapshot.deviceId,
            )
            syncStore.update { it.copy(selfTestNotified = it.selfTestNotified + snapshot.deviceId) }
        } else if (!hasGap && snapshot.deviceId in before.selfTestNotified) {
            syncStore.update { it.copy(selfTestNotified = it.selfTestNotified - snapshot.deviceId) }
        }

        // Clock tamper: the child's clock disagrees with the sync server far beyond drift —
        // the bedtime/budget bypass when the date-time restriction isn't on. One-shot with
        // hysteresis (ClockGuard) so a skew hovering at the threshold can't flap.
        val alreadyClockAlerted = snapshot.deviceId in before.clockTamperNotified
        if (ClockGuard.shouldAlert(snapshot.clockSkewMs, alreadyClockAlerted)) {
            SyncNotifications.notifyClockTamper(context, snapshot.displayName, snapshot.clockSkewMs, snapshot.deviceId)
            syncStore.update { it.copy(clockTamperNotified = it.clockTamperNotified + snapshot.deviceId) }
        } else if (alreadyClockAlerted && ClockGuard.clears(snapshot.clockSkewMs)) {
            syncStore.update { it.copy(clockTamperNotified = it.clockTamperNotified - snapshot.deviceId) }
        }

        // Network (Wi-Fi/cell) location off: indoor tracking silently stops. Alert once,
        // clear when it comes back. Defaults true, so legacy children never false-alarm.
        val netLocOff = !snapshot.networkLocationOn
        if (netLocOff && snapshot.deviceId !in before.networkLocationNotified) {
            SyncNotifications.notifyNetworkLocationOff(context, snapshot.displayName, snapshot.deviceId)
            syncStore.update { it.copy(networkLocationNotified = it.networkLocationNotified + snapshot.deviceId) }
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
                // only post the notification when the parent opted to be told.
                if (settingsStore.current().newAppAlerts) {
                    SyncNotifications.notifyNewApp(
                        context, snapshot.displayName, newApps.first().label, newApps.size - 1, snapshot.deviceId,
                    )
                }
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
        /** How many app icons a child renders+sends per parent request (the rest trickle next cycle). */
        private const val ICON_RENDER_LIMIT = 8
        /** Ignore skew changes smaller than this (network-delay jitter) to spare DataStore. */
        private const val CLOCK_SKEW_RECORD_DELTA_MS = 60_000L
        /** Log lines offered to the diagnostics report before DiagFit trims to the size cap. */
        private const val DIAG_LOG_LINES = 80
        /** One backup rewrite per burst of edits (a wizard changes many settings in seconds). */
        private const val AUTO_BACKUP_DEBOUNCE_MS = 15_000L
        /** How far a restore jumps the version counter past the backup's (see restoreBackup). */
        private const val RESTORE_VERSION_LEAP = 1_000_000L
    }
}
