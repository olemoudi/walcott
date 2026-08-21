package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.net.VpnController
import dev.walcott.sync.HeartbeatAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The emergency release: hands the device back, leaving no sign it was ever enrolled.
 *
 * Two ways in, both of them deliberate and both ending here — the parent PIN on the child's
 * device settings (instant, for a family that lost the parent phone but still knows the PIN),
 * and the child's own twelve-hour panic request (see [dev.walcott.sync.PanicProtocol]) for when the
 * PIN is gone too. Without either, a family whose parent device dies would be stuck with a
 * permanently locked-down phone whose only way out is a factory reset.
 *
 * Order matters and is the whole design: everything that needs Device Owner rights runs while
 * we still have them, and giving up Device Owner is the very last step. Every step is
 * independently guarded, because a device half-released — apps still suspended, restrictions
 * still on, no way to ask again — is much worse than one that failed loudly at step one.
 *
 * The half that decides whether the phone that comes out of this is HEALTHY is [DeviceHandback]:
 * every restriction the system will admit to, every installed package asked one at a time whether
 * it is still suspended, hidden or undeletable, and every other Device Owner knob put back. It
 * runs before Device Owner is given up because afterwards none of it is allowed — and anything
 * still set at that moment is set for good, with a factory reset as the only remaining cure.
 */
object PanicRelease {

    private const val TAG = "WalcottPanic"

    /**
     * Frees this device. Safe to call on a device that was never a Device Owner (the privileged
     * steps simply no-op) and idempotent enough to be retried after a partial failure.
     */
    suspend fun releaseDevice(context: Context) {
        val app = context.applicationContext as WalcottApplication
        DebugLog.w(TAG, "emergency release: standing down enforcement and unenrolling")

        // 1. Everything that could re-arm enforcement, before touching the policy it reads.
        runCatching { HeartbeatAlarm.cancel(context) }
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
        runCatching { VpnController.apply(context, false) }

        // 2. Give the phone back: every restriction, every suspended, hidden or undeletable
        // package, every other Device Owner knob. All of it needs Device Owner rights, so it must
        // precede step 6 — and it is the step that decides whether what comes out of this is a
        // healthy phone (see [DeviceHandback]). Off the caller's thread: it is a binder call per
        // installed package, three times over.
        runCatching { withContext(Dispatchers.IO) { DeviceHandback.run(context) } }
            .onFailure { DebugLog.e(TAG, "handing the device settings back failed", it) }

        // 3. And the lock screen, if the credential in force is one this app set remotely. Also
        // Device Owner only, and the sharpest deadline of the lot: a release that steps over this
        // hands back a phone whose owner may never have been told the PIN, with nothing left on it
        // that could ever reset one — the factory reset this whole feature exists to avoid, handed
        // out as the reward for waiting twenty-four hours. A lock the owner chose is left alone.
        runCatching { app.syncManager.handBackLockScreen() }
            .onFailure { DebugLog.e(TAG, "handing back the lock screen failed", it) }

        // 4. Stop enforcing and forget the family. The released flag is what keeps the boot
        // receiver, watchdog and heartbeat from starting it all up again on a wiped policy
        // (which, being empty, would classify every app as unknown and block the lot).
        runCatching { app.syncManager.markReleased() }
            .onFailure { DebugLog.e(TAG, "identity teardown failed", it) }
        EnforcementService.stop(context)

        // 5. Erase the local record: rules, usage, extra time, location trail, the sync
        // bookkeeping, cached icons and every pending notification. What survives is an app
        // that looks freshly installed.
        runCatching { app.repository.wipeLocalData() }
            .onFailure { DebugLog.e(TAG, "wiping local data failed", it) }
        runCatching { app.syncManager.wipeSyncState() }
            .onFailure { DebugLog.e(TAG, "wiping sync state failed", it) }
        runCatching { withContext(Dispatchers.IO) { dev.walcott.sync.IconStore(context).clear() } }
        runCatching {
            withContext(Dispatchers.IO) { dev.walcott.net.BlocklistStore.get(context).clear() }
        }
        runCatching { NotificationManagerCompat.from(context).cancelAll() }

        // 6. Last: stop being Device Owner. After this the app has no privileges left — the
        // "managed by your organization" badge disappears and Walcott can be uninstalled.
        releaseDeviceOwner(context)
        DebugLog.w(TAG, "emergency release complete")
    }

    /**
     * Finishes a release that stopped halfway, called on every start-up of a device whose
     * identity says it was already released.
     *
     * Steps 4 to 6 above are the dangerous stretch: step 4 stops the foreground service, so from
     * there on the process is ordinary and killable, while Device Owner — the thing the release
     * exists to give up — isn't dropped until step 6. A process death in between used to be
     * terminal: the device is no longer a child, so the settings screen no longer offers the
     * release button that would retry it, and a phone permanently owned by an app that no longer
     * manages anything can only be cleaned up with a factory reset.
     *
     * Everything here is idempotent, so running it on every start-up of a released device costs
     * one Device Owner check and nothing else.
     */
    suspend fun finishIfInterrupted(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        DebugLog.w(TAG, "released device is still Device Owner: finishing the interrupted release")
        // The same handback, and it asks the SYSTEM rather than the policy — which is the only
        // thing that could work here, since the policy was erased before the interruption. A
        // released device that kept an app suspended has no enforcement loop left to lift it.
        runCatching { withContext(Dispatchers.IO) { DeviceHandback.run(context) } }
            .onFailure { DebugLog.e(TAG, "handing the device settings back failed", it) }
        releaseDeviceOwner(context)
    }

    /**
     * Drops Device Owner. Deprecated since API 26 but never replaced for this case: the
     * documented alternative is [DevicePolicyManager.wipeData], which factory-resets the phone —
     * exactly the outcome this feature exists to avoid.
     */
    @Suppress("DEPRECATION")
    private fun releaseDeviceOwner(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
            .onFailure { DebugLog.e(TAG, "clearing device owner failed", it) }
    }

    /** Opens the system uninstall prompt, so the child can finish removing the app themselves. */
    fun requestUninstall(context: Context) {
        val intent = android.content.Intent(android.content.Intent.ACTION_DELETE)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
