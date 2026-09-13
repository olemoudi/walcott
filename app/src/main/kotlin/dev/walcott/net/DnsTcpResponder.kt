package dev.walcott.net

import dev.walcott.net.IpPackets.TCP_ACK
import dev.walcott.net.IpPackets.TCP_FIN
import dev.walcott.net.IpPackets.TCP_PSH
import dev.walcott.net.IpPackets.TCP_RST
import dev.walcott.net.IpPackets.TCP_SYN

/**
 * DNS over TCP, answered inside the tunnel.
 *
 * Every address this tunnel routes is routed so DNS can be read, and TCP to one of them used to be
 * refused outright. Two ordinary things broke on that. A lookup whose answer came back truncated
 * is asked again over TCP by the resolver that asked — that is what TC means — so a long record or
 * a signed zone never resolved for an app that did its own DNS. And "is there internet?" libraries
 * (Flutter's data_connection_checker and its descendants among them) decide by opening a TCP
 * connection to 8.8.8.8:53 or 1.1.1.1:53: refused, the app told the child the phone was offline,
 * on a phone that was online.
 *
 * So a connection to port 53 is accepted, and each length-prefixed message on it (RFC 1035
 * §4.2.2, RFC 7766) is handed to the caller as a [Query] — which goes through the same rules,
 * curfew and forwarding as a datagram — and its answer comes back through [answer] as data
 * segments. TCP to any other port is still refused by [IpPackets.tcpReset]: DNS over TLS and
 * HTTPS to these resolvers is a thing this tunnel says no to, on purpose.
 *
 * Minimal, deliberately. The other end is this phone's own kernel across a tun, which in practice
 * neither loses nor reorders what it is handed, so there is no retransmission timer, no window
 * scaling and no option but the MSS. A segment that starts past a gap is not kept and not guessed
 * at: it is answered with an acknowledgement of where this side is, and the kernel resends from
 * there. What IS honoured exactly is the sequence numbers in both directions and the window the
 * app offers, because those are what a real stack silently throws data away over.
 *
 * Synchronized, because segments arrive on the tunnel's reader thread and answers on the IO pool,
 * and both touch the same connection. What a call returns must reach the tun in the order it was
 * returned, so the caller holds this object's monitor across the call AND the writes.
 */
