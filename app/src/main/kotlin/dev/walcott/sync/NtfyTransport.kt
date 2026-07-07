package dev.walcott.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    private val client: OkHttpClient = OkHttpClient(),
) : SyncTransport {

    private val httpBase = server.trimEnd('/')
    private val wsUrl = httpBase.replaceFirst("http", "ws") + "/$topic/ws"
    private val json = Json { ignoreUnknownKeys = true }

    private val closed = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var onMessage: ((String) -> Unit)? = null

    override fun publish(message: String) {
        val request = Request.Builder()
            .url("$httpBase/$topic")
            .post(message.toByteArray().toRequestBody())
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { /* best-effort; next re-emit heals */ }
            override fun onResponse(call: okhttp3.Call, response: Response) { response.close() }
        })
    }

    override fun connect(onMessage: (String) -> Unit) {
        this.onMessage = onMessage
        openSocket()
    }

    private fun openSocket() {
        if (closed.get()) return
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts.set(0)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // ntfy events look like {"event":"message","message":"<body>",...}; we only
                // care about actual messages, not the "open"/"keepalive" events.
                val event = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (event["event"]?.jsonPrimitive?.content != "message") return
                val body = event["message"]?.jsonPrimitive?.content ?: return
                this@NtfyTransport.onMessage?.invoke(body)
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
}
