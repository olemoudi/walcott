package dev.walcott.update

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.PendingIntentCompat
import dev.walcott.Distribution
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.enforcement.DeviceRestrictions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of one update check, so callers can decide whether a retry makes sense. */
enum class UpdateCheckOutcome {
    UP_TO_DATE,
    /** An install session was committed (silent on owners; confirmation elsewhere). */
    INSTALL_STARTED,
    /** Transient problem (network fetch/download) — worth retrying with backoff. */
    TRANSIENT_FAILURE,
    /** The install session itself failed — retrying immediately won't help. */
    INSTALL_FAILURE,
    /** Canary gate: a newer build exists but the parent isn't running it yet (see [waitsForParent]). */
    WAITING_FOR_PARENT,
    /**
     * Wi-Fi-only policy and a metered connection: the release is known and named, the download
     * is what waits. Its own outcome because the honest answer to "is this device up to date?"
     * is no, and reporting UP_TO_DATE here is how a child sat months behind looking healthy.
     */
    WAITING_FOR_WIFI,
    /** Another check already holds the lock; this caller stood aside instead of fighting it. */
    BUSY,
}

/**
 * Self-updates from GitHub Releases. On a Device Owner device the install is silent (no
 * dialog, can't be skipped). Elsewhere we still request a user-action-free install
 * (granted once Walcott is its own installer of record on Android 12+); when the system
 * insists on confirmation, [InstallReceiver] surfaces it as a notification.
 *
 * The downloaded APK in the cache is deliberately the ONLY record that an update is pending.
 * Nothing else is persisted, and nothing needs to be: the file answers "is there one?", "which
 * build?" and "can it be installed right now?" by itself (see [stagedUpdate]), it survives the
 * process dying between download and install, and a phone that has just been updated proves it
 * by no longer accepting it.
 */
class Updater(private val context: Context) {

    // Derived from the shared client (pools reused); long timeouts for the APK download.
    private val client = dev.walcott.net.Http.client.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        // GitHub redirects the stable asset urls to its CDN, which is both fine and necessary —
        // but never to plaintext. Left at the default, a single redirect is all it would take
        // for the APK to arrive over a connection anyone on the same Wi-Fi can rewrite.
        .followSslRedirects(false)
        .build()

    /**
     * Single-flight wrapper: update checks fire from several places (the enforcement
     * service's 6h loop, the periodic worker, launch/focus triggers, a remote command) and
     * two overlapping runs are actively harmful — install() abandons stale sessions, so a
     * concurrent run would abort the other's half-written session, and both would download
     * the full APK. A second caller reports [UpdateCheckOutcome.BUSY] and lets the first finish.
     */
    suspend fun checkAndUpdate(force: Boolean = false): UpdateCheckOutcome {
        if (!updateMutex.tryLock()) {
            DebugLog.i(TAG, "update check already in flight; skipping")
            return UpdateCheckOutcome.BUSY
        }
        try {
            return doCheckAndUpdate(force)
        } finally {
            updateMutex.unlock()
        }
    }

    /**
     * Installs an update that is already on disk, touching no network at all.
     *
     * This is the way back for somebody who dismissed the system's install prompt — by accident
     * at least as often as on purpose — or who swiped the notification away. The bytes are
     * already here and already checked, so the button that offers this works on a train with no
     * signal and costs nothing to press twice.
     */
    suspend fun installStaged(): UpdateCheckOutcome {
        val staged = stagedUpdate() ?: return UpdateCheckOutcome.UP_TO_DATE
        if (!updateMutex.tryLock()) {
            DebugLog.i(TAG, "install already in flight; skipping")
            return UpdateCheckOutcome.BUSY
        }
        try {
            return withContext(Dispatchers.IO) { commit(apkFile(), staged) }
        } finally {
            updateMutex.unlock()
        }
    }

    /**
     * The update already downloaded and waiting in the cache, if it is one this device could
     * install right now — otherwise null.
     *
     * Null covers every way the file can be useless, and they are not exotic: there is no file;
     * it does not parse as an APK at all (a captive portal's login page served with a 200, a
     * body cut short when the connection dropped); it is not this package; or it is not newer
     * than the build already running (the leftovers of the update that just succeeded).
     *
     * Doubles as the check on a fresh download, which is the same question asked a moment
     * earlier: is what arrived a Walcott build worth installing?
     */
    fun stagedUpdate(): UpdateInfo? {
        val apk = apkIdentity(apkFile()) ?: return null
        if (!apkIsInstallable(apk.packageName, apk.versionCode, context.packageName, currentVersionCode())) {
            return null
        }
        return UpdateInfo(versionCode = apk.versionCode, versionName = apk.versionName)
    }

