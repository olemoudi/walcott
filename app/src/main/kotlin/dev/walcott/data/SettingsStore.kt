package dev.walcott.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Persists one family's [PolicySettings] as a single JSON blob in Preferences DataStore.
 * [familyId] picks the file; the first family keeps the original name (see [FamilyIds.DEFAULT]).
 */
class SettingsStore(context: Context, familyId: String = FamilyIds.DEFAULT) {

    private val fileName = WalcottDataStores.fileName(FILE, familyId)
    private val dataStore = WalcottDataStores.get(context, fileName)
    private val key = stringPreferencesKey("policy_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = PolicySettings.serializer()

    /**
     * Set when a stored blob was present but wouldn't decode. That case is quietly disastrous
     * on a child: the empty fallback classifies every app as unknown, so everything gets
     * blocked, and the parent's periodic re-emit can't repair it — re-emits reuse the same
     * version, which the replay gate rejects. [dev.walcott.sync.SyncManager] consumes this to
     * force the next parent snapshot to be adopted. Re-set on every read while it lasts, so
     * losing the flag can't strand the device.
     */
    @Volatile
    var corruptionSeen: Boolean = false
        private set

    /** Reads and clears the flag (see [corruptionSeen]). */
    fun consumeCorruption(): Boolean {
        val seen = corruptionSeen
        corruptionSeen = false
        return seen
    }

    /**
     * The last blob decoded and what it decoded to, so the same JSON is never parsed twice.
     *
     * This is not a micro-optimisation. [settings] is a cold flow: every collector re-runs
     * [decode] on every emission, and [current] is `settings.first()`, so each call parses the
     * whole policy afresh. The enforcement loop asks for the config AND the idle-earn config on
     * every tick — two full parses of a large object graph every two seconds while a child is
     * using a limited app, plus one per emission in the VPN service, the accessibility blocker
     * and each screen collecting it.
     *
     * The policy only changes when someone edits it or a parent snapshot arrives, which is
     * roughly never in these terms. Comparing the raw string first costs a memory scan against a
     * full parse, and the common case is DataStore handing back the identical instance, which
     * `===` settles immediately. [PolicySettings] is an immutable data class, so handing the same
     * instance to every caller is safe.
     */
    // ONE volatile holding both halves, not two: written separately, a reader racing an update
    // could see the new raw string beside the previously decoded object and hand out the wrong
    // policy — on a child device, the wrong set of blocked apps.
    private class Decoded(val raw: String, val settings: PolicySettings)

    @Volatile private var cache: Decoded? = null

    private fun decode(raw: String?): PolicySettings {
        if (raw == null) {
            // Nothing stored is normally a fresh install. It is something else entirely when the
            // FILE itself was unreadable and had to be started over (see WalcottDataStores): this
            // device just lost the rules it was enforcing, and the parent's re-emits reuse a
            // version the replay gate would reject — so it would sit on an empty policy until
            // somebody happened to edit a rule. Raising the same flag a bad blob raises is what
            // makes the next parent snapshot adopted whatever its version.
            if (WalcottDataStores.wasReplaced(fileName)) corruptionSeen = true
            return PolicySettings()
        }
        cache?.let { hit -> if (raw === hit.raw || raw == hit.raw) return hit.settings }
        val decoded = runCatching { json.decodeFromString(serializer, raw) }
            // Anything written when limits were per category arrives in the shape the rest of
            // the app no longer speaks. Converting on READ rather than in a one-shot migration
            // means it also covers a policy that arrives over the wire from a parent who hasn't
            // updated yet, and it is idempotent (see [PolicySettings.migratedFromCategories]).
            .map { it.migratedFromCategories() }
            .getOrElse {
                corruptionSeen = true
                dev.walcott.debug.DebugLog.e(TAG, "stored policy is unreadable; falling back to empty", it)
                // Deliberately NOT cached. The flag has to be re-raised on every read while the
                // corruption lasts (see [corruptionSeen]) — caching the fallback would raise it
                // exactly once, and a consumer that had already taken it would never see it
                // again, stranding a child with every app blocked.
                return PolicySettings()
            }
        cache = Decoded(raw, decoded)
        return decoded
    }

    val settings: Flow<PolicySettings> = dataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): PolicySettings = settings.first()

    suspend fun update(transform: (PolicySettings) -> PolicySettings) {
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }

    private companion object {
        const val TAG = "WalcottPolicy"
        const val FILE = "walcott_policy"
    }
}