class DnsTcpResponder(
    private val mtu: Int,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val idleMs: Long = IDLE_MS,
    private val initialSequence: () -> Int = { kotlin.random.Random.nextInt() },
) {

    /** One whole DNS message a connection delivered, and where its answer has to go back to. */
    class Query internal constructor(
        /** The DNS message alone, without its length prefix. */
        val message: ByteArray,
        /** The app's end of the connection, then the end it addressed — as its socket sees them. */
        val source: ByteArray,
        val sourcePort: Int,
        val destination: ByteArray,
        val destinationPort: Int,
        internal val connection: Long,
    )

    /** Segments to write to the tun, in this order, and the queries that arrived with them. */
    class Result(val packets: List<ByteArray>, val queries: List<Query>)

    private class Connection(
        val id: Long,
        val key: Key,
        /** The app. */
        val remote: ByteArray,
        val remotePort: Int,
        /** What the app addressed, which is who this side answers as. */
        val local: ByteArray,
        val localPort: Int,
        val iss: Int,
        val irs: Int,
        val sendMss: Int,
        var lastActivityMs: Long,
    ) {
        var established = false
        var sndUna = iss
        var sndNxt = iss + 1 // the SYN takes one number
        var rcvNxt = irs + 1
        var peerWindow = 0
        var inbound = ByteArray(INITIAL_INBOUND)
        var inboundSize = 0
        /** Framed answers not yet sent, and how far into the first one sending has got. */
        val outbound = ArrayDeque<ByteArray>()
        var outboundOffset = 0
        /** Queries handed out and not answered yet. */
        var pending = 0
        /** The app has sent its FIN; this side closes once everything owed has been sent. */
        var peerClosed = false
    }

    /** A connection is its four-tuple; the addresses are raw bytes, so equality is spelled out. */
    private class Key(private val tuple: ByteArray) {
        override fun equals(other: Any?) = other is Key && tuple.contentEquals(other.tuple)
        override fun hashCode() = tuple.contentHashCode()

        companion object {
            fun of(source: ByteArray, sourcePort: Int, destination: ByteArray, destinationPort: Int): Key {
                val tuple = ByteArray(source.size + destination.size + 4)
                source.copyInto(tuple)
                destination.copyInto(tuple, source.size)
                val ports = source.size + destination.size
                tuple[ports] = (sourcePort shr 8).toByte(); tuple[ports + 1] = sourcePort.toByte()
                tuple[ports + 2] = (destinationPort shr 8).toByte(); tuple[ports + 3] = destinationPort.toByte()
                return Key(tuple)
            }
        }
    }

    private val connections = LinkedHashMap<Key, Connection>()
    private var nextId = 0L

    /** Connections currently held, for tests and diagnostics. */
    val connectionCount: Int @Synchronized get() = connections.size

    /** A segment the app sent to port 53 of something this tunnel routes. */
    @Synchronized
    fun onSegment(packet: ByteArray, nowMs: Long): Result {
        val segment = IpPackets.tcpSegment(packet) ?: return NOTHING
        evictIdle(nowMs)
        val key = Key.of(segment.source, segment.sourcePort, segment.destination, segment.destinationPort)
        val flags = segment.flags
        val existing = connections[key]

        // The app gave up on it. Nothing is ever answered to a reset.
        if (flags and TCP_RST != 0) {
            connections.remove(key)
            return NOTHING
        }

        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            if (existing != null && !existing.established && existing.irs == segment.sequence) {
                // The same SYN again: the SYN-ACK was lost or is late, so it is sent again as it was.
                existing.lastActivityMs = nowMs
                return Result(listOf(synAck(existing)), emptyList())
            }
            // A new connection on a tuple still held here: the old one is over on the app's side.
            connections.remove(key)
            if (connections.size >= maxConnections) return refuse(packet)
            val ourMss = if (segment.version == 4) mtu - 40 else mtu - 60
            val connection = Connection(
                id = nextId++,
                key = key,
                remote = segment.source,
                remotePort = segment.sourcePort,
                local = segment.destination,
                localPort = segment.destinationPort,
                iss = initialSequence(),
                irs = segment.sequence,
                // What the app said it can take, or the RFC default when it did not say.
                sendMss = (segment.mss ?: if (segment.version == 4) DEFAULT_MSS_V4 else DEFAULT_MSS_V6)
                    .coerceIn(MIN_MSS, ourMss),
                lastActivityMs = nowMs,
            )
            connection.peerWindow = segment.window
            connections[key] = connection
            return Result(listOf(synAck(connection)), emptyList())
        }

        val connection = existing
            // A bare acknowledgement is what the app sends after this side's FIN, by which point
            // the connection is already forgotten here; a reset to that would only be noise.
            ?: return if (isBareAck(segment)) NOTHING else refuse(packet)
        // Past the handshake every segment carries ACK, and a client never sends a SYN-ACK.
        if (flags and TCP_ACK == 0 || flags and TCP_SYN != 0) return NOTHING
        connection.lastActivityMs = nowMs
        val out = ArrayList<ByteArray>()

        val acked = segment.acknowledgement - connection.sndUna
        if (acked > connection.sndNxt - connection.sndUna) {
            // Acknowledges bytes never sent (RFC 9293 §3.10.7.4): say where this side is, and drop it.
            return Result(listOf(control(connection, TCP_ACK)), emptyList())
        }
        if (acked >= 0) {
            // A negative count is an old duplicate: its data may still be new, its window is not.
            connection.sndUna = segment.acknowledgement
            connection.peerWindow = segment.window
            if (acked > 0) connection.established = true
        }
        if (!connection.established) return NOTHING

        val queries = ArrayList<Query>()
        val length = segment.dataEnd - segment.dataStart
        var acknowledge = false
        if (length > 0) {
            acknowledge = true
            // How many of this segment's bytes are already here. Between 0 and its length, the
            // rest is new; anything else is a retransmission of what we have, or data past a gap,
            // and neither is kept — the acknowledgement below tells the app where to resume.
            val already = connection.rcvNxt - segment.sequence
            if (already in 0 until length) {
                if (!receive(connection, packet, segment.dataStart + already, length - already, queries)) {
                    connections.remove(key)
                    return Result(listOf(control(connection, TCP_RST or TCP_ACK)), emptyList())
                }
            }
        }
        if (flags and TCP_FIN != 0) {
            acknowledge = true
            // Only a FIN that comes after every byte before it; one past a gap waits for the resend.
            if (!connection.peerClosed && segment.sequence + length == connection.rcvNxt) {
                connection.rcvNxt += 1
                connection.peerClosed = true
            }
        }
        flush(connection, out)
        // The FIN carries the acknowledgement, and so does every data segment.
        if (!closeIfDone(connection, out) && acknowledge && out.isEmpty()) out += control(connection, TCP_ACK)
        return Result(out, queries)
    }

    /**
     * The answer to [query], as the segments to write — or none when its connection has gone
     * (reset, closed, evicted) while the answer was being fetched.
     */
    @Synchronized
    fun answer(query: Query, message: ByteArray, nowMs: Long): List<ByteArray> {
        val key = Key.of(query.source, query.sourcePort, query.destination, query.destinationPort)
        val connection = connections[key]?.takeIf { it.id == query.connection } ?: return emptyList()
        if (connection.pending > 0) connection.pending--
        connection.lastActivityMs = nowMs
        if (message.size > 0xFFFF) {
            // Cannot be framed at all. Never produced by the caller; refused rather than cut.
            connections.remove(key)
            return listOf(control(connection, TCP_RST or TCP_ACK))
        }
        val framed = ByteArray(2 + message.size)
        framed[0] = (message.size shr 8).toByte(); framed[1] = message.size.toByte()
        message.copyInto(framed, 2)
        connection.outbound.addLast(framed)
        val out = ArrayList<ByteArray>()
        flush(connection, out)
        closeIfDone(connection, out)
        return out
    }

    /** Forgets every connection, for a tunnel that is going away. */
    @Synchronized
    fun clear() {
        connections.clear()
    }

    /**
     * Takes [count] new bytes into the connection and hands out every message they complete.
     * False when the connection has to be reset: a buffer the app overfilled, or a length prefix
     * too short to be a DNS message, after which nothing on the stream can be trusted to line up.
     */
    private fun receive(connection: Connection, packet: ByteArray, from: Int, count: Int, queries: MutableList<Query>): Boolean {
        if (connection.inboundSize + count > MAX_INBOUND) return false
        if (connection.inboundSize + count > connection.inbound.size) {
            connection.inbound = connection.inbound.copyOf(MAX_INBOUND)
        }
        System.arraycopy(packet, from, connection.inbound, connection.inboundSize, count)
        connection.inboundSize += count
        connection.rcvNxt += count
        // Two bytes of length, then that many bytes of message. Several can share a segment,
        // and one can arrive across many — including a split between the two length bytes.
        var start = 0
        while (connection.inboundSize - start >= 2) {
            val size = ((connection.inbound[start].toInt() and 0xFF) shl 8) or (connection.inbound[start + 1].toInt() and 0xFF)
            if (size < DnsMessage.HEADER_BYTES) return false
            if (connection.inboundSize - start - 2 < size) break
            queries += Query(
                message = connection.inbound.copyOfRange(start + 2, start + 2 + size),
                source = connection.remote,
                sourcePort = connection.remotePort,
                destination = connection.local,
                destinationPort = connection.localPort,
                connection = connection.id,
            )
            connection.pending++
            start += 2 + size
        }
        if (start > 0) {
            System.arraycopy(connection.inbound, start, connection.inbound, 0, connection.inboundSize - start)
            connection.inboundSize -= start
        }
        return true
    }

    /** Sends what is owed, no segment larger than the MSS and nothing past the app's window. */
    private fun flush(connection: Connection, out: MutableList<ByteArray>) {
        while (connection.outbound.isNotEmpty()) {
            val room = connection.peerWindow - (connection.sndNxt - connection.sndUna)
            // A closed window is reopened by the app's next acknowledgement, which calls this again.
            if (room <= 0) return
            val head = connection.outbound.first()
            val count = minOf(room, connection.sendMss, head.size - connection.outboundOffset)
            out += segment(connection, TCP_PSH or TCP_ACK, connection.sndNxt, head, connection.outboundOffset, count)
            connection.sndNxt += count
            connection.outboundOffset += count
            if (connection.outboundOffset == head.size) {
                connection.outbound.removeFirst()
                connection.outboundOffset = 0
            }
        }
    }

    /** The FIN, once the app has closed and everything it asked has been answered and sent. */
    private fun closeIfDone(connection: Connection, out: MutableList<ByteArray>): Boolean {
        if (!connection.peerClosed || connection.pending > 0 || connection.outbound.isNotEmpty()) return false
        out += control(connection, TCP_FIN or TCP_ACK)
        connections.remove(connection.key)
        return true
    }

    private fun evictIdle(nowMs: Long) {
        if (connections.isEmpty()) return
        connections.values.removeAll { nowMs - it.lastActivityMs > idleMs }
    }

    private fun isBareAck(segment: IpPackets.TcpSegment): Boolean =
        segment.flags and TCP_ACK != 0 && segment.flags and (TCP_SYN or TCP_FIN or TCP_RST) == 0 &&
            segment.dataEnd == segment.dataStart

    private fun refuse(packet: ByteArray): Result =
        IpPackets.tcpReset(packet)?.let { Result(listOf(it), emptyList()) } ?: NOTHING

    private fun synAck(connection: Connection): ByteArray = IpPackets.tcpPacket(
        source = connection.local, sourcePort = connection.localPort,
        destination = connection.remote, destinationPort = connection.remotePort,
        sequence = connection.iss, acknowledgement = connection.rcvNxt, flags = TCP_SYN or TCP_ACK,
        window = window(connection),
        mss = if (connection.local.size == 4) mtu - 40 else mtu - 60,
    )

    private fun control(connection: Connection, flags: Int): ByteArray =
        segment(connection, flags, connection.sndNxt, EMPTY, 0, 0)

    private fun segment(connection: Connection, flags: Int, sequence: Int, data: ByteArray, offset: Int, count: Int) =
        IpPackets.tcpPacket(
            source = connection.local, sourcePort = connection.localPort,
            destination = connection.remote, destinationPort = connection.remotePort,
            sequence = sequence, acknowledgement = connection.rcvNxt, flags = flags,
            window = window(connection), data = data, dataOffset = offset, dataLength = count,
        )

    /** What this side can still take: the room left in the buffer a message is assembled in. */
    private fun window(connection: Connection): Int = MAX_INBOUND - connection.inboundSize

    companion object {
        /** Concurrent connections held; a SYN past this is refused with a reset. */
        const val MAX_CONNECTIONS = 32

        /** A connection nothing has happened on for this long is forgotten. */
        const val IDLE_MS = 30_000L

        /**
         * The most this side buffers of an unfinished message. A DNS query is a few hundred
         * bytes; an app that sends this much without completing one is not asking DNS.
         */
        const val MAX_INBOUND = 16_384
        private const val INITIAL_INBOUND = 1_024

        /** RFC 9293 §3.7.1 and RFC 8200 §5: what may be assumed of a peer that names no MSS. */
        private const val DEFAULT_MSS_V4 = 536
        private const val DEFAULT_MSS_V6 = 1_220

        /** A floor under an absurd MSS, so an answer is never sent a byte at a time. */
        private const val MIN_MSS = 64

        private val EMPTY = ByteArray(0)
        private val NOTHING = Result(emptyList(), emptyList())
    }
}
