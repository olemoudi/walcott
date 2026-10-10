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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    /**
     * How far back a connection with no cursor yet reads, in seconds; 0 reads nothing.
     *
     * A phone that has just been paired has no cursor, and used to ask for nothing — so the rules
     * the parent had already published sat on the relay unread, and the child ran with none until
     * the parent happened to publish again. Safe now that a backlog is handled as one batch in
     * which only the newest parent snapshot counts (see [SyncTransport.connect]).
     */
    private val firstConnectBacklogSec: Long = 0L,
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

    /**
     * The one thread every delayed step here waits on. Each retry and each reconnect used to
     * start a thread of its own and sleep on it; a flapping network on a busy child kept a
     * growing pile of them asleep at once.
     */
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ntfy-timer").apply { isDaemon = true }
    }
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
    @Volatile private var onBacklog: ((List<RelayMessage>) -> Unit)? = null

    /** The client the catch-up poll uses: one bounded call, so a stalled read cannot hold a reconnect. */
    private val pollClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(POLL_TIMEOUT_SEC, TimeUnit.SECONDS).build()
    }

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
     * field IS the relay's clock, which is what makes a receipt worth more than a bare 200 — and
     * why a 200 without one is answered here as no receipt at all.
     */
    override fun publishForReceipt(message: String): Long? {
        val request = Request.Builder()
            .url("$httpBase/$topic")
            .post(message.toByteArray().toRequestBody())
            .build()
        return runCatching {
            receiptClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val quota = LedgerDay.isDailyQuota(response.code, runCatching { response.peekBody(ERROR_BODY_BYTES).string() }.getOrDefault(""))
                    DebugLog.w(TAG, "receipted publish rejected: HTTP ${response.code}${if (quota) " (daily quota)" else ""}")
                    PublishHealth.record(ok = false, code = response.code, quota = quota)
                    PublishLedger.recordRefused(quota)
                    return null
                }
                PublishHealth.record(ok = true)
                PublishLedger.recordSent()
                // A 200 is NOT enough on its own, and this is the difference between a delivered
                // notice and a fabricated one. The only caller counts twelve hours on the
                // RELAY's clock, so a response with no usable `time` is a response from
                // something that is not the family's relay: a captive portal at a hotel or an
                // airport, a carrier's block page, a proxy — every one of which answers 200 to a
                // POST and swallows it. Returning a receipt there would bank a notice nobody
                // ever received, and there is no timestamp to check its spacing against either,
                // so the whole anti-tamper rule would be skipped with it. No clock, no receipt.
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                val stamped = runCatching {
                    json.parseToJsonElement(body).jsonObject["time"]?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull()
                if (stamped == null || stamped <= 0) {
                    DebugLog.w(TAG, "receipted publish answered 200 with no relay clock; not a receipt")
                    return null
                }
                stamped
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
        // The relay has refused this address for the rest of its day: every request now is one
        // more "no" spent on nothing, and the messages that matter most — the emergency release,
        // a call for help — go through publishForReceipt, which still tries. Routine snapshots
        // converge again on the first publish after the relay's midnight — or sooner, through the
        // one publish every quarter of an hour that goes anyway to ask (see PublishLedger.probeDue).
        if (attempt == 1 && PublishLedger.quotaExhausted() && !PublishLedger.probeDue()) {
            DebugLog.i(TAG, "not publishing: the relay's daily quota is used up (${message.length} bytes held)")
            return
        }
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
                val errorBody = if (ok) "" else runCatching { response.peekBody(ERROR_BODY_BYTES).string() }.getOrDefault("")
                response.close()
                if (ok) {
                    PublishHealth.record(ok = true)
                    PublishLedger.recordSent()
                    return
                }
                // The daily quota is not retried: it is the rest of the relay's day, not a moment.
                val quota = LedgerDay.isDailyQuota(code, errorBody)
                if (!quota && retryable(code) && attempt < MAX_PUBLISH_ATTEMPTS) {
                    retryPublish(message, attempt)
                    return
                }
                // Kept visible: a swallowed HTTP 413 (oversized message) once hid a permanent,
                // deterministic publish failure, and a 429 is the relay saying this family sends
                // more than it allows — which is a thing they can act on.
                DebugLog.w(TAG, "publish rejected: HTTP $code${if (quota) " (daily quota)" else ""} (${message.length} bytes, attempt $attempt)")
                PublishHealth.record(ok = false, code = code, quota = quota)
                PublishLedger.recordRefused(quota)
            }
        })
    }

    /** Retries after a short backoff on the shared timer, like the socket's reconnect. */
    private fun retryPublish(message: String, attempt: Int) {
        val delayMillis = PUBLISH_RETRY_BASE_MS shl (attempt - 1)
        runCatching {
            timer.schedule({ if (!closed.get()) publish(message, attempt + 1) }, delayMillis, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Whether an HTTP status is worth sending the same message again for: the relay being busy,
     * rate-limiting us, or failing on its own side. Everything else is a property of the message.
     */
    private fun retryable(code: Int): Boolean =
        code == 408 || code == PublishHealth.HTTP_TOO_MANY_REQUESTS || code >= 500

    override fun connect(onMessage: (String, Long) -> Unit, onBacklog: (List<RelayMessage>) -> Unit) {
        this.onMessage = onMessage
        this.onBacklog = onBacklog
        openSocket()
    }

    /**
     * Connects: catches up on whatever was published while this device was not listening, then
     * opens the socket for what comes next.
     *
     * The catch-up used to be the socket's own `since=` replay, which hands the backlog over one
     * message at a time, oldest first, with nothing to say where it ends. That order is wrong for
     * the one thing that cannot be undone: a parent snapshot that queued "free this phone" is
     * followed by the one that took it back, and handled one by one the release ran on the way to
     * the snapshot that cancelled it. Read with a poll instead, the backlog arrives whole, and the
     * caller can see which snapshot is the newest before acting on any of them (see
     * [SyncTransport.connect]). The socket then starts after the last message the poll returned.
     *
     * A cursor is floored at a window rather than taken at face value. It is how a phone that was
     * off catches up, and a phone off for a week asks for a week: every message the topic still
     * holds, decrypted one at a time inside the sync loop, on a topic anyone who knows its name
     * can post to. Anything older than the window has been superseded by a re-emit anyway —
     * snapshots converge, and the parent re-publishes every fifteen minutes.
     */
    private fun openSocket() {
        if (closed.get()) return
        val cursor = sinceProvider()
        val nowSec = System.currentTimeMillis() / 1000
        val since = when {
            cursor > 0 -> cursor.coerceAtLeast(nowSec - MAX_BACKLOG_SEC).toString()
            firstConnectBacklogSec > 0 -> (nowSec - firstConnectBacklogSec).toString()
            else -> null
        }
        if (since == null) openWebSocket(since = null) else catchUpThenOpen(since)
    }

    /**
     * Reads the backlog from [since] in one request, hands it over whole, and only then opens the
     * socket. A catch-up that fails is a connection that failed: it goes on the same backoff,
     * rather than opening a socket that would replay the backlog one message at a time after all.
     */
    private fun catchUpThenOpen(since: String) {
        val generation = reconnectGeneration.get()
        val request = Request.Builder().url(RelayBacklog.pollUrl(httpBase, topic, since)).build()
        pollClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                DebugLog.w(TAG, "could not read the relay backlog: ${e.javaClass.simpleName}: ${e.message}")
                reconnectSoon()
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                val backlog = response.use {
                    if (!it.isSuccessful) {
                        DebugLog.w(TAG, "the relay refused the backlog: HTTP ${it.code}")
                        null
                    } else {
                        runCatching {
                            // A file that cannot be fetched stays in the batch, unresolved, so the
                            // caller can see there is a hole in it (see SyncManager.handleBacklog).
                            RelayBacklog.parse(it.peekBody(RelayBacklog.MAX_BYTES).string())
                                .map { message -> RelayBacklog.resolved(message, httpBase, pollClient) ?: message }
                        }.getOrNull()
                    }
                }
                // Superseded while the poll was out: a reconnect brought forward by the network
                // coming back has started its own catch-up, and two sockets would follow.
                if (closed.get() || reconnectGeneration.get() != generation) return
                if (backlog == null) {
                    // The relay answered, and would not hand the backlog over — a proxy that knows
                    // the socket and not the poll, a burst limit on requests. Being connected
                    // matters more than the backlog, so the socket opens anyway rather than leaving
                    // this phone deaf on a backoff the same refusal would meet every time — and
                    // opens LIVE: replayed by the socket, one message at a time, the backlog would
                    // run a command its own next message withdrew. What was missed comes with the
                    // parent's next publish.
                    openWebSocket(since = null)
                    return
                }
                // Handed over even when empty: an answer from the relay is proof the channel works.
                if (backlog.isNotEmpty()) DebugLog.i(TAG, "caught up on ${backlog.size} message(s) from the relay")
                runCatching { onBacklog?.invoke(backlog) }
                    .onFailure { DebugLog.e(TAG, "handling the backlog failed", it) }
                // After the last message read, by id: a timestamp would hand that same second's
                // messages over a second time. With nothing read, the window the poll asked for
                // still covers anything published in between.
                openWebSocket(since = backlog.lastOrNull()?.id?.takeIf { it.isNotBlank() } ?: since)
            }
        })
    }

    private fun openWebSocket(since: String?) {
        if (closed.get()) return
        val url = if (since != null) "$wsUrl?since=$since" else wsUrl
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val after = reconnectAttempts.getAndSet(0)
                DebugLog.i(TAG, if (after > 0) "relay socket is back after $after attempt(s)" else "relay socket open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // ntfy events look like {"event":"message","time":<unix s>,"message":"<body>",...};
                // we only care about actual messages, not the "open"/"keepalive" events.
                val message = RelayBacklog.messageOf(text) ?: return
                // A body kept as a file is fetched here, on the socket's own thread: it holds up the
                // next frame for one small request, rarely, and keeps the order messages arrive in.
                val resolved = RelayBacklog.resolved(message, httpBase, pollClient) ?: run {
                    DebugLog.w(TAG, "a message kept as a file could not be fetched; skipping it")
                    return
                }
                this@NtfyTransport.onMessage?.invoke(resolved.body, resolved.timeSec)
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
        // Closed while this socket was being opened: close() cancelled whatever was there before
        // it, not this one, which would otherwise live on as a second listener on the topic.
        if (closed.get()) webSocket?.cancel()
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
        runCatching {
            timer.schedule({
                if (closed.get()) return@schedule
                // Superseded while waiting by [onNetworkAvailable], which has already opened one.
                if (reconnectGeneration.get() != generation) return@schedule
                reconnectPending.set(false)
                openSocket()
            }, delayMillis, TimeUnit.MILLISECONDS)
        }
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
        timer.shutdownNow()
    }

    companion object {
        private const val TAG = "WalcottSync"

        /** Attempts per message, including the first. Three spans ~5 s of transient trouble. */
        private const val MAX_PUBLISH_ATTEMPTS = 3

        /** Ceiling on one receipted publish, so its caller's retry schedule means what it says. */
        private const val RECEIPT_TIMEOUT_SEC = 15L

        /**
         * How far back a reconnect asks the relay to replay.
         *
         * Twelve hours covers a phone that was off overnight, which is the case the cursor exists
         * for, and bounds what a topic anyone can post to can make this phone read.
         */
        const val MAX_BACKLOG_SEC = 12 * 60 * 60L

        /** First retry delay; doubles per attempt (1 s, then 2 s). */
        private const val PUBLISH_RETRY_BASE_MS = 1_000L

        /** Ceiling on the catch-up poll; a failed one goes on the reconnect backoff. */
        private const val POLL_TIMEOUT_SEC = 30L

        /** How much of a refusal's body is read to tell the daily quota from a burst limit. */
        private const val ERROR_BODY_BYTES = 2_048L

        /** First wait before reopening a dropped socket; doubles per consecutive failure. */
        private const val RECONNECT_BASE_MS = 3_000L

        /** Caps the doubling at 2^10, well past where [RECONNECT_MAX_MS] takes over. */
        private const val MAX_BACKOFF_SHIFT = 10

        /** Longest wait between attempts: a phone with no signal must not hammer the radio. */
        private const val RECONNECT_MAX_MS = 5 * 60 * 1000L
    }
}
