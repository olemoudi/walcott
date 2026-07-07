package dev.walcott.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persistent sync bookkeeping, separate from the rules ([dev.walcott.data.PolicySettings]). */
@Serializable
data class SyncState(
    // Child side
    val childVersion: Long = 0,
    val pendingRequests: List<ExtraTimeRequest> = emptyList(),
    val appliedResolutionIds: Set<String> = emptySet(),
    val appliedBonusIds: Set<String> = emptySet(),
    // Parent side
    val parentVersion: Long = 0,
    val resolutions: List<Resolution> = emptyList(),
    val bonuses: List<Bonus> = emptyList(),
    val children: List<ChildSnapshot> = emptyList(),
    /** deviceId -> wall-clock ms of the last message received from that child. */
    val lastSeen: Map<String, Long> = emptyMap(),
    /** deviceId -> the lastSeen value we already alerted about (one alert per outage). */
    val staleNotifiedLastSeen: Map<String, Long> = emptyMap(),
)

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "walcott_sync")

class SyncStore(private val context: Context) {

    private val key = stringPreferencesKey("sync_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = SyncState.serializer()

    private fun decode(raw: String?): SyncState =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: SyncState()

    val state: Flow<SyncState> = context.syncDataStore.data.map { decode(it[key]) }

    suspend fun current(): SyncState = state.first()

    suspend fun update(transform: (SyncState) -> SyncState) {
        context.syncDataStore.edit { it[key] = json.encodeToString(serializer, transform(decode(it[key]))) }
    }
}
