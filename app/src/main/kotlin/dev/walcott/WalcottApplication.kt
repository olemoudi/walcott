package dev.walcott

import android.app.Application
import dev.walcott.data.AppInventory
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottDatabase
import dev.walcott.data.WalcottRepository
import dev.walcott.sync.IdentityStore
import dev.walcott.sync.SyncManager
import dev.walcott.sync.SyncStore
import dev.walcott.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Process-wide dependency container (manual DI — no frameworks). */
class WalcottApplication : Application() {

    lateinit var repository: WalcottRepository
        private set
    lateinit var syncManager: SyncManager
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val settingsStore = SettingsStore(this)
        repository = WalcottRepository(
            db = WalcottDatabase.get(this),
            settingsStore = settingsStore,
            inventory = AppInventory(this),
            ownPackage = packageName,
        )
        syncManager = SyncManager(
            context = this,
            repository = repository,
            settingsStore = settingsStore,
            identityStore = IdentityStore(this),
            syncStore = SyncStore(this),
            scope = appScope,
        )
        syncManager.start()

        // Keep the app up to date: a periodic check plus one now (covers app launch).
        UpdateWorker.schedule(this)
        UpdateWorker.runNow(this)
    }
}
