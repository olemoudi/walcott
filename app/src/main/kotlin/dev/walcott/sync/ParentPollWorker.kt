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
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Parent-side catch-up: the ntfy WebSocket only lives while the app process does, so with the
 * app closed the parent would miss children's requests, tamper alerts and check-ins. Polls each
 * family's topic with the persisted `since=` cursor and replays anything missed through the same
 * apply path as the socket — snapshot-diff notifications fire from there, and every apply is
 * idempotent, so socket/poll overlap is harmless. No-op on non-parent devices.
 *
 * Driven from two places on purpose: [ParentPollWorker] (survives reboots by itself, deferred by
 * Doze) and [ParentCheckAlarm] (needs re-arming after a reboot, but Doze honours it).
 */
object ParentPoll {

    private const val TAG = "WalcottSync"

    suspend fun pollAll(context: Context) {
        val app = context.applicationContext as? WalcottApplication ?: return
        if (app.identityStore.current().effectiveMode != DeviceMode.PARENT) return
        // One poll per family: each has its own topic, cursor and apply path.
        for (family in app.hub.allNow()) {
            runCatching { pollFamily(family) }
                .onFailure { DebugLog.w(TAG, "poll failed for ${family.id}", it) }
        }
    }

    private suspend fun pollFamily(family: dev.walcott.FamilyScope) {
        val id = family.identityStore.current()
        if (!id.isPaired) return
        val since = family.syncStore.current().ntfySinceSec
        val sinceParam = if (since > 0) since.toString() else "all"
        val url = "${id.ntfyServer.trimEnd('/')}/${id.topic}/json?poll=1&since=$sinceParam"

        val client = dev.walcott.net.Http.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
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
            family.syncManager.applyIncoming(body, timeSec)
            applied++
        }
        if (applied > 0) DebugLog.i(TAG, "poll applied $applied message(s) to ${family.id}")
    }
}

/** The WorkManager half of [ParentPoll]: survives reboots on its own, but Doze defers it. */
class ParentPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ParentPoll.pollAll(applicationContext)
        return Result.success() // best-effort: the next periodic run retries anyway
    }

    companion object {
        private const val PERIODIC = "walcott-parent-poll"

        /**
         * How often the worker polls. Two hours, and deliberately far slower than the alarm.
         *
         * These two were spaced apart on purpose — a worker on the same cadence as the alarm
         * spends a wakeup making a request the alarm just made — and 0.46 took the alarm from
         * thirty minutes to ten without moving this, putting them back in lockstep and costing
         * a parent's phone about ninety-six wakeups a day to learn nothing.
         *
         * The worker is not the thing that keeps latency down; the alarm is, because Doze
         * honours it. What this is for is the case the alarm cannot cover — a chain that never
         * got re-armed — and a backstop does not need to be frequent, it needs to exist.
         */
        private const val PERIOD_HOURS = 2L

        /** Idempotent backstop poll; only runs with a network connection. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ParentPollWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                // UPDATE, not KEEP: with KEEP a release that changes the period or the
                // constraints never reaches the installs that already have this scheduled, which
                // is every install. The cadence would be frozen at whatever first shipped.
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
