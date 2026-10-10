package dev.walcott.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reading what the relay holds: one place for the shape of an ntfy event, shared by the live
 * socket, the reconnect catch-up and the parent's background poll.
 *
 * Three copies of "is this a message, and what is in it" had grown in three files, and the first
 * thing any change to the wire has to do is find all of them.
 */
object RelayBacklog {

    /**
     * The most one catch-up reads from the relay; a message is under 4 kB, so this is hundreds of
     * them. Bounded because a topic anyone who knows its name can post to must not be read into
     * memory whole, and a catch-up from `since=all` after a relay move is exactly when the backlog
     * is longest.
     */
    const val MAX_BYTES = 4L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** The poll URL for [topic] on [server], from [since] (a unix second, a message id, or `all`). */
    fun pollUrl(server: String, topic: String, since: String): String =
        "${server.trimEnd('/')}/$topic/json?poll=1&since=$since"

    /** The messages in a poll response — one JSON event per line — in the relay's order. */
    fun parse(body: String): List<RelayMessage> =
        body.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() }
            .mapNotNull { messageOf(it) }
            .toList()

    /**
     * One event's message, or null for anything else ntfy sends (`open`, `keepalive`…) — and for
     * an event whose fields are not the shapes ntfy gives them. Reading a field that is an object
     * where a string belongs throws, and thrown from here one odd line failed the whole poll, whose
     * cursor then could not move past it for as long as the relay kept it.
     */
    fun messageOf(event: JsonObject): RelayMessage? = runCatching {
        if (event["event"]?.jsonPrimitive?.content != "message") return@runCatching null
        val body = event["message"]?.jsonPrimitive?.content ?: return@runCatching null
        val timeSec = event["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val id = event["id"]?.jsonPrimitive?.content.orEmpty()
        val attachment = runCatching { event["attachment"]?.jsonObject?.get("url")?.jsonPrimitive?.content }.getOrNull()
        RelayMessage(body, timeSec, id, attachmentUrl = attachment.orEmpty())
    }.getOrNull()

    /**
     * The most a message kept as a file may weigh. A family's rules are a few kilobytes even when
     * they no longer fit in one message; this is room for growth, and a ceiling on what a topic
     * anyone can post to can make a phone download.
     */
    const val MAX_ATTACHMENT_BYTES = 64L * 1024

    /**
     * [message] with its real body: fetched from the relay when the relay kept it as a file,
     * or null when that file cannot be had.
     *
     * ntfy keeps a message longer than 4,096 bytes as an attachment — and answers the publish
     * with a 200 — leaving "You received a file: attachment.txt" where the body was. A family's
     * rules grow past that size (per-member limits on the apps each one uses are enough), and
     * from that moment every rule change reached every phone as that sentence, failed to decode
     * and was dropped, with nothing anywhere saying so (see ParentFit).
     *
     * Only a file on the relay [server] itself, under its `/file/` path: anything else would let
     * a message posted to the topic send this phone downloading from wherever it pointed. The
     * relay keeps files for three hours against twelve for messages, so an older one can be gone;
     * the parent's next publish brings a fresh one.
     */
    fun resolved(message: RelayMessage, server: String, client: OkHttpClient): RelayMessage? {
        if (message.attachmentUrl.isBlank()) return message
        if (!onRelay(message.attachmentUrl, server)) return null
        return runCatching {
            client.newCall(Request.Builder().url(message.attachmentUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                message.copy(body = resp.peekBody(MAX_ATTACHMENT_BYTES).string(), attachmentUrl = "")
            }
        }.getOrNull()
    }

    /** Whether [url] is a file kept by the relay at [server] (same scheme, host and port; `/file/`). */
    fun onRelay(url: String, server: String): Boolean {
        val file = url.toHttpUrlOrNull() ?: return false
        val relay = server.toHttpUrlOrNull() ?: return false
        return file.scheme == relay.scheme && file.host == relay.host && file.port == relay.port &&
            file.encodedPath.startsWith("/file/")
    }

    /** [messageOf] for one line of text off the socket; null for anything unreadable. */
    fun messageOf(text: String): RelayMessage? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { messageOf(it) }
}
