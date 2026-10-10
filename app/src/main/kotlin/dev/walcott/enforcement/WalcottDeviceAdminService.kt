package dev.walcott.enforcement

import android.app.admin.DeviceAdminService
import dev.walcott.debug.DebugLog
import dev.walcott.sync.IdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The service Android keeps bound for as long as this app is the Device Owner.
 *
 * Declaring it is the platform's own answer to "how does a DPC stay alive": the system binds it
 * when the device owner is set and at every start-up, holds that binding, and binds it again —
 * with a backoff — when the process dies. A bound service of the system server is about as hard
 * to kill as a process gets without being one, and every revival is a process start, which is the
 * one thing that brings enforcement back without depending on anything else having survived.
 *
 * That is what this app was missing on the phones that kill hardest. MIUI, ColorOS and friends
 * stop apps their own way, and with "autostart" off they also drop that app's alarms, jobs and
 * boot broadcast — every other route back. Whether a given OEM respects this binding is not
 * something the code can know (see docs on OEM behaviour); on AOSP and Pixel it is the contract.
 *
 * Nothing happens in it beyond that: it brings the service and the alarm chains back the way a
 * boot would, on a phone that is enforcing.
 */
class WalcottDeviceAdminService : DeviceAdminService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val identity = runCatching { IdentityStore(this@WalcottDeviceAdminService).current() }
                .onFailure { DebugLog.e(TAG, "could not read this device's identity", it) }
                .getOrNull() ?: return@launch
            if (!identity.enforcesLocally) return@launch
            DebugLog.i(TAG, "bound by the system: making sure enforcement is running")
            val context = this@WalcottDeviceAdminService
            runCatching { EnforcementService.start(context) }
                .onFailure { DebugLog.e(TAG, "could not start enforcement", it) }
            // An OEM that stopped this app the hard way took its alarms and jobs with it.
            runCatching { dev.walcott.sync.HeartbeatAlarm.schedule(context) }
            runCatching { dev.walcott.sync.PanicAlarm.sync(context) }
            runCatching { AppUpdateWindowAlarm.sync(context) }
            runCatching { WatchdogWorker.schedule(context) }
            runCatching { dev.walcott.update.UpdateWorker.schedule(context) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WalcottAdminService"
    }
}
