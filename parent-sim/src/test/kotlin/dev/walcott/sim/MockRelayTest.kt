package dev.walcott.sim

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The relay has to behave like the one the app was written against, because every scenario
 * built on it inherits its mistakes. These check the three things NtfyTransport actually
 * depends on: a POST reaches subscribers, `since=` replays a backlog, and non-message frames
 * exist and are ignorable.
 */
class MockRelayTest {

    private lateinit var relay: MockRelay
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    @BeforeEach fun setUp() { relay = MockRelay().start() }

    @AfterEach fun tearDown() { relay.stop() }

    private class Collector : WebSocketListener() {
        val frames = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = opened.countDown()
        override fun onMessage(webSocket: WebSocket, text: String) { frames += text }
        fun bodies() = frames.mapNotNull { NtfyEvent.messageBody(it) }
    }

    private fun subscribe(topic: String, since: Long = 0): Collector {
        val collector = Collector()
        val suffix = if (since > 0) "?since=$since" else ""
        client.newWebSocket(
            Request.Builder().url("${relay.localUrl.replaceFirst("http", "ws")}/$topic/ws$suffix").build(),
            collector,
        )
        assertTrue(collector.opened.await(5, TimeUnit.SECONDS), "socket never opened")
        return collector
    }

    private fun post(topic: String, body: String) {
        client.newCall(
            Request.Builder().url("${relay.localUrl}/$topic").post(body.toByteArray().toRequestBody()).build(),
        ).execute().use { assertTrue(it.isSuccessful, "publish failed: HTTP ${it.code}") }
    }

    private fun waitFor(collector: Collector, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (collector.bodies().size < count && System.currentTimeMillis() < deadline) Thread.sleep(25)
    }

    @Test
    fun `a published message reaches a live subscriber`() {
        val collector = subscribe("t1")
        post("t1", "hello")
        waitFor(collector, 1)
        assertEquals(listOf("hello"), collector.bodies())
    }

    @Test
    fun `a body with quotes and newlines survives the event framing`() {
        // Envelopes are JSON, so every message the app sends is full of quotes. A framing bug
        // here would corrupt every envelope and look exactly like a decryption failure.
        val collector = subscribe("t2")
        val body = """{"kind":"child","ciphertext":"a/b+c=","note":"line1
line2"}"""
        post("t2", body)
        waitFor(collector, 1)
        assertEquals(listOf(body), collector.bodies())
    }

    @Test
    fun `since replays what was published while nobody was listening`() {
        // This is the mechanism the child's reconnect leans on: a socket that dropped comes back
        // with a cursor and expects the gap filled, not skipped.
        post("t3", "first")
        Thread.sleep(1_100) // the cursor's resolution is one second
        val cutoff = System.currentTimeMillis() / 1000
        post("t3", "second")
        val collector = subscribe("t3", since = cutoff)
        waitFor(collector, 1)
        assertEquals(listOf("second"), collector.bodies())
    }

    @Test
    fun `a dropped publish is recorded nowhere and delivered to no one`() {
        val collector = subscribe("t4")
        relay.dropNext("t4")
        post("t4", "lost")
        post("t4", "kept")
        waitFor(collector, 1)
        assertEquals(listOf("kept"), collector.bodies())
        assertEquals(listOf("kept"), relay.published("t4").map { it.body })
    }

    @Test
    fun `replay re-delivers an old message, as a relay repeating itself would`() {
        val collector = subscribe("t5")
        post("t5", "once")
        waitFor(collector, 1)
        relay.replay("t5", 0)
        waitFor(collector, 2)
        assertEquals(listOf("once", "once"), collector.bodies())
    }

    @Test
    fun `the open frame is not a message`() {
        val collector = subscribe("t6")
        val deadline = System.currentTimeMillis() + 2_000
        while (collector.frames.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(collector.frames.isNotEmpty(), "expected an open frame")
        assertTrue(collector.bodies().isEmpty(), "open frame must not decode as a message body")
    }
}
