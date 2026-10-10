package dev.walcott.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How the app resolves light/dark. A device-local preference, never part of family policy. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Through WalcottDataStores (same file as the old delegate) for its corruption handler: a damaged
// file otherwise throws from every read, and this one is read as the app opens.
private val Context.themeDataStore: DataStore<Preferences>
    get() = WalcottDataStores.get(this, "walcott_theme")

/** Persists the manual theme choice; SYSTEM (follow the device) is the default. */
class ThemeStore(private val context: Context) {
    private val key = stringPreferencesKey("mode")

    val mode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        prefs[key]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[key] = mode.name }
    }
}
