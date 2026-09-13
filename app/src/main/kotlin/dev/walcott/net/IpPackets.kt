package dev.walcott.net

/**
 * The IPv4 and IPv6 frames this filter reads off its tun and writes back to it.
 *
 * Pure, and that is the point: everything here used to be inline in the packet loop, where the
 * only way to find out whether a reply was well formed was to watch an app fail to use it. What
 * a phone actually puts on a tun — a fragment, a header with options, a datagram whose length
 * fields disagree with the frame — is cheap to reproduce here and impossible to reproduce on a
 * device on demand.
 *
 * IPv6 arrives because the tun has an IPv6 address and routes the public resolvers' IPv6
 * addresses (see [PublicResolvers]). Its extension headers are NOT walked: nothing sends DNS, a
 * TCP connection to a resolver or a ping with one, and declining a frame is safe where guessing at
 * its layout is not. What the kernel sends of its own accord on a tun with an IPv6 address —
 * multicast listener reports behind a hop-by-hop header, neighbour discovery — is declined here
 * and dropped without a word by the loop.
 */
object IpPackets {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMPV6 = 58

    /** The shortest frame that could carry a DNS header: 20 IP + 8 UDP + 12 DNS. */
    private const val MIN_DNS_FRAME = 40

    private const val IPV6_HEADER_BYTES = 40

    /** ICMP: type 3 code 3, and the eight bytes of header before the quote. */
    private const val ICMP_UNREACHABLE = 3
    private const val ICMP_PORT_UNREACHABLE = 3
    private const val ICMP_HEADER_BYTES = 8

    /** ICMPv6 (RFC 4443): destination unreachable is type 1, port unreachable its code 4. */
    private const val ICMPV6_UNREACHABLE = 1
    private const val ICMPV6_PORT_UNREACHABLE = 4
    private const val ICMPV6_ECHO_REQUEST = 128
    private const val ICMPV6_ECHO_REPLY = 129

    /** RFC 4443 §2.4(c): an error quotes as much of the invoking packet as fits the minimum MTU. */
    private const val ICMPV6_QUOTE_MAX = 1280 - IPV6_HEADER_BYTES - ICMP_HEADER_BYTES

    /** TCP flags, as the byte at offset 13 of the header carries them. */
    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    /** What a parsed frame is, as far as this filter cares. */
    data class Frame(
        val protocol: Int,
        /** Where the transport header starts (the IP header may carry options). */
        val transportStart: Int,
        /** Where the transport payload starts, and where it ends (clamped to the frame). */
        val payloadStart: Int,
        val payloadEnd: Int,
        /** 4 or 6. */
        val version: Int = 4,
    )

    /**
     * Reads the IP header, or null when the frame is one this filter must not act on.
     *
     * Refuses a fragment, which is the guard the hand-written version was missing: the first
     * fragment of a fragmented datagram carries a UDP header and a piece of a DNS message, and
     * forwarding that piece as if it were a whole query sends garbage upstream and answers the
     * app with nothing at all. In IPv6 a fragment is an extension header, and so is refused with
     * every other one (see the class comment).
     */
    fun parse(packet: ByteArray, length: Int = packet.size): Frame? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    private fun parseV4(packet: ByteArray, length: Int): Frame? {
        if (length < 21) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > length) return null
        // Fragment offset in the low 13 bits, and MF in bit 5 of the byte before it.
        val fragmentOffset = (((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF))
        val moreFragments = packet[6].toInt() and 0x20 != 0
        if (fragmentOffset != 0 || moreFragments) return null
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        // The frame is the authority when the header claims more than arrived.
        val end = if (totalLength in ihl..length) totalLength else length
        val protocol = packet[9].toInt() and 0xFF
        val payloadStart = when (protocol) {
            PROTO_UDP -> ihl + 8
            else -> ihl
        }
        if (payloadStart > end) return null
        return Frame(protocol, ihl, payloadStart, end)
    }

    private fun parseV6(packet: ByteArray, length: Int): Frame? {
        if (length < IPV6_HEADER_BYTES) return null
        val claimed = IPV6_HEADER_BYTES + readShort(packet, 4)
        // The frame is the authority when the header claims more than arrived, as for IPv4.
        val end = if (claimed <= length) claimed else length
        // The next header is the transport or the frame is declined: a hop-by-hop header (the
        // kernel's own listener reports), a fragment header, a routing header — none is walked.
        val protocol = packet[6].toInt() and 0xFF
        if (protocol != PROTO_UDP && protocol != PROTO_TCP && protocol != PROTO_ICMPV6) return null
        val payloadStart = if (protocol == PROTO_UDP) IPV6_HEADER_BYTES + 8 else IPV6_HEADER_BYTES
        if (payloadStart > end) return null
        return Frame(protocol, IPV6_HEADER_BYTES, payloadStart, end, version = 6)
    }

