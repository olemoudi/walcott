package dev.walcott.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Parent-side catch-up: the ntfy WebSocket only lives while the app process does, so with the
 * app closed the parent would miss children's requests, tamper alerts and check-ins. This worker
 * polls the topic every ~15 min with the persisted `since=` cursor and replays anything missed
 * through the same apply path as the socket — snapshot-diff notifications fire from there, and
 * every apply is idempotent, so socket/poll overlap is harmless. No-op on non-parent devices.
 */
class ParentPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val id = IdentityStore(context).current()
        if (id.effectiveMode != DeviceMode.PARENT || !id.isPaired) return Result.success()

        val app = context as? WalcottApplication ?: return Result.success()
        val syncStore = SyncStore(context)
        val since = syncStore.current().ntfySinceSec
        val sinceParam = if (since > 0) since.toString() else "all"
        val url = "${id.ntfyServer.trimEnd('/')}/${id.topic}/json?poll=1&since=$sinceParam"

        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val json = Json { ignoreUnknownKeys = true }
        val lines = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w(TAG, "poll rejected: HTTP ${resp.code}")
                    return@runCatching emptyList()
                }
                resp.body?.string()?.lines().orEmpty()
            }
        }.onFailure { DebugLog.w(TAG, "poll failed", it) }.getOrDefault(emptyList())

        var applied = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (event["event"]?.jsonPrimitive?.content != "message") continue
            val body = event["message"]?.jsonPrimitive?.content ?: continue
            val timeSec = event["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            app.syncManager.applyIncoming(body, timeSec)
            applied++
        }
        if (applied > 0) DebugLog.i(TAG, "poll applied $applied message(s)")
        return Result.success() // best-effort: the next periodic run retries anyway
    }

    companion object {
        private const val TAG = "WalcottSync"
        private const val PERIODIC = "walcott-parent-poll"

        /** Idempotent ~15-min poll; only runs with a network connection. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ParentPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
