package dev.walcott.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persists one family's [PolicySettings] as a single JSON blob in Preferences DataStore.
 * [familyId] picks the file; the first family keeps the original name (see [FamilyIds.DEFAULT]).
 */
class SettingsStore(context: Context, familyId: String = FamilyIds.DEFAULT) {

    private val dataStore = WalcottDataStores.get(context, WalcottDataStores.fileName(FILE, familyId))
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
        return runCatching { json.decodeFromString(serializer, raw) }
            // Anything written when limits were per category arrives in the shape the rest of
            // the app no longer speaks. Converting on READ rather than in a one-shot migration
            // means it also covers a policy that arrives over the wire from a parent who hasn't
            // updated yet, and it is idempotent (see [PolicySettings.migratedFromCategories]).
            .map { it.migratedFromCategories() }
            .getOrElse {
                corruptionSeen = true
                dev.walcott.debug.DebugLog.e(TAG, "stored policy is unreadable; falling back to empty", it)
                PolicySettings()
            }
    }

    val settings: Flow<PolicySettings> = dataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): PolicySettings = settings.first()

    suspend fun update(transform: (PolicySettings) -> PolicySettings) {
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }

    private companion object {
        const val TAG = "WalcottPolicy"
        const val FILE = "walcott_policy"
    }
}