    /**
     * Where the DNS message starts in a UDP frame that could carry one, or null.
     *
     * The UDP length field is honoured rather than the frame's, clamped to what actually
     * arrived: a datagram whose header claims more than it carries must not have the slack read
     * as part of the question.
     */
    fun dnsStart(packet: ByteArray, length: Int = packet.size): Int? {
        if (length < MIN_DNS_FRAME) return null
        val frame = parse(packet, length) ?: return null
        if (frame.protocol != PROTO_UDP) return null
        if (frame.payloadStart + DnsMessage.HEADER_BYTES > frame.payloadEnd) return null
        return frame.payloadStart
    }

    /** The end of the DNS message in a UDP frame, honouring the UDP length field. */
    fun dnsEnd(packet: ByteArray, length: Int = packet.size): Int {
        val frame = parse(packet, length) ?: return length
        val udpLength = ((packet[frame.transportStart + 4].toInt() and 0xFF) shl 8) or
            (packet[frame.transportStart + 5].toInt() and 0xFF)
        val claimed = frame.transportStart + udpLength
        return if (udpLength >= 8 && claimed in frame.payloadStart..frame.payloadEnd) claimed else frame.payloadEnd
    }

    /** The source port of a UDP frame. */
    fun sourcePort(packet: ByteArray): Int? {
        val frame = parse(packet) ?: return null
        if (frame.protocol != PROTO_UDP) return null
        return ((packet[frame.transportStart].toInt() and 0xFF) shl 8) or
            (packet[frame.transportStart + 1].toInt() and 0xFF)
    }

    /**
     * The destination port of a UDP frame.
     *
     * Worth its own function because nothing used to ask. Every UDP datagram that reached the tun
     * was read as a DNS query whatever port it was addressed to, so a QUIC or HTTP/3 handshake
     * aimed at the sentinel had its bytes walked as a question and then sent on to a real resolver
     * on port 53.
     */
    fun destinationPort(packet: ByteArray, length: Int = packet.size): Int? {
        val frame = parse(packet, length) ?: return null
        if (frame.protocol != PROTO_UDP) return null
        return ((packet[frame.transportStart + 2].toInt() and 0xFF) shl 8) or
            (packet[frame.transportStart + 3].toInt() and 0xFF)
    }

    /**
     * Who sent [packet] and whom it was sent to, as raw addresses (four bytes or sixteen), or null.
     *
     * What attribution asks the platform about: the app's socket is identified by BOTH ends, and a
     * query sent straight to a routed public resolver is not a query to the sentinel.
     */
    fun sourceAddress(packet: ByteArray): ByteArray? {
        val frame = parse(packet) ?: return null
        return if (frame.version == 4) packet.copyOfRange(12, 16) else packet.copyOfRange(8, 24)
    }

    fun destinationAddress(packet: ByteArray): ByteArray? {
        val frame = parse(packet) ?: return null
        return if (frame.version == 4) packet.copyOfRange(16, 20) else packet.copyOfRange(24, 40)
    }

