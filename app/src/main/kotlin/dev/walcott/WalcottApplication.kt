package dev.walcott

import android.app.Application
import dev.walcott.data.AppInventory
import dev.walcott.data.ThemeStore
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

    /** The families this device holds, all of them live (see [FamilyHub]). */
    lateinit var hub: FamilyHub
        private set

    /**
     * This device's OWN family — the one it enforces, is gated by, and boots into. Everything
     * device-local (enforcement, watchdog, heartbeat, updates, the panic release) goes through
     * these, and they never move: a device only ever enforces for one family, however many a
     * parent phone happens to manage. The parent UI works on [FamilyHub.active] instead.
     */
    val repository: WalcottRepository get() = hub.own.repository
    val syncManager: SyncManager get() = hub.own.syncManager
    val identityStore: IdentityStore get() = hub.own.identityStore

    /** Exposed for the debug-only test seeder; the app itself goes through [syncManager]. */
    val syncStore: SyncStore get() = hub.own.syncStore

    /** Device-local light/dark preference (see [ThemeStore]). */
    lateinit var themeStore: ThemeStore
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        dev.walcott.debug.CrashCounter.init(this)
        installCrashLogger()
        themeStore = ThemeStore(this)
        hub = FamilyHub(
            context = this,
            db = WalcottDatabase.get(this),
            inventory = AppInventory(this),
            scope = appScope,
        )
        // Brings this device's own family online immediately (the child path depends on it) and
        // every other family the parent holds as soon as the registry has been read.
        hub.own
        hub.start()
        observeModeTransitions()

        // One-time seeding on the parent (children receive it via sync).
        appScope.launch {
            if (identityStore.current().role == Role.PARENT) repository.seedHardeningIfNeeded()
        }

        // Keep the app up to date: a periodic check plus one now (covers app launch).
        UpdateWorker.schedule(this)
        UpdateWorker.runNow(this)

        // Parent-side watchdog for children that stop checking in (no-op on other modes).
        StaleChildWorker.schedule(this)

        // Parent-side nudge when the family backup is missing or stale (no-op elsewhere).
        dev.walcott.sync.BackupReminderWorker.schedule(this)

        // Parent-side nightly copy into shared storage, so an uninstall isn't the end of the
        // family (no-op elsewhere, and until the PIN has been seen once).
        dev.walcott.sync.LocalBackupWorker.schedule(this)

        // Parent-side catch-up poll so requests/alerts arrive while the app is closed.
        ParentPollWorker.schedule(this)

        // Child-side watchdog: keep enforcement alive and re-assert Device Owner policies.
        WatchdogWorker.schedule(this)

        // Child-side ~30-min check-in that Doze can't defer for hours (see HeartbeatAlarm), and
        // the parent's mirror image of it: the catch-up poll that fetches a child's request or an
        // emergency-release notice while the app is closed had only WorkManager behind it, which
        // Doze defers for exactly as long as the parent's phone is resting.
        appScope.launch {
            val id = identityStore.current()
            if (id.enforcesLocally) dev.walcott.sync.HeartbeatAlarm.schedule(this@WalcottApplication)
            if (id.effectiveMode == dev.walcott.sync.DeviceMode.PARENT) {
                dev.walcott.sync.ParentCheckAlarm.schedule(this@WalcottApplication)
            }
        }

        // A release that stopped halfway leaves a device nobody manages but that is still owned
        // by this app, and no screen offering to retry (see PanicRelease.finishIfInterrupted).
        appScope.launch {
            if (identityStore.current().released) {
                runCatching { dev.walcott.enforcement.PanicRelease.finishIfInterrupted(this@WalcottApplication) }
                    .onFailure { DebugLog.e(TAG, "finishing the interrupted release failed", it) }
            }
        }

        // The share-a-backup flow parks the encrypted file in cache (see FamilyBackupCard);
        // deleting it right after sharing would race the receiving app's read, so it is
        // pruned here instead — the next process start, once any share has long finished.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.io.File(cacheDir, "backups").deleteRecursively() }
        }
    }

    /**
     * Writes the crash to the persisted debug log before the process dies, then lets Android
     * take its normal course (which is what actually kills it — swallowing the throw would leave
     * a half-dead process behind, and this app is not the right place to guess at recovery).
     *
     * The log tail IS this app's diagnostics: it is what a remote DIAGNOSE ships to the parent
     * and what the debug screen shows. Without this, the one failure worth investigating on a
     * child device — the app dying — is the only one that leaves no trace, and the parent sees
     * nothing but a device that went quiet.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DebugLog.crash(TAG, "uncaught exception on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Emergency release of this device (see [dev.walcott.enforcement.PanicRelease]), run on the
     * app scope: it unsuspends apps, drops Device Owner and wipes the local data, and a screen
     * disappearing mid-way — which is exactly what the identity reset causes — must not leave
     * the device half-freed. [onDone] is called on the main thread when it has finished.
     */
    fun releaseDevice(onDone: () -> Unit = {}) {
        appScope.launch {
            runCatching { dev.walcott.enforcement.PanicRelease.releaseDevice(this@WalcottApplication) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    /**
     * Pushes an assisted app install to a child from the share-sheet flow. Runs on the app
     * scope (not the launching activity's) so it survives the activity finishing immediately.
     */
    fun pushAppInstall(deviceId: String, pkg: String) {
        appScope.launch {
            // The push has to leave from the family that device belongs to — its topic and its
            // keys — which on a parent holding several is not necessarily the one on screen
            // (the share sheet lists every family's children).
            val family = hub.scopeForDevice(deviceId) ?: hub.active
            family.syncManager.sendCommand(deviceId, dev.walcott.sync.RemoteAction.INSTALL_APP, arg = pkg)
        }
    }

    /**
     * Child's side of the share-sheet flow: queues an install request for the parents. Runs on
     * the app scope so it survives the activity finishing; [onResult] is invoked on Main.
     */
    fun requestAppInstall(
        pkg: String,
        label: String,
        onResult: (dev.walcott.sync.SyncManager.InstallRequestResult) -> Unit,
    ) {
        appScope.launch {
            val result = runCatching { syncManager.sendInstallRequest(pkg, label) }
                .getOrDefault(dev.walcott.sync.SyncManager.InstallRequestResult.SENT)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * Starts/stops enforcement when the device mode CHANGES (a foreground, user-driven
     * event, so the foreground-service start is always allowed). The initial start is
     * done by MainActivity / BootReceiver, which run in exempt contexts — reacting to the
     * first emission here could be a background FGS start and get the app killed.
     */
    private companion object {
        const val TAG = "WalcottApp"
    }

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
