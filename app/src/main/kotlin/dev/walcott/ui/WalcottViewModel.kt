package dev.walcott.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.walcott.AppCategory
import dev.walcott.data.InstalledApp
import dev.walcott.data.PolicySettings
import dev.walcott.data.WalcottRepository
import dev.walcott.data.withBudget
import dev.walcott.data.withSpecialDaysOwnBudget
import dev.walcott.rules.CategoryStatus
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.categoryStatus
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

data class CategoryStatusUi(
    val category: AppCategory,
    val status: CategoryStatus,
    val earned: Duration = Duration.ZERO,
)

data class ChildUiState(
    val loading: Boolean = true,
    val bedtimeActive: Boolean = false,
    /** Today's configured bedtime window, if any (for the "bedtime tonight" row). */
    val bedtimeTonight: dev.walcott.rules.TimeWindow? = null,
    val categories: List<CategoryStatusUi> = emptyList(),
)

data class AppRow(
    val app: InstalledApp,
    val categoryId: String?,
    /** Which children have this app installed (registry name, legacy device name as fallback). */
    val owners: List<dev.walcott.data.AppCatalog.Owner> = emptyList(),
)

class WalcottViewModel(
    val repository: WalcottRepository,
    private val sync: SyncManager,
) : ViewModel() {

    val identity: StateFlow<FamilyIdentity> = sync.identity
    val bootMode: StateFlow<DeviceMode?> = sync.bootMode
    val children: StateFlow<List<ChildSnapshot>> =
        sync.state.map { it.children }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastSeen: StateFlow<Map<String, Long>> =
        sync.state.map { it.lastSeen }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    /** The parent's current rules version, to tell which children have caught up. */
    val parentVersion: StateFlow<Long> =
        sync.state.map { it.parentVersion }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val pendingRequests: StateFlow<List<SyncManager.PendingRequest>> = sync.pendingRequests
    val pendingAsks: StateFlow<List<SyncManager.PendingAsk>> = sync.pendingAsks
    val installExemption: StateFlow<Long> = sync.installExemption
    /** Package of a parent-pushed install still waiting for its tap on this device, or "". */
    val pendingInstall: StateFlow<String> = sync.pendingInstall
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
    fun allowInstallsTemporarily() = viewModelScope.launch { sync.allowInstallsTemporarily() }

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
    suspend fun pairAsChild(pairingText: String): Boolean = sync.pairAsChild(pairingText)
    fun setMode(mode: DeviceMode) = viewModelScope.launch { sync.setMode(mode) }
    fun resetDeviceMode() = viewModelScope.launch { sync.resetDeviceMode() }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { sync.setAppLock(enabled) }
    fun setAppLockBiometric(enabled: Boolean) = viewModelScope.launch { sync.setAppLockBiometric(enabled) }

    // --- Children registry (parent mode) ---

    /** Registers a child and returns its id so the UI can navigate to the detail right away. */
    fun addChild(name: String): String {
        val childId = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    children = it.children + dev.walcott.data.ChildEntry(
                        childId,
                        name,
                        // Location tracking on by default — it's what a parent expects from
                        // enrollment; the LocationCard can still turn it off per child.
                        overrides = dev.walcott.data.ChildOverrides(
                            trackingIntervalMinutes = DEFAULT_TRACKING_MINUTES,
                        ),
                        addedAtMs = System.currentTimeMillis(),
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

    fun removeChild(childId: String) = viewModelScope.launch {
        repository.updateSettings { s -> s.copy(children = s.children.filterNot { it.childId == childId }) }
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

    /** Caps every leisure category at [minutes] per day, all day types (null removes the caps). */
    /**
     * The wizard's leisure cap. [weekdaysOnly] once the family has said weekends are different,
     * so the weekend step's value isn't overwritten when the parent walks back a step.
     */
    fun setLeisureBudget(minutes: Int?, weekdaysOnly: Boolean = false) = viewModelScope.launch {
        repository.updateSettings {
            if (weekdaysOnly) {
                dev.walcott.data.SetupPresets.withWeekdayLeisureBudget(it, minutes)
            } else {
                dev.walcott.data.SetupPresets.withLeisureBudget(it, minutes)
            }
        }
    }

    fun setWeekendLeisureBudget(minutes: Int?) = viewModelScope.launch {
        repository.updateSettings { dev.walcott.data.SetupPresets.withWeekendLeisureBudget(it, minutes) }
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
        repository.usageTodayFlow.map { repository.weeklyUsage() }
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
    fun setSpecialDaysOwnBudget(on: Boolean) = viewModelScope.launch {
        repository.updateSettings { it.withSpecialDaysOwnBudget(on) }
    }

    fun addHoliday(epochDay: Long) =
        viewModelScope.launch { repository.updateSettings { it.copy(holidays = it.holidays + epochDay) } }

    fun removeHoliday(epochDay: Long) =
        viewModelScope.launch { repository.updateSettings { it.copy(holidays = it.holidays - epochDay) } }

    fun addVacation(startEpochDay: Long, endEpochDay: Long) = viewModelScope.launch {
        repository.updateSettings { it.copy(vacations = it.vacations + dev.walcott.data.VacationDto(startEpochDay, endEpochDay)) }
    }

    fun removeVacation(index: Int) = viewModelScope.launch {
        repository.updateSettings { it.copy(vacations = it.vacations.filterIndexed { i, _ -> i != index }) }
    }

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
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            state.diagHistory + state.diagReports
                .filterKeys { state.diagHistory[it].isNullOrEmpty() }
                .mapValues { (_, report) -> listOf(dev.walcott.sync.StoredDiag(report)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The activity feed, newest first (parent side), for the home wall and child dashboards. */
    val recentEvents: StateFlow<List<dev.walcott.sync.ParentEvent>> =
        sync.state.map { it.events.asReversed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-child daily usage ledger (see [dev.walcott.sync.UsageLedger]), for the dashboard average. */
    val usageLedgers: StateFlow<Map<String, Map<Long, Long>>> =
        sync.state.map { it.usageHistory }
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

    data class AutoBackupUi(val enabled: Boolean, val failing: Boolean)

    /** Whether the fire-and-forget backup is on, and whether its last rewrite failed. */
    val autoBackup: StateFlow<AutoBackupUi> = sync.state
        .map { AutoBackupUi(it.autoBackupUri.isNotBlank(), it.autoBackupError) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoBackupUi(false, false))

    /** Start rewriting the backup into [uri] automatically on every rule change. */
    suspend fun enableAutoBackup(uri: String, passphrase: CharArray) = sync.enableAutoBackup(uri, passphrase)

    fun disableAutoBackup() = viewModelScope.launch { sync.disableAutoBackup() }

    /** Toggle the "your backup is missing/stale" nudge notifications. */
    fun setBackupReminders(enabled: Boolean) = viewModelScope.launch { sync.setBackupReminders(enabled) }

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

    /** The idle-earn target category id (or "") so childState can attribute earned minutes. */
    private val settingsFlowForEarn: kotlinx.coroutines.flow.Flow<String> =
        repository.settingsFlow.map { it.idleEarn?.targetCategoryId ?: "" }

    val childState: StateFlow<ChildUiState> = combine(
        repository.familyConfigFlow,
        repository.usageTodayFlow,
        repository.effectiveExtraTodayFlow,
        combine(sync.earnedTodayMinutes, settingsFlowForEarn) { minutes, target -> Pair(minutes, target) },
        combine(clock, clockTampered) { now, tampered -> Pair(now, tampered) },
    ) { config, usage, effectiveExtra, earnedPair, clockPair ->
        val earnedMinutes = earnedPair.first
        val earnTarget = earnedPair.second
        val now = clockPair.first
        val clockTampered = clockPair.second
        val dayType = config.calendar.dayTypeOf(now)
        val bedtimeTonight = config.bedtime[dayType]
        val bedtimeActive = bedtimeTonight?.let { now.toLocalTime() in it } ?: false

        // Show categories that have a defined budget/window or have apps assigned.
        val relevantIds = buildSet {
            addAll(config.assignments.values)
            config.policies.forEach { (id, policy) ->
                if (policy.dailyBudget.isNotEmpty() || policy.blockedWindows.isNotEmpty()) add(id)
            }
        }
        val cards = relevantIds
            .mapNotNull { id -> AppCategory.byId(id)?.let { it to id } }
            .sortedBy { it.first.ordinal }
            .map { (category, id) ->
                CategoryStatusUi(
                    category = category,
                    status = RuleEngine.categoryStatus(
                        config, id, now, usage, effectiveExtra,
                        // Same fail-closed the enforcement loop applies, so the cards can't
                        // promise time the device is refusing to hand out.
                        failClosed = clockTampered && RuleEngine.requiresTrustedClock(config),
                    ),
                    // Idle-earned time all lands in the target category.
                    earned = if (id == earnTarget) Duration.ofMinutes(earnedMinutes.toLong()) else Duration.ZERO,
                )
            }
        ChildUiState(
            loading = false,
            bedtimeActive = bedtimeActive,
            bedtimeTonight = bedtimeTonight,
            categories = cards,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChildUiState())

    val settings: StateFlow<PolicySettings> =
        repository.settingsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PolicySettings())

    val hasPin: StateFlow<Boolean> =
        repository.settingsFlow.map { it.pinHash != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Parent classifies the apps its children actually have installed (reported over sync),
    // deduplicated across children but remembering WHO has each one (tags + per-child filter).
    val appRows: StateFlow<List<AppRow>> =
        combine(children, repository.assignmentsFlow, settings) { snapshots, assignments, s ->
            dev.walcott.data.AppCatalog.build(snapshots, s.children.associate { it.childId to it.name })
                .map {
                    AppRow(InstalledApp(it.packageName, it.label, isSystem = false), assignments[it.packageName], it.owners)
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Actions ---

    fun assign(packageName: String, categoryId: String) =
        viewModelScope.launch { repository.assign(packageName, categoryId) }

    fun unassign(packageName: String) =
        viewModelScope.launch { repository.unassign(packageName) }

    fun setBudget(categoryId: String, dayType: DayType, minutes: Int?) = viewModelScope.launch {
        repository.updateSettings { it.copy(budgets = it.budgets.withBudget(categoryId, dayType.name, minutes)) }
    }

    // --- Per-app policy (Apps & categories) ---

    private fun mutateAppPolicy(
        pkg: String,
        transform: (dev.walcott.data.AppPolicyDto) -> dev.walcott.data.AppPolicyDto,
    ) = viewModelScope.launch {
        repository.updateSettings { s ->
            val next = transform(s.appPolicies[pkg] ?: dev.walcott.data.AppPolicyDto())
            // Drop the entry entirely once it carries no restrictions, so it never lingers.
            s.copy(appPolicies = if (next.isEmpty) s.appPolicies - pkg else s.appPolicies + (pkg to next))
        }
    }

    /**
     * Set this app's own daily budget for a day type: a positive number of minutes, 0 to block
     * the app entirely that day (even when its category is open), or null for no per-app limit.
     */
    fun setAppBudget(pkg: String, dayType: DayType, minutes: Int?) = mutateAppPolicy(pkg) { dto ->
        val budgets = dto.budgets.toMutableMap()
        if (minutes == null) budgets.remove(dayType.name) else budgets[dayType.name] = minutes
        dto.copy(budgets = budgets)
    }

    /**
     * Set this app's own daily budget the same way across ALL day types in one write — the
     * common case (one limit, or "block it everywhere") without editing three rows. 0 blocks,
     * null clears the per-app limit entirely.
     */
    fun setAppBudgetAllDays(pkg: String, minutes: Int?) = mutateAppPolicy(pkg) { dto ->
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

    /** Set this app's own blocked windows (any number), applied to every day type. */
    fun setAppWindows(pkg: String, windows: List<dev.walcott.data.WindowDto>) = mutateAppPolicy(pkg) { dto ->
        dto.copy(
            blockedWindows = if (windows.isEmpty()) emptyMap() else DAY_TYPES.associate { it.name to windows },
        )
    }

    fun setBedtime(bedtime: Map<String, dev.walcott.data.WindowDto>) = viewModelScope.launch {
        repository.updateSettings { it.copy(bedtime = bedtime) }
    }

    /** Family-wide screen-free windows (they block ALL apps), applied to every day type. */
    fun setAllAppsWindows(windows: List<dev.walcott.data.WindowDto>) = viewModelScope.launch {
        repository.updateSettings {
            it.copy(
                allAppsBlockedWindows =
                    if (windows.isEmpty()) emptyMap() else DAY_TYPES.associate { d -> d.name to windows },
            )
        }
    }

    fun grantExtra(categoryId: String, minutes: Long) =
        viewModelScope.launch { repository.grantExtraMinutes(categoryId, minutes) }

    /** Sets the parent PIN: creating it during setup, or replacing it from app settings. */
    fun setPin(pin: String) = viewModelScope.launch { repository.setPin(pin) }

    /** PIN check with brute-force lockout. */
    suspend fun verifyPin(pin: String): dev.walcott.data.PinResult = sync.verifyPinGuarded(pin)

    class Factory(
        private val repository: WalcottRepository,
        private val sync: SyncManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WalcottViewModel(repository, sync) as T
    }

    companion object {
        /** Default per-child location-tracking interval seeded at registration. */
        private const val DEFAULT_TRACKING_MINUTES = 15
    }
}
