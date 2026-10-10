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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
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

    /**
     * Written into a file that could not be read and was started over (see [get]).
     *
     * On disk, not in memory: the replacement is saved, so a flag held only by this process said
     * "fresh install" to the next one. For the policy that was the difference between a child
     * keeping its device restrictions and losing every one of them after a reboot (see
     * SettingsStore). The store that cares removes it with its first real write.
     */
    val REPLACED = androidx.datastore.preferences.core.booleanPreferencesKey("walcott_file_replaced")

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
                    DebugLog.e(TAG, "$name was unreadable and has been started over", it)
                    androidx.datastore.preferences.core.preferencesOf(REPLACED to true)
                },
                scope = scope,
            ) { app.preferencesDataStoreFile(name) }
        }
    }

    private const val TAG = "WalcottStores"

    /**
     * A long-lived view of a store that survives a failed read instead of ending.
     *
     * DataStore reports a read it could not do (an I/O error, not a corrupt file) by throwing
     * through its `data` flow, which ends it. Everything built on these with `stateIn` then sat on
     * its last value for the rest of the process — the identity, the sync state, the quarantine —
     * on a phone whose process can live for weeks. Retried with a backoff instead, capped at a
     * minute. One-shot reads (`current()`) do not use this: their callers want the failure now.
     */
    fun <T> Flow<T>.retryingReads(): Flow<T> {
        // Failures in a row, not over the life of the process: a read that works again starts the
        // backoff over, where retryWhen's own count would leave every later failure at the cap.
        var failuresInARow = 0
        return onEach { failuresInARow = 0 }.retryWhen { cause, _ ->
            if (cause !is java.io.IOException) return@retryWhen false
            DebugLog.w(TAG, "a store read failed; trying again", cause)
            delay((1_000L shl failuresInARow.coerceAtMost(6)).coerceAtMost(60_000L))
            failuresInARow++
            true
        }
    }

    /** The file backing [base] for [familyId]; the first family keeps the pre-multi-family name. */
    fun fileName(base: String, familyId: String): String =
        if (familyId == FamilyIds.DEFAULT) base else "${base}_$familyId"
}