    /**
     * Wraps [payload] in a fresh UDP datagram going back the way [request] came.
     *
     * Over IPv4 the UDP checksum is left zero, which RFC 768 allows and every stub resolver
     * accepts. Over IPv6 zero is illegal (RFC 8200 §8.1) and the kernel drops the datagram, so
     * there it is computed over the pseudo-header.
     */
    fun udpResponse(request: ByteArray, payload: ByteArray): ByteArray? {
        val frame = parse(request) ?: return null
        if (frame.protocol != PROTO_UDP) return null
        val srcPort = ((request[frame.transportStart].toInt() and 0xFF) shl 8) or
            (request[frame.transportStart + 1].toInt() and 0xFF)
        val dstPort = ((request[frame.transportStart + 2].toInt() and 0xFF) shl 8) or
            (request[frame.transportStart + 3].toInt() and 0xFF)
        if (frame.version == 6) return udpResponseV6(request, srcPort, dstPort, payload)
        val total = 20 + 8 + payload.size
        if (total > 0xFFFF) return null
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        System.arraycopy(request, 16, out, 12, 4) // src = whoever the query was sent to
        System.arraycopy(request, 12, out, 16, 4) // dst = whoever sent it
        writeChecksum(out, 0, 20, 10)
        out[20] = (dstPort shr 8).toByte(); out[21] = dstPort.toByte()
        out[22] = (srcPort shr 8).toByte(); out[23] = srcPort.toByte()
        val udpLen = 8 + payload.size
        out[24] = (udpLen shr 8).toByte(); out[25] = udpLen.toByte()
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    private fun udpResponseV6(request: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray? {
        val udpLength = 8 + payload.size
        if (udpLength > 0xFFFF) return null
        val out = ByteArray(IPV6_HEADER_BYTES + udpLength)
        writeIpv6Header(out, from = request.copyOfRange(24, 40), to = request.copyOfRange(8, 24), PROTO_UDP, udpLength)
        val udp = IPV6_HEADER_BYTES
        writeShort(out, udp, dstPort)
        writeShort(out, udp + 2, srcPort)
        writeShort(out, udp + 4, udpLength)
        System.arraycopy(payload, 0, out, udp + 8, payload.size)
        val sum = transportChecksum(out, 6, PROTO_UDP, udp, udpLength)
        // A computed zero is sent as its other ones-complement spelling: zero means "none".
        writeShort(out, udp + 6, if (sum == 0) 0xFFFF else sum)
        return out
    }

    /**
     * An echo reply to an echo request, or null when [request] is not one.
     *
     * Everything the tun routes is a resolver's address, so the only ping that can arrive is one
     * aimed at a DNS server — which is exactly what a connectivity check does before deciding a
     * network is dead. Swallowing it makes the phone conclude its resolver is gone; answering
     * costs one packet.
     */
    fun echoReply(request: ByteArray): ByteArray? {
        val frame = parse(request) ?: return null
        if (frame.version == 6) return echoReplyV6(request, frame)
        if (frame.protocol != PROTO_ICMP) return null
        val icmp = frame.transportStart
        if (icmp + 8 > frame.payloadEnd) return null
        if (request[icmp].toInt() and 0xFF != 8) return null // not an echo request
        val body = request.copyOfRange(icmp, frame.payloadEnd)
        val total = 20 + body.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = PROTO_ICMP.toByte()
        System.arraycopy(request, 16, out, 12, 4)
        System.arraycopy(request, 12, out, 16, 4)
        writeChecksum(out, 0, 20, 10)
        System.arraycopy(body, 0, out, 20, body.size)
        out[20] = 0 // echo reply
        out[22] = 0; out[23] = 0
        writeChecksum(out, 20, body.size, 22)
        return out
    }

    /** The same over ICMPv6, whose checksum, unlike ICMP's, covers a pseudo-header (RFC 4443 §2.3). */
    private fun echoReplyV6(request: ByteArray, frame: Frame): ByteArray? {
        if (frame.protocol != PROTO_ICMPV6) return null
        val icmp = frame.transportStart
        if (icmp + ICMP_HEADER_BYTES > frame.payloadEnd) return null
        if (request[icmp].toInt() and 0xFF != ICMPV6_ECHO_REQUEST) return null
        val body = request.copyOfRange(icmp, frame.payloadEnd)
        val out = ByteArray(IPV6_HEADER_BYTES + body.size)
        writeIpv6Header(out, from = request.copyOfRange(24, 40), to = request.copyOfRange(8, 24), PROTO_ICMPV6, body.size)
        System.arraycopy(body, 0, out, IPV6_HEADER_BYTES, body.size)
        out[IPV6_HEADER_BYTES] = ICMPV6_ECHO_REPLY.toByte()
        out[IPV6_HEADER_BYTES + 2] = 0; out[IPV6_HEADER_BYTES + 3] = 0
        writeShort(out, IPV6_HEADER_BYTES + 2, transportChecksum(out, 6, PROTO_ICMPV6, IPV6_HEADER_BYTES, body.size))
        return out
    }

    /**
     * A reset for a TCP segment that reached the tun, or null when [request] is not one.
     *
     * TCP to anything but port 53 is not served here (see [DnsTcpResponder]), and what that used
     * to mean was silence: a SYN into the tunnel and a connect timeout waited out. A minute of an
     * app looking hung, for a question that was going to be answered "no" anyway. A reset ends
     * it at once.
     */
    fun tcpReset(request: ByteArray): ByteArray? {
        val frame = parse(request) ?: return null
        if (frame.protocol != PROTO_TCP) return null
        val tcp = frame.transportStart
        if (tcp + 20 > frame.payloadEnd) return null
        val flags = request[tcp + 13].toInt() and 0xFF
        if (flags and TCP_RST != 0) return null // already a reset; answering one is a loop
        val srcPort = ((request[tcp].toInt() and 0xFF) shl 8) or (request[tcp + 1].toInt() and 0xFF)
        val dstPort = ((request[tcp + 2].toInt() and 0xFF) shl 8) or (request[tcp + 3].toInt() and 0xFF)
        val seq = readInt(request, tcp + 4)
        val dataOffset = ((request[tcp + 12].toInt() and 0xF0) shr 4) * 4
        val payloadLength = (frame.payloadEnd - (tcp + dataOffset)).coerceAtLeast(0)
        // RFC 793 §3.4, and the numbers are the whole point: a peer that reads a reset whose
        // sequence is outside its window IGNORES it and goes back to waiting, which is exactly
        // the minute-long hang this is here to end.
        //
        // Two shapes. An unacknowledged segment (a bare SYN, which is all this tunnel ever really
        // sees) is refused with RST+ACK: our sequence is nothing, and we acknowledge what the
        // sender would send next — a SYN and a FIN each counting as one byte of sequence space.
        // A segment that already carries an acknowledgement is refused with a bare RST whose
        // sequence is the number the sender said it was expecting; there is nothing of ours left
        // to acknowledge, and setting ACK with a zero sequence is what gets a reset discarded.
        val acknowledged = flags and TCP_ACK != 0
        val synFin = (if (flags and TCP_SYN != 0) 1 else 0) + (if (flags and TCP_FIN != 0) 1 else 0)
        val sequence = if (acknowledged) readInt(request, tcp + 8) else 0
        val ack = if (acknowledged) 0 else seq + payloadLength + synFin
        val resetFlags = if (acknowledged) TCP_RST else TCP_RST or TCP_ACK
        val (to, from) = addressesOf(request, frame)
        return tcpPacket(
            source = from, sourcePort = dstPort, destination = to, destinationPort = srcPort,
            sequence = sequence, acknowledgement = ack, flags = resetFlags, window = 0,
        )
    }

    /** A TCP segment as [DnsTcpResponder] needs to read it. Sequence numbers wrap, as TCP's do. */
    class TcpSegment(
        val version: Int,
        /** The app's end, then the end it addressed. */
        val source: ByteArray,
        val sourcePort: Int,
        val destination: ByteArray,
        val destinationPort: Int,
        val sequence: Int,
        val acknowledgement: Int,
        val flags: Int,
        val window: Int,
        /** The maximum segment size option, when the segment carries one (a SYN does). */
        val mss: Int?,
        /** Where the segment's data is in the packet it was read from. */
        val dataStart: Int,
        val dataEnd: Int,
    )

    /** Reads a TCP segment, or null when [packet] is not one whose header is all there. */
    fun tcpSegment(packet: ByteArray, length: Int = packet.size): TcpSegment? {
        val frame = parse(packet, length) ?: return null
        if (frame.protocol != PROTO_TCP) return null
        val tcp = frame.transportStart
        if (tcp + 20 > frame.payloadEnd) return null
        val dataOffset = ((packet[tcp + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOffset < 20 || tcp + dataOffset > frame.payloadEnd) return null
        val (source, destination) = addressesOf(packet, frame)
        return TcpSegment(
            version = frame.version,
            source = source,
            sourcePort = readShort(packet, tcp),
            destination = destination,
            destinationPort = readShort(packet, tcp + 2),
            sequence = readInt(packet, tcp + 4),
            acknowledgement = readInt(packet, tcp + 8),
            flags = packet[tcp + 13].toInt() and 0xFF,
            window = readShort(packet, tcp + 14),
            mss = mssOption(packet, tcp + 20, tcp + dataOffset),
            dataStart = tcp + dataOffset,
            dataEnd = frame.payloadEnd,
        )
    }

    /** The MSS option (kind 2, length 4) among the options in [from]..[to], or null. */
    private fun mssOption(packet: ByteArray, from: Int, to: Int): Int? {
        var i = from
        while (i < to) {
            val kind = packet[i].toInt() and 0xFF
            if (kind == 0) return null // end of options
            if (kind == 1) { i++; continue } // no-op padding
            if (i + 1 >= to) return null
            val size = packet[i + 1].toInt() and 0xFF
            if (size < 2 || i + size > to) return null
            if (kind == 2 && size == 4) return readShort(packet, i + 2)
            i += size
        }
        return null
    }

    /**
     * A TCP segment in a fresh IPv4 or IPv6 packet — which one follows from the length of the
     * addresses — with both checksums written. [mss] adds the one option this tunnel ever sends,
     * on a SYN-ACK.
     */
    fun tcpPacket(
        source: ByteArray,
        sourcePort: Int,
        destination: ByteArray,
        destinationPort: Int,
        sequence: Int,
        acknowledgement: Int,
        flags: Int,
        window: Int,
        mss: Int? = null,
        data: ByteArray = EMPTY,
        dataOffset: Int = 0,
        dataLength: Int = 0,
    ): ByteArray {
        val v6 = source.size == 16
        val ip = if (v6) IPV6_HEADER_BYTES else 20
        val header = if (mss != null) 24 else 20
        val tcpLength = header + dataLength
        val out = ByteArray(ip + tcpLength)
        if (v6) writeIpv6Header(out, source, destination, PROTO_TCP, tcpLength) else writeIpv4Header(out, source, destination, PROTO_TCP)
        writeShort(out, ip, sourcePort)
        writeShort(out, ip + 2, destinationPort)
        writeInt(out, ip + 4, sequence)
        writeInt(out, ip + 8, acknowledgement)
        out[ip + 12] = ((header / 4) shl 4).toByte()
        out[ip + 13] = flags.toByte()
        writeShort(out, ip + 14, window)
        if (mss != null) {
            out[ip + 20] = 2; out[ip + 21] = 4
            writeShort(out, ip + 22, mss)
        }
        System.arraycopy(data, dataOffset, out, ip + header, dataLength)
        writeShort(out, ip + 16, transportChecksum(out, if (v6) 6 else 4, PROTO_TCP, ip, tcpLength))
        return out
    }

    /**
     * A port-unreachable for a UDP datagram this tunnel routes but does not serve, or null.
     *
     * The twin of [tcpReset], and the same lesson: everything routed here is routed so that DNS
     * can be read, and a datagram to any other port used to vanish. Silence is the worst answer a
     * tunnel can give. A connectionless protocol has no way to tell it from a slow network, so the
     * asker waits out its whole timeout before trying anything else — which for DNS over QUIC or
     * HTTP/3 is seconds of an app that looks hung, per attempt.
     *
     * The addresses are reversed so the error appears to come from the host the app addressed.
     * That is not cosmetic: the kernel matches an ICMP error to a socket by the quoted headers and
     * the outer source address together, and only a match becomes `ECONNREFUSED` on a connected
     * UDP socket. Get it wrong and the datagram is discarded, which is where we started.
     *
     * RFC 792 for IPv4: type 3, code 3, four unused bytes, then the offending IP header and the
     * first eight bytes after it — the ports, the length and the checksum, which is what the
     * kernel needs to find the socket. There is no pseudo-header in an ICMPv4 checksum. RFC 4443
     * for IPv6: type 1, code 4, as much of the offending packet as fits the minimum MTU, and a
     * checksum that does cover a pseudo-header.
     */
    fun portUnreachable(request: ByteArray, length: Int = request.size): ByteArray? {
        val frame = parse(request, length) ?: return null
        if (frame.protocol != PROTO_UDP) return null
        if (frame.version == 6) return portUnreachableV6(request, frame)
        // The quote is the header as it arrived, options and all, plus eight bytes of datagram.
        val quoted = minOf(frame.payloadEnd, frame.transportStart + 8)
        if (quoted <= frame.transportStart) return null
        val message = ByteArray(ICMP_HEADER_BYTES + quoted)
        message[0] = ICMP_UNREACHABLE.toByte()
        message[1] = ICMP_PORT_UNREACHABLE.toByte()
        System.arraycopy(request, 0, message, ICMP_HEADER_BYTES, quoted)
        val total = 20 + message.size
        if (total > 0xFFFF) return null
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = PROTO_ICMP.toByte()
        System.arraycopy(request, 16, out, 12, 4) // from the host the app addressed
        System.arraycopy(request, 12, out, 16, 4) // to whoever sent it
        writeChecksum(out, 0, 20, 10)
        System.arraycopy(message, 0, out, 20, message.size)
        writeChecksum(out, 20, message.size, 22)
        return out
    }

    private fun portUnreachableV6(request: ByteArray, frame: Frame): ByteArray {
        val quoted = minOf(frame.payloadEnd, ICMPV6_QUOTE_MAX)
        val message = ByteArray(ICMP_HEADER_BYTES + quoted)
        message[0] = ICMPV6_UNREACHABLE.toByte()
        message[1] = ICMPV6_PORT_UNREACHABLE.toByte()
        System.arraycopy(request, 0, message, ICMP_HEADER_BYTES, quoted)
        val out = ByteArray(IPV6_HEADER_BYTES + message.size)
        writeIpv6Header(out, from = request.copyOfRange(24, 40), to = request.copyOfRange(8, 24), PROTO_ICMPV6, message.size)
        System.arraycopy(message, 0, out, IPV6_HEADER_BYTES, message.size)
        writeShort(out, IPV6_HEADER_BYTES + 2, transportChecksum(out, 6, PROTO_ICMPV6, IPV6_HEADER_BYTES, message.size))
        return out
    }

    /** The ones-complement checksum RFC 1071 defines, written into [into]. */
    private fun writeChecksum(buf: ByteArray, start: Int, len: Int, into: Int) {
        val sum = checksum(buf, start, len)
        buf[into] = (sum shr 8).toByte()
        buf[into + 1] = sum.toByte()
    }

    fun checksum(buf: ByteArray, start: Int, len: Int): Int = fold(sum(buf, start, len))

    /**
     * The checksum TCP, UDP and ICMPv6 carry: over a pseudo-header of the two addresses, the
     * protocol and the length (RFC 793, RFC 8200 §8.1), then the segment itself, whose own
     * checksum field must still be zero. The pseudo-header's words are added rather than laid out
     * in a buffer; a ones-complement sum does not care about their order.
     */
    private fun transportChecksum(packet: ByteArray, version: Int, protocol: Int, start: Int, length: Int): Int {
        val addresses = if (version == 4) sum(packet, 12, 8) else sum(packet, 8, 32)
        return fold(addresses + protocol + length + sum(packet, start, length))
    }

    /** The unfolded sum of [len] bytes from [start] as big-endian 16-bit words, odd byte padded. */
    private fun sum(buf: ByteArray, start: Int, len: Int): Long {
        var sum = 0L
        var i = start
        var remaining = len
        while (remaining > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2; remaining -= 2
        }
        if (remaining == 1) sum += (buf[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun fold(total: Long): Int {
        var sum = total
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** A minimal IPv4 header at the start of [out], sized to all of it and checksummed. */
    private fun writeIpv4Header(out: ByteArray, from: ByteArray, to: ByteArray, protocol: Int) {
        out[0] = 0x45
        writeShort(out, 2, out.size)
        out[8] = 64
        out[9] = protocol.toByte()
        System.arraycopy(from, 0, out, 12, 4)
        System.arraycopy(to, 0, out, 16, 4)
        writeChecksum(out, 0, 20, 10)
    }

    /** A fixed IPv6 header with no traffic class, no flow label and a hop limit of 64. */
    private fun writeIpv6Header(out: ByteArray, from: ByteArray, to: ByteArray, nextHeader: Int, payloadLength: Int) {
        out[0] = 0x60
        writeShort(out, 4, payloadLength)
        out[6] = nextHeader.toByte()
        out[7] = 64
        System.arraycopy(from, 0, out, 8, 16)
        System.arraycopy(to, 0, out, 24, 16)
    }

    /** Source and destination of a parsed frame, in that order. */
    private fun addressesOf(packet: ByteArray, frame: Frame): Pair<ByteArray, ByteArray> =
        if (frame.version == 4) {
            packet.copyOfRange(12, 16) to packet.copyOfRange(16, 20)
        } else {
            packet.copyOfRange(8, 24) to packet.copyOfRange(24, 40)
        }

    private val EMPTY = ByteArray(0)

    private fun readShort(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun writeShort(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value shr 8).toByte()
        buf[at + 1] = value.toByte()
    }

    private fun readInt(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)

    private fun writeInt(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value shr 24).toByte()
        buf[at + 1] = (value shr 16).toByte()
        buf[at + 2] = (value shr 8).toByte()
        buf[at + 3] = value.toByte()
    }
}
