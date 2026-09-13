package dev.walcott.net

import dev.walcott.net.IpPackets.TCP_ACK
import dev.walcott.net.IpPackets.TCP_FIN
import dev.walcott.net.IpPackets.TCP_PSH
import dev.walcott.net.IpPackets.TCP_RST
import dev.walcott.net.IpPackets.TCP_SYN
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DNS over TCP inside the tunnel, one segment at a time.
 *
 * What the app's side sends is built by hand here rather than by [IpPackets.tcpPacket], and what
 * comes back is read by offset, so the responder is not checked against its own plumbing. The
 * checksums are verified against a pseudo-header laid out as the RFCs draw it.
 */
class DnsTcpResponderTest {

    private val app4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val resolver4 = byteArrayOf(8, 8, 8, 8)
    private val app6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val resolver6 = byteArrayOf(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88.toByte(), 0x88.toByte())

    private fun responder(maxConnections: Int = 32, idleMs: Long = 30_000) =
        DnsTcpResponder(MTU, maxConnections, idleMs) { ISS }

    /** A segment from the app, built by hand. */
    private fun segment(
        flags: Int,
        seq: Int,
        ack: Int = 0,
        data: ByteArray = ByteArray(0),
        window: Int = 65_535,
        mss: Int? = null,
        v6: Boolean = false,
        srcPort: Int = 40_000,
    ): ByteArray {
        val options = if (mss != null) byteArrayOf(2, 4, (mss shr 8).toByte(), mss.toByte()) else ByteArray(0)
        val tcp = ByteArray(20 + options.size + data.size)
        putShort(tcp, 0, srcPort)
        putShort(tcp, 2, 53)
        putInt(tcp, 4, seq)
        putInt(tcp, 8, ack)
        tcp[12] = (((20 + options.size) / 4) shl 4).toByte()
        tcp[13] = flags.toByte()
        putShort(tcp, 14, window)
        options.copyInto(tcp, 20)
        data.copyInto(tcp, 20 + options.size)
        val ip = if (v6) {
            ByteArray(40).also {
                it[0] = 0x60
                putShort(it, 4, tcp.size)
                it[6] = IpPackets.PROTO_TCP.toByte()
                it[7] = 64
                app6.copyInto(it, 8)
                resolver6.copyInto(it, 24)
            }
        } else {
            ByteArray(20).also {
                it[0] = 0x45
                putShort(it, 2, 20 + tcp.size)
                it[8] = 64
                it[9] = IpPackets.PROTO_TCP.toByte()
                app4.copyInto(it, 12)
                resolver4.copyInto(it, 16)
            }
        }
        return ip + tcp
    }

    /** A segment the responder sent, read by offset. */
    private class Out(val packet: ByteArray) {
        val v6 = packet[0].toInt() and 0xF0 == 0x60
        val tcp = if (v6) 40 else 20
        val flags = packet[tcp + 13].toInt() and 0xFF
        val seq = int(packet, tcp + 4)
        val ack = int(packet, tcp + 8)
        val sourcePort = short(packet, tcp)
        val destinationPort = short(packet, tcp + 2)
        val headerBytes = ((packet[tcp + 12].toInt() and 0xF0) shr 4) * 4
        val payload: ByteArray = packet.copyOfRange(tcp + headerBytes, packet.size)
    }

    private fun dnsMessage(id: Int, size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }.also {
        it[0] = (id shr 8).toByte(); it[1] = id.toByte()
    }

    private fun framed(message: ByteArray) = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message

    /** SYN, SYN-ACK, ACK, with the app's sequence starting at 1000. */
    private fun open(
        responder: DnsTcpResponder,
        v6: Boolean = false,
        mss: Int? = 1400,
        window: Int = 65_535,
        srcPort: Int = 40_000,
        nowMs: Long = 0,
    ) {
        val synAck = responder.onSegment(segment(TCP_SYN, 1000, mss = mss, v6 = v6, window = window, srcPort = srcPort), nowMs)
        assertEquals(TCP_SYN or TCP_ACK, Out(synAck.packets.single()).flags)
        val established = responder.onSegment(segment(TCP_ACK, 1001, ISS + 1, v6 = v6, window = window, srcPort = srcPort), nowMs)
        assertTrue(established.packets.isEmpty(), "an acknowledgement is not acknowledged")
    }

    // ---- the handshake ----------------------------------------------------------------------

