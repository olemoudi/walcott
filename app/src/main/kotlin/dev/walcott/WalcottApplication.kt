package dev.walcott

import android.app.Application
import dev.walcott.data.AppInventory
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottDatabase
import dev.walcott.data.WalcottRepository
import dev.walcott.debug.DebugLog
import dev.walcott.enforcement.EnforcementService
import dev.walcott.enforcement.WatchdogWorker
import dev.walcott.net.VpnController
import dev.walcott.sync.IdentityStore
import dev.walcott.sync.ParentPollWorker
import dev.walcott.sync.Role
import dev.walcott.sync.StaleChildWorker
import dev.walcott.sync.SyncManager
import dev.walcott.sync.SyncStore
import dev.walcott.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide dependency container (manual DI — no frameworks). */
class WalcottApplication : Application() {

    lateinit var repository: WalcottRepository
        private set
    lateinit var syncManager: SyncManager
        private set
    lateinit var identityStore: IdentityStore
        private set
    /** Exposed for the debug-only test seeder; the app itself goes through [syncManager]. */
    lateinit var syncStore: SyncStore
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        val settingsStore = SettingsStore(this)
        identityStore = IdentityStore(this)
        repository = WalcottRepository(
            db = WalcottDatabase.get(this),
            settingsStore = settingsStore,
            inventory = AppInventory(this),
            ownPackage = packageName,
        )
        syncStore = SyncStore(this)
        syncManager = SyncManager(
            context = this,
            repository = repository,
            settingsStore = settingsStore,
            identityStore = identityStore,
            syncStore = syncStore,
            scope = appScope,
        )
        syncManager.start()
        observeModeTransitions()

        // One-time migrations/seeding on the parent (children receive these via sync).
        appScope.launch {
            repository.migrateLocalAssignmentsToSettings()
            if (identityStore.current().role == Role.PARENT) repository.seedHardeningIfNeeded()
        }

        // Keep the app up to date: a periodic check plus one now (covers app launch).
        UpdateWorker.schedule(this)
        UpdateWorker.runNow(this)

        // Parent-side watchdog for children that stop checking in (no-op on other modes).
        StaleChildWorker.schedule(this)

        // Parent-side catch-up poll so requests/alerts arrive while the app is closed.
        ParentPollWorker.schedule(this)

        // Child-side watchdog: keep enforcement alive and re-assert Device Owner policies.
        WatchdogWorker.schedule(this)

        // Child-side ~30-min check-in that Doze can't defer for hours (see HeartbeatAlarm).
        appScope.launch {
            if (identityStore.current().enforcesLocally) dev.walcott.sync.HeartbeatAlarm.schedule(this@WalcottApplication)
        }

        // The share-a-backup flow parks the encrypted file in cache (see FamilyBackupCard);
        // deleting it right after sharing would race the receiving app's read, so it is
        // pruned here instead — the next process start, once any share has long finished.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.io.File(cacheDir, "backups").deleteRecursively() }
        }
    }

    /**
     * Pushes an assisted app install to a child from the share-sheet flow. Runs on the app
     * scope (not the launching activity's) so it survives the activity finishing immediately.
     * Classifies the app first, when a category was chosen, so it isn't blocked on install.
     */
    fun pushAppInstall(deviceId: String, pkg: String, categoryId: String?) {
        appScope.launch {
            if (categoryId != null) repository.assign(pkg, categoryId)
            syncManager.sendCommand(deviceId, dev.walcott.sync.RemoteAction.INSTALL_APP, arg = pkg)
        }
    }

    /**
     * Starts/stops enforcement when the device mode CHANGES (a foreground, user-driven
     * event, so the foreground-service start is always allowed). The initial start is
     * done by MainActivity / BootReceiver, which run in exempt contexts — reacting to the
     * first emission here could be a background FGS start and get the app killed.
     */
    private fun observeModeTransitions() {
        appScope.launch {
            identityStore.identity
                .map { it.enforcesLocally }
                .distinctUntilChanged()
                .drop(1)
                .collect { enforcing ->
                    if (enforcing) {
                        runCatching { EnforcementService.start(this@WalcottApplication) }
                    } else {
                        EnforcementService.stop(this@WalcottApplication)
                        VpnController.apply(this@WalcottApplication, false)
                    }
                }
        }
    }
}
