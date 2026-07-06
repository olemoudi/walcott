package dev.walcott

import android.app.Application
import dev.walcott.data.AppInventory
import dev.walcott.data.SettingsStore
import dev.walcott.data.WalcottDatabase
import dev.walcott.data.WalcottRepository

/** Process-wide dependency container (manual DI — no frameworks). */
class WalcottApplication : Application() {

    lateinit var repository: WalcottRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = WalcottRepository(
            db = WalcottDatabase.get(this),
            settingsStore = SettingsStore(this),
            inventory = AppInventory(this),
            ownPackage = packageName,
        )
    }
}
