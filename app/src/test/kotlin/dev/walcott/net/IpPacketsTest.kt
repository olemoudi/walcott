package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The frames a phone actually puts on a tun.
 *
 * All of this used to be inline in the packet loop, where the only way to find out that a reply
 * was malformed was to watch an app fail to use it. A fragment, a header carrying options, a
 * datagram whose length fields disagree with the frame — each is two lines here and not
 * reproducible on a device on demand.
 */
class IpPacketsTest {

    private val src = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dst = byteArrayOf(10, 111, 222.toByte(), 2)

    private fun ipv4(
        protocol: Int,
        payload: ByteArray,
        optionWords: Int = 0,
        fragmentOffset: Int = 0,
        moreFragments: Boolean = false,
        totalLengthOverride: Int? = null,
    ): ByteArray {
        val headerBytes = 20 + optionWords * 4
        val total = totalLengthOverride ?: (headerBytes + payload.size)
        val out = ByteArray(headerBytes + payload.size)
        out[0] = (0x40 or (headerBytes / 4)).toByte()
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[6] = (((fragmentOffset shr 8) and 0x1F) or (if (moreFragments) 0x20 else 0)).toByte()
        out[7] = fragmentOffset.toByte()
        out[8] = 64
        out[9] = protocol.toByte()
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        System.arraycopy(payload, 0, out, headerBytes, payload.size)
        return out
    }

