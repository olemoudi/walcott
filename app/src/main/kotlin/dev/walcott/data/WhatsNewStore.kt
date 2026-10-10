package dev.walcott.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Through WalcottDataStores (same file as the old delegate) for its corruption handler: a damaged
// file otherwise throws from every read, and this one is read as the app opens.
private val Context.whatsNewDataStore: DataStore<Preferences>
    get() = WalcottDataStores.get(this, "walcott_whats_new")

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
