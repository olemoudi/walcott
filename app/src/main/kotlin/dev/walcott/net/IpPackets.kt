package dev.walcott.net

/**
 * The IPv4 frames this filter reads off its tun and writes back to it.
 *
 * Pure, and that is the point: everything here used to be inline in the packet loop, where the
 * only way to find out whether a reply was well formed was to watch an app fail to use it. What
 * a phone actually puts on a tun — a fragment, a header with options, a datagram whose length
 * fields disagree with the frame — is cheap to reproduce here and impossible to reproduce on a
 * device on demand.
 *
 * IPv4 only, deliberately and for now: the tun advertises a v4 address and routes one v4 /32,
 * so nothing else can arrive. What that costs is written down in the README.
 */
object IpPackets {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    /** The shortest frame that could carry a DNS header: 20 IP + 8 UDP + 12 DNS. */
    private const val MIN_DNS_FRAME = 40

    /** What a parsed frame is, as far as this filter cares. */
    data class Frame(
        val protocol: Int,
        /** Where the transport header starts (the IP header may carry options). */
        val transportStart: Int,
        /** Where the transport payload starts, and where it ends (clamped to the frame). */
        val payloadStart: Int,
        val payloadEnd: Int,
    )

    /**
     * Reads the IPv4 header, or null when the frame is one this filter must not act on.
     *
     * Refuses a fragment, which is the guard the hand-written version was missing: the first
     * fragment of a fragmented datagram carries a UDP header and a piece of a DNS message, and
     * forwarding that piece as if it were a whole query sends garbage upstream and answers the
     * app with nothing at all.
     */
    fun parse(packet: ByteArray, length: Int = packet.size): Frame? {
        if (length < 21) return null
        if ((packet[0].toInt() and 0xF0) shr 4 != 4) return null
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
     * Wraps [payload] in a fresh IPv4+UDP datagram going back the way [request] came.
     *
     * The UDP checksum is left zero, which IPv4 allows (RFC 768) and every stub resolver
     * accepts. It would have to be computed if this ever spoke IPv6, where zero is illegal.
     */
    fun udpResponse(request: ByteArray, payload: ByteArray): ByteArray? {
        val frame = parse(request) ?: return null
        if (frame.protocol != PROTO_UDP) return null
        val srcPort = ((request[frame.transportStart].toInt() and 0xFF) shl 8) or
            (request[frame.transportStart + 1].toInt() and 0xFF)
        val dstPort = ((request[frame.transportStart + 2].toInt() and 0xFF) shl 8) or
            (request[frame.transportStart + 3].toInt() and 0xFF)
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

    /**
     * An echo reply to an echo request, or null when [request] is not one.
     *
     * The tun routes the sentinel resolver and nothing else, so the only ping that can arrive is
     * one aimed at the phone's own DNS server — which is exactly what a connectivity check does
     * before deciding a network is dead. Swallowing it makes the phone conclude its resolver is
     * gone; answering costs one packet.
     */
    fun echoReply(request: ByteArray): ByteArray? {
        val frame = parse(request) ?: return null
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

    /**
     * A reset for a TCP segment that reached the tun, or null when [request] is not one.
     *
     * Nothing here speaks TCP, and what that used to mean was silence: a resolver falling back
     * to TCP — which is what every one of them does when a reply comes back truncated — sent a
     * SYN into the tunnel and waited out its connect timeout. A minute of an app looking hung,
     * for a question that was going to be answered "no" anyway. A reset ends it at once.
     */
    fun tcpReset(request: ByteArray): ByteArray? {
        val frame = parse(request) ?: return null
        if (frame.protocol != PROTO_TCP) return null
        val tcp = frame.transportStart
        if (tcp + 20 > frame.payloadEnd) return null
        val flags = request[tcp + 13].toInt() and 0xFF
        if (flags and 0x04 != 0) return null // already a reset; answering one is a loop
        val srcPort = ((request[tcp].toInt() and 0xFF) shl 8) or (request[tcp + 1].toInt() and 0xFF)
        val dstPort = ((request[tcp + 2].toInt() and 0xFF) shl 8) or (request[tcp + 3].toInt() and 0xFF)
        val seq = readInt(request, tcp + 4)
        val dataOffset = ((request[tcp + 12].toInt() and 0xF0) shr 4) * 4
        val payloadLength = (frame.payloadEnd - (tcp + dataOffset)).coerceAtLeast(0)
        // RFC 793 §3.4: a SYN counts as one, and the acknowledgement is what the sender would
        // send next.
        val synFin = (if (flags and 0x02 != 0) 1 else 0) + (if (flags and 0x01 != 0) 1 else 0)
        val ack = seq + payloadLength + synFin

        val total = 20 + 20
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64
        out[9] = PROTO_TCP.toByte()
        System.arraycopy(request, 16, out, 12, 4)
        System.arraycopy(request, 12, out, 16, 4)
        writeChecksum(out, 0, 20, 10)
        out[20] = (dstPort shr 8).toByte(); out[21] = dstPort.toByte()
        out[22] = (srcPort shr 8).toByte(); out[23] = srcPort.toByte()
        // An acknowledged reset carries the sequence the sender expects next; the segment we are
        // refusing had none of ours to acknowledge, so the sequence is zero and ACK is set.
        writeInt(out, 24, 0)
        writeInt(out, 28, ack)
        out[32] = 0x50 // data offset 5, no options
        out[33] = 0x14 // RST + ACK
        tcpChecksum(out)
        return out
    }

    /** The ones-complement checksum RFC 1071 defines, written into [into]. */
    private fun writeChecksum(buf: ByteArray, start: Int, len: Int, into: Int) {
        val sum = checksum(buf, start, len)
        buf[into] = (sum shr 8).toByte()
        buf[into + 1] = sum.toByte()
    }

    fun checksum(buf: ByteArray, start: Int, len: Int): Int {
        var sum = 0L
        var i = start
        var remaining = len
        while (remaining > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2; remaining -= 2
        }
        if (remaining == 1) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** TCP's checksum covers a pseudo-header of the addresses, the protocol and the length. */
    private fun tcpChecksum(packet: ByteArray) {
        val tcpLength = packet.size - 20
        val pseudo = ByteArray(12 + tcpLength)
        System.arraycopy(packet, 12, pseudo, 0, 8)
        pseudo[9] = PROTO_TCP.toByte()
        pseudo[10] = (tcpLength shr 8).toByte(); pseudo[11] = tcpLength.toByte()
        System.arraycopy(packet, 20, pseudo, 12, tcpLength)
        pseudo[12 + 16] = 0; pseudo[12 + 17] = 0
        val sum = checksum(pseudo, 0, pseudo.size)
        packet[20 + 16] = (sum shr 8).toByte()
        packet[20 + 17] = sum.toByte()
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
