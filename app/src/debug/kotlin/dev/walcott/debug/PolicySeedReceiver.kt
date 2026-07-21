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
                    // Mark it a paired child (role=CHILD) so child-only UI (requests, asks) shows;
                    // a generated family key keeps the sync layer from choking on empty crypto.
                    "child" -> app.identityStore.save(
                        app.identityStore.current().copy(
                            mode = DeviceMode.CHILD,
                            role = dev.walcott.sync.Role.CHILD,
                            topic = "debug-topic",
                            familyKeyB64 = dev.walcott.sync.FamilyCrypto.toB64(
                                dev.walcott.sync.FamilyCrypto.generateFamilyKey().encoded,
                            ),
                        ),
                    )
                    "parent" -> app.identityStore.save(app.identityStore.current().copy(mode = DeviceMode.PARENT))
                    "reset" -> app.identityStore.save(dev.walcott.sync.FamilyIdentity())
                }
                // `--ez legacy_keys true` converts this parent family to a pre-v0.11 one
                // (signing key in the Android Keystore, not exportable), so the backup's
                // legacy branch — recovery keypair + RotationCert minted by the Keystore
                // key — can be exercised end-to-end on one emulator.
                if (intent.getBooleanExtra("legacy_keys", false)) {
                    dev.walcott.sync.ParentKeystore.ensureKeyPair()
                    app.identityStore.save(
                        app.identityStore.current().copy(
                            parentPublicKeyB64 = dev.walcott.sync.FamilyCrypto.toB64(
                                dev.walcott.sync.ParentKeystore.publicKey().encoded,
                            ),
                            parentPrivateKeyB64 = "",
                            rotationCertB64 = "",
                        ),
                    )
                    DebugLog.i("WalcottSeed", "converted to legacy Keystore signing key")
                }
                // Family backup e2e hooks: `--es backup_pass P [--es backup_to F]` writes the
                // encrypted backup into the app's files dir; `--es restore_from F --es
                // restore_pass P` restores from it. Together with mode=reset they exercise
                // the full lose-the-phone → restore path on one emulator.
                intent.getStringExtra("backup_pass")?.let { pass ->
                    // Basename only: the receiver is exported (adb), don't allow traversal.
                    val name = (intent.getStringExtra("backup_to") ?: "debug-backup.json").substringAfterLast('/')
                    val text = app.syncManager.createBackup(pass.toCharArray())
                    java.io.File(context.filesDir, name).writeText(text)
                    DebugLog.i("WalcottSeed", "backup written: $name (${text.length} bytes)")
                }
                intent.getStringExtra("restore_from")?.let { rawName ->
                    val name = rawName.substringAfterLast('/')
                    val pass = intent.getStringExtra("restore_pass") ?: ""
                    val ok = app.syncManager.restoreBackup(
                        java.io.File(context.filesDir, name).readText(),
                        pass.toCharArray(),
                    )
                    DebugLog.i("WalcottSeed", "restore from $name -> ok=$ok")
                }
                if (childApps != null) seedChild(app, childApps, intent)
                // Optional: back-date the child-side channel-health stamp (--el channel_ok_ago_ms N)
                // so the "no connection with your family" card can be exercised without cutting
                // the network and waiting hours.
                val channelAgo = intent.getLongExtra("channel_ok_ago_ms", -1)
                if (channelAgo >= 0) {
                    app.syncStore.update { it.copy(lastChannelOkMs = System.currentTimeMillis() - channelAgo) }
                }
                // Optional: render an installed app's icon and cache it under the fake apps'
                // packages, so the parent app list exercises the remote-icon render path.
                val iconFrom = intent.getStringExtra("child_icon_from")
                if (iconFrom != null && childApps != null) seedIcons(app, childApps, iconFrom)
                DebugLog.i("WalcottSeed", "seeded mode=$mode policy=${policyJson != null} childApps=${childApps != null}")
            } catch (t: Throwable) {
                DebugLog.e("WalcottSeed", "seed failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Writes a fake child snapshot ("childId:Name:pkg|Label,...") into the parent's sync store.
     * Optional extras drive the reliability UI on a single emulator: `--es child_gaps a,b`
     * (failed self-test), `--el child_skew_ms N` (clock tamper), `--es child_update_error e`
     * (e.g. waiting_parent), `--ez child_diag true` (a synthesized health report),
     * `--es child_usage "games=1800,video=600"` (today's per-category seconds),
     * `--ei child_history_days N` (a ledger of N past days, for the dashboard average),
     * `--ez child_feed true` (a handful of activity-feed entries for the wall).
     */
    private suspend fun seedChild(app: WalcottApplication, spec: String, intent: Intent) {
        val (childId, name, appsPart) = spec.split(":", limit = 3).let {
            Triple(it.getOrElse(0) { "c1" }, it.getOrElse(1) { "Device" }, it.getOrElse(2) { "" })
        }
        val apps = appsPart.split(",").filter { it.isNotBlank() }.map {
            val (pkg, label) = it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { p[0] } }
            dev.walcott.sync.InstalledAppInfo(pkg, label)
        }
        val usage = intent.getStringExtra("child_usage")?.split(",")?.filter { it.isNotBlank() }?.map {
            val (cat, secs) = it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1)?.toLongOrNull() ?: 0L) }
            dev.walcott.sync.UsageEntry(cat, secs)
        } ?: emptyList()
        val today = java.time.LocalDate.now().toEpochDay()
        val snapshot = dev.walcott.sync.ChildSnapshot(
            deviceId = "dev-$childId",
            displayName = name,
            version = System.currentTimeMillis(),
            epochDay = today,
            childId = childId,
            apps = apps,
            usage = usage,
            batteryPercent = intent.getIntExtra("child_battery", -1),
            enforcementGaps = intent.getStringExtra("child_gaps")?.split(",")?.filter { it.isNotBlank() }
                ?: emptyList(),
            clockSkewMs = intent.getLongExtra("child_skew_ms", 0),
            updateError = intent.getStringExtra("child_update_error") ?: "",
        )
        app.syncStore.update { s ->
            s.copy(
                children = s.children.filterNot { it.deviceId == snapshot.deviceId } + snapshot,
                lastSeen = s.lastSeen + (snapshot.deviceId to System.currentTimeMillis()),
            )
        }
        val historyDays = intent.getIntExtra("child_history_days", 0)
        if (historyDays > 0) {
            // Route through the real merge so the seeded ledger is exactly what snapshots build.
            val history = (1..historyDays).map { d ->
                dev.walcott.sync.DayUsage(
                    today - d,
                    listOf(dev.walcott.sync.UsageEntry("games", 5400L + (d % 5) * 900L)),
                )
            }
            val key = dev.walcott.sync.UsageLedger.keyOf(childId, snapshot.deviceId)
            app.syncStore.update { s ->
                s.copy(
                    usageHistory = s.usageHistory + (
                        key to dev.walcott.sync.UsageLedger.merge(
                            s.usageHistory[key].orEmpty(), history, today, usage.sumOf { it.seconds },
                        )
                        ),
                )
            }
        }
        if (intent.getBooleanExtra("child_feed", false)) {
            val now = System.currentTimeMillis()
            fun entry(type: String, agoMs: Long, detail: String = "", count: Int = 0) = dev.walcott.sync.ParentEvent(
                id = java.util.UUID.randomUUID().toString(),
                atMs = now - agoMs, type = type, childId = childId, childName = name,
                detail = detail, count = count,
            )
            val feed = listOf(
                entry(dev.walcott.sync.ParentEvent.TYPE_BONUS, 3 * 24 * 3_600_000L, count = 15),
                entry(dev.walcott.sync.ParentEvent.TYPE_ENFORCEMENT_GAP, 26 * 3_600_000L, count = 2),
                entry(dev.walcott.sync.ParentEvent.TYPE_ENFORCEMENT_GAP_CLEARED, 25 * 3_600_000L),
                entry(dev.walcott.sync.ParentEvent.TYPE_NEW_APP, 2 * 3_600_000L, detail = "Instagram"),
                entry(dev.walcott.sync.ParentEvent.TYPE_TIME_REQUEST, 20 * 60_000L, count = 30),
            )
            app.syncStore.update { s -> feed.fold(s) { acc, e -> acc.plusEvent(e) } }
        }
        if (intent.getBooleanExtra("child_diag", false)) {
            val report = dev.walcott.sync.DiagPayload(
                deviceId = snapshot.deviceId,
                atMs = System.currentTimeMillis(),
                enforcement = dev.walcott.sync.EnforcementStatus.DEVICE_OWNER,
                deviceOwner = true,
                usageAccess = false,
                gpsOn = true,
                networkLocationOn = false,
                locationPermission = true,
                batteryPercent = 37,
                charging = false,
                updateError = snapshot.updateError,
                suspendFailures = snapshot.enforcementGaps,
                appVersionCode = dev.walcott.BuildConfig.VERSION_CODE,
                appVersionName = dev.walcott.BuildConfig.VERSION_NAME,
                logLines = DebugLog.tail(20).ifEmpty { listOf("(empty log)") },
            )
            app.syncStore.update { it.copy(diagReports = it.diagReports + (snapshot.deviceId to report)) }
        }
    }

    /** Caches [iconFrom]'s (an installed app) icon under each fake app's package, for the remote-render path. */
    private fun seedIcons(app: WalcottApplication, spec: String, iconFrom: String) {
        val pkgs = spec.substringAfter(":", "").substringAfter(":", "")
            .split(",").filter { it.isNotBlank() }.map { it.substringBefore("=") }
        val drawable = runCatching { app.packageManager.getApplicationIcon(iconFrom) }.getOrNull() ?: return
        val bytes = dev.walcott.sync.IconStore.encode(drawable)
            ?.let { dev.walcott.sync.IconStore.decodeBase64(it) } ?: return
        val store = dev.walcott.sync.IconStore(app)
        pkgs.forEach { store.store(it, bytes) }
        DebugLog.i("WalcottSeed", "seeded ${pkgs.size} icons from $iconFrom")
    }
}
