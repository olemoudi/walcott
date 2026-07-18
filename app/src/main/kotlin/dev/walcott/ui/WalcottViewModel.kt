package dev.walcott.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.walcott.AppCategory
import dev.walcott.data.InstalledApp
import dev.walcott.data.PolicySettings
import dev.walcott.data.WalcottRepository
import dev.walcott.data.withBudget
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

data class AppRow(val app: InstalledApp, val categoryId: String?)

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
    val pendingRequests: StateFlow<List<SyncManager.PendingRequest>> = sync.pendingRequests
    val pendingAsks: StateFlow<List<SyncManager.PendingAsk>> = sync.pendingAsks
    val installExemption: StateFlow<Long> = sync.installExemption

    fun askFor(kind: String, text: String) = viewModelScope.launch { sync.askFor(kind, text) }
    fun allowInstallsTemporarily() = viewModelScope.launch { sync.allowInstallsTemporarily() }

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

    fun requestExtraTimeRemote(categoryId: String, minutes: Int, reason: String) =
        viewModelScope.launch { sync.requestExtraTime(categoryId, minutes, reason) }

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

    /** Adds an earn rule to the family, or to [childId]'s override when given. */
    fun addEarnRule(rule: dev.walcott.data.EarnRuleDto, childId: String? = null) =
        if (childId == null) {
            viewModelScope.launch { repository.updateSettings { it.copy(earnRules = it.earnRules + rule) } }
        } else {
            updateOverrides(childId) { it.copy(earnRules = it.earnRules.orEmpty() + rule) }
        }

    fun removeEarnRule(index: Int, childId: String? = null) =
        if (childId == null) {
            viewModelScope.launch {
                repository.updateSettings { it.copy(earnRules = it.earnRules.filterIndexed { i, _ -> i != index }) }
            }
        } else {
            updateOverrides(childId) {
                it.copy(earnRules = it.earnRules.orEmpty().filterIndexed { i, _ -> i != index })
            }
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

    val childState: StateFlow<ChildUiState> = combine(
        repository.familyConfigFlow,
        repository.usageTodayFlow,
        repository.effectiveExtraTodayFlow,
        repository.earnedTodayFlow,
        clock,
    ) { config, usage, effectiveExtra, earned, now ->
        val dayType = config.calendar.dayTypeOf(now.toLocalDate())
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
                    status = RuleEngine.categoryStatus(config, id, now, usage, effectiveExtra),
                    earned = earned[id] ?: Duration.ZERO,
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
    // deduplicated across children, not the parent device's own apps.
    val appRows: StateFlow<List<AppRow>> =
        combine(children, repository.assignmentsFlow) { snapshots, assignments ->
            snapshots.flatMap { it.apps }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .map { AppRow(InstalledApp(it.packageName, it.label, isSystem = false), assignments[it.packageName]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Actions ---

    fun assign(packageName: String, categoryId: String) =
        viewModelScope.launch { repository.assign(packageName, categoryId) }

    fun unassign(packageName: String) =
        viewModelScope.launch { repository.unassign(packageName) }

    fun setBudget(categoryId: String, dayType: DayType, minutes: Int?) = viewModelScope.launch {
        repository.updateSettings { it.copy(budgets = it.budgets.withBudget(categoryId, dayType.name, minutes)) }
    }

    fun setBedtime(bedtime: Map<String, dev.walcott.data.WindowDto>) = viewModelScope.launch {
        repository.updateSettings { it.copy(bedtime = bedtime) }
    }

    fun grantExtra(categoryId: String, minutes: Long) =
        viewModelScope.launch { repository.grantExtraMinutes(categoryId, minutes) }

    fun createPin(pin: String) = viewModelScope.launch { repository.setPin(pin) }

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
