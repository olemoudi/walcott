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
import kotlinx.serialization.json.Json

private val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(name = "walcott_identity")

/** Persists this device's [FamilyIdentity] as JSON in DataStore. */
class IdentityStore(private val context: Context) {

    private val key = stringPreferencesKey("identity_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = FamilyIdentity.serializer()

    private fun decode(raw: String?): FamilyIdentity =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: FamilyIdentity()

    val identity: Flow<FamilyIdentity> = context.identityDataStore.data.map { decode(it[key]) }

    suspend fun current(): FamilyIdentity = identity.first()

    suspend fun save(identity: FamilyIdentity) {
        context.identityDataStore.edit { it[key] = json.encodeToString(serializer, identity) }
    }
}
