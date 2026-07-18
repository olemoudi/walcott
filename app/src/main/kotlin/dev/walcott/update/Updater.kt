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
}

/**
 * Self-updates from GitHub Releases. On a Device Owner device the install is silent (no
 * dialog, can't be skipped). Elsewhere we still request a user-action-free install
 * (granted once Walcott is its own installer of record on Android 12+); when the system
 * insists on confirmation, [InstallReceiver] surfaces it as a notification.
 */
class Updater(private val context: Context) {

    // Derived from the shared client (pools reused); long timeouts for the APK download.
    private val client = dev.walcott.net.Http.client.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Single-flight wrapper: update checks fire from several places (the enforcement
     * service's 6h loop, the periodic worker, launch/focus triggers, a remote command) and
     * two overlapping runs are actively harmful — install() abandons stale sessions, so a
     * concurrent run would abort the other's half-written session, and both would download
     * the full APK. A second caller just reports UP_TO_DATE and lets the first finish.
     */
    suspend fun checkAndUpdate(): UpdateCheckOutcome {
        if (!updateMutex.tryLock()) {
            DebugLog.i(TAG, "update check already in flight; skipping")
            return UpdateCheckOutcome.UP_TO_DATE
        }
        try {
            return doCheckAndUpdate()
        } finally {
            updateMutex.unlock()
        }
    }

    private suspend fun doCheckAndUpdate(): UpdateCheckOutcome = withContext(Dispatchers.IO) {
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
        if (!info.isNewerThan(current)) {
            UpdateCenter.report(UpdateUiState.UpToDate(current))
            return@withContext UpdateCheckOutcome.UP_TO_DATE
        }
        UpdateCenter.report(UpdateUiState.Downloading(info))
        val apk = runCatching { download(info.apk) }
            .onFailure { DebugLog.w(TAG, "download failed", it) }
            .getOrNull()
        if (apk == null) {
            UpdateCenter.report(UpdateUiState.Failed("download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        DebugLog.i(TAG, "downloaded ${apk.length()} bytes, installing")
        // As a Device Owner child, Walcott blocks app installs on itself (DISALLOW_INSTALL_APPS);
        // lift that around our own install, or commit() throws SecurityException synchronously.
        liftInstallBlockIfNeeded()
        val installError = runCatching { install(apk) }
            .onFailure { DebugLog.e(TAG, "install failed", it) }
            .exceptionOrNull()
        if (installError != null) {
            UpdateCenter.report(UpdateUiState.Failed("install: ${installError.javaClass.simpleName}"))
            return@withContext UpdateCheckOutcome.INSTALL_FAILURE
        }
        // The final status (success / pending confirmation / failure) lands in InstallReceiver.
        UpdateCheckOutcome.INSTALL_STARTED
    }

    /**
     * If we're a Device Owner enforcing DISALLOW_INSTALL_APPS, open the same PIN-gated install
     * exemption the manual "Allow installs" button uses and apply it right now, so our own
     * install session can commit. [dev.walcott.enforcement.EnforcementService] re-arms the block
     * automatically when the exemption window closes.
     */
    private suspend fun liftInstallBlockIfNeeded() {
        if (!isDeviceOwner()) return
        val app = context.applicationContext as? WalcottApplication ?: return
        val keys = runCatching { app.repository.settingsFlow.first().deviceRestrictions }.getOrNull() ?: return
        if (DeviceRestrictions.KEY_INSTALLS !in keys) return
        DebugLog.w(TAG, "install blocked by DISALLOW_INSTALL_APPS; opening temporary exemption")
        runCatching {
            app.syncManager.allowInstallsTemporarily()
            val exemptUntil = System.currentTimeMillis() + DeviceRestrictions.INSTALL_EXEMPTION_MS
            DeviceRestrictions.apply(context, keys, exemptUntil)
        }.onFailure { DebugLog.e(TAG, "failed to lift install block", it) }
    }

    private fun currentVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    private fun fetchInfo(): UpdateInfo? {
        client.newCall(Request.Builder().url(Distribution.VERSION_JSON_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return UpdateInfo.parse(resp.body?.string() ?: return null)
        }
    }

    private fun download(url: String): File {
        val target = File(context.cacheDir, "update.apk")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            require(resp.isSuccessful) { "download failed: ${resp.code}" }
            resp.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Abandon sessions leaked by earlier failed attempts (e.g. a commit blocked by policy),
        // so createSession can't eventually hit "Too many active sessions".
        runCatching { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } }
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
    }
}
