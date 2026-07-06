package dev.walcott.update

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.PendingIntentCompat
import dev.walcott.Distribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Self-updates from GitHub Releases. On a Device Owner device the install is silent (no
 * dialog, can't be skipped); otherwise the system shows the install confirmation.
 */
class Updater(private val context: Context) {

    private val client = OkHttpClient()

    suspend fun checkAndUpdate(): Boolean = withContext(Dispatchers.IO) {
        val info = fetchInfo() ?: return@withContext false
        if (!info.isNewerThan(currentVersionCode())) return@withContext false
        val apk = runCatching { download(info.apk) }.getOrNull() ?: return@withContext false
        runCatching { install(apk) }.isSuccess
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
        }
    }
}
