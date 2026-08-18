package dev.walcott.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.walcott.data.AppPolicyDto
import dev.walcott.data.InstalledApp
import dev.walcott.data.PolicySettings
import dev.walcott.data.WalcottRepository
import dev.walcott.data.withBudget
import dev.walcott.data.withSpecialDaysOwnRules
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.appStatus
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.DeviceMode
import dev.walcott.sync.FamilyIdentity
import dev.walcott.sync.SyncManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/** One app with a limit today, for the child home's cards. */
data class AppStatusUi(
    val packageName: String,
    val label: String,
    /** Today's limit for this app: its own, or the family default. */
    val budget: Duration,
    val used: Duration,
    /** Time left right now; null when blocked. */
    val remaining: Duration?,
    val blocked: Boolean,
    /**
     * Why it is blocked, when it is. The card used to carry only the fact, so bedtime and a
     * spent budget looked identical — and the "ask for more time" button was offered for both,
     * although minutes cannot lift a window (bedtime outranks every budget in
     * [dev.walcott.rules.appStatus]). The child asked, a parent granted, and nothing happened.
     */
    val blockReason: dev.walcott.rules.BlockReason? = null,
    /** True when this app is running on the family default rather than a limit of its own. */
    val fromDefault: Boolean,
) {
    /**
     * Whether more minutes could actually change anything. False during bedtime and screen-free
     * windows, which no grant can shorten — and while the clock is untrusted, where nothing is
     * being counted in the first place.
     */
    val moreTimeWouldHelp: Boolean
        get() = blockReason == null || blockReason == dev.walcott.rules.BlockReason.BUDGET_EXHAUSTED
}

data class ChildUiState(
    val loading: Boolean = true,
    val bedtimeActive: Boolean = false,
    /** Today's configured bedtime window, if any (for the "bedtime tonight" row). */
    val bedtimeTonight: dev.walcott.rules.TimeWindow? = null,
    /** Apps with a limit today — their own or the family default — busiest first. */
    val apps: List<AppStatusUi> = emptyList(),
    /** The limit every app gets today unless something was set for it; null when there is none. */
    val defaultBudget: Duration? = null,
    /** Minutes earned by staying off the phone today; they widen every app's allowance. */
    val earnedMinutes: Int = 0,
    /**
     * Today's family-wide screen-free windows. The child could not see these anywhere: they are
     * the rule most likely to be the answer to "why did everything just stop", and the home only
     * ever showed the app that ran out.
     */
    val screenFreeToday: List<dev.walcott.rules.TimeWindow> = emptyList(),
    /**
     * The screen-free window in force right now, if any. Bedtime had this and screen-free time
     * did not, so a window that closes the whole phone left the home reading "Nothing is running
     * out" over a column of blocked apps — the one rule most likely to be the answer to "why did
     * everything just stop", and the only one the screen never said out loud.
     */
    val screenFreeNow: dev.walcott.rules.TimeWindow? = null,
)

/** One app in the parent's list, with whatever was set for it (null = the family default). */
/**
 * The apps worth a card on the child's home, each with its label: everything with a limit
 * today, out of the ones the child has actually used and the ones somebody set a rule for.
 * With no family default that is just the capped apps; with one it is what they have been
 * using — never the whole launcher, which is what listing "every app with a limit" would mean.
 *
 * Anything not installed any more is dropped, which [label] answers by returning null for it.
 * Both sources outlive the app: a limit stays in the policy on purpose (it has to survive an
 * uninstall, or uninstalling and reinstalling would be the way to wipe it), and today's counter
 * keeps the minutes that were really spent. Neither is a reason to keep offering the child a
 * card for something they can no longer open.
 *
 * [managed] is what this device can actually block ([dev.walcott.data.AppInventory.managedPackages]),
 * and it is the last word here. Screen time is counted for a wider set on purpose — the browser,
 * the video app and the gallery ship as system apps on most phones, and a parent has to be able
 * to SEE where the day went — but counting is not enforcing, and the enforcement loop has always
 * refused to suspend a system app. Without this filter the family default gave every one of them
 * a card that counted down to "Blocked" over an app that went on opening perfectly well: a
 * screen that contradicted the phone, on exactly the apps a day disappears into.
 */
internal fun childCardPackages(
    config: dev.walcott.rules.FamilyConfig,
    usedToday: Set<String>,
    dayType: DayType,
    managed: Set<String>,
    label: (String) -> String?,
): List<Pair<String, String>> =
    (config.perAppPolicies.keys + usedToday)
        .filter { it in managed && config.budgetFor(it, dayType) != null }
        .mapNotNull { pkg -> label(pkg)?.let { pkg to it } }

data class AppRow(
    val app: InstalledApp,
    val policy: AppPolicyDto?,
    /** Which children have this app installed (registry name, legacy device name as fallback). */
    val owners: List<dev.walcott.data.AppCatalog.Owner> = emptyList(),
)

/**
 * One family's screens. A parent holding several has one of these per family (keyed by family id
 * where it is created), so nothing on screen can accidentally mix two households; the handful of
 * genuinely device-wide actions — the PIN, the app lock — go through [hub] to reach them all.
 */
