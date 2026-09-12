package dev.walcott.net

/**
 * The DNS message, as much of it as a filter has to understand.
 *
 * Pure and byte-level, so the three things this app does to a DNS message — decide whether it is
 * a question at all, forge an answer to it, and recognise the reply that belongs to it — are
 * settled in tests rather than on a phone. Everything here takes the raw datagram and an offset,
 * because the packet loop already holds the IP frame and copying it again per query is the cost
 * this sits in the middle of.
 */
object DnsMessage {

    /** Bytes of DNS header before the first question. */
    const val HEADER_BYTES = 12

    /** Response codes this app either sets or reacts to. */
    const val RCODE_NO_ERROR = 0
    const val RCODE_SERVER_FAILURE = 2
    const val RCODE_NAME_ERROR = 3
    const val RCODE_REFUSED = 5

    /** The 16-bit id that ties a reply to its query. */
    fun transactionId(data: ByteArray, start: Int = 0): Int? {
        if (start + 2 > data.size) return null
        return ((data[start].toInt() and 0xFF) shl 8) or (data[start + 1].toInt() and 0xFF)
    }

    /** The reply's response code, or null when there is not even a header there. */
    fun rcode(data: ByteArray, start: Int = 0): Int? {
        if (start + 4 > data.size) return null
        return data[start + 3].toInt() and 0x0F
    }

    /**
     * Whether a resolver answered "I could not do this" rather than an answer.
     *
     * SERVFAIL and REFUSED only. NXDOMAIN is deliberately NOT in here: "that name does not
     * exist" is a real answer and the next resolver would only say the same thing more slowly.
     * A network whose first resolver refuses everything is common enough to be worth the second
     * attempt — it looks perfectly healthy with the filter off and resolves nothing with it on.
     */
    fun isServerFailure(rcode: Int?): Boolean = rcode == RCODE_SERVER_FAILURE || rcode == RCODE_REFUSED

    /**
     * Whether [data] from [start] is a question this filter should act on: a query (QR=0), a
     * standard one (OPCODE=0) and carrying at least one question.
     *
     * Checked before the name is read, because a name read out of something that is not a
     * question is a name nobody asked for — and it would go on to be matched against the rules
     * and shown to a parent as "this app looked up …".
     */
    fun isStandardQuery(data: ByteArray, start: Int = 0): Boolean {
        if (start + HEADER_BYTES > data.size) return false
        val flags = data[start + 2].toInt() and 0xFF
        if (flags and 0x80 != 0) return false // QR=1: this is a response
        if ((flags shr 3) and 0x0F != 0) return false // OPCODE != 0
        val qdCount = ((data[start + 4].toInt() and 0xFF) shl 8) or (data[start + 5].toInt() and 0xFF)
        return qdCount >= 1
    }

    /**
     * Where the first question ends (exclusive), or null when it does not parse.
     *
     * What the answer is truncated at: everything a query carries after its question — an EDNS
     * OPT record, most often — belongs to the query and not to a reply this app makes up.
     */
    fun questionEnd(data: ByteArray, start: Int = 0): Int? {
        var i = start + HEADER_BYTES
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                // The root label, then QTYPE and QCLASS.
                val end = i + 1 + 4
                return if (end <= data.size) end else null
            }
            // A compression pointer cannot appear in the question of a well-formed query.
            if (len and 0xC0 != 0) return null
            i += 1 + len
            if (i > data.size) return null
        }
        return null
    }

    /**
     * The hostname in the first question, lower-cased and without its trailing dot, or null when
     * it cannot be read.
     *
     * Refuses anything that is not a standard query first (see [isStandardQuery]), so the bytes
     * being walked really are a question.
     */
    fun questionName(data: ByteArray, start: Int = 0): String? {
        if (!isStandardQuery(data, start)) return null
        var i = start + HEADER_BYTES
        val name = StringBuilder()
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null
            i++
            if (i + len > data.size) return null
            for (j in 0 until len) name.append((data[i + j].toInt() and 0xFF).toChar())
            name.append('.')
            i += len
        }
        return name.toString().trimEnd('.').lowercase().ifEmpty { null }
    }

    /**
     * A reply to [query] carrying [rcode] and no records.
     *
     * Rebuilt rather than edited in place, which is what the two-byte version of this used to do.
     * The query's own counts said "one question, and an additional record" — the EDNS OPT every
     * modern resolver sends — and flipping two bits left both of those in a message that then
     * contained neither. A stub resolver that checks its sections against the body discards
     * that, and the app behaves as though the rule had not applied. So: the question is kept,
     * everything after it is dropped, and all four counts are written.
     */
    fun answer(query: ByteArray, start: Int, rcode: Int): ByteArray {
        val end = questionEnd(query, start) ?: (query.size)
        val out = query.copyOfRange(start, end)
        if (out.size < HEADER_BYTES) return out
        // QR=1, keep OPCODE and RD, drop AA/TC.
        out[2] = ((out[2].toInt() and 0x79) or 0x80).toByte()
        // RA=1, Z=0, and the code itself.
        out[3] = (0x80 or (rcode and 0x0F)).toByte()
        out[4] = 0; out[5] = 1 // QDCOUNT = 1
        out[6] = 0; out[7] = 0 // ANCOUNT
        out[8] = 0; out[9] = 0 // NSCOUNT
        out[10] = 0; out[11] = 0 // ARCOUNT — the query's OPT does not survive into a reply
        return out
    }

    /**
     * Whether [reply] is the answer to [query]: same transaction id, and an answer rather than
     * another question.
     *
     * The id check is not bookkeeping. The socket these arrive on used to be unconnected, so the
     * first datagram to reach the phone's ephemeral port was relayed to the app as the answer —
     * anyone on the same Wi-Fi could beat the real resolver to it and put an address of their
     * choosing in front of the child, including for a domain the family had blocked.
     */
    fun answersQuery(query: ByteArray, queryStart: Int, reply: ByteArray): Boolean {
        if (reply.size < HEADER_BYTES) return false
        val asked = transactionId(query, queryStart) ?: return false
        if (transactionId(reply) != asked) return false
        return reply[2].toInt() and 0x80 != 0
    }
}
