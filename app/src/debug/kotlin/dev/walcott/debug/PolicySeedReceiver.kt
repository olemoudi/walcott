package dev.walcott.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.walcott.WalcottApplication
import dev.walcott.data.PolicySettings
import dev.walcott.sync.DeviceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Debug-build-only test hook: seeds this device with a policy and a device mode from adb, so
 * an emulator can be driven into child mode with a mocked policy and real enforcement can be
 * observed without a second (parent) device. Lives in the debug source set — release builds
 * do not contain it.
 *
 * Usage:
 *   adb shell am broadcast -n dev.walcott/.debug.PolicySeedReceiver \
 *       --es mode child --es policy_b64 "$(base64 -w0 policy.json)"
 *
 * `policy`/`policy_b64` is a full [PolicySettings] JSON (replaces the stored one); `mode` is
 * "child", "parent" or "reset" (forget the identity, as a fresh install would start — useful
 * on a Device Owner emulator where `pm clear` is refused). Either extra may be omitted.
 */
class PolicySeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WalcottApplication
        val mode = intent.getStringExtra("mode")
        val policyJson = intent.getStringExtra("policy")
            ?: intent.getStringExtra("policy_b64")?.let { String(java.util.Base64.getDecoder().decode(it)) }

        // Optional fake child snapshot so the parent's app list / catalog populates on a
        // single emulator: "childId:Device Name:pkg=Label,pkg=Label" (no shell-special chars).
        val childApps = intent.getStringExtra("child_apps")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (policyJson != null) {
                    val decoded = Json { ignoreUnknownKeys = true }
                        .decodeFromString(PolicySettings.serializer(), policyJson)
                    app.repository.updateSettings { decoded }
                }
                when (mode) {
                    "child" -> app.identityStore.save(app.identityStore.current().copy(mode = DeviceMode.CHILD))
                    "parent" -> app.identityStore.save(app.identityStore.current().copy(mode = DeviceMode.PARENT))
                    "reset" -> app.identityStore.save(dev.walcott.sync.FamilyIdentity())
                }
                if (childApps != null) seedChild(app, childApps, intent.getIntExtra("child_battery", -1))
                DebugLog.i("WalcottSeed", "seeded mode=$mode policy=${policyJson != null} childApps=${childApps != null}")
            } catch (t: Throwable) {
                DebugLog.e("WalcottSeed", "seed failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Writes a fake child snapshot ("childId:Name:pkg|Label,...") into the parent's sync store. */
    private suspend fun seedChild(app: WalcottApplication, spec: String, batteryLevel: Int) {
        val (childId, name, appsPart) = spec.split(":", limit = 3).let {
            Triple(it.getOrElse(0) { "c1" }, it.getOrElse(1) { "Device" }, it.getOrElse(2) { "" })
        }
        val apps = appsPart.split(",").filter { it.isNotBlank() }.map {
            val (pkg, label) = it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { p[0] } }
            dev.walcott.sync.InstalledAppInfo(pkg, label)
        }
        val snapshot = dev.walcott.sync.ChildSnapshot(
            deviceId = "dev-$childId",
            displayName = name,
            version = System.currentTimeMillis(),
            epochDay = java.time.LocalDate.now().toEpochDay(),
            childId = childId,
            apps = apps,
            batteryPercent = batteryLevel,
        )
        app.syncStore.update { s ->
            s.copy(
                children = s.children.filterNot { it.deviceId == snapshot.deviceId } + snapshot,
                lastSeen = s.lastSeen + (snapshot.deviceId to System.currentTimeMillis()),
            )
        }
    }
}
