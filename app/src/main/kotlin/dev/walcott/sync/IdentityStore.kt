package dev.walcott.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.walcott.data.FamilyIds
import dev.walcott.data.WalcottDataStores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persists this device's [FamilyIdentity] within one family, as JSON in DataStore. A parent
 * holding several families has one of these per family (its own topic, keys and device id);
 * a child device only ever has the default one.
 */
class IdentityStore(context: Context, familyId: String = FamilyIds.DEFAULT) {

    private val dataStore = WalcottDataStores.get(context, WalcottDataStores.fileName(FILE, familyId))
    private val key = stringPreferencesKey("identity_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = FamilyIdentity.serializer()

    private fun decode(raw: String?): FamilyIdentity =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: FamilyIdentity()

    val identity: Flow<FamilyIdentity> = dataStore.data.map { decode(it[key]) }

    suspend fun current(): FamilyIdentity = identity.first()

    suspend fun save(identity: FamilyIdentity) {
        dataStore.edit { it[key] = json.encodeToString(serializer, identity) }
    }

    private companion object {
        const val FILE = "walcott_identity"
    }
}
