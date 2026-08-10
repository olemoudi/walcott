package dev.walcott.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.whatsNewDataStore: DataStore<Preferences> by preferencesDataStore(name = "walcott_whats_new")

/**
 * The newest build whose changes this device has already been shown.
 *
 * Device-local, never family policy: two phones in a family update at different moments, and
 * "have you read this" is about the person holding the phone. Kept out of [ThemeStore] because
 * that one is a user preference and this is a bookmark.
 */
class WhatsNewStore(private val context: Context) {
    private val key = intPreferencesKey("last_seen_version_code")

    /** 0 on a fresh install, which is what [WhatsNew.entriesFor] reads as "say nothing". */
    val lastSeenVersionCode: Flow<Int> = context.whatsNewDataStore.data.map { it[key] ?: 0 }

    suspend fun markSeen(versionCode: Int) {
        context.whatsNewDataStore.edit { it[key] = versionCode }
    }
}
