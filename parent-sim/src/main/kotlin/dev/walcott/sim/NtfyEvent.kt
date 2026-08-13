package dev.walcott.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one thing a subscriber has to know about ntfy's stream: which frames are messages.
 *
 * The relay also sends "open" and "keepalive" events, and treating either as a body would hand
 * the decoder garbage on every connection — which, since the decoder is deliberately silent
 * about garbage, would look like a channel that simply never delivers.
 */
object NtfyEvent {

    private val json = Json { ignoreUnknownKeys = true }

    /** The message body of an event frame, or null if it isn't one. */
    fun messageBody(frame: String): String? {
        val event = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        if (event["event"]?.jsonPrimitive?.content != "message") return null
        return event["message"]?.jsonPrimitive?.content
    }
}
