package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** One odd line from the relay must cost that line, not the whole poll (see [RelayBacklog.messageOf]). */
class RelayBacklogParseTest {

    @Test
    fun `an event with the wrong shapes is skipped and the rest still read`() {
        val body = listOf(
            """{"id":"a1","event":{"not":"a string"},"time":1,"message":"x"}""",
            """{"id":"a2","event":"message","time":["nope"],"message":{"also":"not"}}""",
            """{"id":"a3","event":"message","time":1700000000,"message":"hello"}""",
            """not json at all""",
            """{"id":"a4","event":"keepalive","time":1700000001}""",
        ).joinToString("\n")

        val messages = RelayBacklog.parse(body)

        assertEquals(listOf("a3"), messages.map { it.id })
        assertEquals("hello", messages.single().body)
        assertEquals(1_700_000_000L, messages.single().timeSec)
    }
}
