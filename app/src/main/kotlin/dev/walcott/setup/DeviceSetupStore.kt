package dev.walcott.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    val dismissed: Flow<Set<String>> = context.setupDataStore.data.map { it[key] ?: emptySet() }

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
