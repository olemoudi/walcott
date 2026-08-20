package dev.walcott

import android.content.Context
import dev.walcott.data.AppInventory
import dev.walcott.data.FamiliesState
import dev.walcott.data.FamiliesStore
import dev.walcott.data.FamilyIds
import dev.walcott.data.PolicySettings
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottDatabase
import dev.walcott.data.WalcottRepository
import dev.walcott.debug.DebugLog
import dev.walcott.sync.FamilyHealth
import dev.walcott.sync.IconStore
import dev.walcott.sync.IdentityStore
import dev.walcott.sync.SyncManager
import dev.walcott.sync.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A family as the chooser shows it. [name] blank = never named (the UI falls back to a default). */
data class FamilySummary(
    val id: String,
    val name: String,
    val children: Int,
    val pending: Int,
    val alerts: Int,
)

/**
 * Everything that belongs to ONE family on this device: its rules, its identity on the wire, its
 * sync bookkeeping, and the manager that keeps them talking to that family's ntfy topic.
 *
 * A child device has exactly one of these. A parent has one per family and they all run at once —
 * a request from the family you are not currently looking at still has to reach you.
 */
class FamilyScope(
    context: Context,
    val id: String,
    db: WalcottDatabase,
    inventory: AppInventory,
    scope: CoroutineScope,
    iconStore: IconStore,
    /** Whether this device holds more than one family, i.e. whether alerts must name it. */
    private val multiFamily: suspend () -> Boolean = { false },
) {
    val settingsStore = SettingsStore(context, id)
    val identityStore = IdentityStore(context, id)
    val syncStore = SyncStore(context, id)

    /**
     * Room is shared across families on purpose: everything it holds (usage counters, the location
     * trail) belongs to the device that enforces, and a device only ever enforces for one family.
     * On a parent phone these tables stay empty whatever the family count.
     */
    val repository = WalcottRepository(
        db = db,
        settingsStore = settingsStore,
        inventory = inventory,
        ownPackage = context.packageName,
    )

    val syncManager = SyncManager(
        context = context,
        repository = repository,
        settingsStore = settingsStore,
        identityStore = identityStore,
        syncStore = syncStore,
        scope = scope,
        iconStore = iconStore,
        familyId = id,
        familyLabel = { if (multiFamily()) name().takeIf { it.isNotBlank() } else null },
    )

    /** This family's name as the parent set it, for choosers, notifications and file names. */
    suspend fun name(): String = settingsStore.current().familyName
}

/**
 * The families this device holds, live.
 *
 * Multi-family is deliberately a *parent-side* concept and changes nothing on the wire: each
 * family is already its own ntfy topic with its own keys, so a child cannot tell whether the
 * parent holds one family or five. What this class adds is the missing plural on the parent:
 * one set of stores, one transport and one alert stream per family, instead of the single
 * global one everything used to assume.
 */