    /**
     * What a file on disk says it is, or null if it is not an APK at all. Split out from
     * [stagedUpdate] because it is the one step here that only a real device can answer — the
     * platform parses the archive — and so the one step that deserves an instrumented test
     * rather than an assumption (see `UpdaterDeviceTest`).
     */
    internal fun apkIdentity(file: File): ApkIdentity? {
        if (!file.isFile || file.length() == 0L) return null
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") archive.versionCode
        }
        return ApkIdentity(archive.packageName, code, archive.versionName.orEmpty())
    }

    /** True when this device is an enrolled child and the parent isn't on [info]'s build yet. */
    private suspend fun childWaitsForParent(info: UpdateInfo): Boolean {
        val app = context.applicationContext as? WalcottApplication ?: return false
        if (app.identityStore.current().role != dev.walcott.sync.Role.CHILD) return false
        return waitsForParent(info.versionCode, app.syncManager.parentAppVersionCode())
    }

    /** True when the policy restricts updates to Wi-Fi and the active connection is metered. */
    private suspend fun wifiOnlyBlocks(): Boolean {
        val app = context.applicationContext as? WalcottApplication ?: return false
        val wifiOnly = runCatching { app.repository.settingsFlow.first().updateWifiOnly }.getOrDefault(false)
        if (!wifiOnly) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        // isActiveNetworkMetered covers cellular and metered Wi-Fi hotspots.
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    private suspend fun doCheckAndUpdate(force: Boolean): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        DebugLog.i(TAG, "checking for update")
        UpdateCenter.report(UpdateUiState.Checking)
        val info = runCatching { fetchInfo() }.onFailure { DebugLog.w(TAG, "fetch failed", it) }.getOrNull()
        if (info == null) {
            DebugLog.w(TAG, "no version info")
            UpdateCenter.report(UpdateUiState.Failed("fetch"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        val current = currentVersionCode()
        DebugLog.i(TAG, "installed=$current latest=${info.versionCode}")
        // Everything the decision needs, gathered before any of it is acted on, so the ORDER of
        // those decisions is one readable table (see [nextUpdateStep]) instead of a ladder of
        // early returns whose sequence nothing can check. The Wi-Fi question in particular is
        // asked here, AFTER the fetch, rather than before it: version.json is a hundred bytes,
        // and paying them buys the difference between a phone that says nothing and one that
        // can say which version it is holding back and why.
        val staged = stagedUpdate()
        val step = nextUpdateStep(
            isNewer = info.isNewerThan(current),
            parentIsBehind = childWaitsForParent(info),
            hasStagedApk = staged != null,
            trustedUrl = trustedApkUrl(info.apk),
            metered = wifiOnlyBlocks(),
            enoughSpace = context.cacheDir.usableSpace >= REQUIRED_FREE_BYTES,
            force = force,
        )
        DebugLog.i(TAG, "next step: $step")
        when (step) {
            UpdateStep.NOTHING_TO_DO -> {
                // Anything still in the cache is for a build this device has already passed —
                // usually the APK it just installed, whose success broadcast never reached the
                // process that was replaced. Fifty megabytes on the phone least able to spare
                // them, kept for an install that can never happen again.
                discardStagedApk()
                UpdateCenter.report(UpdateUiState.UpToDate(current))
                return@withContext UpdateCheckOutcome.UP_TO_DATE
            }
            UpdateStep.WAIT_FOR_PARENT -> {
                DebugLog.i(TAG, "update gated: waiting for the parent to run ${info.versionCode} first")
                UpdateCenter.report(UpdateUiState.WaitingForParent(info))
                return@withContext UpdateCheckOutcome.WAITING_FOR_PARENT
            }
            UpdateStep.INSTALL_STAGED -> {
                // Committed again on every check, deliberately, even when the last one is still
                // waiting to be confirmed. It costs a re-staged session and buys a prompt that
                // comes back: on Android 14 an ongoing notification can still be swiped away,
                // and a person who ignores this one twice a day is exactly who it is for.
                val ready = staged!!
                DebugLog.i(TAG, "installing the ${ready.versionName} APK already in the cache")
                return@withContext commit(apkFile(), ready)
            }
            UpdateStep.UNTRUSTED_URL -> {
                DebugLog.e(TAG, "refusing an APK url outside the release host: ${info.apk}")
                UpdateCenter.report(UpdateUiState.Failed("untrusted url"))
                return@withContext UpdateCheckOutcome.INSTALL_FAILURE
            }
            UpdateStep.WAIT_FOR_WIFI -> {
                DebugLog.i(TAG, "update ${info.versionName} held: Wi-Fi-only and this connection is metered")
                UpdateCenter.report(UpdateUiState.WaitingForWifi(info))
                return@withContext UpdateCheckOutcome.WAITING_FOR_WIFI
            }
            UpdateStep.NEED_SPACE -> {
                DebugLog.w(TAG, "not enough free space for the update (${context.cacheDir.usableSpace} bytes)")
                UpdateCenter.report(UpdateUiState.Failed("no space"))
                return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
            }
            UpdateStep.DOWNLOAD -> Unit
        }
        UpdateCenter.report(UpdateUiState.Downloading(info))
        val downloaded = runCatching { download(info.apk) }
            .onFailure { DebugLog.w(TAG, "download failed", it) }
            .isSuccess
        if (!downloaded) {
            UpdateCenter.report(UpdateUiState.Failed("download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        val arrived = stagedUpdate()
        if (arrived == null) {
            // What came down is not a Walcott build newer than this one. A captive portal's
            // login page served with a 200, a body cut short by a dropped connection, or a
            // release whose assets don't match the version.json that announced them — handing
            // any of those to the installer only fails later, and less legibly.
            DebugLog.e(TAG, "the downloaded file is not an installable Walcott APK; discarding it")
            discardStagedApk()
            UpdateCenter.report(UpdateUiState.Failed("bad download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        commit(apkFile(), arrived)
    }

    /**
     * Commits the install session for an APK that is on disk and has been checked. The final
     * status (success / pending confirmation / failure) lands in [InstallReceiver].
     */
    private suspend fun commit(apk: File, target: UpdateInfo): UpdateCheckOutcome {
        DebugLog.i(TAG, "installing ${apk.length()} bytes (${target.versionName})")
        UpdateCenter.report(UpdateUiState.Installing(target))
        // Any prompt still in the shade belongs to a session install() is about to abandon, so
        // it is already a dead end — a notification that looks like the way forward and does
        // nothing. Clear it here; the new session posts its own, or the failure posts its own.
        UpdateNotifications.cancel(context)
        // As a Device Owner child, Walcott blocks app installs on itself (DISALLOW_INSTALL_APPS);
        // lift that around our own install, or commit() throws SecurityException synchronously.
        val lifted = liftInstallBlockIfNeeded()
        val installError = runCatching { install(apk) }
            .onFailure { DebugLog.e(TAG, "install failed", it) }
            .exceptionOrNull()
        // Whatever happened, close the window now rather than letting it time out.
        if (lifted) reArmInstallBlock()
        if (installError != null) {
            UpdateCenter.report(UpdateUiState.Failed("install: ${installError.javaClass.simpleName}"))
            return UpdateCheckOutcome.INSTALL_FAILURE
        }
        return UpdateCheckOutcome.INSTALL_STARTED
    }

    /**
     * If we're a Device Owner enforcing DISALLOW_INSTALL_APPS, lift it just long enough for our
     * own session to commit, and put it straight back ([reArmInstallBlock]).
     *
     * This used to open the shared 15-minute "Allow installs" exemption — a quarter of an hour,
     * unannounced, during which the child could sideload anything. The lift here is local: it
     * only passes a near-future timestamp to [DeviceRestrictions.apply] and never touches the
     * stored exemption, so it can't be observed as an open window and can't cut short a
     * legitimate one the parent or child opened.
     */
    private suspend fun liftInstallBlockIfNeeded(): Boolean {
        if (!isDeviceOwner()) return false
        val app = context.applicationContext as? WalcottApplication ?: return false
        val keys = runCatching { app.repository.settingsFlow.first().restrictionKeysToApply() }.getOrNull() ?: return false
        if (DeviceRestrictions.KEY_INSTALLS !in keys) return false
        DebugLog.i(TAG, "install blocked by DISALLOW_INSTALL_APPS; lifting it for this commit")
        return runCatching {
            DeviceRestrictions.apply(context, keys, System.currentTimeMillis() + COMMIT_WINDOW_MS)
        }.onFailure { DebugLog.e(TAG, "failed to lift install block", it) }.isSuccess
    }

    /**
     * Puts DISALLOW_INSTALL_APPS back the moment the session is committed, honouring any
     * exemption the family actually asked for (the PIN-gated button, an approved app request).
     */
    private suspend fun reArmInstallBlock() {
        val app = context.applicationContext as? WalcottApplication ?: return
        runCatching {
            val keys = app.repository.settingsFlow.first().restrictionKeysToApply()
            DeviceRestrictions.apply(context, keys, app.syncManager.installExemption.value)
        }.onFailure { DebugLog.e(TAG, "failed to re-arm the install block", it) }
    }

    private fun currentVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    private fun apkFile(): File = File(context.cacheDir, APK_FILE)

    private fun discardStagedApk() {
        runCatching { apkFile().delete() }
    }

    private fun fetchInfo(): UpdateInfo? {
        client.newCall(Request.Builder().url(Distribution.VERSION_JSON_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // Bounded: version.json is a hundred bytes. Whatever else ends up behind that url —
            // a mis-published release, a proxy's error page — must not be read into memory whole.
            return UpdateInfo.parse(resp.peekBody(MAX_INFO_BYTES).string())
        }
    }

    private fun download(url: String) {
        val target = apkFile()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            require(resp.isSuccessful) { "download failed: ${resp.code}" }
            resp.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        DebugLog.i(TAG, "downloaded ${target.length()} bytes")
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Abandon sessions leaked by earlier failed attempts (e.g. a commit blocked by policy),
        // so createSession can't eventually hit "Too many active sessions". One at a time: a
        // session that refuses to be abandoned must not stop the rest from being cleared.
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach {
            runCatching { installer.abandonSession(it.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Silent when the system allows it: always on Device Owner devices, and on the
            // parent once Walcott is its own installer of record. Otherwise the system
            // falls back to asking, which lands in InstallReceiver as pending-user-action.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        DebugLog.i(TAG, "session $sessionId created")
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("walcott", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                DebugLog.i(TAG, "wrote ${apk.length()} bytes; committing")
                val statusIntent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                val pending = PendingIntentCompat.getBroadcast(
                    context, sessionId, statusIntent, PendingIntent.FLAG_UPDATE_CURRENT, true,
                )!!
                session.commit(pending.intentSender)
                DebugLog.i(TAG, "session committed (deviceOwner=${isDeviceOwner()})")
            }
        } catch (t: Throwable) {
            // Don't leave the half-written session behind for the next attempt to trip over.
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    private fun isDeviceOwner(): Boolean = runCatching {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "WalcottUpdater"
        /** Process-wide: Updater is instantiated per check, so the lock must be shared. */
        private val updateMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * How long the install block is lifted around our own commit. Only a ceiling: the
         * block goes back on as soon as the session is committed (see [reArmInstallBlock]).
         */
        private const val COMMIT_WINDOW_MS = 60_000L

        /** Downloaded APK, deleted once the install reaches a terminal state (see InstallReceiver). */
        const val APK_FILE = "update.apk"

        /** Free space required before downloading, so a full phone fails fast instead of mid-write. */
        private const val REQUIRED_FREE_BYTES = 200L * 1024 * 1024

        /** Ceiling on the version.json body we will read (see [fetchInfo]). */
        private const val MAX_INFO_BYTES = 64L * 1024

        /** Where the APK must come from. See [trustedApkUrl]. */
        private const val APK_HOST = "github.com"
        private const val APK_PATH_PREFIX = "/olemoudi/walcott/"

        /**
         * Whether an APK url from version.json may be downloaded. The OS already refuses to
         * install anything not signed with our key, so this is belt-and-braces — but pointing
         * a child's downloader at an arbitrary host is not something version.json should be
         * able to do.
         *
         * Parsed rather than string-matched, because the request is made from the PARSED url and
         * the two do not agree: OkHttp normalises
         * `https://github.com/olemoudi/walcott/../../someone/evil.apk` down to a different
         * repository entirely, and a `startsWith` over the raw text waves that straight through.
         */
        fun trustedApkUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.scheme == "https" &&
                parsed.host == APK_HOST &&
                parsed.encodedPath.startsWith(APK_PATH_PREFIX)
        }
    }
}