class WalcottViewModel(
    val repository: WalcottRepository,
    private val sync: SyncManager,
    private val hub: dev.walcott.FamilyHub,
) : ViewModel() {

    val identity: StateFlow<FamilyIdentity> = sync.identity
    val bootMode: StateFlow<DeviceMode?> = sync.bootMode
    val children: StateFlow<List<ChildSnapshot>> =
        sync.state.map { it.children }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastSeen: StateFlow<Map<String, Long>> =
        sync.state.map { it.lastSeen }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The whole device-local sync state, for the screens that need more of it than one field: the
     * notification log reads its pages AND the app names of the device that sent them, and those
     * have to come from the same emission or a page renders against a stale app list.
     */
    val syncState: StateFlow<dev.walcott.sync.SyncState> = sync.state
    /**
     * The settings edited on this phone that are not on the wire yet (see [PolicyDiff]), so each
     * screen can mark exactly what it is showing that no child has been told about.
     *
     * Recomputed from the deployed snapshot rather than tracked as the edits happen: a set built
     * by remembering "what did I touch" drifts the moment a value is changed and changed back,
     * and would then mark a setting as pending for ever.
     */
    val pendingPolicyKeys: StateFlow<Set<String>> =
        combine(sync.state, repository.settingsFlow) { state, current ->
            val deployed = state.deployedPolicyJson.takeIf { it.isNotBlank() }?.let {
                runCatching { policyJson.decodeFromString(dev.walcott.data.PolicySettings.serializer(), it) }
                    .getOrNull()
            }
            dev.walcott.data.PolicyDiff.changedKeys(deployed, current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val policyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** deviceId -> when that child last confirmed it had the parent's rules. */
    val policyConfirmedAt: StateFlow<Map<String, Long>> =
        sync.state.map { it.policyConfirmedAtMs }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The parent's current rules version, to tell which children have caught up. */
    val parentVersion: StateFlow<Long> =
        sync.state.map { it.parentVersion }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val pendingRequests: StateFlow<List<SyncManager.PendingRequest>> = sync.pendingRequests
    val pendingAsks: StateFlow<List<SyncManager.PendingAsk>> = sync.pendingAsks
    val installExemption: StateFlow<Long> = sync.installExemption
    /** Package of a parent-pushed install still waiting for its tap on this device, or "". */
    val pendingInstall: StateFlow<String> = sync.pendingInstall
    val pendingInstallLabel: StateFlow<String> = sync.pendingInstallLabel
    /** This device's own unanswered requests/asks, for the child home's "waiting" section. */
    val myPendingRequests: StateFlow<List<dev.walcott.sync.ExtraTimeRequest>> = sync.myPendingRequests
    val myPendingAsks: StateFlow<List<dev.walcott.sync.ChildRequest>> = sync.myPendingAsks
    /** The parents' latest answer (approval/denial/bonus), until dismissed. */
    val notice: StateFlow<dev.walcott.sync.NoticeEntry?> = sync.notice

    fun dismissNotice() = viewModelScope.launch { sync.dismissNotice() }

    /** Bumps when child app icons arrive over sync, so the app list re-reads the cache. */
    val iconRefresh: StateFlow<Int> = sync.iconsCached
    /** Cached child app icon bytes for [pkg] (parent-side), or null if not fetched yet. */
    fun childAppIcon(pkg: String): ByteArray? = sync.iconBytes(pkg)

    fun askFor(kind: String, text: String) = viewModelScope.launch { sync.askFor(kind, text) }
    fun allowInstallsFor(durationMs: Long) = viewModelScope.launch { sync.allowInstallsFor(durationMs) }
    fun endInstallExemption() = viewModelScope.launch { sync.endInstallExemption() }

    /** Approves a child's one-app install request: resolve and push the single-app install. */
    fun approveInstallAsk(requestId: String) = viewModelScope.launch { sync.approveInstallAsk(requestId) }

    /** The two answers to an app that appeared on a child device unapproved (see InstallGuard). */
    fun removeChildApp(deviceId: String, pkg: String) = viewModelScope.launch {
        sync.sendCommand(deviceId, dev.walcott.sync.RemoteAction.UNINSTALL_APP, arg = pkg)
    }

    fun allowChildApp(deviceId: String, pkg: String) = viewModelScope.launch {
        sync.sendCommand(deviceId, dev.walcott.sync.RemoteAction.ALLOW_APP, arg = pkg)
    }

    // --- Remote support: the lock screen, and what arrived on that phone ---

    /**
     * Sets that device's unlock PIN, or removes its lock screen when [pin] is blank.
     *
     * The PIN is remembered on THIS phone (see [SyncState.lastLockPin]) because the person doing
     * the helping has to be able to read it back down the line — "I have changed it and I do not
     * know to what" is not help. It never travels anywhere else.
     */
    fun setChildLockPin(deviceId: String, pin: String) = viewModelScope.launch {
        sync.rememberLockPin(deviceId, pin)
        sync.sendCommand(deviceId, dev.walcott.sync.RemoteAction.SET_LOCK_PIN, arg = pin)
    }

    fun lockChildNow(deviceId: String) = viewModelScope.launch {
        sync.sendCommand(deviceId, dev.walcott.sync.RemoteAction.LOCK_NOW)
    }

    /**
     * deviceId -> the unlock PIN this phone last set there (see [dev.walcott.sync.SyncState.lastLockPin]).
     *
     * A flow rather than a lookup: the PIN is written just before the command goes out, so a screen
     * that read it once while drawing would show nothing at all until something else on it changed
     * — and the whole point is reading the new number back to somebody on the telephone, seconds
     * after tapping.
     */
    val lastLockPins: StateFlow<Map<String, String>> =
        sync.state.map { it.lastLockPin }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Corrects who a member is, long after enrollment (see [dev.walcott.data.MemberKind]).
     *
     * Deliberately only the label: the other kind's DEFAULTS are not applied. Defaults answer
     * "what should this start as", and by the time somebody uses this row the phone has a history
     * — re-seeding it would switch rules and locks nobody asked to change.
     */
    fun setMemberKind(childId: String, kind: String) = viewModelScope.launch {
        repository.updateSettings { s ->
            s.copy(
                children = s.children.map {
                    if (it.childId == childId) it.copy(kind = dev.walcott.data.MemberKind.of(kind)) else it
                },
            )
        }
    }

    /**
     * Asks that device for its notification log.
     *
     * [pkg] narrows it to one app — the question a family usually has ("did the message from the
     * clinic arrive?"), answered without reading a day of somebody's private messages. [beforeMs]
     * pages backwards: 0 starts at now, and the oldest entry of a page is the cursor for the one
     * before it.
     */
    fun requestNotifications(deviceId: String, pkg: String = "", beforeMs: Long = 0) =
        viewModelScope.launch {
            sync.sendCommand(
                deviceId,
                dev.walcott.sync.RemoteAction.NOTIFICATION_LOG,
                arg = dev.walcott.sync.NotificationQuery.encode(pkg, beforeMs),
            )
        }

    // --- Domain monitor: the child device, driven by a parent holding it ---

    /** Live view of the current look at what apps resolve; see [dev.walcott.net.DomainMonitor]. */
    val domainMonitor: StateFlow<dev.walcott.net.DomainMonitor.State> = dev.walcott.net.DomainMonitor.state

    /** package -> label for the apps on THIS device, to name what the tunnel attributes. */
    val installedLabels: StateFlow<Map<String, String>> =
        flow {
            emit(
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.inventory.launchableApps().associate { it.packageName to it.label }
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Whether the DNS tunnel is really up; see [dev.walcott.net.VpnStatus]. */
    val dnsTunnelUp: StateFlow<Boolean> = dev.walcott.net.VpnStatus.tunnelUp

    fun startDomainMonitor() = dev.walcott.net.DomainMonitor.start()

    fun stopDomainMonitor() = dev.walcott.net.DomainMonitor.stop()

    /**
     * Hands the chosen domains to the parent — deliberately the only path off this device.
     * Everything else the monitor saw stays in memory and dies with the session.
     */
    fun sendDomainsToParent(packageName: String, label: String, domains: List<String>) = viewModelScope.launch {
        sync.sendDomains(packageName, label, domains)
    }

    /** How the last selection this device sent is getting on (see [dev.walcott.sync.DomainDelivery]). */
    val domainDelivery: StateFlow<dev.walcott.sync.DomainBatch?> = sync.domainDelivery

    // --- Domain requests (parent mode) ---

    /** Domain selections from children that arrived whole and are waiting for an answer. */
    val domainRequests: StateFlow<List<dev.walcott.sync.DomainInboxEntry>> = sync.pendingDomainBatches

    /** Turns a reviewed selection into web-filter rules; see [SyncManager.applyDomainRules]. */
    fun applyDomainRules(batchId: String, domains: List<String>, familyWide: Boolean, anyApp: Boolean) =
        viewModelScope.launch { sync.applyDomainRules(batchId, domains, familyWide, anyApp) }

    fun discardDomainRequest(batchId: String) = viewModelScope.launch { sync.discardDomainBatch(batchId) }

    suspend fun becomeParent(familyName: String) = sync.becomeParent(familyName)

    /** The relay this family talks through, and whether it can still be changed (see [setRelayServer]). */
    val relayServer: StateFlow<String> =
        sync.identity.map { it.ntfyServer }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), dev.walcott.sync.RelayServer.DEFAULT)

    /** Whether messages are currently being rejected by the relay (see [dev.walcott.sync.PublishHealth]). */
    val publishHealth: StateFlow<dev.walcott.sync.PublishHealth.Status> = dev.walcott.sync.PublishHealth.status

    /**
     * Whether this family's rules have outgrown one relay message (see [dev.walcott.sync.ParentFit]).
     * Every publish is then refused, so nothing reaches any child — the one failure this app must
     * never leave to a debug log.
     */
    val policyTooLarge: StateFlow<Boolean> =
        sync.state.map { it.policyTooLarge }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun setRelayServer(server: String): dev.walcott.sync.SyncManager.RelayChangeResult =
        sync.setRelayServer(server)

    suspend fun pairAsChild(pairingText: String): Boolean = sync.pairAsChild(pairingText)
    fun setMode(mode: DeviceMode) = viewModelScope.launch { sync.setMode(mode) }
    fun resetDeviceMode() = viewModelScope.launch { sync.resetDeviceMode() }
    // Device-level, so they are written to every family (each stores its own copy — see
    // FamilyHub.updateEveryIdentity): locking "the app" cannot mean locking one household.
    fun setAppLock(enabled: Boolean) =
        viewModelScope.launch { hub.updateEveryIdentity { it.copy(appLock = enabled) } }

    fun setAppLockBiometric(enabled: Boolean) =
        viewModelScope.launch { hub.updateEveryIdentity { it.copy(appLockBiometric = enabled) } }

    // --- Families held by this device (parent mode) ---

    /** One card per family for the chooser: name, children, what is waiting, what is wrong. */
    val familySummaries: StateFlow<List<dev.walcott.FamilySummary>> = hub.summaries

    /** Which family the screens are showing; null only before the registry has been read. */
    val activeFamilyId: StateFlow<String?> = hub.activeId

    fun switchFamily(id: String) = viewModelScope.launch { hub.setActive(id) }

    /**
     * Shows the family [childId] belongs to, if some other family has them. What makes a
     * notification tap land on the right child on a phone managing several families: the alert
     * only carries the child, and the child is enough to find their family.
     */
    fun switchToFamilyOf(childId: String) = viewModelScope.launch {
        hub.scopeForChild(childId)?.let { if (it.id != hub.activeId.value) hub.setActive(it.id) }
    }

    /** Creates a family and returns its id (runs on the app scope; safe if the dialog closes). */
    suspend fun createFamily(name: String): String = hub.createFamily(name)

    /** Adopts a backup as an ADDITIONAL family, leaving the ones already here alone. */
    suspend fun addFamilyFromBackup(fileJson: String, passphrase: CharArray): dev.walcott.FamilyHub.AddResult =
        hub.addFamilyFromBackup(fileJson, passphrase)

    /** Stops managing a family (its children keep the last rules they received — see the dialog). */
    fun removeFamily(id: String) = viewModelScope.launch { hub.removeFamily(id) }

    // --- Children registry (parent mode) ---

    /**
     * Registers a member and returns its id so the UI can navigate to the detail right away.
     *
     * [kind] decides what they START with, never what they can have (see [dev.walcott.data.MemberKind]):
     *
     *  - A **child** gets location tracking, because that is what a parent expects from enrolling
     *    one, and no protections beyond the family's.
     *  - An **adult** gets the accident-proofing instead ([DeviceRestrictions.RECOMMENDED_FOR_ADULT])
     *    and a ringer that stays audible — the two things somebody supporting a phone from a
     *    distance always ends up wanting — and NO location tracking, because an adult's whereabouts
     *    is not something to switch on for them by default. One tap turns it on if they agree.
     */
    fun addChild(name: String, kind: String = dev.walcott.data.MemberKind.CHILD): String {
        val childId = java.util.UUID.randomUUID().toString()
        val adult = kind == dev.walcott.data.MemberKind.ADULT
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    children = it.children + dev.walcott.data.ChildEntry(
                        childId,
                        name,
                        overrides = dev.walcott.data.ChildOverrides(
                            trackingIntervalMinutes = if (adult) 0 else DEFAULT_TRACKING_MINUTES,
                            deviceRestrictions = if (adult) {
                                it.deviceRestrictions + dev.walcott.enforcement.DeviceRestrictions.RECOMMENDED_FOR_ADULT
                            } else {
                                null
                            },
                            keepRingerAudible = if (adult) true else null,
                        ),
                        addedAtMs = System.currentTimeMillis(),
                        kind = dev.walcott.data.MemberKind.of(kind),
                    ),
                )
            }
        }
        return childId
    }

    fun renameChild(childId: String, name: String) = viewModelScope.launch {
        repository.updateSettings { s ->
            s.copy(children = s.children.map { if (it.childId == childId) it.copy(name = name) else it })
        }
    }

    /**
     * Removes a member from the family, and — when [releaseDevices] — asks the phone(s) they are
     * enrolled on to hand themselves back first (see [dev.walcott.sync.RemoteAction.RELEASE_DEVICE]).
     *
     * The release is queued BEFORE the registry entry goes, because the entry is how the screens
     * find those devices; the command itself is addressed to the device and survives the removal,
     * so a phone that is off is still freed when it comes back. Without it, removing a child left
     * a phone enforcing the family's rules with nobody left to change them.
     */
    fun removeChild(childId: String, releaseDevices: Boolean = false) = hub.launchDurable {
        if (releaseDevices) {
            sync.devicesOfChild(childId).forEach { deviceId ->
                runCatching { sync.releaseChildDevice(deviceId) }
            }
        }
        repository.updateSettings { s -> s.copy(children = s.children.filterNot { it.childId == childId }) }
    }

    /** Frees one supervised phone without touching the family registry (see [removeChild]). */
    fun releaseChildDevice(deviceId: String) = hub.launchDurable { sync.releaseChildDevice(deviceId) }

    /**
     * Takes an orphaned device back into the family under [name], by giving it a registry entry
     * with the childId it is already enrolled under.
     *
     * The recovery from a removal somebody regrets. Re-pairing is not available from the child's
     * own screen once it is enrolled, so without this the only way back was releasing the phone
     * and setting it up from scratch — which is a factory reset for a mis-tap.
     */
    fun adoptOrphanDevice(childId: String, name: String) = viewModelScope.launch {
        if (childId.isBlank()) return@launch
        repository.updateSettings { s ->
            if (s.children.any { it.childId == childId }) {
                s
            } else {
                s.copy(
                    children = s.children + dev.walcott.data.ChildEntry(
                        childId = childId,
                        name = name,
                        addedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** Rename the family (shown on the parent home and on every enrolled child). */
    fun renameFamily(name: String) = viewModelScope.launch {
        repository.updateSettings { it.copy(familyName = name) }
    }

    /** Forget an orphaned device (it re-appears if it is still alive and paired). */
    fun removeLegacyDevice(deviceId: String) = viewModelScope.launch { sync.removeChildDevice(deviceId) }

    /**
     * Applies [transform] to one child's overrides. The scoped rule editors funnel through
     * here so "edit for this child" and "edit for the family" share the same shapes.
     */
    private fun updateOverrides(childId: String, transform: (dev.walcott.data.ChildOverrides) -> dev.walcott.data.ChildOverrides) =
        viewModelScope.launch {
            repository.updateSettings { s ->
                s.copy(
                    children = s.children.map {
                        if (it.childId == childId) it.copy(overrides = transform(it.overrides)) else it
                    },
                )
            }
        }

    fun setChildOverrides(childId: String, overrides: dev.walcott.data.ChildOverrides) = viewModelScope.launch {
        repository.updateSettings { s ->
            s.copy(children = s.children.map { if (it.childId == childId) it.copy(overrides = overrides) else it })
        }
    }

    fun requestExtraTimeRemote(categoryId: String, minutes: Int, reason: String, targetLabel: String = "") =
        viewModelScope.launch { sync.requestExtraTime(categoryId, minutes, reason, targetLabel) }

    /** This child device's own launchable (non-system) apps, for the "request more time" list. */
    val myApps: StateFlow<List<InstalledApp>> =
        flow {
            val apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.inventory.launchableApps().filterNot { it.isSystem }
            }
            emit(apps)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun resolveRequest(requestId: String, approved: Boolean, grantedMinutes: Int) =
        viewModelScope.launch { sync.resolveRequest(requestId, approved, grantedMinutes) }

    fun giveBonus(targetDeviceId: String, categoryId: String, minutes: Int) =
        viewModelScope.launch { sync.giveBonus(targetDeviceId, categoryId, minutes) }

    /** Ask a child device to report its current location on its next check-in. */
    fun requestLocation(targetDeviceId: String) =
        viewModelScope.launch { sync.requestLocation(targetDeviceId) }

    /** Queue a remote fix for a child device (see [dev.walcott.sync.RemoteAction]). */
    fun sendRemoteCommand(targetDeviceId: String, action: String, arg: String = "") =
        viewModelScope.launch { sync.sendCommand(targetDeviceId, action, arg) }

    /** Withdraw a queued remote command before the child fetches it (best-effort). */
    fun cancelRemoteCommand(commandId: String) = viewModelScope.launch { sync.cancelCommand(commandId) }

    /** Withdraw a pending "locate now" for a device (best-effort). */
    fun cancelLocationRequest(targetDeviceId: String) =
        viewModelScope.launch { sync.cancelLocationRequest(targetDeviceId) }

    /** Turn the 48h location trail on/off for this child (off = current position only). */
    fun setLocationHistory(childId: String, enabled: Boolean) = viewModelScope.launch {
        repository.updateSettings { s ->
            s.copy(
                children = s.children.map {
                    if (it.childId == childId) {
                        it.copy(overrides = it.overrides.copy(locationHistoryEnabled = enabled))
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Restrict the child self-update to Wi-Fi (family-wide policy). */
    fun setUpdateWifiOnly(enabled: Boolean) = viewModelScope.launch {
        repository.updateSettings { it.copy(updateWifiOnly = enabled) }
    }

    /** Notify the parent when a child installs a new app (family-wide policy). */
    fun setNewAppAlerts(enabled: Boolean) = viewModelScope.launch {
        repository.updateSettings { it.copy(newAppAlerts = enabled) }
    }

    /** Family-default location tracking interval (0 = off); children inherit unless overridden. */
    fun setFamilyTrackingInterval(minutes: Int) = viewModelScope.launch {
        repository.updateSettings { it.copy(trackingIntervalMinutes = minutes) }
    }

    /** Family-default 48h location history; children inherit unless overridden. */
    fun setFamilyLocationHistory(enabled: Boolean) = viewModelScope.launch {
        repository.updateSettings { it.copy(locationHistoryEnabled = enabled) }
    }

    /** Set this child's periodic location-tracking interval (0 = off). */
    fun setTrackingInterval(childId: String, minutes: Int) = viewModelScope.launch {
        repository.updateSettings { s ->
            s.copy(
                children = s.children.map {
                    if (it.childId == childId) {
                        it.copy(overrides = it.overrides.copy(trackingIntervalMinutes = minutes))
                    } else {
                        it
                    }
                },
            )
        }
    }

    // --- Guided setup (presets) ---

    /**
     * The wizard's one budget question: the limit every app gets unless something is set for it.
     * [weekdaysOnly] once the family has said weekends are different, so the weekend step's
     * value isn't overwritten when the parent walks back a step.
     */
    fun setDefaultBudgetPreset(minutes: Int?, weekdaysOnly: Boolean = false) = viewModelScope.launch {
        repository.updateSettings {
            if (weekdaysOnly) {
                dev.walcott.data.SetupPresets.withWeekdayDefaultBudget(it, minutes)
            } else {
                dev.walcott.data.SetupPresets.withDefaultBudget(it, minutes)
            }
        }
    }

    fun setWeekendDefaultBudget(minutes: Int?) = viewModelScope.launch {
        repository.updateSettings { dev.walcott.data.SetupPresets.withWeekendDefaultBudget(it, minutes) }
    }

    /** "Weekends are the same as weekdays": one cap for every day, both edges back to midnight. */
    fun clearWeekendDistinction() = viewModelScope.launch {
        repository.updateSettings { dev.walcott.data.SetupPresets.withoutWeekendDistinction(it) }
    }

    /** Recommended anti-tamper set plus the parent's choice on blocking new installs. */
    fun applyProtectionPreset(blockInstalls: Boolean) = viewModelScope.launch {
        repository.updateSettings { dev.walcott.data.SetupPresets.withProtection(it, blockInstalls) }
    }

    // Reloads the 7-day history whenever today's usage changes.
    val weeklyUsage: StateFlow<Map<Long, Map<String, java.time.Duration>>> =
        repository.usageTodayAllFlow.map { repository.weeklyUsage() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Weekly usage aggregated across all children, for the parent's report (the local
     * [weeklyUsage] is empty on a parent phone). Built from each child's reported history.
     */
    val childrenWeeklyUsage: StateFlow<Map<Long, Map<String, Duration>>> =
        children.map { list ->
            val byDay = mutableMapOf<Long, MutableMap<String, Duration>>()
            list.forEach { child ->
                child.history.forEach { day ->
                    val byCat = byDay.getOrPut(day.epochDay) { mutableMapOf() }
                    day.usage.forEach { e ->
                        byCat[e.categoryId] = (byCat[e.categoryId] ?: Duration.ZERO) + Duration.ofSeconds(e.seconds)
                    }
                }
            }
            byDay
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Set (or clear, with null) the family idle-earn config. */
    fun setIdleEarn(config: dev.walcott.data.IdleEarnDto?) =
        viewModelScope.launch { repository.updateSettings { it.copy(idleEarn = config) } }

    /** Set (or clear) the earn window for a day type, on the current idle-earn config. */
    fun setEarnWindow(dayType: DayType, window: dev.walcott.data.WindowDto?) = viewModelScope.launch {
        repository.updateSettings { s ->
            val current = s.idleEarn ?: return@updateSettings s
            val windows = current.earnWindows.toMutableMap()
            if (window == null) windows.remove(dayType.name) else windows[dayType.name] = listOf(window)
            s.copy(idleEarn = current.copy(earnWindows = windows))
        }
    }

    /**
     * Whether the calendar's special days carry their own daily limits. See
     * [dev.walcott.data.withSpecialDaysOwnBudget] for why turning it on seeds the column.
     */
    /** The one family-wide switch behind every special-day row (see [withSpecialDaysOwnRules]). */
    fun setSpecialDaysOwnRules(on: Boolean) = viewModelScope.launch {
        repository.updateSettings { it.withSpecialDaysOwnRules(on) }
    }

    /** Adds a special day. [childIds] empty = the whole family; a birthday names one child. */
    fun addHoliday(epochDay: Long, childIds: Set<String> = emptySet()) =
        viewModelScope.launch { repository.updateSettings { it.withHolidayScope(epochDay, childIds) } }

    /** Re-points an existing day at a different set of children (or back at the whole family). */
    fun setHolidayScope(epochDay: Long, childIds: Set<String>) =
        viewModelScope.launch { repository.updateSettings { it.withHolidayScope(epochDay, childIds) } }

    fun removeHoliday(epochDay: Long) =
        viewModelScope.launch { repository.updateSettings { it.withoutHoliday(epochDay) } }

    fun addVacation(startEpochDay: Long, endEpochDay: Long, childIds: Set<String> = emptySet()) =
        viewModelScope.launch {
            repository.updateSettings {
                it.withVacationScope(dev.walcott.data.VacationDto(startEpochDay, endEpochDay), childIds)
            }
        }

    fun setVacationScope(period: dev.walcott.data.VacationDto, childIds: Set<String>) =
        viewModelScope.launch { repository.updateSettings { it.withVacationScope(period, childIds) } }

    fun removeVacation(period: dev.walcott.data.VacationDto) =
        viewModelScope.launch { repository.updateSettings { it.withoutVacation(period) } }

    /**
     * Moves the weekend edges. Minute-of-day, or null to leave the edge at midnight (the
     * weekend then runs Saturday 00:00 → Monday 00:00, as it always has).
     */
    fun setWeekendEdges(startsFridayAtMinute: Int?, endsSundayAtMinute: Int?) = viewModelScope.launch {
        repository.updateSettings {
            it.copy(
                weekendStartsFridayAtMinute = startsFridayAtMinute,
                weekendEndsSundayAtMinute = endsSundayAtMinute,
            )
        }
    }

    fun addBlockedDomain(raw: String, childId: String? = null) {
        val domain = normalizeDomain(raw)
        if (domain.isEmpty()) return
        if (childId == null) {
            viewModelScope.launch { repository.updateSettings { it.copy(blockedDomains = it.blockedDomains + domain) } }
        } else {
            updateOverrides(childId) { it.copy(blockedDomains = it.blockedDomains.orEmpty() + domain) }
        }
    }

    /**
     * Turns one of the built-in lists on or off for the whole family (see
     * [dev.walcott.rules.Blocklists]). Family-wide by design: what travels is the id, and the
     * lists are one household decision — a child can still have domains of its own.
     */
    fun setBlocklist(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    enabledBlocklists = if (enabled) it.enabledBlocklists + id else it.enabledBlocklists - id,
                )
            }
        }
    }

    /**
     * Waives the blocklists for one app, or puts it back under them (see
     * [dev.walcott.data.PolicySettings.blocklistExemptApps]).
     *
     * The family's own typed domains keep applying either way — this only ever waives the lists,
     * which is what the screen says and the only thing that makes the switch safe to offer.
     */
    fun setBlocklistExempt(packageName: String, exempt: Boolean) {
        if (packageName.isBlank()) return
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    blocklistExemptApps = if (exempt) {
                        it.blocklistExemptApps + packageName
                    } else {
                        it.blocklistExemptApps - packageName
                    },
                )
            }
        }
    }

    /**
     * How often the children re-download the public lists behind those switches. One household
     * decision like the lists themselves, and it reaches them through the same policy push.
     */
    fun setBlocklistRefreshHours(hours: Int) {
        viewModelScope.launch { repository.updateSettings { it.copy(blocklistRefreshHours = hours) } }
    }

    fun removeBlockedDomain(domain: String, childId: String? = null) =
        if (childId == null) {
            viewModelScope.launch { repository.updateSettings { it.copy(blockedDomains = it.blockedDomains - domain) } }
        } else {
            updateOverrides(childId) { it.copy(blockedDomains = it.blockedDomains.orEmpty() - domain) }
        }

    fun setDeviceRestriction(key: String, enabled: Boolean, childId: String? = null) =
        if (childId == null) {
            viewModelScope.launch {
                repository.updateSettings {
                    it.copy(deviceRestrictions = if (enabled) it.deviceRestrictions + key else it.deviceRestrictions - key)
                }
            }
        } else {
            updateOverrides(childId) {
                val current = it.deviceRestrictions.orEmpty()
                it.copy(deviceRestrictions = if (enabled) current + key else current - key)
            }
        }

    fun addDomainAppRule(rawDomain: String, packageName: String, allowOnlyFromApp: Boolean) {
        val domain = normalizeDomain(rawDomain)
        if (domain.isEmpty() || packageName.isEmpty()) return
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(domainAppRules = it.domainAppRules + dev.walcott.data.DomainAppRuleDto(domain, packageName, allowOnlyFromApp))
            }
        }
    }

    fun removeDomainAppRule(index: Int) = viewModelScope.launch {
        repository.updateSettings { it.copy(domainAppRules = it.domainAppRules.filterIndexed { i, _ -> i != index }) }
    }

    private fun normalizeDomain(raw: String): String =
        raw.trim().lowercase()
            .substringAfter("://")
            .substringBefore("/")
            .removePrefix("www.")
            .trim()


    // Low-frequency clock so the UI reacts to time-based limits (bedtime, windows).
    private val clock = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(15_000)
        }
    }

    /**
     * Everything queued or in flight toward the children, for the home's pending-actions
     * list. Re-derived on the 15s clock so entries age out without a sync event.
     */
    val pendingOps: StateFlow<List<dev.walcott.sync.SyncEngine.PendingOp>> =
        combine(sync.state, clock) { s, _ ->
            dev.walcott.sync.SyncEngine.pendingOps(s.commands, s.locationRequests, s.children, System.currentTimeMillis())
                // Dismissed by the parent: an install the child never finishes shouldn't sit
                // on the home for a week — they can always send the request again.
                .filterNot { it.id.isNotBlank() && it.id in s.dismissedOpIds }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hides a delivered-but-unfinished operation from the home for good. */
    fun dismissPendingOp(id: String) = viewModelScope.launch { sync.dismissPendingOp(id) }

    /**
     * Wall-clock ms of the last proof the family channel worked, once that's long enough ago
     * to admit on the child home; null while healthy. On the 15s clock so it ages in/out.
     */
    val channelOfflineSince: StateFlow<Long?> =
        combine(sync.state, clock) { s, _ ->
            dev.walcott.sync.ChannelHealth.offlineSinceMs(s.lastChannelOkMs, System.currentTimeMillis())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * True on a child whose clock is provably wrong (see [dev.walcott.sync.ClockGuard]). The
     * rules fail closed then, so the child home has to say why everything went quiet.
     */
    val clockTampered: StateFlow<Boolean> =
        sync.state.map { dev.walcott.sync.ClockGuard.isTampered(it.clockSkewMs) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Recent health reports per child device (parent side), newest first. A report left over
     * from a build that only kept the last one is surfaced as the single entry it is, so it
     * stays readable until the next report files it into the history for good.
     */
    val diagHistory: StateFlow<Map<String, List<dev.walcott.sync.StoredDiag>>> =
        sync.state.map { state ->
            // Deduplicated on the way out: a report is identified by the instant the child
            // filed it, and an archive that already holds the same one twice (filed by a build
            // that didn't check — see SyncManager.applyDiagPayload) should read as one report.
            state.diagHistory.mapValues { (_, reports) -> reports.distinctBy { it.report.atMs } } +
                state.diagReports
                    .filterKeys { state.diagHistory[it].isNullOrEmpty() }
                    .mapValues { (_, report) -> listOf(dev.walcott.sync.StoredDiag(report)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The activity feed, newest first (parent side), for the home wall and child dashboards.
     * Ordered by when things HAPPENED, not by when we heard: a child reports what its rules did
     * on its next publish, so a phone that was offline all evening delivers a bedtime from hours
     * ago after events the parent logged since. Sorting by arrival put those lines at the top
     * with a timestamp that contradicted their position.
     */
    val recentEvents: StateFlow<List<dev.walcott.sync.ParentEvent>> =
        sync.state.map { state -> state.events.sortedByDescending { it.atMs } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Per-child, per-app daily ledger (see [dev.walcott.sync.UsageLedger.mergeByApp]): the only
     * place a month of app-by-app history exists, since a child's snapshot carries seven days.
     */
    val usageByApp: StateFlow<Map<String, Map<Long, Map<String, Long>>>> =
        sync.state.map { it.usageByApp }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Per-child daily usage ledger (see [dev.walcott.sync.UsageLedger]), for the dashboard average. */
    val usageLedgers: StateFlow<Map<String, Map<Long, Long>>> =
        sync.state.map { it.usageHistory }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Per-child ledger of what the filter and the rules blocked (see [dev.walcott.sync.BlockLedger]). */
    val blockLedgers: StateFlow<Map<String, dev.walcott.sync.BlockLedger.Ledger>> =
        sync.state.map { it.blockLedgers }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // --- Family backup / restore ---

    /** When the parent last saved a family backup (0 = never), for the backup card. */
    val lastBackupAtMs: StateFlow<Long> =
        sync.state.map { it.lastBackupAtMs }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Builds the encrypted backup file's text; the caller writes it wherever the user chose. */
    suspend fun createBackup(passphrase: CharArray): String = sync.createBackup(passphrase)

    /** Record that a backup file actually reached its destination. */
    fun recordBackupSaved() = viewModelScope.launch { sync.recordBackupSaved() }

    /** Restores a family from a backup file. False = wrong passphrase or invalid file. */
    suspend fun restoreBackup(fileJson: String, passphrase: CharArray): Boolean =
        sync.restoreBackup(fileJson, passphrase)

    /**
     * True when this parent has a PIN but no key for the on-device copies yet, so the nightly
     * backup cannot run. Only ever true for a family that already existed before the copies did:
     * a new parent sets the PIN moments after creating the family and is keyed from day one.
     * Surfaced on the home rather than left to a settings screen — a safety net nobody is told
     * about is the same opt-in problem it was built to remove.
     */
    val localBackupNeedsPin: StateFlow<Boolean> = combine(
        sync.state, repository.settingsFlow,
    ) { state, settings -> settings.pinHash != null && state.localBackupKeyB64.isBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Derives and caches the on-device backup key from a PIN the parent just re-entered. */
    suspend fun enableLocalBackup(pin: String) = sync.cacheLocalBackupKey(pin)

    /** Toggle the "your backup is missing/stale" nudge notifications (all families). */
    fun setBackupReminders(enabled: Boolean) =
        viewModelScope.launch { hub.updateEveryIdentity { it.copy(backupReminders = enabled) } }

    // --- Emergency release (see dev.walcott.sync.PanicProtocol) ---

    /** This device's own release request and the gates around it (child mode). */
    val panicStatus: StateFlow<SyncManager.PanicStatus> = sync.panicStatus

    /** Starts the 24-hour request. False when a gate refuses it (the UI mirrors the same gates). */
    suspend fun startPanic(): Boolean = sync.startPanic()

    /** The child withdraws their own request. */
    fun cancelPanic() = viewModelScope.launch { sync.cancelPanic() }

    /** Parent refuses a child's request: it dies and the child is locked out for three days. */
    fun denyPanic(deviceId: String, requestId: String) =
        viewModelScope.launch { sync.denyPanicRequest(deviceId, requestId) }

    val childState: StateFlow<ChildUiState> = combine(
        repository.familyConfigFlow,
        // ALL the counters, packages included. usageTodayFlow strips every key with a dot in it
        // — which is every package name — so this screen was computing "time left" from a map
        // that always said the child had used nothing. Two consequences, both reported from a
        // real phone: an app running on the family default never appeared here at all (its card
        // only exists because the child has USED it), and the ones that did appear showed a
        // number that disagreed with what the enforcement loop was counting, because that loop
        // reads usageTodayAllFlow and always has.
        repository.usageTodayAllFlow,
        repository.effectiveExtraTodayFlow,
        sync.earnedTodayMinutes,
        combine(clock, clockTampered) { now, tampered -> Pair(now, tampered) },
    ) { config, usage, effectiveExtra, earnedMinutes, clockPair ->
        val now = clockPair.first
        val clockTampered = clockPair.second
        val dayType = config.calendar.dayTypeOf(now)
        val bedtimeTonight = config.bedtime[dayType]
        val bedtimeActive = bedtimeTonight?.let { now.toLocalTime() in it } ?: false

        val failClosed = clockTampered && RuleEngine.requiresTrustedClock(config)
        // The same set the enforcement loop blocks from, so this screen can only ever promise
        // what the device will actually do (see childCardPackages). Reads the memoized launcher
        // list, which the package receivers keep exact — but a cache miss enumerates every
        // launcher activity on the phone, so it is not something to do on the main thread.
        val managed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.managedPackagesNow()
        }
        val appCards = childCardPackages(config, usage.keys, dayType, managed) { repository.inventory.label(it) }
            .map { (pkg, label) ->
                val status = RuleEngine.appStatus(config, pkg, now, usage, effectiveExtra, failClosed)
                AppStatusUi(
                    packageName = pkg,
                    label = label,
                    budget = status.budget ?: Duration.ZERO,
                    used = status.used,
                    remaining = status.remaining,
                    blocked = status.state == dev.walcott.rules.AppState.BLOCKED,
                    blockReason = status.blockReason,
                    fromDefault = config.usesDefaultBudget(pkg),
                )
            }
            // Closest to running out first: that is the card the child came to look at.
            .sortedWith(compareBy({ it.remaining ?: Duration.ZERO }, { it.label.lowercase() }))
        ChildUiState(
            loading = false,
            bedtimeActive = bedtimeActive,
            bedtimeTonight = bedtimeTonight,
            apps = appCards,
            defaultBudget = config.defaultAppBudget[dayType],
            earnedMinutes = earnedMinutes,
            screenFreeToday = config.blockedWindows[dayType].orEmpty(),
            screenFreeNow = config.blockedWindows[dayType].orEmpty()
                .firstOrNull { it.appliesAt(now, dayType == DayType.HOLIDAY) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChildUiState())

    /**
     * The one thing the child's own screen says about their numbers today, and the numbers
     * themselves. Recomputed when today's counters move; the LINE only changes with the date
     * (see [dev.walcott.data.Insights]), so it holds still while they read it.
     */
    val childInsight: StateFlow<dev.walcott.data.Insight?> =
        repository.usageTodayAllFlow.map {
            val today = java.time.LocalDate.now()
            val day = today.toEpochDay()
            dev.walcott.data.Insights.forToday(
                today = it,
                week = repository.usageBetween(day - 6, day),
                month = repository.usageBetween(day - 29, day),
                previousWeek = repository.usageBetween(day - 13, day - 7),
                rotation = today.dayOfYear,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everything this phone has been used for today, added up — the child's own headline. */
    val childScreenTimeToday: StateFlow<Duration> =
        repository.usageTodayAllFlow
            .map { usage -> usage.values.fold(Duration.ZERO, Duration::plus) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Duration.ZERO)

    val settings: StateFlow<PolicySettings> =
        repository.settingsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PolicySettings())

    val hasPin: StateFlow<Boolean> =
        repository.settingsFlow.map { it.pinHash != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // The apps the children actually have installed (reported over sync), deduplicated across
    // children but remembering WHO has each one (tags + per-child filter), each with the limit
    // set for it — which is the only thing there is to say about an app now.
    val appRows: StateFlow<List<AppRow>> =
        combine(children, settings) { snapshots, s ->
            dev.walcott.data.AppCatalog.build(snapshots, s.children.associate { it.childId to it.name })
                .map {
                    AppRow(
                        InstalledApp(it.packageName, it.label, isSystem = false),
                        s.appPolicies[it.packageName],
                        it.owners,
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Actions ---

    /**
     * The limit every app gets unless something was set for it, for one day type ([minutes] null
     * removes it). [childId] set edits that child's own default instead of the family's.
     */
    fun setDefaultBudget(dayType: DayType, minutes: Int?, childId: String? = null) = viewModelScope.launch {
        repository.updateSettings { s ->
            if (childId == null) {
                s.copy(defaultAppBudget = s.defaultAppBudget.withBudget(dayType.name, minutes))
            } else {
                s.copy(
                    children = s.children.map { child ->
                        if (child.childId != childId) child
                        else child.copy(
                            overrides = child.overrides.copy(
                                defaultAppBudget = child.overrides.defaultAppBudget.orEmpty()
                                    .withBudget(dayType.name, minutes),
                            ),
                        )
                    },
                )
            }
        }
    }

    // --- Per-app limits ---

    /**
     * [childId] null edits the family map; set, it edits that child's [ChildOverrides.appPolicies]
     * (which the editors only offer while the override is active, so the base is never null in
     * practice — an empty map otherwise, matching the snapshot-on-switch-on convention).
     */
    private fun mutateAppPolicy(
        pkg: String,
        childId: String? = null,
        transform: (dev.walcott.data.AppPolicyDto) -> dev.walcott.data.AppPolicyDto,
    ) = viewModelScope.launch {
        repository.updateSettings { s ->
            if (childId == null) {
                val next = transform(s.appPolicies[pkg] ?: dev.walcott.data.AppPolicyDto())
                // Drop the entry entirely once it carries no restrictions, so it never lingers.
                s.copy(appPolicies = if (next.isEmpty) s.appPolicies - pkg else s.appPolicies + (pkg to next))
            } else {
                s.copy(
                    children = s.children.map { child ->
                        if (child.childId != childId) return@map child
                        val base = child.overrides.appPolicies.orEmpty()
                        val next = transform(base[pkg] ?: dev.walcott.data.AppPolicyDto())
                        child.copy(
                            overrides = child.overrides.copy(
                                appPolicies = if (next.isEmpty) base - pkg else base + (pkg to next),
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * Set this app's own daily budget for a day type: a positive number of minutes, 0 to block
     * the app entirely that day (even when its category is open), or null for no per-app limit.
     */
    fun setAppBudget(pkg: String, dayType: DayType, minutes: Int?, childId: String? = null) =
        mutateAppPolicy(pkg, childId) { dto ->
            val budgets = dto.budgets.toMutableMap()
            if (minutes == null) budgets.remove(dayType.name) else budgets[dayType.name] = minutes
            dto.copy(budgets = budgets)
        }

    /**
     * Set this app's own daily budget the same way across ALL day types in one write — the
     * common case (one limit, or "block it everywhere") without editing three rows. 0 blocks,
     * null clears the per-app limit entirely.
     */
    fun setAppBudgetAllDays(pkg: String, minutes: Int?, childId: String? = null) =
        mutateAppPolicy(pkg, childId) { dto ->
            // Every day type, special days included: with the column off, the write's mirror pass
            // collapses HOLIDAY back onto WEEKEND, so this is correct either way.
            dto.copy(
                budgets = if (minutes == null) {
                    emptyMap()
                } else {
                    dev.walcott.rules.DayType.entries.associate { it.name to minutes }
                },
            )
        }

    /**
     * Sets this app free of the family default (or puts it back under it). Distinct from having
     * no budget: "nothing set" follows the default, this ignores it.
     */
    fun setAppUnlimited(pkg: String, unlimited: Boolean, childId: String? = null) =
        mutateAppPolicy(pkg, childId) { it.copy(unlimited = unlimited) }

    /** Set this app's own blocked windows (any number), applied to every day type. */
    /** One app's own screen-free schedule, written whole (see [setAllAppsWindows]). */
    fun setAppWindows(
        pkg: String,
        windows: List<dev.walcott.data.WindowDto>,
        childId: String? = null,
    ) = mutateAppPolicy(pkg, childId) { dto ->
        dto.copy(blockedWindows = windowsForEveryDayType(windows))
    }

    fun setBedtime(bedtime: Map<String, dev.walcott.data.WindowDto>) = viewModelScope.launch {
        repository.updateSettings { it.copy(bedtime = bedtime) }
    }

    /** Family-wide screen-free windows (they block ALL apps), for one day type. */
    /**
     * A schedule as the wire wants it: the same list under every day type.
     *
     * The map is keyed by day type because that is the shape children parse, but the rules
     * themselves carry the days they apply on, so every key holds the same list and each rule
     * filters itself. An empty schedule clears the map rather than storing three empty lists.
     */
    private fun windowsForEveryDayType(
        windows: List<dev.walcott.data.WindowDto>,
    ): Map<String, List<dev.walcott.data.WindowDto>> =
        if (windows.isEmpty()) emptyMap() else DayType.entries.associate { it.name to windows }

    /**
     * The family's screen-free schedule, written whole.
     *
     * Every day type gets the same list because the rules carry their own days now; the map
     * shape stays because that is what the wire is keyed by. ONE update, not one per day type:
     * three separate writes for a single edit raced each other, and each intermediate state was
     * a schedule that disagreed with itself about which rules existed.
     */
    fun setAllAppsWindows(windows: List<dev.walcott.data.WindowDto>) = viewModelScope.launch {
        repository.updateSettings { it.copy(allAppsBlockedWindows = windowsForEveryDayType(windows)) }
    }

    fun grantExtra(categoryId: String, minutes: Long) =
        viewModelScope.launch { repository.grantExtraMinutes(categoryId, minutes) }

    /**
     * Sets the parent PIN: creating it during setup, or replacing it from app settings.
     *
     * Written to EVERY family this device holds. The PIN belongs to the parent, not to a
     * household — they type the same one on their own phone and on every child's — but each
     * family carries its own copy because that is how its children come to know it. The
     * on-device backup key is re-derived with it, per family: a changed PIN must be the one
     * that opens the next backup, or a restore would ask for a PIN nobody remembers.
     */
    fun setPin(pin: String) = viewModelScope.launch { hub.setPinEverywhere(pin) }

    /** PIN check with brute-force lockout. */
    suspend fun verifyPin(pin: String): dev.walcott.data.PinResult = sync.verifyPinGuarded(pin)

    /** The PIN in the clear on this parent phone, "" if it has never been held here. */
    val readablePin: StateFlow<String> get() = sync.readablePin

    class Factory(
        private val repository: WalcottRepository,
        private val sync: SyncManager,
        private val hub: dev.walcott.FamilyHub,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WalcottViewModel(repository, sync, hub) as T
    }

    companion object {
        /** Default per-child location-tracking interval seeded at registration. */
        private const val DEFAULT_TRACKING_MINUTES = 15
    }
}