class FamilyHub(
    private val context: Context,
    private val db: WalcottDatabase,
    private val inventory: AppInventory,
    private val scope: CoroutineScope,
) {
    private val store = FamiliesStore(context)
    private val instances = ConcurrentHashMap<String, FamilyScope>()

    /** Shared across families: an icon is a property of a package, not of who installed it. */
    private val iconStore = IconStore(context)

    val families: StateFlow<FamiliesState> =
        store.state.stateIn(scope, SharingStarted.Eagerly, FamiliesState())

    /** One card's worth of each family, for the chooser: who they are and what needs attention. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val summaries: StateFlow<List<FamilySummary>> = store.state.flatMapLatest { state ->
        val scopes = state.ids.map { scopeOf(it) }
        combine(
            scopes.map { family ->
                combine(family.settingsStore.settings, family.syncStore.state) { settings, sync ->
                    FamilySummary(
                        id = family.id,
                        name = settings.familyName,
                        children = settings.children.size,
                        pending = FamilyHealth.pending(sync),
                        alerts = FamilyHealth.alerts(sync.children, sync.lastSeen, System.currentTimeMillis()),
                    )
                }
            },
        ) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Which family the UI shows; null until the registry has actually been read (no flash). */
    val activeId: StateFlow<String?> =
        store.state.map { it.active }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * This device's own family: the one whose rules it enforces, whose PIN gates it and whose
     * identity the boot receiver reads. Always the default id, which is why enforcement never
     * has to care that a parent phone may hold others.
     */
    val own: FamilyScope get() = scopeOf(FamilyIds.DEFAULT)

    val active: FamilyScope get() = scopeOf(activeId.value ?: FamilyIds.DEFAULT)

    fun scopeOf(id: String): FamilyScope = instances.computeIfAbsent(id) {
        FamilyScope(context, id, db, inventory, scope, iconStore, multiFamily = { isMultiNow() })
            .also { it.syncManager.start() }
    }

    /** Every family currently held, in registry order (UI; reads the already-loaded state). */
    fun all(): List<FamilyScope> = families.value.ids.map { scopeOf(it) }

    /**
     * Every family, read from disk rather than from the cached state. Workers and receivers run
     * in freshly started processes where the registry flow may not have emitted yet, and a
     * background pass that silently covered only the first family would be worse than none.
     */
    suspend fun allNow(): List<FamilyScope> = store.current().ids.map { scopeOf(it) }

    /** Whether this device holds more than one family (alerts say which one when it does). */
    suspend fun isMultiNow(): Boolean = store.current().isMulti

    /** Brings every registered family online, and any that is added later. */
    fun start() {
        scope.launch {
            store.state.collect { state -> state.ids.forEach { scopeOf(it) } }
        }
    }

    /**
     * Creating, adopting and forgetting a family all run on the hub's own scope rather than the
     * caller's. They are multi-step and half-done is the one outcome that must not happen —
     * and every caller is a dialog that closes (cancelling its scope) the instant it confirms.
     */
    private suspend fun <T> onHubScope(block: suspend () -> T): T = scope.async { block() }.await()

    /**
     * Runs [block] on the hub's own scope, which lives as long as the process.
     *
     * For the actions that must finish once started even though the screen that asked for them is
     * already gone — the same reason [onHubScope] exists, for callers that have nothing to await.
     * Freeing a phone is the one that matters: it is a queued command followed by a registry edit,
     * and stopping in between is how a device ends up removed from the family and never told.
     */
    fun launchDurable(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { DebugLog.e(TAG, "durable action failed", it) } }
    }

    suspend fun setActive(id: String) = onHubScope { store.update { it.withActive(id) } }

    /**
     * Creates a brand-new family on this parent device and shows it.
     *
     * The PIN comes across from the family that is already here: it is the parent's PIN, not the
     * family's — they type it on their own phone and on every child's — so making them keep two
     * would be a bug dressed as a feature. It has to be COPIED rather than shared because each
     * family's children learn it from their own policy.
     */
    suspend fun createFamily(name: String): String = onHubScope {
        val source = active
        val id = newFamilyId()
        val fresh = scopeOf(id)
        fresh.syncManager.becomeParent(name)
        carryOverDeviceSecrets(source, fresh)
        store.update { it.plus(id, System.currentTimeMillis()) }
        DebugLog.i(TAG, "family created ($id)")
        id
    }

    enum class AddResult { OK, BAD_FILE, ALREADY_HERE }

    /**
     * Adopts a family from one of its backup files as an ADDITIONAL family, leaving the ones
     * already here untouched. Refuses a file for a family this device already holds: two scopes
     * on one topic would both publish as the parent and fight over the version counter.
     */
    suspend fun addFamilyFromBackup(fileJson: String, passphrase: CharArray): AddResult = onHubScope {
        val id = newFamilyId()
        val fresh = scopeOf(id)
        // Restored SILENT. The only way to know whose family this file is, is to open it, and a
        // scope that has already published on the topic cannot be taken back: its snapshot carries
        // a version a million ahead of the backup's, the children adopt it, and the scope that
        // really manages that family — still counting from its own much lower number — is refused
        // by every child from then on (SyncEngine.adoptsPolicy). The refusal below used to happen
        // one publish too late, so declining a duplicate was what broke the family.
        val restored = runCatching { fresh.syncManager.restoreBackup(fileJson, passphrase, goLive = false) }
            .getOrDefault(false)
        if (!restored) {
            discard(id)
            return@onHubScope AddResult.BAD_FILE
        }
        val topic = fresh.identityStore.current().topic
        val clash = allNow().any { it.id != id && it.identityStore.current().topic == topic }
        if (clash) {
            discard(id)
            return@onHubScope AddResult.ALREADY_HERE
        }
        carryOverDeviceSecrets(active, fresh)
        store.update { it.plus(id, System.currentTimeMillis()) }
        // Only now, and deliberately after the PIN has been carried over: the first snapshot then
        // already carries the credential this device actually gates on, instead of the backup's.
        fresh.syncManager.goLiveAfterRestore()
        DebugLog.i(TAG, "family restored as a new family ($id)")
        AddResult.OK
    }

    /**
     * Forgets a family: this device stops managing it and everything it knew about it is erased.
     * Its children are NOT freed — they keep enforcing the rules they last received — so the UI
     * asks in those words before calling this.
     */
    suspend fun removeFamily(id: String) = onHubScope {
        if (store.current().families.size <= 1) return@onHubScope
        store.update { it.minus(id) }
        discard(id)
        DebugLog.w(TAG, "family removed ($id)")
    }

    /** The family that holds [deviceId], or null when no family has ever heard from it. */
    suspend fun scopeForDevice(deviceId: String): FamilyScope? =
        allNow().firstOrNull { scope -> scope.syncStore.current().children.any { it.deviceId == deviceId } }

    /** The family whose registry holds [childId], for deep-linking an alert to the right home. */
    suspend fun scopeForChild(childId: String): FamilyScope? =
        allNow().firstOrNull { scope -> scope.settingsStore.current().children.any { it.childId == childId } }

    /**
     * Sets the parent PIN on EVERY family. One device, one PIN (see [createFamily]); each family
     * still carries its own copy because that is how its children receive it.
     */
    suspend fun setPinEverywhere(pin: String) {
        for (scope in allNow()) {
            scope.repository.setPin(pin)
            // The readable reminder, parent devices only — the call itself checks (see
            // FamilyIdentity.pinPlain for why it must never exist on a child).
            runCatching { scope.syncManager.rememberPinIfParent(pin) }
        }
        // The backup key is derived and the three on-device copies rewritten AFTER the caller
        // is free to go: that is PBKDF2 at 600k iterations plus three encrypt-and-write cycles
        // per family, which held the Save button frozen for tens of seconds while the thing the
        // parent asked for — the new PIN — had already been saved. Nothing downstream waits on
        // it, and a process death before it lands is repaired by the next correct PIN entry
        // (see verifyPinGuarded), which is where families predating the feature get it anyway.
        scope.launch {
            for (family in allNow()) {
                runCatching { family.syncManager.cacheLocalBackupKey(pin) }
                    .onFailure { dev.walcott.debug.DebugLog.w(TAG, "caching the backup key failed", it) }
            }
        }
    }

    /**
     * Applies a device-level preference (app lock, backup nudges) to every family. Those live in
     * each family's identity because each family's transport needs its own — but the person
     * setting them is choosing for the phone, not for one household.
     */
    suspend fun updateEveryIdentity(transform: (dev.walcott.sync.FamilyIdentity) -> dev.walcott.sync.FamilyIdentity) {
        for (scope in allNow()) scope.identityStore.save(transform(scope.identityStore.current()))
    }

    /** One child of one family, as the share sheet and other cross-family pickers see them. */
    data class ChildTarget(
        val name: String,
        val deviceId: String,
        val familyId: String,
        val familyName: String,
    )

    /** Every enrolled child across every family, so a share can reach any of them. */
    suspend fun allChildTargets(): List<ChildTarget> = allNow().flatMap { family ->
        val settings = family.settingsStore.current()
        val byChildId = family.syncStore.current().children.associateBy { it.childId }
        settings.children.mapNotNull { entry ->
            byChildId[entry.childId]?.let {
                ChildTarget(entry.name, it.deviceId, family.id, settings.familyName)
            }
        }
    }

    private suspend fun discard(id: String) {
        val scope = instances.remove(id) ?: return
        scope.syncManager.forgetFamily()
    }

    private fun newFamilyId(): String {
        val taken = families.value.ids.toSet()
        var id = FamilyIds.newId()
        while (id in taken) id = FamilyIds.newId()
        return id
    }

    /** The PIN and the key derived from it, carried into a family that has just appeared. */
    private suspend fun carryOverDeviceSecrets(from: FamilyScope, to: FamilyScope) {
        val source: PolicySettings = from.settingsStore.current()
        val hash = source.pinHash ?: return
        to.repository.updateSettings { it.copy(pinHash = hash, pinSalt = source.pinSalt) }
        val key = from.syncStore.current()
        if (key.localBackupKeyB64.isNotBlank()) {
            to.syncStore.update {
                it.copy(localBackupKeyB64 = key.localBackupKeyB64, localBackupSaltB64 = key.localBackupSaltB64)
            }
            runCatching { to.syncManager.writeDueLocalBackups(java.time.LocalDate.now()) }
        }
    }

    private companion object {
        const val TAG = "WalcottFamilies"
    }
}
