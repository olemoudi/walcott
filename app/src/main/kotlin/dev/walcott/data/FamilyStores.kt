package dev.walcott.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap

/** Identifiers of the families this device holds. */
object FamilyIds {

    /**
     * The family every install has had until multi-family existed. Its stores keep the ORIGINAL
     * file names, so updating to a multi-family build moves no data at all: the family that is
     * already there simply becomes family number one. Every later family gets a fresh id and its
     * own files beside them.
     */
    const val DEFAULT = "default"

    /** A new family's id: short, opaque and safe as a file-name suffix. */
    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    /** Guards against an id that would escape the datastore directory or collide with [DEFAULT]. */
    fun isValid(id: String): Boolean = id.isNotBlank() && id.all { it.isLetterOrDigit() }
}

/**
 * The process's Preferences DataStores, one instance per file.
 *
 * Replaces the `by preferencesDataStore(...)` Context delegates, which can only express a fixed
 * set of file names known at compile time — and multi-family needs one set of files per family.
 * The file path is computed exactly as the delegate did ([preferencesDataStoreFile]), so existing
 * installs keep reading the same bytes they always have.
 *
 * The cache is not an optimisation: DataStore throws if a second instance is created over a file
 * that already has one in this process, and workers/receivers construct their stores ad hoc.
 */
object WalcottDataStores {

    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Files whose contents could not be read and were started over (see [get]). */
    private val replaced = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun get(context: Context, name: String): DataStore<Preferences> {
        val app = context.applicationContext
        return stores.computeIfAbsent(name) {
            PreferenceDataStoreFactory.create(
                // Without a handler, a file DataStore cannot parse throws from every read and
                // every write, for ever. On a child device that is the worst state this app can
                // be in: the enforcement loop's config read fails on every tick, so whatever was
                // suspended when it broke stays suspended, nothing re-evaluates it, and the only
                // way out is a factory reset. Starting the file over loses settings that are
                // re-synced from the parent within seconds — see SettingsStore, which treats a
                // replaced policy as corruption precisely so that re-sync cannot be blocked by
                // the replay gate.
                corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                    replaced += name
                    DebugLog.e(TAG, "$name was unreadable and has been started over", it)
                    androidx.datastore.preferences.core.emptyPreferences()
                },
                scope = scope,
            ) { app.preferencesDataStoreFile(name) }
        }
    }

    /** Whether [name] had to be started over in this process (see [get]). */
    fun wasReplaced(name: String): Boolean = name in replaced

    private const val TAG = "WalcottStores"

    /** The file backing [base] for [familyId]; the first family keeps the pre-multi-family name. */
    fun fileName(base: String, familyId: String): String =
        if (familyId == FamilyIds.DEFAULT) base else "${base}_$familyId"
}
