package dev.walcott.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.net.VpnController
import dev.walcott.sync.HeartbeatAlarm
import dev.walcott.sync.PanicAlarm
import dev.walcott.sync.PanicNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The emergency release: hands the device back, leaving no sign it was ever enrolled.
 *
 * Three ways in, all of them deliberate and all ending here — the parent PIN on the child's
 * device settings (instant, for a family that lost the parent phone but still knows the PIN),
 * the parent's own "free this phone" from a distance ([dev.walcott.sync.RemoteAction.RELEASE_DEVICE]),
 * and the child's twelve-hour panic request (see [dev.walcott.sync.PanicProtocol]) for when the
 * PIN is gone too. Without any of them, a family whose parent device dies would be stuck with a
 * permanently locked-down phone whose only way out is a factory reset.
 *
 * Order matters and is the whole design: everything that needs Device Owner rights runs while
 * we still have them, and giving up Device Owner is the very last step. Every step is
 * independently guarded, because a device half-released — apps still suspended, restrictions
 * still on, no way to ask again — is much worse than one that failed loudly at step one.
 *
 * Two things frame the steps, and both exist because of what a release costs when it goes wrong:
 *
 *  - **Nothing may re-arm while it runs.** The enforcement loop re-asserts suspensions every
 *    thirty seconds, alarms re-apply restrictions, the filter re-pins the always-on VPN — and
 *    every one of them checks `isDeviceOwnerApp`, which stays true until the last step. So the
 *    first thing a release does is raise [inProgress], which every one of those gates reads, and
 *    then it stops the service and WAITS for it to die before touching anything. A suspension
 *    re-asserted after the handback and before Device Owner is dropped is a suspension for life.
 *  - **It must be resumable from any point.** The persisted [dev.walcott.sync.FamilyIdentity.released]
 *    is written before the first privileged call, not after the last: a process that dies in the
 *    middle comes back and [finishIfInterrupted] runs the same steps again, each of which asks the
 *    system what is still set rather than remembering what it once put on.
 *
 * The half that decides whether the phone that comes out of this is HEALTHY is [DeviceHandback]:
 * every restriction the system will admit to, every installed package asked one at a time whether
 * it is still suspended, hidden or undeletable, and every other Device Owner knob put back — and
 * then asked again, so what could not come off is known by name rather than guessed at.
 */
object PanicRelease {

    private const val TAG = "WalcottPanic"

    /**
     * True while a release is running in this process. Read by every gate that can put Device
     * Owner state back — the enforcement service's start, the suspension reconciler, the
     * restriction applier, the always-on VPN, the reset token, the policy-granted permissions,
     * lost mode — so nothing re-arms what the handback is taking off.
     *
     * In memory on purpose: it answers "is a teardown running right now", and a process that
     * died mid-way has nothing running. The persisted `released` flag is what resumes it.
     */
    @Volatile
    var inProgress: Boolean = false
        private set

    /**
     * Debug builds only, written by the seed receiver: kill the process right before Device
     * Owner would be given up, so the resume on the next start can be exercised on a device —
     * the one failure `am force-stop` cannot produce on a Device Owner. Nothing in a release
     * build writes it.
     */
    @Volatile
    var dieBeforeClearForTest: Boolean = false

    /** Serializes the teardown: the parent's command and the child's PIN can arrive together. */
    private val releaseMutex = Mutex()

    /** How long to wait for the enforcement service to confirm it has died once told to stop. */
    private const val SERVICE_STOP_TIMEOUT_MS = 5_000L

    /**
     * Frees this device. Safe to call on a device that was never a Device Owner (the privileged
     * steps simply no-op), idempotent, and serialized: a second caller waits for the first and
     * then finds nothing left to do.
     */
    suspend fun releaseDevice(context: Context) = releaseMutex.withLock { releaseLocked(context) }

