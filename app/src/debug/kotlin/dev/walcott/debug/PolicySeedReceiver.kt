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
                // `--es family_new "Name"` creates an ADDITIONAL family and logs its id; passing
                // that id back as `--es family <id>` aims every other extra at it. Together they
                // let one emulator play a parent who manages two families, which is otherwise a
                // two-phone scenario.
                intent.getStringExtra("family_new")?.let { name ->
                    val id = app.hub.createFamily(name)
                    DebugLog.i("WalcottSeed", "family created: id=$id name=$name")
                }
                val target = intent.getStringExtra("family")?.let { app.hub.scopeOf(it) } ?: app.hub.own
                if (policyJson != null) {
                    val decoded = Json { ignoreUnknownKeys = true }
                        .decodeFromString(PolicySettings.serializer(), policyJson)
                    target.repository.updateSettings { decoded }
                }
                when (mode) {
                    // Mark it a paired child (role=CHILD) so child-only UI (requests, asks) shows;
                    // a generated family key keeps the sync layer from choking on empty crypto.
                    "child" -> target.identityStore.save(
                        target.identityStore.current().copy(
                            mode = DeviceMode.CHILD,
                            role = dev.walcott.sync.Role.CHILD,
                            topic = "debug-topic",
                            familyKeyB64 = dev.walcott.sync.FamilyCrypto.toB64(
                                dev.walcott.sync.FamilyCrypto.generateFamilyKey().encoded,
                            ),
                        ),
                    )
                    "parent" -> target.identityStore.save(target.identityStore.current().copy(mode = DeviceMode.PARENT))
                    // `--es mode pair --es pair_with "walcott1:…"`: joins this device to a family
                    // as a real child, through the same code path the QR uses — so one emulator
                    // can play the parent, publish, and then become the child that receives it.
                    "pair" -> {
                        val ok = target.syncManager.pairAsChild(intent.getStringExtra("pair_with").orEmpty())
                        DebugLog.i("WalcottSeed", "pairAsChild ok=$ok")
                    }
                    "reset" -> target.identityStore.save(dev.walcott.sync.FamilyIdentity())
                    // `--es mode local_backup [--es local_backup_slots daily,weekly,monthly]`:
                    // writes the shared-storage copies now, and logs what this install can see
                    // afterwards. Exists to answer the scoped-storage question on a device rather
                    // than from documentation: does the write need a permission, does the file
                    // outlive an uninstall, and can a reinstalled app still enumerate it.
                    // `--es mode local_backup --es local_backup_pin 4291`: creates a family if
                    // needed, derives the on-device backup key from that PIN and writes the copies
                    // through the real path — so the resulting file can be pulled off the device
                    // and decrypted elsewhere to prove it is genuinely restorable.
                    "local_backup" -> {
                        if (target.identityStore.current().role != dev.walcott.sync.Role.PARENT) {
                            target.syncManager.becomeParent("DebugFamily")
                        }
                        val pin = intent.getStringExtra("local_backup_pin") ?: "4291"
                        target.repository.setPin(pin)
                        target.syncManager.cacheLocalBackupKey(pin)
                        val written = target.syncManager.writeDueLocalBackups(java.time.LocalDate.now())
                        DebugLog.i("WalcottSeed", "local backup wrote=$written pin=$pin")
                        DebugLog.i("WalcottSeed", "visible to this install: ${dev.walcott.sync.LocalBackupStore.listOwn(app)}")
                    }
                    // `--es mode clear_do`: drops Device Owner so the app can be uninstalled.
                    // `dpm remove-active-admin` refuses to do this from adb, and without it an
                    // uninstall-and-reinstall — the whole scenario the local backup exists for —
                    // can't be rehearsed. Re-provision afterwards with `dpm set-device-owner`.
                    "clear_do" -> {
                        val dpm = app.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                        val ok = runCatching { dpm.clearDeviceOwnerApp(app.packageName) }.isSuccess
                        DebugLog.i("WalcottSeed", "clearDeviceOwnerApp ok=$ok")
                    }
                    // `--es mode upgraded_parent`: a parent that existed BEFORE the on-device
                    // copies did — PIN set, no backup key. Reproduces what every current family
                    // sees the first time they update, which is the only way to check that the
                    // "turn it on" card actually shows up for them.
                    "upgraded_parent" -> {
                        if (target.identityStore.current().role != dev.walcott.sync.Role.PARENT) {
                            target.syncManager.becomeParent("DebugFamily")
                        }
                        target.repository.setPin(intent.getStringExtra("local_backup_pin") ?: "4291")
                        target.syncManager.clearLocalBackupKeyForDebug()
                        DebugLog.i("WalcottSeed", "upgraded parent: pin set, local backup key cleared")
                    }
                    // `--es mode local_backup_list`: only enumerates, so a freshly installed app
                    // can be asked what it sees without writing anything first.
                    "local_backup_list" ->
                        DebugLog.i("WalcottSeed", "visible to this install: ${dev.walcott.sync.LocalBackupStore.listOwn(app)}")
                }
                // `--es ntfy_server http://10.0.2.2:8099`: points this device's channel at a local
                // sink, so a parent->child exchange can be exercised when ntfy.sh is rate-limiting.
                intent.getStringExtra("ntfy_server")?.let { url ->
                    target.identityStore.save(target.identityStore.current().copy(ntfyServer = url))
                    // Reconnect on the spot: the transport captures its URL when it is built, and
                    // a Device Owner refuses `am force-stop`, so there is no restarting into it.
                    target.syncManager.start()
                    DebugLog.i("WalcottSeed", "ntfy server -> $url")
                }
                // `--es apply_msg_b64 <base64 of an envelope>`: feeds one message into the real
                // receive path (decode, signature check, resolveForChild, settings write). Lets the
                // parent->child half be verified without depending on the public server's mood.
                intent.getStringExtra("apply_msg_b64")?.let { b64 ->
                    val raw = String(java.util.Base64.getDecoder().decode(b64))
                    target.syncManager.applyIncoming(raw, System.currentTimeMillis() / 1000)
                    DebugLog.i("WalcottSeed", "applied incoming message (${raw.length} bytes)")
                }
                // `--ez legacy_keys true` converts this parent family to a pre-v0.11 one
                // (signing key in the Android Keystore, not exportable), so the backup's
                // legacy branch — recovery keypair + RotationCert minted by the Keystore
                // key — can be exercised end-to-end on one emulator.
                if (intent.getBooleanExtra("legacy_keys", false)) {
                    dev.walcott.sync.ParentKeystore.ensureKeyPair()
                    target.identityStore.save(
                        target.identityStore.current().copy(
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
                    val text = target.syncManager.createBackup(pass.toCharArray())
                    java.io.File(context.filesDir, name).writeText(text)
                    DebugLog.i("WalcottSeed", "backup written: $name (${text.length} bytes)")
                }
                intent.getStringExtra("restore_from")?.let { rawName ->
                    val name = rawName.substringAfterLast('/')
                    val pass = intent.getStringExtra("restore_pass") ?: ""
                    val ok = target.syncManager.restoreBackup(
                        java.io.File(context.filesDir, name).readText(),
                        pass.toCharArray(),
                    )
                    DebugLog.i("WalcottSeed", "restore from $name -> ok=$ok")
                }
                if (childApps != null) seedChild(target, childApps, intent)
                // Optional: back-date the child-side channel-health stamp (--el channel_ok_ago_ms N)
                // so the "no connection with your family" card can be exercised without cutting
                // the network and waiting hours.
                val channelAgo = intent.getLongExtra("channel_ok_ago_ms", -1)
                if (channelAgo >= 0) {
                    target.syncStore.update { it.copy(lastChannelOkMs = System.currentTimeMillis() - channelAgo) }
                }
                // `--el self_skew_ms N` fakes THIS device's measured clock drift, so the
                // fail-closed-on-a-wrong-clock path (and the child's card for it) can be driven
                // without actually moving the clock and waiting for a server timestamp.
                val selfSkew = intent.getLongExtra("self_skew_ms", Long.MIN_VALUE)
                if (selfSkew != Long.MIN_VALUE) {
                    target.syncStore.update { it.copy(clockSkewMs = selfSkew) }
                }
                // Emergency-release hooks (see PanicProtocol). `--ei panic_self N` puts THIS
                // device N checkpoints into a request, with the gates satisfied (fresh channel
                // proof, a server clock, a parent new enough), so the child screens and the
                // 2 h checkpoint can be driven without waiting a day. `--el panic_due_ago_sec S`
                // back-dates the last checkpoint so the next incoming message lands a notice
                // (or, past the grace, cancels the request).
                val panicSelf = intent.getIntExtra("panic_self", -1)
                if (panicSelf >= 0) {
                    val nowSec = System.currentTimeMillis() / 1000
                    val dueAgo = intent.getLongExtra("panic_due_ago_sec", 0)
                    target.syncStore.update {
                        it.copy(
                            panic = dev.walcott.sync.PanicRequest(
                                id = "debug-panic",
                                startedAtSec = nowSec - panicSelf * dev.walcott.sync.PanicProtocol.CHECKPOINT_INTERVAL_SEC,
                                lastCheckpointSec = nowSec - dueAgo,
                                checkpoints = panicSelf,
                            ),
                            ntfySinceSec = nowSec,
                            lastChannelOkMs = System.currentTimeMillis(),
                            parentAppVersionCode = dev.walcott.BuildConfig.VERSION_CODE,
                        )
                    }
                }
                // `--ez panic_clear true` drops a seeded request and any standing lockout.
                if (intent.getBooleanExtra("panic_clear", false)) {
                    target.syncStore.update { it.copy(panic = null, panicBlockedUntilSec = 0) }
                }
                // `--ez panic_ready true` just satisfies the start gates (channel + parent build),
                // for exercising the child's "Request release" button itself.
                if (intent.getBooleanExtra("panic_ready", false)) {
                    target.syncStore.update {
                        it.copy(
                            ntfySinceSec = System.currentTimeMillis() / 1000,
                            lastChannelOkMs = System.currentTimeMillis(),
                            parentAppVersionCode = dev.walcott.BuildConfig.VERSION_CODE,
                        )
                    }
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
     * `--es child_usage "com.whatsapp=1800,com.roblox=600"` (today's per-app seconds),
     * `--ei child_history_days N` (a ledger of N past days, for the dashboard average),
     * `--ez child_feed true` (a handful of activity-feed entries for the wall),
     * `--ei child_tz_offset_min N` (a child in another timezone, reporting its own day).
     */
    private suspend fun seedChild(target: dev.walcott.FamilyScope, spec: String, intent: Intent) {
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
        // `--ei child_tz_offset_min N`: pretend this child is in another timezone, so its own
        // calendar day differs from the parent's and the dashboard can be checked against a
        // travelling child. Derived independently of ChildStats.localNow — the point is to test
        // that function, not to agree with it.
        val tzOffsetMinutes = intent.getIntExtra("child_tz_offset_min", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
        val today = tzOffsetMinutes?.let {
            java.time.LocalDateTime
                .ofInstant(java.time.Instant.now(), java.time.ZoneOffset.ofTotalSeconds(it * 60))
                .toLocalDate().toEpochDay()
        } ?: java.time.LocalDate.now().toEpochDay()
        val snapshot = dev.walcott.sync.ChildSnapshot(
            tzOffsetMinutes = tzOffsetMinutes,
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
            // `--ei child_app_version N`: report an app build for this fake child (N below the
            // parent's own drives the "outdated" chip and the Update-now emphasis).
            appVersionCode = intent.getIntExtra("child_app_version", 0),
            appVersionName = if (intent.getIntExtra("child_app_version", 0) > 0) "seeded" else "",
            // `--el child_applied_version N`: the policy version this fake child claims to run
            // (N >= 1 but below the parent's own drives the "updating rules" chip).
            appliedPolicyVersion = intent.getLongExtra("child_applied_version", 0),
            // `--es child_ask "install:pkg:Label"` (or "app:text" / "other:text"): one pending
            // ask from this fake child, so the parent's request cards can be driven locally.
            asks = intent.getStringExtra("child_ask")?.let { spec ->
                val parts = spec.split(":", limit = 3)
                val kind = parts.getOrElse(0) { "other" }
                listOf(
                    dev.walcott.sync.ChildRequest(
                        requestId = "debug-ask-${spec.hashCode()}",
                        kind = kind,
                        text = parts.getOrElse(2) { parts.getOrElse(1) { "" } },
                        pkg = if (kind == dev.walcott.sync.ChildRequest.KIND_INSTALL) parts.getOrElse(1) { "" } else "",
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            } ?: emptyList(),
            // `--ei child_panic N`: this fake child is N checkpoints into an emergency release,
            // so the parent's alert card, home banner and refusal can be driven on one emulator.
            panic = intent.getIntExtra("child_panic", -1).takeIf { it >= 0 }?.let { checkpoints ->
                val nowSec = System.currentTimeMillis() / 1000
                dev.walcott.sync.PanicRequest(
                    id = "debug-panic",
                    startedAtSec = nowSec - checkpoints * dev.walcott.sync.PanicProtocol.CHECKPOINT_INTERVAL_SEC,
                    lastCheckpointSec = nowSec,
                    checkpoints = checkpoints,
                )
            },
        )
        target.syncStore.update { s ->
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
                    listOf(dev.walcott.sync.UsageEntry("com.seeded.app", 5400L + (d % 5) * 900L)),
                )
            }
            val key = dev.walcott.sync.UsageLedger.keyOf(childId, snapshot.deviceId)
            target.syncStore.update { s ->
                s.copy(
                    usageHistory = s.usageHistory + (
                        key to dev.walcott.sync.UsageLedger.merge(
                            s.usageHistory[key].orEmpty(), history, today, usage.sumOf { it.seconds },
                        )
                        ),
                )
            }
        }
        // `--es child_domains "com.game=Game=a.com,b.com"`: a domain selection from this fake
        // child, folded in through the real DomainInbox so the parent's card, review screen and
        // rule creation can be driven on one emulator. `--ez child_domains_partial true` holds the
        // last slice back, which is how the "not actionable yet" case is checked.
        intent.getStringExtra("child_domains")?.let { spec ->
            val parts = spec.split("=", limit = 3)
            val pkg = parts.getOrElse(0) { "com.example" }
            val label = parts.getOrElse(1) { pkg }
            val domains = parts.getOrElse(2) { "" }.split(",").filter { it.isNotBlank() }
            val slices = dev.walcott.sync.DomainDelivery.chunk("debug-batch", pkg, label, domains)
            val delivered = if (intent.getBooleanExtra("child_domains_partial", false)) {
                slices.dropLast(1)
            } else {
                slices
            }
            target.syncStore.update { s ->
                s.copy(
                    domainInbox = dev.walcott.sync.DomainInbox.merge(
                        inbox = s.domainInbox,
                        incoming = delivered,
                        deviceId = snapshot.deviceId,
                        childId = childId,
                        childName = name,
                        handled = s.domainsHandled,
                        nowMs = System.currentTimeMillis(),
                    ),
                    domainAcks = dev.walcott.sync.DomainInbox.withAcks(s.domainAcks, delivered),
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
                entry(dev.walcott.sync.ParentEvent.TYPE_BEDTIME, 11 * 3_600_000L),
                entry(dev.walcott.sync.ParentEvent.TYPE_APP_TIME_OUT, 4 * 3_600_000L, detail = "Roblox"),
                entry(dev.walcott.sync.ParentEvent.TYPE_SCREEN_FREE, 3 * 3_600_000L),
                entry(dev.walcott.sync.ParentEvent.TYPE_BONUS, 3 * 24 * 3_600_000L, count = 15),
                entry(dev.walcott.sync.ParentEvent.TYPE_ENFORCEMENT_GAP, 26 * 3_600_000L, count = 2),
                entry(dev.walcott.sync.ParentEvent.TYPE_ENFORCEMENT_GAP_CLEARED, 25 * 3_600_000L),
                entry(dev.walcott.sync.ParentEvent.TYPE_NEW_APP, 2 * 3_600_000L, detail = "Instagram"),
                entry(dev.walcott.sync.ParentEvent.TYPE_TIME_REQUEST, 20 * 60_000L, count = 30),
            )
            target.syncStore.update { s -> feed.fold(s) { acc, e -> acc.plusEvent(e) } }
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
            target.syncStore.update { it.copy(diagReports = it.diagReports + (snapshot.deviceId to report)) }
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
