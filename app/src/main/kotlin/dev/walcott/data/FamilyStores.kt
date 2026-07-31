package dev.walcott.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
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

    fun get(context: Context, name: String): DataStore<Preferences> {
        val app = context.applicationContext
        return stores.computeIfAbsent(name) {
            PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile(name) }
        }
    }

    /** The file backing [base] for [familyId]; the first family keeps the pre-multi-family name. */
    fun fileName(base: String, familyId: String): String =
        if (familyId == FamilyIds.DEFAULT) base else "${base}_$familyId"
}
