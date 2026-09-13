package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `anything that is not UDP is not a DNS query`() {
        assertNull(IpPackets.dnsStart(ipv4(IpPackets.PROTO_TCP, ByteArray(24))))
        assertNull(IpPackets.dnsStart(ipv4(IpPackets.PROTO_ICMP, ByteArray(24))))
        assertNull(IpPackets.dnsStart(ipv6(IpPackets.PROTO_TCP, ByteArray(24))))
        // Neither IPv4 nor IPv6 at all.
        assertNull(IpPackets.parse(ByteArray(60).also { it[0] = 0x50 }))
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

    // ---- IPv4, unchanged by IPv6 ----------------------------------------------------------

    @Test
    fun `the IPv4 answers are byte for byte what they were before IPv6 arrived`() {
        // Captured from the IPv4-only implementation, so a refactor for the other family cannot
        // quietly change a single bit of what every phone already relies on.
        val udpHeader = "4500002c1234000040110000" + "0a6fde01" + "08080808"
        val query = hex(udpHeader + "9c400035001800aa" + "abcd01000001000000000000" + "01610000")
        val quic = hex(udpHeader + "9c4001bb001800aa" + "abcd01000001000000000000" + "01610000")
        val echo = hex("4500002000000000400100000a6fde010a6fde02" + "080000001234000101020304")
        val syn = hex("450000280000000040060000" + "0a6fde01" + "01010101" + "9c400035" + "0000002a" + "00000000" + "5002ffff" + "00000000")
        val acked = hex("4500002b0000000040060000" + "0a6fde01" + "01010101" + "9c400035" + "0000004d" + "000003e9" + "5018ffff" + "00000000" + "414243")

        assertEquals(
            "450000280000000040118245080808080a6fde0100359c4000140000abcd81830001000000000000",
            hex(IpPackets.udpResponse(query, hex("abcd81830001000000000000"))!!),
        )
        assertEquals(
            "450000380000000040018245080808080a6fde010303ce4c000000004500002c12340000401100000a6fde01080808089c4001bb001800aa",
            hex(IpPackets.portUnreachable(quic)!!),
        )
        assertEquals("45000020000000004001a9fb0a6fde020a6fde010000e9c41234000101020304", hex(IpPackets.echoReply(echo)!!))
        assertEquals(
            "45000028000000004006905e010101010a6fde0100359c40000000000000002b5014000028be0000",
            hex(IpPackets.tcpReset(syn)!!),
        )
        assertEquals(
            "45000028000000004006905e010101010a6fde0100359c40000003e9000000005004000025100000",
            hex(IpPackets.tcpReset(acked)!!),
        )
    }

    // ---- IPv6 -------------------------------------------------------------------------------

    private val src6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val dst6 = byteArrayOf(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88.toByte(), 0x88.toByte())

    private fun ipv6(nextHeader: Int, payload: ByteArray, payloadLengthOverride: Int? = null): ByteArray {
        val out = ByteArray(40 + payload.size)
        out[0] = 0x60
        val length = payloadLengthOverride ?: payload.size
        out[4] = (length shr 8).toByte(); out[5] = length.toByte()
        out[6] = nextHeader.toByte()
        out[7] = 64
        System.arraycopy(src6, 0, out, 8, 16)
        System.arraycopy(dst6, 0, out, 24, 16)
        System.arraycopy(payload, 0, out, 40, payload.size)
        return out
    }

    @Test
    fun `an IPv6 DNS query is located like an IPv4 one`() {
        val query = ipv6(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        assertEquals(48, IpPackets.dnsStart(query))
        assertEquals(48 + 20, IpPackets.dnsEnd(query))
        assertEquals(40000, IpPackets.sourcePort(query))
        assertEquals(53, IpPackets.destinationPort(query))
        assertArrayEquals(src6, IpPackets.sourceAddress(query))
        assertArrayEquals(dst6, IpPackets.destinationAddress(query))
        assertArrayEquals(src, IpPackets.sourceAddress(ipv4(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))))

        // A header claiming more than arrived: the frame is the authority.
        val lying = ipv6(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()), payloadLengthOverride = 9000)
        assertEquals(lying.size, IpPackets.dnsEnd(lying))
    }

    @Test
    fun `an IPv6 answer carries the checksum IPv6 makes mandatory`() {
        val query = ipv6(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        val answer = IpPackets.udpResponse(query, dnsBody(41))!!

        assertEquals(0x60, answer[0].toInt() and 0xF0)
        assertEquals(8 + 41, readShort(answer, 4), "payload length")
        assertEquals(IpPackets.PROTO_UDP, answer[6].toInt())
        assertArrayEquals(dst6, answer.copyOfRange(8, 24), "from the resolver the app addressed")
        assertArrayEquals(src6, answer.copyOfRange(24, 40))
        assertEquals(53, readShort(answer, 40))
        assertEquals(40000, readShort(answer, 42))
        assertEquals(8 + 41, readShort(answer, 44))
        // Zero would be "no checksum", which IPv6 does not allow: the kernel drops the datagram.
        assertNotEquals(0, readShort(answer, 46))
        assertTrue(transportChecksumVerifies(answer))
    }

    @Test
    fun `an IPv6 checksum that works out to zero is sent as all ones`() {
        val query = ipv6(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        // The checksum is linear: two payload bytes equal to the checksum without them cancel it.
        val probe = readShort(IpPackets.udpResponse(query, ByteArray(2))!!, 46)
        val answer = IpPackets.udpResponse(query, byteArrayOf((probe shr 8).toByte(), probe.toByte()))!!
        assertEquals(0xFFFF, readShort(answer, 46))
        assertTrue(transportChecksumVerifies(answer))
    }

    @Test
    fun `IPv6 extension headers and fragments are declined, not walked`() {
        assertNull(IpPackets.parse(ipv6(0, ByteArray(40))), "hop-by-hop: the kernel's own listener reports")
        assertNull(IpPackets.parse(ipv6(44, udp(40000, 53, dnsBody()))), "a fragment")
        assertNull(IpPackets.dnsStart(ipv6(44, udp(40000, 53, dnsBody()))))
        assertNull(IpPackets.parse(ipv6(IpPackets.PROTO_UDP, ByteArray(4))), "a UDP header that is not all there")
        val full = ipv6(IpPackets.PROTO_UDP, udp(40000, 53, dnsBody()))
        for (cut in 0 until full.size) {
            // Nothing may throw: this is fed straight off a tun.
            IpPackets.parse(full, cut)
            IpPackets.dnsStart(full, cut)
            IpPackets.tcpSegment(full, cut)
        }
    }

    @Test
    fun `an IPv6 datagram to a port this tunnel does not serve is refused with ICMPv6`() {
        val quic = ipv6(IpPackets.PROTO_UDP, udp(40000, 443, dnsBody(5)))
        val refusal = IpPackets.portUnreachable(quic)!!

        assertEquals(IpPackets.PROTO_ICMPV6, refusal[6].toInt())
        assertEquals(1, refusal[40].toInt(), "type 1, destination unreachable")
        assertEquals(4, refusal[41].toInt(), "code 4, port unreachable")
        assertArrayEquals(dst6, refusal.copyOfRange(8, 24))
        assertArrayEquals(src6, refusal.copyOfRange(24, 40))
        assertEquals(refusal.size - 40, readShort(refusal, 4))
        assertArrayEquals(quic, refusal.copyOfRange(48, refusal.size), "a small packet is quoted whole")
        assertTrue(transportChecksumVerifies(refusal))

        // A large one is quoted only as far as the minimum IPv6 MTU allows (RFC 4443 §2.4).
        val big = ipv6(IpPackets.PROTO_UDP, udp(40000, 443, ByteArray(3000)))
        val cut = IpPackets.portUnreachable(big)!!
        assertEquals(1280, cut.size)
        assertArrayEquals(big.copyOfRange(0, 1232), cut.copyOfRange(48, 1280))
        assertTrue(transportChecksumVerifies(cut))
    }

    @Test
    fun `a ping to an IPv6 resolver is answered, and nothing else ICMPv6 is`() {
        val echo = ByteArray(17)
        echo[0] = 128.toByte() // echo request
        echo[4] = 0x12; echo[5] = 0x34
        echo[7] = 0x01
        val reply = IpPackets.echoReply(ipv6(IpPackets.PROTO_ICMPV6, echo))!!

        assertEquals(129, reply[40].toInt() and 0xFF, "an echo reply is type 129")
        assertEquals(0x12.toByte(), reply[44])
        assertEquals(0x34.toByte(), reply[45])
        assertEquals(0x01.toByte(), reply[47])
        assertArrayEquals(dst6, reply.copyOfRange(8, 24))
        assertTrue(transportChecksumVerifies(reply))
        val solicitation = ByteArray(16).also { it[0] = 135.toByte() }
        assertNull(IpPackets.echoReply(ipv6(IpPackets.PROTO_ICMPV6, solicitation)), "neighbour discovery")
    }

    @Test
    fun `an IPv6 TCP connection to a port this tunnel does not serve is reset`() {
        val syn = ByteArray(20)
        syn[0] = 0x9C.toByte(); syn[1] = 0x40
        syn[2] = 0x01; syn[3] = 0xBB.toByte() // 443
        syn[7] = 0x2A // seq 42
        syn[12] = 0x50
        syn[13] = 0x02
        val reset = IpPackets.tcpReset(ipv6(IpPackets.PROTO_TCP, syn))!!

        assertEquals(60, reset.size)
        assertEquals(IpPackets.PROTO_TCP, reset[6].toInt())
        assertEquals(20, readShort(reset, 4))
        assertEquals(0x14, reset[53].toInt() and 0xFF, "RST + ACK")
        assertEquals(43, readInt(reset, 48))
        assertArrayEquals(dst6, reset.copyOfRange(8, 24))
        assertTrue(transportChecksumVerifies(reset))
    }

    @Test
    fun `a TCP segment reads back as it was built, in both families`() {
        for ((from, to) in listOf(dst to src, dst6 to src6)) {
            val data = "hello".toByteArray()
            val packet = IpPackets.tcpPacket(
                source = from, sourcePort = 53, destination = to, destinationPort = 40000,
                sequence = -2, acknowledgement = 0x7FFFFFFF, flags = IpPackets.TCP_SYN or IpPackets.TCP_ACK,
                window = 16384, mss = 1400, data = data, dataOffset = 0, dataLength = data.size,
            )
            val segment = IpPackets.tcpSegment(packet)!!
            assertEquals(if (from.size == 4) 4 else 6, segment.version)
            assertArrayEquals(from, segment.source)
            assertArrayEquals(to, segment.destination)
            assertEquals(53, segment.sourcePort)
            assertEquals(40000, segment.destinationPort)
            assertEquals(-2, segment.sequence, "sequence numbers wrap and are kept as they are")
            assertEquals(0x7FFFFFFF, segment.acknowledgement)
            assertEquals(IpPackets.TCP_SYN or IpPackets.TCP_ACK, segment.flags)
            assertEquals(16384, segment.window)
            assertEquals(1400, segment.mss)
            assertArrayEquals(data, packet.copyOfRange(segment.dataStart, segment.dataEnd))
            assertTrue(transportChecksumVerifies(packet))
            if (from.size == 4) assertEquals(0, IpPackets.checksum(packet, 0, 20), "IPv4 header checksum")
        }
    }

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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun readShort(buf: ByteArray, at: Int): Int = ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun readInt(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)
}
