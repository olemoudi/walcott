package dev.walcott.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One family this device holds. Its name, rules and children live in its own stores. */
@Serializable
data class FamilyRef(val id: String, val createdAtMs: Long = 0)

/**
 * Which families this device holds and which one the UI is currently showing.
 *
 * Deliberately tiny and separate from every other store: it is read before anything else exists,
 * by workers and by the Application, and it must have a sane answer even on the very first launch
 * — which is why [normalized] can conjure the default family out of an empty file rather than
 * having a migration write one.
 */
@Serializable
data class FamiliesState(
    val families: List<FamilyRef> = emptyList(),
    val activeId: String = "",
) {

    /**
     * The state as the rest of the app is allowed to see it: at least one family, no duplicates
     * or unusable ids, and an [activeId] that actually exists. An install that predates
     * multi-family has an empty file and lands on exactly one family — the default — whose
     * stores are the ones it has been using all along.
     */
    val normalized: FamiliesState
        get() {
            val clean = families.filter { FamilyIds.isValid(it.id) }.distinctBy { it.id }
                .ifEmpty { listOf(FamilyRef(FamilyIds.DEFAULT)) }
            val active = clean.firstOrNull { it.id == activeId }?.id ?: clean.first().id
            return FamiliesState(clean, active)
        }

    val ids: List<String> get() = normalized.families.map { it.id }

    val active: String get() = normalized.activeId

    val isMulti: Boolean get() = normalized.families.size > 1

    /** Adds a family (no-op if [id] is already there) and makes it the one being shown. */
    fun plus(id: String, nowMs: Long): FamiliesState {
        if (!FamilyIds.isValid(id)) return normalized
        val base = normalized
        if (base.families.any { it.id == id }) return base.copy(activeId = id)
        return FamiliesState(base.families + FamilyRef(id, nowMs), id)
    }

    /**
     * Forgets a family. Refuses to remove the last one: a parent with no family at all has no
     * home to show and no identity to publish, and the state is unreachable through the UI —
     * which makes it exactly the kind of state worth making unrepresentable.
     */
    fun minus(id: String): FamiliesState {
        val base = normalized
        if (base.families.size <= 1 || base.families.none { it.id == id }) return base
        val rest = base.families.filterNot { it.id == id }
        return FamiliesState(rest, if (base.activeId == id) rest.first().id else base.activeId)
    }

    /** Switches which family the UI shows; unknown ids leave it alone. */
    fun withActive(id: String): FamiliesState {
        val base = normalized
        return if (base.families.any { it.id == id }) base.copy(activeId = id) else base
    }
}

/** Persists [FamiliesState]; device-local, never part of any family's policy. */
class FamiliesStore(context: Context) {

    private val dataStore = WalcottDataStores.get(context, FILE)
    private val key = stringPreferencesKey("families_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = FamiliesState.serializer()

    private fun decode(raw: String?): FamiliesState =
        (raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: FamiliesState()).normalized

    val state: Flow<FamiliesState> = dataStore.data.map { decode(it[key]) }

    suspend fun current(): FamiliesState = state.first()

    suspend fun update(transform: (FamiliesState) -> FamiliesState) {
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])).normalized)
        }
    }

    private companion object {
        const val FILE = "walcott_families"
    }
}
