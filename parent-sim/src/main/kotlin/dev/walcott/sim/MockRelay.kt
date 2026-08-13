package dev.walcott.sim

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The family's relay, running on this machine.
 *
 * Walcott's phones talk through ntfy: publishing is a POST of the body to `<server>/<topic>`,
 * subscribing is a WebSocket to `<server>/<topic>/ws` that streams JSON events, and a `since=`
 * cursor replays what was published while a socket was down. That is the whole contract, and
 * it is small enough to stand up locally — which is worth doing for three reasons:
 *
 *  - **Determinism.** The public relay rate-limits, reorders under load and occasionally drops.
 *    A test that fails for those reasons teaches nothing, and one that passes despite them
 *    proves less than it looks like it does.
 *  - **Observability.** Every envelope the child publishes is kept here, in order, so a
 *    scenario can assert on what actually went over the wire rather than on what a screen says
 *    afterwards.
 *  - **Ability to be cruel.** Dropping messages, holding them back, replaying an old one — the
 *    failure modes the sync layer is *designed* for — can only be exercised by a relay that
 *    takes instructions. See [dropNext] and [replay].
 *
 * The emulator reaches this through its host loopback alias, so [emulatorUrl] — not
 * [localUrl] — is what belongs in a pairing payload. Debug builds already permit cleartext to
 * exactly that address (see app/src/debug/res/xml/network_security_config.xml).
 */
class MockRelay {

    /** One published message, as the relay would hand it back. */
    data class Message(val body: String, val timeSec: Long)

    private class Topic {
        val messages = CopyOnWriteArrayList<Message>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
    }

    private val server = MockWebServer()
    private val topics = ConcurrentHashMap<String, Topic>()

    /**
     * How many of the next publishes to accept-and-discard, per topic. The relay still answers
     * 200, because the interesting case is the one the sender cannot see: a message that was
     * taken and never arrived, which is exactly what the periodic re-emit exists to heal.
     */
    private val dropCounts = ConcurrentHashMap<String, Int>()

    @Volatile private var started = false

    fun start(): MockRelay {
        if (started) return this
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start()
        started = true
        return this
    }

    fun stop() {
        if (!started) return
        topics.values.forEach { topic -> topic.sockets.forEach { it.close(1000, "bye") } }
        server.shutdown()
        started = false
    }

    /** Base URL as seen from this machine (the parent sim's side). */
    val localUrl: String get() = "http://127.0.0.1:${server.port}"

    /** Base URL as seen from inside the emulator, where the host is 10.0.2.2. */
    val emulatorUrl: String get() = "http://10.0.2.2:${server.port}"

    /** Everything published to [topic] so far, oldest first. */
    fun published(topic: String): List<Message> = topics[topic]?.messages?.toList().orEmpty()

    /**
     * Waits until [topic] holds more than [moreThan] messages, and answers whether it did.
     *
     * Counting at the relay is the only way to ask "did that device put anything on the wire",
     * separately from whether it decoded to something a test recognises. A scenario that only
     * ever waited on decoded snapshots cannot tell a silent device from a malformed message.
     */
    fun awaitPublished(topic: String, moreThan: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (published(topic).size > moreThan) return true
            Thread.sleep(50)
        }
        return published(topic).size > moreThan
    }

    /** Swallows the next [count] publishes to [topic] (see [dropCounts]). */
    fun dropNext(topic: String, count: Int = 1) {
        dropCounts[topic] = (dropCounts[topic] ?: 0) + count
    }

    /**
     * Delivers an already-published message again, as a relay replaying its backlog would. The
     * anti-replay gates (policy version, applied-command ids, snapshot version) are all written
     * against this happening, and none of them had a way to be exercised against a real device.
     */
    fun replay(topic: String, index: Int) {
        val state = topics[topic] ?: return
        val message = state.messages.getOrNull(index) ?: return
        state.sockets.forEach { it.send(eventJson(message)) }
    }

    /** Publishes [body] to [topic] as any client would. Used by the parent sim. */
    fun publish(topic: String, body: String) {
        val state = topics.computeIfAbsent(topic) { Topic() }
        val message = Message(body, System.currentTimeMillis() / 1000)
        val toDrop = dropCounts[topic] ?: 0
        if (toDrop > 0) {
            dropCounts[topic] = toDrop - 1
            return
        }
        state.messages += message
        state.sockets.forEach { it.send(eventJson(message)) }
    }

    private fun route(request: RecordedRequest): MockResponse {
        // "/<topic>", "/<topic>/ws", "/<topic>/json" — with the query still attached.
        val path = (request.path ?: "/").substringBefore('?').trim('/')
        val query = (request.path ?: "").substringAfter('?', "")
        val segments = path.split('/').filter { it.isNotBlank() }
        val topic = segments.firstOrNull() ?: return MockResponse().setResponseCode(404)
        val endpoint = segments.getOrNull(1)

        return when {
            endpoint == null && request.method == "POST" -> {
                publish(topic, request.body.readUtf8())
                MockResponse().setResponseCode(200).setBody("""{"id":"sim","time":${nowSec()}}""")
            }
            endpoint == "ws" -> webSocketFor(topic, sinceOf(query))
            // The parent app polls this when its socket has been down; the sim doesn't use it,
            // but a relay that 404s on it is not the relay the app was written against.
            endpoint == "json" -> {
                val since = sinceOf(query)
                val body = published(topic).filter { it.timeSec >= since }
                    .joinToString("\n") { eventJson(it) }
                MockResponse().setResponseCode(200).setBody(body)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    /**
     * Upgrades to a WebSocket and, before anything new arrives, replays what [since] asks for.
     * The replay happens inside onOpen so it cannot race a message published a moment later:
     * the socket is not in the broadcast list until its backlog has gone out.
     */
    private fun webSocketFor(topicName: String, since: Long): MockResponse {
        val state = topics.computeIfAbsent(topicName) { Topic() }
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"event":"open","topic":"$topicName"}""")
                if (since > 0) {
                    state.messages.filter { it.timeSec >= since }.forEach { webSocket.send(eventJson(it)) }
                }
                state.sockets += webSocket
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                state.sockets -= webSocket
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                state.sockets -= webSocket
            }
        })
    }

    /** ntfy accepts a unix-seconds cursor or "all"; the app only ever sends the former. */
    private fun sinceOf(query: String): Long {
        val raw = query.split('&').firstOrNull { it.startsWith("since=") }?.removePrefix("since=")
            ?: return 0
        if (raw == "all") return 1
        return raw.toLongOrNull() ?: 0
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    /** The event shape NtfyTransport parses: kind, server-side time, and the body. */
    private fun eventJson(message: Message): String {
        val escaped = message.body
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"event":"message","time":${message.timeSec},"message":"$escaped"}"""
    }
}
