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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    val pendingRequests: StateFlow<List<SyncManager.PendingRequest>> = sync.pendingRequests

    suspend fun becomeParent(familyName: String) = sync.becomeParent(familyName)
    suspend fun pairAsChild(pairingText: String): Boolean = sync.pairAsChild(pairingText)
    fun setMode(mode: DeviceMode) = viewModelScope.launch { sync.setMode(mode) }
    fun resetDeviceMode() = viewModelScope.launch { sync.resetDeviceMode() }

    // --- Children registry (parent mode) ---

    /** Registers a child and returns its id so the UI can navigate to the detail right away. */
    fun addChild(name: String): String {
        val childId = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            repository.updateSettings { it.copy(children = it.children + dev.walcott.data.ChildEntry(childId, name)) }
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

    // Reloads the 7-day history whenever today's usage changes.
    val weeklyUsage: StateFlow<Map<Long, Map<String, java.time.Duration>>> =
        repository.usageTodayFlow.map { repository.weeklyUsage() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addEarnRule(rule: dev.walcott.data.EarnRuleDto) =
        viewModelScope.launch { repository.updateSettings { it.copy(earnRules = it.earnRules + rule) } }

    fun removeEarnRule(index: Int) = viewModelScope.launch {
        repository.updateSettings { it.copy(earnRules = it.earnRules.filterIndexed { i, _ -> i != index }) }
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

    fun addBlockedDomain(raw: String) {
        val domain = normalizeDomain(raw)
        if (domain.isEmpty()) return
        viewModelScope.launch { repository.updateSettings { it.copy(blockedDomains = it.blockedDomains + domain) } }
    }

    fun removeBlockedDomain(domain: String) =
        viewModelScope.launch { repository.updateSettings { it.copy(blockedDomains = it.blockedDomains - domain) } }

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
        val bedtimeActive = config.bedtime[dayType]?.let { now.toLocalTime() in it } ?: false

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
        ChildUiState(loading = false, bedtimeActive = bedtimeActive, categories = cards)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChildUiState())

    val settings: StateFlow<PolicySettings> =
        repository.settingsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PolicySettings())

    val hasPin: StateFlow<Boolean> =
        repository.settingsFlow.map { it.pinHash != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val installedApps: StateFlow<List<InstalledApp>> =
        flow { emit(repository.inventory.launchableApps()) }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val appRows: StateFlow<List<AppRow>> =
        combine(installedApps, repository.assignmentsFlow) { apps, assignments ->
            apps.map { AppRow(it, assignments[it.packageName]) }
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

    suspend fun checkPin(pin: String): Boolean = repository.verifyPin(pin)

    class Factory(
        private val repository: WalcottRepository,
        private val sync: SyncManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WalcottViewModel(repository, sync) as T
    }
}