    private fun udp(srcPort: Int, dstPort: Int, body: ByteArray, lengthOverride: Int? = null): ByteArray {
        val out = ByteArray(8 + body.size)
        out[0] = (srcPort shr 8).toByte(); out[1] = srcPort.toByte()
        out[2] = (dstPort shr 8).toByte(); out[3] = dstPort.toByte()
        val len = lengthOverride ?: (8 + body.size)
        out[4] = (len shr 8).toByte(); out[5] = len.toByte()
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    private fun dnsBody(size: Int = 20) = ByteArray(size) { (it + 1).toByte() }

    @Test
    fun `a plain DNS query is located, options and all`() {
        val plain = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        assertEquals(28, IpPackets.dnsStart(plain))
        assertEquals(40000, IpPackets.sourcePort(plain))
        assertEquals(53, IpPackets.destinationPort(plain))
        // Nothing used to read this, so every datagram reaching the tun was taken for a query.
        assertEquals(443, IpPackets.destinationPort(ipv4(IpPackets.PROTO_UDP, udp(40000, 443, dnsBody()))))
        assertNull(IpPackets.destinationPort(ipv4(IpPackets.PROTO_TCP, ByteArray(24))))

        // An IP header may carry options; the DNS message does not start at a fixed offset.
        val withOptions = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()), optionWords = 2)
        assertEquals(36, IpPackets.dnsStart(withOptions))
    }

    @Test
    fun `a fragment is refused rather than forwarded as a whole query`() {
        // The first fragment carries a UDP header and a piece of a DNS message. Forwarding that
        // piece sends garbage upstream and answers the app with nothing at all.
        val first = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()), moreFragments = true)
        assertNull(IpPackets.dnsStart(first))
        val later = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()), fragmentOffset = 185)
        assertNull(IpPackets.dnsStart(later))
    }

    @Test
    fun `the UDP length decides where the message ends, clamped to what arrived`() {
        val body = dnsBody(30)
        val exact = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, body))
        assertEquals(28 + 30, IpPackets.dnsEnd(exact))

        // A header claiming more than the frame holds must not have the slack read as content.
        val lying = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, body, lengthOverride = 9000))
        assertEquals(lying.size, IpPackets.dnsEnd(lying))

        // And one claiming less is believed: the tail is padding, not question.
        val short = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, body, lengthOverride = 8 + 12))
        assertEquals(28 + 12, IpPackets.dnsEnd(short))
    }

    @Test
    fun `anything that is not IPv4 UDP is not a DNS query`() {
        assertNull(IpPackets.dnsStart(ipv4(IpPackets.PROTO_TCP, ByteArray(24))))
        assertNull(IpPackets.dnsStart(ipv4(IpPackets.PROTO_ICMP, ByteArray(24))))
        val v6 = ByteArray(60).also { it[0] = 0x60 }
        assertNull(IpPackets.dnsStart(v6))
        assertNull(IpPackets.parse(v6))
    }

    @Test
    fun `a truncated frame is declined at every length rather than misread`() {
        val full = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        for (cut in 0 until full.size) {
            // Nothing may throw: this is fed straight off a tun.
            IpPackets.parse(full, cut)
            IpPackets.dnsStart(full, cut)
        }
        assertNull(IpPackets.dnsStart(full, 30), "too short to carry a DNS header")
    }

    @Test
    fun `a header claiming a length the frame does not have is refused`() {
        val impossible = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        impossible[0] = 0x4F // IHL 15 words = 60 bytes, more than this frame
        assertNull(IpPackets.parse(impossible, 48))
    }

    @Test
    fun `a response goes back the way the query came`() {
        val request = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        val answer = IpPackets.udpResponse(request, dnsBody(40))!!

        assertEquals(0x45.toByte(), answer[0])
        assertEquals(20 + 8 + 40, answer.size)
        assertEquals(answer.size, ((answer[2].toInt() and 0xFF) shl 8) or (answer[3].toInt() and 0xFF))
        assertEquals(IpPackets.PROTO_UDP, answer[9].toInt() and 0xFF)
        // Addresses and ports swapped: from the sentinel, back to the asker.
        assertTrue(answer.copyOfRange(12, 16).contentEquals(dst))
        assertTrue(answer.copyOfRange(16, 20).contentEquals(src))
        assertEquals(53, ((answer[20].toInt() and 0xFF) shl 8) or (answer[21].toInt() and 0xFF))
        assertEquals(40000, ((answer[22].toInt() and 0xFF) shl 8) or (answer[23].toInt() and 0xFF))
        assertEquals(8 + 40, ((answer[24].toInt() and 0xFF) shl 8) or (answer[25].toInt() and 0xFF))
        // The header checksum has to be right or the phone's own stack drops it silently.
        assertEquals(0, IpPackets.checksum(answer, 0, 20), "the IPv4 header checksum does not verify")
    }

    @Test
    fun `a ping to the phone's own resolver is answered`() {
        // A connectivity check pings the resolver it was given. Swallowing that makes the phone
        // conclude its DNS server is dead and leave a perfectly good network.
        val echo = ByteArray(16)
        echo[0] = 8 // echo request
        echo[4] = 0x12; echo[5] = 0x34 // identifier
        echo[6] = 0x00; echo[7] = 0x01 // sequence
        val request = ipv4(IpPackets.PROTO_ICMP, echo)
        val reply = IpPackets.echoReply(request)!!

        assertEquals(0, reply[20].toInt(), "an echo reply is type 0")
        assertEquals(0x12.toByte(), reply[24], "the identifier is the asker's and must come back")
        assertEquals(0x34.toByte(), reply[25])
        assertEquals(0x01.toByte(), reply[27], "and so is the sequence number")
        assertTrue(reply.copyOfRange(12, 16).contentEquals(dst))
        assertEquals(0, IpPackets.checksum(reply, 0, 20), "IPv4 header checksum")
        assertEquals(0, IpPackets.checksum(reply, 20, reply.size - 20), "ICMP checksum")
    }

    @Test
    fun `anything but an echo request is left alone`() {
        val notEcho = ByteArray(16).also { it[0] = 3 } // destination unreachable
        assertNull(IpPackets.echoReply(ipv4(IpPackets.PROTO_ICMP, notEcho)))
        assertNull(IpPackets.echoReply(ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))))
    }

    @Test
    fun `a TCP connection to the tunnel is refused at once instead of hanging`() {
        // A resolver whose answer came back truncated retries over TCP. The SYN used to vanish
        // and the app sat on its connect timeout — a minute of looking hung, for a question that
        // was going to be answered anyway.
        val syn = ByteArray(20)
        syn[0] = 0x9C.toByte(); syn[1] = 0x40 // src port 40000
        syn[2] = 0x00; syn[3] = 53.toByte()
        syn[4] = 0x00; syn[5] = 0x00; syn[6] = 0x00; syn[7] = 0x2A // seq 42
        syn[12] = 0x50 // data offset 5
        syn[13] = 0x02 // SYN
        val reset = IpPackets.tcpReset(ipv4(IpPackets.PROTO_TCP, syn))!!

        assertEquals(40, reset.size)
        assertEquals(IpPackets.PROTO_TCP, reset[9].toInt() and 0xFF)
        assertEquals(0x14, reset[33].toInt() and 0xFF, "RST + ACK")
        // A SYN counts as one byte of sequence space, so the acknowledgement is seq + 1.
        val ack = ((reset[28].toInt() and 0xFF) shl 24) or ((reset[29].toInt() and 0xFF) shl 16) or
            ((reset[30].toInt() and 0xFF) shl 8) or (reset[31].toInt() and 0xFF)
        assertEquals(43, ack)
        assertEquals(53, ((reset[20].toInt() and 0xFF) shl 8) or (reset[21].toInt() and 0xFF))
        assertEquals(40000, ((reset[22].toInt() and 0xFF) shl 8) or (reset[23].toInt() and 0xFF))
        assertEquals(0, IpPackets.checksum(reset, 0, 20), "IPv4 header checksum")
    }

    @Test
    fun `a reset is never answered with a reset`() {
        val rst = ByteArray(20)
        rst[12] = 0x50
        rst[13] = 0x04 // RST
        assertNull(IpPackets.tcpReset(ipv4(IpPackets.PROTO_TCP, rst)), "answering a reset is a loop")
    }

    @Test
    fun `a response too big for any IP datagram is refused rather than built wrong`() {
        val request = ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        assertNull(IpPackets.udpResponse(request, ByteArray(70_000)))
        assertNotNull(IpPackets.udpResponse(request, ByteArray(1200)))
    }

    @Test
    fun `an acknowledged segment is refused with its own acknowledgement number`() {
        // A peer that reads a reset whose sequence is outside its window ignores it and goes back
        // to waiting — the very hang the reset exists to end. So a segment that already carries an
        // acknowledgement is refused with a bare RST whose sequence is the number the sender said
        // it expected, and nothing of ours is acknowledged.
        val segment = ByteArray(20)
        segment[0] = 0x9C.toByte(); segment[1] = 0x40
        segment[2] = 0x00; segment[3] = 53.toByte()
        segment[4] = 0; segment[5] = 0; segment[6] = 0; segment[7] = 77 // seq 77
        segment[8] = 0; segment[9] = 0; segment[10] = 0x03; segment[11] = 0xE9.toByte() // ack 1001
        segment[12] = 0x50
        segment[13] = 0x10 // ACK
        val reset = IpPackets.tcpReset(ipv4(IpPackets.PROTO_TCP, segment))!!

        assertEquals(0x04, reset[33].toInt() and 0xFF, "a bare RST, with no acknowledgement of ours")
        assertEquals(1001, readInt(reset, 24), "the sequence is what the sender said it expected")
        assertEquals(0, readInt(reset, 28), "there was nothing of ours to acknowledge")
        assertEquals(0, IpPackets.checksum(reset, 0, 20), "IPv4 header checksum")
    }

    // ---- a datagram this tunnel routes but does not serve ----------------------------------

    @Test
    fun `a datagram to a port this tunnel does not serve is refused, not swallowed`() {
        // The twin of the reset. A connectionless protocol cannot tell silence from a slow
        // network, so DNS over QUIC or HTTP/3 to a routed address waited out its whole handshake
        // timeout before trying anything else.
        val quic = ipv4(IpPackets.PROTO_UDP, udp(40000, 443, dnsBody(5)))
        val refusal = IpPackets.portUnreachable(quic)!!

        assertEquals(IpPackets.PROTO_ICMP, refusal[9].toInt() and 0xFF)
        assertEquals(3, refusal[20].toInt() and 0xFF, "type 3, destination unreachable")
        assertEquals(3, refusal[21].toInt() and 0xFF, "code 3, port unreachable")
        // Reversed, or the kernel cannot match the error to the socket that sent the datagram.
        assertTrue(refusal.copyOfRange(12, 16).contentEquals(dst))
        assertTrue(refusal.copyOfRange(16, 20).contentEquals(src))
        // The offending header and the first eight bytes after it, which are its ports.
        assertTrue(refusal.copyOfRange(28, 56).contentEquals(quic.copyOfRange(0, 28)))
        assertEquals(56, refusal.size)
        assertEquals(0, IpPackets.checksum(refusal, 0, 20), "IPv4 header checksum")
        assertEquals(0, IpPackets.checksum(refusal, 20, refusal.size - 20), "ICMP checksum")
    }

    @Test
    fun `only a whole datagram is refused with port unreachable`() {
        // Never forge an error from a packet we did not fully parse.
        assertNull(IpPackets.portUnreachable(ipv4(IpPackets.PROTO_TCP, ByteArray(24))))
        assertNull(IpPackets.portUnreachable(ipv4(IpPackets.PROTO_ICMP, ByteArray(24))))
        assertNull(
            IpPackets.portUnreachable(ipv4(IpPackets.PROTO_UDP, udp(40000, 443, dnsBody()), fragmentOffset = 185)),
        )
        val short = ipv4(IpPackets.PROTO_UDP, ByteArray(4))
        assertNull(IpPackets.portUnreachable(short), "a UDP header that is not all there")
    }

    private fun readInt(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)
}
