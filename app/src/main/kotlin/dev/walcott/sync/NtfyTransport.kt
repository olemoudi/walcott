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

    /**
     * The client [publishForReceipt] waits on. OkHttp bounds each PHASE of a call and not the
     * call, so a POST that connects, sends and then stalls can sit there for the better part of
     * a minute — which would make a retry ladder measured in thirty-second steps meaningless.
     * A whole-call ceiling is what lets the caller plan around it. Shares the pools.
     */
    private val receiptClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(RECEIPT_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val wsUrl = httpBase.replaceFirst("http", "ws") + "/$topic/ws"
    private val json = Json { ignoreUnknownKeys = true }

    private val closed = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)

    /**
     * Whether a reconnect is already scheduled and still waiting.
     *
     * Two jobs. It stops a single dead socket from scheduling several — OkHttp can report the
     * same death twice, through `onFailure` and `onClosed` — and it tells [onNetworkAvailable]
     * whether there is anything to bring forward, so a healthy socket is left alone.
     */
    private val reconnectPending = AtomicBoolean(false)

    /**
     * Which reconnect attempt is the live one. A sleeping retry checks this before opening, so
     * bringing one forward cannot leave the superseded thread to open a second socket behind it.
     */
    private val reconnectGeneration = AtomicInteger(0)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var onMessage: ((String, Long) -> Unit)? = null

    override fun publish(message: String) = publish(message, attempt = 1)

    /**
     * The blocking half of [publish]: one POST, waited on, answering the server second the relay
     * stamped on the message — or null if it did not take it.
     *
     * No retry ladder of its own, deliberately. The one caller that needs this ([PanicProtocol])
     * has retry rules of its own, spread over minutes rather than seconds, and a failure it must
     * be told about rather than have quietly papered over — the whole point is that the counter
     * only moves for a message that really left the phone.
     *
     * ntfy answers a publish with the message it stored, `{"id":…,"time":<unix s>,…}`; the `time`
     * field IS the relay's clock, which is what makes a receipt worth more than a bare 200.
     */
    override fun publishForReceipt(message: String): Long? {
        val request = Request.Builder()
            .url("$httpBase/$topic")
            .post(message.toByteArray().toRequestBody())
            .build()
        return runCatching {
            receiptClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w(TAG, "receipted publish rejected: HTTP ${response.code}")
                    PublishHealth.record(ok = false, code = response.code)
                    return null
                }
                PublishHealth.record(ok = true)
                // A relay that answers 200 and says nothing about when has still taken the
                // message; zero says "no clock from this one" and the caller decides.
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                runCatching {
                    json.parseToJsonElement(body).jsonObject["time"]?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull() ?: 0L
            }
        }.onFailure {
            DebugLog.w(TAG, "receipted publish failed: ${it.javaClass.simpleName}: ${it.message}")
            PublishHealth.record(ok = false)
        }.getOrNull()
    }

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
                val after = reconnectAttempts.getAndSet(0)
                DebugLog.i(TAG, if (after > 0) "relay socket is back after $after attempt(s)" else "relay socket open")
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
                // Worth a line: a relay that vanishes without a word is only discovered at the
                // next keepalive ping, and until this was said out loud "the socket died minutes
                // ago" and "the relay has nothing to say" looked identical from the outside.
                DebugLog.w(TAG, "relay socket failed: ${t.javaClass.simpleName}: ${t.message}")
                reconnectSoon()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Answered, not merely noted, and that is the whole point: OkHttp does NOT send
                // the reply for you. The peer has said it will send nothing more, and until this
                // side closes too the socket stays half-shut with no further callback coming —
                // [onClosed] never fires, so the reconnect below never starts. A relay that shuts
                // down politely (an ntfy restart, a proxy retiring a socket) then costs the phone
                // a whole keepalive interval instead of three seconds: up to eight minutes with no
                // rules arriving, on a socket that looks alive from here (see Http.webSocketClient
                // and OutageScenarioTest, which spent four releases looking flaky because of it).
                DebugLog.i(TAG, "relay is closing the socket ($code); answering")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.i(TAG, "relay closed the socket ($code)")
                reconnectSoon()
            }
        })
    }

    private fun reconnectSoon() {
        if (closed.get()) return
        // One pending reconnect at a time, and one step of backoff per real failure.
        //
        // A dying socket can call back twice — `onFailure` and then `onClosed` — and each call
        // used to take its own step up the ladder. On its own that merely wasted a thread, since
        // both of them opened a socket. Paired with the generation guard below it stopped being
        // harmless: only the last thread survives, so a single failure advanced the backoff twice
        // and the wait doubled — 3s, 12s, 48s instead of 3s, 6s, 12s. A phone that lost the relay
        // then took minutes to look again when the schedule says seconds.
        if (!reconnectPending.compareAndSet(false, true)) return
        // Exponential backoff (3s, 6s, 12s… capped at 5 min) so an offline or dozing
        // device doesn't hammer the radio; a successful connection resets it.
        val attempt = reconnectAttempts.getAndIncrement().coerceAtMost(MAX_BACKOFF_SHIFT)
        val delayMillis = (RECONNECT_BASE_MS shl attempt).coerceAtMost(RECONNECT_MAX_MS)
        val generation = reconnectGeneration.incrementAndGet()
        DebugLog.i(TAG, "reopening the relay socket in $delayMillis ms (attempt ${attempt + 1})")
        Thread {
            Thread.sleep(delayMillis)
            if (closed.get()) return@Thread
            // Superseded while asleep by [onNetworkAvailable], which has already opened one.
            if (reconnectGeneration.get() != generation) return@Thread
            reconnectPending.set(false)
            openSocket()
        }.apply { isDaemon = true }.start()
    }

    /**
     * Reconnects at once instead of sitting out the rest of the backoff.
     *
     * The backoff is right when there is nothing to connect TO: a phone in a tunnel or a dead
     * zone would otherwise hammer the radio for five minutes to be refused five hundred times.
     * It is exactly wrong the moment coverage comes back — the wait was calibrated against how
     * long the outage had already lasted, so the longest outages ended with the longest silences
     * after they were over. A child walking out of the underground could sit there for five more
     * minutes, connected to the internet, with the parent's message already waiting on the relay.
     *
     * Only when a reconnect is actually pending: a healthy socket is left alone, so the ordinary
     * Wi-Fi/cellular handovers a phone does all day cost nothing.
     */
    override fun onNetworkAvailable() {
        if (closed.get()) return
        // Claims the pending reconnect, so the thread sleeping on it stands down rather than
        // opening a second socket behind this one. Nothing pending means a healthy socket.
        if (!reconnectPending.compareAndSet(true, false)) return
        DebugLog.i(TAG, "network is back; reconnecting now instead of waiting out the backoff")
        reconnectGeneration.incrementAndGet()
        reconnectAttempts.set(0)
        openSocket()
    }

    override fun close() {
        closed.set(true)
        reconnectPending.set(false)
        webSocket?.cancel()
        webSocket = null
    }

    companion object {
        private const val TAG = "WalcottSync"

        /** Attempts per message, including the first. Three spans ~5 s of transient trouble. */
        private const val MAX_PUBLISH_ATTEMPTS = 3

        /** Ceiling on one receipted publish, so its caller's retry schedule means what it says. */
        private const val RECEIPT_TIMEOUT_SEC = 15L

        /** First retry delay; doubles per attempt (1 s, then 2 s). */
        private const val PUBLISH_RETRY_BASE_MS = 1_000L

        /** First wait before reopening a dropped socket; doubles per consecutive failure. */
        private const val RECONNECT_BASE_MS = 3_000L

        /** Caps the doubling at 2^10, well past where [RECONNECT_MAX_MS] takes over. */
        private const val MAX_BACKOFF_SHIFT = 10

        /** Longest wait between attempts: a phone with no signal must not hammer the radio. */
        private const val RECONNECT_MAX_MS = 5 * 60 * 1000L
    }
}
