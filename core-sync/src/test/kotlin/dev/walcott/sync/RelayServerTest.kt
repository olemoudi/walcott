package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RelayServerTest {

    @Test
    fun `a bare host becomes https`() {
        assertEquals("https://ntfy.example.com", RelayServer.normalize("ntfy.example.com"))
        assertEquals("https://ntfy.example.com", RelayServer.normalize("  ntfy.example.com/  "))
    }

    @Test
    fun `an explicit https scheme is kept`() {
        assertEquals("https://ntfy.sh", RelayServer.normalize("https://ntfy.sh"))
    }

    @Test
    fun `http is refused unless the build permits cleartext`() {
        // A release build refuses cleartext at the platform level, so an http relay accepted
        // here would be adopted by every phone and opened by none — a family with no channel
        // and a week before it may move again. The debug build permits it, for the test relay.
        assertNull(RelayServer.normalize("http://nas.local"))
        assertNull(RelayServer.normalize("http://localhost:8080"))
        assertEquals("http://nas.local", RelayServer.normalize("http://nas.local", cleartextAllowed = true))
        assertEquals("http://localhost:8080", RelayServer.normalize("http://localhost:8080", cleartextAllowed = true))
        assertEquals("https://ntfy.sh", RelayServer.normalize("https://ntfy.sh", cleartextAllowed = true))
    }

    @Test
    fun `a port survives`() {
        assertEquals("https://ntfy.example.com:8443", RelayServer.normalize("ntfy.example.com:8443"))
    }

    @Test
    fun `a path, query or fragment is refused`() {
        // The topic is appended by the transport: a path here would silently reshape every URL.
        assertNull(RelayServer.normalize("https://ntfy.sh/mytopic"))
        assertNull(RelayServer.normalize("https://ntfy.sh/?x=1"))
        assertNull(RelayServer.normalize("https://ntfy.sh#frag"))
    }

    @Test
    fun `nonsense is refused rather than half-accepted`() {
        listOf("", "   ", "://", "https://", "ftp://ntfy.sh", "not a host", "https://user:pw@ntfy.sh")
            .forEach { assertNull(RelayServer.normalize(it), it) }
    }

    @Test
    fun `a host with no dot is refused unless it is localhost`() {
        assertNull(RelayServer.normalize("ntfy"))
        assertEquals("https://localhost", RelayServer.normalize("localhost"))
    }

    @Test
    fun `the default is itself valid and unchanged by normalizing`() {
        assertEquals(RelayServer.DEFAULT, RelayServer.normalize(RelayServer.DEFAULT))
    }
}
