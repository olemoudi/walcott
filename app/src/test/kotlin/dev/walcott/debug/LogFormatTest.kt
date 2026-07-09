package dev.walcott.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogFormatTest {

    private fun entry(millis: Long, msg: String) = LogEntry(millis, 'I', "Tag", msg)

    @Test
    fun `cap keeps only the newest max entries, in order`() {
        var list = emptyList<LogEntry>()
        repeat(5) { i -> list = LogFormat.cap(list, entry(i.toLong(), "m$i"), max = 3) }
        assertEquals(listOf("m2", "m3", "m4"), list.map { it.message })
    }

    @Test
    fun `cap below the limit appends without trimming`() {
        var list = emptyList<LogEntry>()
        list = LogFormat.cap(list, entry(1, "a"), max = 10)
        list = LogFormat.cap(list, entry(2, "b"), max = 10)
        assertEquals(listOf("a", "b"), list.map { it.message })
    }

    @Test
    fun `format renders one line per entry with level, tag and message`() {
        val text = LogFormat.format(listOf(entry(0, "hello"), LogEntry(0, 'E', "Boom", "kaput")))
        val lines = text.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("I/Tag: hello"), lines[0])
        assertTrue(lines[1].contains("E/Boom: kaput"), lines[1])
    }

    @Test
    fun `serialize then deserialize round-trips, even with newlines in the message`() {
        val original = LogEntry(1234L, 'E', "WalcottUpdater", "install failed\n at Foo.bar()\n at Baz.qux()")
        val restored = LogFormat.deserialize(LogFormat.serialize(original))
        assertEquals(original, restored)
    }

    @Test
    fun `deserialize returns null for garbage`() {
        assertEquals(null, LogFormat.deserialize("not json"))
        assertEquals(null, LogFormat.deserialize(""))
    }
}
