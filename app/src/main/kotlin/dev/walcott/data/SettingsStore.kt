package dev.walcott.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "walcott_policy")

/** Persists [PolicySettings] as a single JSON blob in Preferences DataStore. */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("policy_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = PolicySettings.serializer()

    /**
     * Set when a stored blob was present but wouldn't decode. That case is quietly disastrous
     * on a child: the empty fallback classifies every app as unknown, so everything gets
     * blocked, and the parent's periodic re-emit can't repair it — re-emits reuse the same
     * version, which the replay gate rejects. [dev.walcott.sync.SyncManager] consumes this to
     * force the next parent snapshot to be adopted. Re-set on every read while it lasts, so
     * losing the flag can't strand the device.
     */
    @Volatile
    var corruptionSeen: Boolean = false
        private set

    /** Reads and clears the flag (see [corruptionSeen]). */
    fun consumeCorruption(): Boolean {
        val seen = corruptionSeen
        corruptionSeen = false
        return seen
    }

    private fun decode(raw: String?): PolicySettings {
        if (raw == null) return PolicySettings() // fresh install, nothing stored yet
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            corruptionSeen = true
            dev.walcott.debug.DebugLog.e(TAG, "stored policy is unreadable; falling back to empty", it)
            PolicySettings()
        }
    }

    val settings: Flow<PolicySettings> = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): PolicySettings = settings.first()

    suspend fun update(transform: (PolicySettings) -> PolicySettings) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }

    private companion object {
        const val TAG = "WalcottPolicy"
    }
}