    @Test
    fun `a SYN to port 53 is answered with a SYN-ACK naming our MSS`() {
        // Connectivity checks decide "no internet" on exactly this connect to 8.8.8.8:53 failing.
        val responder = responder()
        val result = responder.onSegment(segment(TCP_SYN, 1000, mss = 1400), 0)
        val synAck = Out(result.packets.single())

        assertEquals(TCP_SYN or TCP_ACK, synAck.flags)
        assertEquals(ISS, synAck.seq)
        assertEquals(1001, synAck.ack, "a SYN takes one sequence number")
        assertEquals(53, synAck.sourcePort)
        assertEquals(40_000, synAck.destinationPort)
        // From the resolver the app addressed, to the app.
        assertArrayEquals(resolver4, synAck.packet.copyOfRange(12, 16))
        assertArrayEquals(app4, synAck.packet.copyOfRange(16, 20))
        assertEquals(24, synAck.headerBytes)
        assertEquals(2, synAck.packet[40].toInt(), "MSS option kind")
        assertEquals(MTU - 40, short(synAck.packet, 42), "the MSS is what the tun's MTU leaves")
        assertEquals(0, IpPackets.checksum(synAck.packet, 0, 20), "IPv4 header checksum")
        assertTrue(transportChecksumVerifies(synAck.packet), "TCP checksum")
        assertTrue(result.queries.isEmpty())

        // The same SYN again (our SYN-ACK was lost) gets the same answer, not a second connection.
        val again = Out(responder.onSegment(segment(TCP_SYN, 1000, mss = 1400), 5).packets.single())
        assertEquals(ISS, again.seq)
        assertEquals(1001, again.ack)
        assertEquals(1, responder.connectionCount)
    }

    // ---- queries ----------------------------------------------------------------------------

