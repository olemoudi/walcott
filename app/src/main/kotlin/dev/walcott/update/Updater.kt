package dev.walcott.update

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.PendingIntentCompat
import dev.walcott.Distribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkAndUpdate(): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        Log.i(TAG, "checking for update")
        UpdateCenter.report(UpdateUiState.Checking)
        val info = runCatching { fetchInfo() }.onFailure { Log.w(TAG, "fetch failed", it) }.getOrNull()
        if (info == null) {
            Log.w(TAG, "no version info")
            UpdateCenter.report(UpdateUiState.Failed("fetch"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        val current = currentVersionCode()
        Log.i(TAG, "installed=$current latest=${info.versionCode}")
        if (!info.isNewerThan(current)) {
            UpdateCenter.report(UpdateUiState.UpToDate(current))
            return@withContext UpdateCheckOutcome.UP_TO_DATE
        }
        UpdateCenter.report(UpdateUiState.Downloading(info))
        val apk = runCatching { download(info.apk) }
            .onFailure { Log.w(TAG, "download failed", it) }
            .getOrNull()
        if (apk == null) {
            UpdateCenter.report(UpdateUiState.Failed("download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        Log.i(TAG, "downloaded ${apk.length()} bytes, installing")
        val committed = runCatching { install(apk) }
            .onFailure { Log.w(TAG, "install failed", it) }
            .isSuccess
        if (!committed) {
            UpdateCenter.report(UpdateUiState.Failed("install"))
            return@withContext UpdateCheckOutcome.INSTALL_FAILURE
        }
        // The final status (success / pending confirmation / failure) lands in InstallReceiver.
        UpdateCheckOutcome.INSTALL_STARTED
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
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Silent when the system allows it: always on Device Owner devices, and on the
            // parent once Walcott is its own installer of record. Otherwise the system
            // falls back to asking, which lands in InstallReceiver as pending-user-action.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("walcott", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val pending = PendingIntentCompat.getBroadcast(
                context, sessionId, statusIntent, PendingIntent.FLAG_UPDATE_CURRENT, true,
            )!!
            session.commit(pending.intentSender)
            Log.i(TAG, "session committed (deviceOwner=${isDeviceOwner()})")
        }
    }

    private fun isDeviceOwner(): Boolean = runCatching {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "WalcottUpdater"
    }
}
