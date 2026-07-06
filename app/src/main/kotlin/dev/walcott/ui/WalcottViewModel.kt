package dev.walcott.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.walcott.AppCategory
import dev.walcott.data.InstalledApp
import dev.walcott.data.PolicySettings
import dev.walcott.data.WalcottRepository
import dev.walcott.rules.CategoryStatus
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.categoryStatus
import dev.walcott.sync.ChildSnapshot
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
import java.time.LocalDateTime
import java.time.LocalTime

data class CategoryStatusUi(val category: AppCategory, val status: CategoryStatus)

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
    val children: StateFlow<List<ChildSnapshot>> =
        sync.state.map { it.children }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingRequests: StateFlow<List<SyncManager.PendingRequest>> = sync.pendingRequests

    suspend fun becomeParent(displayName: String): String = sync.becomeParent(displayName)
    suspend fun pairAsChild(pairingText: String, displayName: String): Boolean =
        sync.pairAsChild(pairingText, displayName)

    fun requestExtraTimeRemote(categoryId: String, minutes: Int, reason: String) =
        viewModelScope.launch { sync.requestExtraTime(categoryId, minutes, reason) }

    fun resolveRequest(requestId: String, approved: Boolean, grantedMinutes: Int) =
        viewModelScope.launch { sync.resolveRequest(requestId, approved, grantedMinutes) }


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
        repository.extraTodayFlow,
        clock,
    ) { config, usage, extra, now ->
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
                CategoryStatusUi(category, RuleEngine.categoryStatus(config, id, now, usage, extra))
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
        repository.updateSettings { current ->
            val perDay = current.budgets[categoryId].orEmpty().toMutableMap()
            if (minutes == null) perDay.remove(dayType.name) else perDay[dayType.name] = minutes
            val budgets = current.budgets.toMutableMap()
            if (perDay.isEmpty()) budgets.remove(categoryId) else budgets[categoryId] = perDay
            current.copy(budgets = budgets)
        }
    }

    fun setBedtime(dayType: DayType, start: LocalTime?, end: LocalTime?) = viewModelScope.launch {
        repository.updateSettings { current ->
            val bedtime = current.bedtime.toMutableMap()
            if (start == null || end == null) {
                bedtime.remove(dayType.name)
            } else {
                bedtime[dayType.name] = dev.walcott.data.WindowDto(start.toMinute(), end.toMinute())
            }
            current.copy(bedtime = bedtime)
        }
    }

    fun grantExtra(categoryId: String, minutes: Long) =
        viewModelScope.launch { repository.grantExtraMinutes(categoryId, minutes) }

    fun createPin(pin: String) = viewModelScope.launch { repository.setPin(pin) }

    suspend fun checkPin(pin: String): Boolean = repository.verifyPin(pin)

    private fun LocalTime.toMinute() = hour * 60 + minute

    class Factory(
        private val repository: WalcottRepository,
        private val sync: SyncManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WalcottViewModel(repository, sync) as T
    }
}