    private suspend fun releaseLocked(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val identity = app.identityStore.current()
        if (identity.released && !isDeviceOwner(context) && !identity.isPaired) {
            DebugLog.i(TAG, "emergency release already complete; nothing to do")
            return
        }
        inProgress = true
        try {
            DebugLog.w(TAG, "emergency release: standing down enforcement and unenrolling")

            // 1. The marker, before anything privileged is touched. From here the device does not
            // enforce (see FamilyIdentity.enforcesLocally) and a process that dies anywhere below
            // is finished by finishIfInterrupted on its next start. Keys and topic stay for now:
            // the parent's acknowledgement may still be on its way out over the channel.
            runCatching { app.syncManager.markReleasing() }
                .onFailure { DebugLog.e(TAG, "could not persist the release marker", it) }

            // 2. Stop everything that could re-arm enforcement, and wait for the loop to be dead
            // rather than merely told to stop: stopService returns at once, and a tick already in
            // flight would re-suspend into the middle of the handback below.
            EnforcementService.stop(context)
            val stopped = withTimeoutOrNull(SERVICE_STOP_TIMEOUT_MS) {
                EnforcementService.running.first { !it }
            } != null
            if (!stopped) DebugLog.w(TAG, "the enforcement service did not confirm stopping; going on")
            // Every alarm that applies policy when it fires, cancelled by name. Each of them
            // self-heals when it fires on a device that no longer enforces — which is one fire
            // too late when it lands mid-handback.
            runCatching { HeartbeatAlarm.cancel(context) }
            runCatching { dev.walcott.location.LocationAlarm.cancel(context) }
            runCatching { InstallBlockAlarm.cancel(context) }
            runCatching { AppUpdateWindowAlarm.cancel(context) }
            runCatching { PanicAlarm.cancel(context) }
            runCatching { WorkManager.getInstance(context).cancelAllWork() }
            runCatching { VpnController.apply(context, false) }

            // 3. Give the phone back: every restriction, every suspended, hidden or undeletable
            // package, every other Device Owner knob — then asked again, and swept again if
            // anything stayed. All of it needs Device Owner rights, so it must precede step 8, and
            // it is the step that decides whether what comes out of this is a healthy phone (see
            // DeviceHandback). Off the caller's thread: it is a binder call per installed package,
            // several times over.
            val held = runCatching { withContext(Dispatchers.IO) { DeviceHandback.run(context) } }
                .onFailure { DebugLog.e(TAG, "handing the device settings back failed", it) }
                .getOrDefault(emptyList())

            // 4. And the lock screen, if the credential in force is one this app set remotely. Also
            // Device Owner only, and the sharpest deadline of the lot: a release that steps over this
            // hands back a phone whose owner may never have been told the PIN, with nothing left on
            // it that could ever reset one — the factory reset this whole feature exists to avoid,
            // handed out as the reward for waiting twelve hours. A lock the owner chose is left alone.
            runCatching { app.syncManager.handBackLockScreen() }
                .onFailure { DebugLog.e(TAG, "handing back the lock screen failed", it) }

            // 5. Forget the family: close the channel, drop the keys, keep the released flag and
            // what could not be given back (shown on the mode screen afterwards).
            runCatching { app.syncManager.markReleased(held) }
                .onFailure { DebugLog.e(TAG, "identity teardown failed", it) }

            // 6. Erase the local record: rules, usage, extra time, location trail, the sync
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

            // 7. Debug builds only: the process death the device scenario asks for (see above).
            if (dieBeforeClearForTest) {
                DebugLog.w(TAG, "dying on purpose before clearing device owner (test hook)")
                android.os.Process.killProcess(android.os.Process.myPid())
            }

            // 8. Last: stop being Device Owner. After this the app has no privileges left — the
            // "managed by your organization" badge disappears and Walcott can be uninstalled.
            releaseDeviceOwner(context)
            if (held.isEmpty()) {
                DebugLog.w(TAG, "emergency release complete")
            } else {
                DebugLog.e(TAG, "emergency release complete; could not give back: ${held.joinToString()}")
            }
            // Said on the device itself, whichever door it came through. Posted after the wipe
            // above, which cancels every notification this app ever showed.
            runCatching { PanicNotifications.notifyReleased(context) }
        } finally {
            inProgress = false
        }
    }

    /**
     * Finishes a release that stopped halfway, called on every start-up of a device whose
     * identity says it was released.
     *
     * Two shapes of "halfway", both of them a process death: one before Device Owner was given
     * up, which used to be terminal — the device is no longer a child, so the settings screen no
     * longer offers the release button that would retry it, and a phone permanently owned by an
     * app that manages nothing can only be cleaned up with a factory reset; and one before the
     * family was forgotten, which would have this phone reconnect and publish to a parent that
     * already let it go. Both are answered by running the same release again: every step asks
     * the system what is still set, so a release that already finished costs one identity read.
     */
    suspend fun finishIfInterrupted(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val identity = app.identityStore.current()
        if (!identity.released) return
        val owner = isDeviceOwner(context)
        if (!owner && !identity.isPaired) return
        DebugLog.w(
            TAG,
            "released device is still ${if (owner) "Device Owner" else "paired"}: finishing the interrupted release",
        )
        releaseDevice(context)
    }

    private fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

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
