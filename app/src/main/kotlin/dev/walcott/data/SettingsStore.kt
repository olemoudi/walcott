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

    private fun decode(raw: String?): PolicySettings =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: PolicySettings()

    val settings: Flow<PolicySettings> = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): PolicySettings = settings.first()

    suspend fun update(transform: (PolicySettings) -> PolicySettings) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }
}
