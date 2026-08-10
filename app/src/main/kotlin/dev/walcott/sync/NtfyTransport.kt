package dev.walcott.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import dev.walcott.debug.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ntfy transport: publishing is an HTTP POST of the message body to the topic; subscribing
 * is a WebSocket to `<server>/<topic>/ws` that streams JSON events. Reconnects on drop.
 */
class NtfyTransport(
    server: String,
    private val topic: String,
    // Ping-enabled: see Http.webSocketClient for why a plain client can wedge this socket open.
    private val client: OkHttpClient = dev.walcott.net.Http.webSocketClient,
    /**
     * Unix-seconds cursor appended as `since=` when (re)connecting, so messages published
     * while the socket was down are replayed instead of lost. 0 = no replay (legacy behavior).
     */
    private val sinceProvider: () -> Long = { 0L },
) : SyncTransport {

    private val httpBase = server.trimEnd('/')
    private val wsUrl = httpBase.replaceFirst("http", "ws") + "/$topic/ws"
    private val json = Json { ignoreUnknownKeys = true }

    private val closed = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var onMessage: ((String, Long) -> Unit)? = null

    override fun publish(message: String) = publish(message, attempt = 1)

    /**
     * Publishes, retrying a transient failure a few times before giving up on this message.
     *
     * It used to be pure fire-and-forget, on the reasoning that the periodic re-emit heals
     * whatever is lost. It does — fifteen minutes later, and only while the process is alive.
     * That is the wrong price for the messages people actually wait on: a parent tapping
     * "approve 15 more minutes" on a train, or closing the app straight afterwards, and a child
     * whose phone stays locked meanwhile. One flaky POST should not cost a quarter of an hour.
     *
     * Deterministic rejections are NOT retried ([retryable]): a 413 will be 413 every time, and
     * hammering it would only burn the relay's rate limit that a 429 is already complaining about.
     */
    private fun publish(message: String, attempt: Int) {
        val request = Request.Builder()
            .url("$httpBase/$topic")
            .post(message.toByteArray().toRequestBody())
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (attempt < MAX_PUBLISH_ATTEMPTS) {
                    retryPublish(message, attempt)
                } else {
                    DebugLog.w(TAG, "publish failed after $attempt attempts (${message.length} bytes)", e)
                    PublishHealth.record(ok = false)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                val code = response.code
                val ok = response.isSuccessful
                response.close()
                if (ok) {
                    PublishHealth.record(ok = true)
                    return
                }
                if (retryable(code) && attempt < MAX_PUBLISH_ATTEMPTS) {
                    retryPublish(message, attempt)
                    return
                }
                // Kept visible: a swallowed HTTP 413 (oversized message) once hid a permanent,
                // deterministic publish failure, and a 429 is the relay saying this family sends
                // more than it allows — which is a thing they can act on.
                DebugLog.w(TAG, "publish rejected: HTTP $code (${message.length} bytes, attempt $attempt)")
                PublishHealth.record(ok = false, code = code)
            }
        })
    }

    /** Retries after a short backoff on a daemon thread, like the socket's reconnect. */
    private fun retryPublish(message: String, attempt: Int) {
        val delayMillis = PUBLISH_RETRY_BASE_MS shl (attempt - 1)
        Thread {
            Thread.sleep(delayMillis)
            if (!closed.get()) publish(message, attempt + 1)
        }.apply { isDaemon = true }.start()
    }

    /**
     * Whether an HTTP status is worth sending the same message again for: the relay being busy,
     * rate-limiting us, or failing on its own side. Everything else is a property of the message.
     */
    private fun retryable(code: Int): Boolean =
        code == 408 || code == PublishHealth.HTTP_TOO_MANY_REQUESTS || code >= 500

    override fun connect(onMessage: (String, Long) -> Unit) {
        this.onMessage = onMessage
        openSocket()
    }

    private fun openSocket() {
        if (closed.get()) return
        val since = sinceProvider()
        val url = if (since > 0) "$wsUrl?since=$since" else wsUrl
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts.set(0)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // ntfy events look like {"event":"message","time":<unix s>,"message":"<body>",...};
                // we only care about actual messages, not the "open"/"keepalive" events.
                val event = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (event["event"]?.jsonPrimitive?.content != "message") return
                val body = event["message"]?.jsonPrimitive?.content ?: return
                val timeSec = event["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                this@NtfyTransport.onMessage?.invoke(body, timeSec)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnectSoon()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnectSoon()
            }
        })
    }

    private fun reconnectSoon() {
        if (closed.get()) return
        // Exponential backoff (3s, 6s, 12s… capped at 5 min) so an offline or dozing
        // device doesn't hammer the radio; a successful connection resets it.
        val attempt = reconnectAttempts.getAndIncrement().coerceAtMost(10)
        val delayMillis = (3_000L shl attempt).coerceAtMost(5 * 60 * 1000L)
        Thread {
            Thread.sleep(delayMillis)
            if (!closed.get()) openSocket()
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        closed.set(true)
        webSocket?.cancel()
        webSocket = null
    }

    companion object {
        private const val TAG = "WalcottSync"

        /** Attempts per message, including the first. Three spans ~5 s of transient trouble. */
        private const val MAX_PUBLISH_ATTEMPTS = 3

        /** First retry delay; doubles per attempt (1 s, then 2 s). */
        private const val PUBLISH_RETRY_BASE_MS = 1_000L
    }
}