    @Test
    fun `one query is handed out, and its answer goes back as data`() {
        val responder = responder()
        open(responder)
        val query = dnsMessage(0x1234, 30)
        val arrived = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framed(query)), 0)

        val handed = arrived.queries.single()
        assertArrayEquals(query, handed.message)
        // Both ends as the app's socket sees them, which is what attribution asks the platform.
        assertArrayEquals(app4, handed.source)
        assertEquals(40_000, handed.sourcePort)
        assertArrayEquals(resolver4, handed.destination)
        assertEquals(53, handed.destinationPort)
        val ack = Out(arrived.packets.single())
        assertEquals(TCP_ACK, ack.flags)
        assertEquals(1001 + 32, ack.ack)

        val answer = dnsMessage(0x1234, 100)
        val data = Out(responder.answer(handed, answer, 0).single())
        assertEquals(TCP_PSH or TCP_ACK, data.flags)
        assertEquals(ISS + 1, data.seq)
        assertEquals(1001 + 32, data.ack)
        assertArrayEquals(framed(answer), data.payload, "two bytes of length, then the message")
        assertTrue(transportChecksumVerifies(data.packet))
    }

    @Test
    fun `two queries on one connection are both handed out and answered in either order`() {
        val responder = responder()
        open(responder)
        val first = dnsMessage(1, 20)
        val second = dnsMessage(2, 40)
        val arrived = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framed(first) + framed(second)), 0)

        assertEquals(2, arrived.queries.size)
        assertArrayEquals(first, arrived.queries[0].message)
        assertArrayEquals(second, arrived.queries[1].message)
        // RFC 7766 lets the answers come back in any order; the byte stream stays contiguous.
        val answeredSecond = Out(responder.answer(arrived.queries[1], dnsMessage(2, 50), 0).single())
        val answeredFirst = Out(responder.answer(arrived.queries[0], dnsMessage(1, 60), 0).single())
        assertEquals(ISS + 1, answeredSecond.seq)
        assertEquals(ISS + 1 + 52, answeredFirst.seq)
    }

    @Test
    fun `a query split across segments, even between its two length bytes, is handed out once whole`() {
        val responder = responder()
        open(responder)
        val query = dnsMessage(7, 40)
        val bytes = framed(query)
        val pieces = listOf(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, 10), bytes.copyOfRange(10, bytes.size))
        var seq = 1001
        for (piece in pieces.dropLast(1)) {
            val partial = responder.onSegment(segment(TCP_ACK, seq, ISS + 1, piece), 0)
            assertTrue(partial.queries.isEmpty())
            seq += piece.size
            assertEquals(seq, Out(partial.packets.single()).ack)
        }
        val last = responder.onSegment(segment(TCP_PSH or TCP_ACK, seq, ISS + 1, pieces.last()), 0)
        assertArrayEquals(query, last.queries.single().message)
    }

    @Test
    fun `a large answer is cut at the MSS the app named`() {
        val responder = responder()
        open(responder, mss = 536)
        val handed = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framed(dnsMessage(9, 30))), 0)
            .queries.single()
        val answer = dnsMessage(9, 1500)
        val segments = responder.answer(handed, answer, 0).map(::Out)

        assertEquals(listOf(536, 536, 430), segments.map { it.payload.size })
        assertEquals(listOf(ISS + 1, ISS + 537, ISS + 1073), segments.map { it.seq })
        assertArrayEquals(framed(answer), segments.map { it.payload }.reduce { a, b -> a + b })
        assertTrue(segments.all { it.flags == TCP_PSH or TCP_ACK })
        assertTrue(segments.all { transportChecksumVerifies(it.packet) })
    }

    @Test
    fun `nothing is sent past the app's window, and the rest follows its acknowledgement`() {
        // A real stack drops data beyond the window it offered, and nothing here retransmits.
        val responder = responder()
        open(responder, window = 1000)
        val framedQuery = framed(dnsMessage(9, 30))
        val handed = responder.onSegment(
            segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framedQuery, window = 1000), 0,
        ).queries.single()
        val first = responder.answer(handed, dnsMessage(9, 1998), 0).map(::Out)
        assertEquals(1000, first.sumOf { it.payload.size })

        val rest = responder.onSegment(
            segment(TCP_ACK, 1001 + framedQuery.size, ISS + 1 + 1000, window = 1000), 0,
        ).packets.map(::Out)
        assertEquals(1000, rest.sumOf { it.payload.size })
        assertEquals(ISS + 1001, rest.first().seq)
    }

    // ---- closing ----------------------------------------------------------------------------

    @Test
    fun `a FIN is answered with FIN and ACK, and the connection is forgotten`() {
        val responder = responder()
        open(responder)
        val fin = Out(responder.onSegment(segment(TCP_FIN or TCP_ACK, 1001, ISS + 1), 0).packets.single())
        assertEquals(TCP_FIN or TCP_ACK, fin.flags)
        assertEquals(1002, fin.ack, "a FIN takes one sequence number")
        assertEquals(ISS + 1, fin.seq)
        assertEquals(0, responder.connectionCount)
        // The app's last acknowledgement reaches a connection already forgotten: not reset.
        assertTrue(responder.onSegment(segment(TCP_ACK, 1002, ISS + 2), 0).packets.isEmpty())
    }

    @Test
    fun `a FIN while a query is out waits for its answer before closing`() {
        val responder = responder()
        open(responder)
        val framedQuery = framed(dnsMessage(5, 30))
        val handed = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framedQuery), 0).queries.single()
        val finSeq = 1001 + framedQuery.size
        val halfClosed = responder.onSegment(segment(TCP_FIN or TCP_ACK, finSeq, ISS + 1), 0).packets.map(::Out)
        assertEquals(listOf(TCP_ACK), halfClosed.map { it.flags })
        assertEquals(finSeq + 1, halfClosed.single().ack)
        assertEquals(1, responder.connectionCount)

        val answer = dnsMessage(5, 80)
        val closing = responder.answer(handed, answer, 0).map(::Out)
        assertEquals(listOf(TCP_PSH or TCP_ACK, TCP_FIN or TCP_ACK), closing.map { it.flags })
        assertEquals(ISS + 1 + framed(answer).size, closing[1].seq)
        assertEquals(0, responder.connectionCount)
    }

    @Test
    fun `a reset forgets the connection, and an answer arriving later goes nowhere`() {
        val responder = responder()
        open(responder)
        val framedQuery = framed(dnsMessage(5, 30))
        val handed = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framedQuery), 0).queries.single()

        assertTrue(responder.onSegment(segment(TCP_RST, 1001 + framedQuery.size), 0).packets.isEmpty())
        assertEquals(0, responder.connectionCount)
        assertTrue(responder.answer(handed, dnsMessage(5, 80), 0).isEmpty())
    }

    // ---- what a stack resends --------------------------------------------------------------

    @Test
    fun `retransmitted data is acknowledged again and never asked twice`() {
        val responder = responder()
        open(responder)
        val framedQuery = framed(dnsMessage(3, 30))
        val data = segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framedQuery)
        assertEquals(1, responder.onSegment(data, 0).queries.size)

        val again = responder.onSegment(data, 0)
        assertTrue(again.queries.isEmpty(), "the same bytes are not a second query")
        assertEquals(1001 + framedQuery.size, Out(again.packets.single()).ack)

        // Bytes past a gap are not kept or guessed at: the acknowledgement says where to resume.
        val ahead = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001 + framedQuery.size + 10, ISS + 1, framedQuery), 0)
        assertTrue(ahead.queries.isEmpty())
        assertEquals(1001 + framedQuery.size, Out(ahead.packets.single()).ack)

        // A segment that overlaps what arrived contributes only its new bytes.
        val next = framed(dnsMessage(4, 40))
        val base = 1001 + framedQuery.size
        responder.onSegment(segment(TCP_ACK, base, ISS + 1, next.copyOfRange(0, 20)), 0)
        val overlap = responder.onSegment(segment(TCP_PSH or TCP_ACK, base + 10, ISS + 1, next.copyOfRange(10, next.size)), 0)
        assertArrayEquals(dnsMessage(4, 40), overlap.queries.single().message)
    }

    // ---- limits -----------------------------------------------------------------------------

    @Test
    fun `a SYN past the connection cap is reset, until an idle connection is evicted`() {
        val responder = responder(maxConnections = 2, idleMs = 30_000)
        open(responder, srcPort = 40_000)
        open(responder, srcPort = 40_001)

        val refused = Out(responder.onSegment(segment(TCP_SYN, 1000, srcPort = 40_002), 1_000).packets.single())
        assertEquals(TCP_RST or TCP_ACK, refused.flags)
        assertEquals(1001, refused.ack)
        assertEquals(2, responder.connectionCount)

        // Nothing has happened on the first two for longer than the idle limit.
        val accepted = Out(responder.onSegment(segment(TCP_SYN, 1000, srcPort = 40_002), 31_000).packets.single())
        assertEquals(TCP_SYN or TCP_ACK, accepted.flags)
        assertEquals(1, responder.connectionCount)
    }

    @Test
    fun `a segment for a connection that does not exist is reset, unless it is a bare acknowledgement`() {
        val responder = responder()
        val reset = Out(responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, 5001, framed(dnsMessage(1, 20))), 0).packets.single())
        assertEquals(TCP_RST, reset.flags, "an acknowledged segment is refused with a bare RST")
        assertTrue(responder.onSegment(segment(TCP_ACK, 1001, 5001), 0).packets.isEmpty())
        assertEquals(0, responder.connectionCount)
    }

    @Test
    fun `a length too short to be a DNS message resets the connection`() {
        val responder = responder()
        open(responder)
        val bad = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, byteArrayOf(0, 3, 1, 2, 3)), 0)
        assertTrue(bad.queries.isEmpty())
        assertEquals(TCP_RST or TCP_ACK, Out(bad.packets.single()).flags)
        assertEquals(0, responder.connectionCount)
    }

    // ---- IPv6 -------------------------------------------------------------------------------

    @Test
    fun `the same over IPv6, with checksums over the IPv6 pseudo-header`() {
        val responder = responder()
        val synAck = Out(responder.onSegment(segment(TCP_SYN, 1000, mss = 1400, v6 = true), 0).packets.single())
        assertTrue(synAck.v6)
        assertEquals(IpPackets.PROTO_TCP, synAck.packet[6].toInt())
        assertArrayEquals(resolver6, synAck.packet.copyOfRange(8, 24))
        assertArrayEquals(app6, synAck.packet.copyOfRange(24, 40))
        assertEquals(synAck.packet.size - 40, short(synAck.packet, 4), "payload length")
        assertEquals(MTU - 60, short(synAck.packet, 62), "the MSS is what an IPv6 header leaves")
        assertTrue(transportChecksumVerifies(synAck.packet))

        responder.onSegment(segment(TCP_ACK, 1001, ISS + 1, v6 = true), 0)
        val query = dnsMessage(6, 33)
        val handed = responder.onSegment(segment(TCP_PSH or TCP_ACK, 1001, ISS + 1, framed(query), v6 = true), 0)
            .queries.single()
        assertArrayEquals(app6, handed.source)
        assertArrayEquals(resolver6, handed.destination)

        val answer = dnsMessage(6, 77) // odd, so the checksum's padding byte is exercised
        val data = Out(responder.answer(handed, answer, 0).single())
        assertArrayEquals(framed(answer), data.payload)
        assertTrue(transportChecksumVerifies(data.packet))
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** The transport checksum, over a pseudo-header laid out exactly as RFC 793 and RFC 8200 draw it. */
    private fun transportChecksumVerifies(packet: ByteArray): Boolean {
        val v6 = packet[0].toInt() and 0xF0 == 0x60
        val start = if (v6) 40 else (packet[0].toInt() and 0x0F) * 4
        val protocol = (if (v6) packet[6] else packet[9]).toInt() and 0xFF
        val length = packet.size - start
        val pseudo = if (v6) {
            packet.copyOfRange(8, 40) + byteArrayOf(
                (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
                0, 0, 0, protocol.toByte(),
            )
        } else {
            packet.copyOfRange(12, 20) + byteArrayOf(0, protocol.toByte(), (length shr 8).toByte(), length.toByte())
        }
        val whole = pseudo + packet.copyOfRange(start, packet.size)
        return IpPackets.checksum(whole, 0, whole.size) == 0
    }

    private fun putShort(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value shr 8).toByte(); buf[at + 1] = value.toByte()
    }

    private fun putInt(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value shr 24).toByte(); buf[at + 1] = (value shr 16).toByte()
        buf[at + 2] = (value shr 8).toByte(); buf[at + 3] = value.toByte()
    }

    private companion object {
        const val MTU = 4096
        const val ISS = 5000

        fun short(buf: ByteArray, at: Int): Int = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

        fun int(buf: ByteArray, at: Int): Int =
            ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
                ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)
    }
}
