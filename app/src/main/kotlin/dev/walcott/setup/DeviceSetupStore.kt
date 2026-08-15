package dev.walcott.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "walcott_device_setup")

/**
 * Which setup nudges this device has been told to stop showing on the home screen.
 *
 * Device-local, never family policy: it is about the person holding this phone having said "not
 * now", and a parent's decision on their own phone has nothing to do with a child's.
 *
 * Note what this store does NOT do: nothing here removes a requirement from the settings screen.
 * A dismissal hides the interruption, not the fact.
 */
class DeviceSetupStore(private val context: Context) {

    private val key = stringSetPreferencesKey("dismissed")
    private val journeyKey = longPreferencesKey("journey_done_at")

    val dismissed: Flow<Set<String>> = context.setupDataStore.data.map { it[key] ?: emptySet() }

    /**
     * When someone was last walked through the guided setup on this phone (0 = never).
     *
     * "Walked through", not "everything granted": the journey ends whether or not every switch
     * was flipped, because a parent who genuinely refuses one of them must be able to leave, and
     * what is still missing is covered from then on by the home-screen cards, the device's own
     * periodic nudge and the parent's reminder. This flag answers one question only — has anyone
     * ever been offered the tour on this device — and it is what keeps the "finish setting up"
     * card from living on the child's home for ever.
     */
    val journeyDoneAt: Flow<Long> = context.setupDataStore.data.map { it[journeyKey] ?: 0L }

    suspend fun markJourneyDone(atMs: Long = System.currentTimeMillis()) {
        context.setupDataStore.edit { it[journeyKey] = atMs }
    }

    /**
     * Forgets the journey, so it is offered again. Called when the device is enrolled into a
     * family ([dev.walcott.sync.SyncManager.pairAsChild]): a new enrollment is a new phone as far
     * as this is concerned — new rules, possibly a web filter or tracking that the last family
     * never asked for, and a different adult standing over it.
     */
    suspend fun resetJourney() {
        context.setupDataStore.edit { it.remove(journeyKey) }
    }

    suspend fun dismiss(requirement: DeviceRequirement) {
        context.setupDataStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) + requirement.key
        }
    }

    suspend fun restore(requirement: DeviceRequirement) {
        context.setupDataStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) - requirement.key
        }
    }

    /**
     * Drops dismissals whose requirement is satisfied again, so a later relapse nags afresh
     * (see [DeviceSetup.survivingDismissals]). Writes only on an actual change — this runs on
     * every screen resume.
     */
    suspend fun pruneSatisfied(unmet: List<DeviceRequirement>) {
        context.setupDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            val surviving = DeviceSetup.survivingDismissals(unmet, current)
            if (surviving != current) prefs[key] = surviving
        }
    }
}
