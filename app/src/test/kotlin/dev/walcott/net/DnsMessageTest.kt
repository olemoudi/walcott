package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three things this filter does to a DNS message: decide it is a question, forge an answer
 * to it, and recognise the reply that belongs to it.
 *
 * None of this had a test before. The bugs it pins are the kind nobody sees by using the app —
 * a reply whose section counts disagree with its body is discarded by some stub resolvers and
 * accepted by others, so the rule appears to work on one phone and not on the next.
 */
class DnsMessageTest {

    /** A query for [name], with the header bits a real resolver sends. */
    private fun query(
        name: String = "ads.example.com",
        id: Int = 0x1234,
        flags: Int = 0x0100, // RD
        qdCount: Int = 1,
        arCount: Int = 0,
        trailing: ByteArray = ByteArray(0),
    ): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        val body = ArrayList<Byte>()
        body += (id shr 8).toByte(); body += id.toByte()
        body += (flags shr 8).toByte(); body += flags.toByte()
        body += (qdCount shr 8).toByte(); body += qdCount.toByte()
        body += 0; body += 0 // ANCOUNT
        body += 0; body += 0 // NSCOUNT
        body += (arCount shr 8).toByte(); body += arCount.toByte()
        labels.forEach { label ->
            body += label.length.toByte()
            label.forEach { body += it.code.toByte() }
        }
        body += 0 // root
        body += 0; body += 1 // QTYPE A
        body += 0; body += 1 // QCLASS IN
        body += trailing.toTypedArray()
        return body.toByteArray()
    }

    @Test
    fun `a question is read, lower-cased and without its trailing dot`() {
        assertEquals("ads.example.com", DnsMessage.questionName(query("Ads.Example.COM")))
    }

    @Test
    fun `a response is not a question, whatever its bytes spell`() {
        // The guard that matters: without it a reply, or an opcode nobody handles, has its
        // bytes walked as though they were a name — and that name reaches the rules and the
        // parent's "what did this app look up" screen.
        assertFalse(DnsMessage.isStandardQuery(query(flags = 0x8180)))
        assertNull(DnsMessage.questionName(query(flags = 0x8180)))
    }

    @Test
    fun `an update or a notify is not a standard query`() {
        assertFalse(DnsMessage.isStandardQuery(query(flags = 0x2800))) // OPCODE 5, UPDATE
        assertNull(DnsMessage.questionName(query(flags = 0x2800)))
    }

    @Test
    fun `a query that carries no question at all is refused`() {
        // QDCOUNT 0 with an additional record is a real shape — an EDNS-only probe — and its
        // additional section used to be read as a hostname.
        assertFalse(DnsMessage.isStandardQuery(query(qdCount = 0)))
        assertNull(DnsMessage.questionName(query(qdCount = 0)))
    }

    @Test
    fun `a truncated name does not run off the end`() {
        val full = query()
        for (cut in DnsMessage.HEADER_BYTES until full.size) {
            // Nothing here may throw, whatever arrives on the tun.
            DnsMessage.questionName(full.copyOf(cut))
            DnsMessage.questionEnd(full.copyOf(cut))
        }
    }

    @Test
    fun `a compression pointer in the question is refused rather than followed`() {
        val bytes = query()
        bytes[DnsMessage.HEADER_BYTES] = 0xC0.toByte()
        assertNull(DnsMessage.questionName(bytes))
    }

    @Test
    fun `the answer keeps the question and drops everything after it`() {
        // A real query carries an EDNS OPT record in its additional section. A reply that echoes
        // it back is not an EDNS reply, and its ARCOUNT would claim a record the body no longer
        // has — which is what a strict stub resolver throws away.
        val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
        val q = query(arCount = 1, trailing = opt)
        val answer = DnsMessage.answer(q, 0, DnsMessage.RCODE_NAME_ERROR)

        assertEquals(DnsMessage.questionEnd(q), answer.size, "the OPT record survived into the reply")
        assertEquals(0x1234, DnsMessage.transactionId(answer))
        assertEquals(DnsMessage.RCODE_NAME_ERROR, DnsMessage.rcode(answer))
        assertTrue(answer[2].toInt() and 0x80 != 0, "QR must say this is a response")
        assertTrue(answer[3].toInt() and 0x80 != 0, "RA must be set")
        assertEquals(1, ((answer[4].toInt() and 0xFF) shl 8) or (answer[5].toInt() and 0xFF), "QDCOUNT")
        assertEquals(0, ((answer[6].toInt() and 0xFF) shl 8) or (answer[7].toInt() and 0xFF), "ANCOUNT")
        assertEquals(0, ((answer[8].toInt() and 0xFF) shl 8) or (answer[9].toInt() and 0xFF), "NSCOUNT")
        assertEquals(0, ((answer[10].toInt() and 0xFF) shl 8) or (answer[11].toInt() and 0xFF), "ARCOUNT")
        // And the question itself is still there, so the reply answers what was asked.
        assertEquals("ads.example.com", DnsMessage.questionName(answer.copyOf().also { it[2] = 0x00 }))
    }

    @Test
    fun `the answer keeps the recursion-desired bit and loses the truncation one`() {
        val q = query(flags = 0x0300) // RD + TC
        val answer = DnsMessage.answer(q, 0, DnsMessage.RCODE_SERVER_FAILURE)
        assertTrue(answer[2].toInt() and 0x01 != 0, "RD is the asker's, and is echoed")
        assertFalse(answer[2].toInt() and 0x02 != 0, "TC belongs to a reply we are not making")
    }

    @Test
    fun `a reply is only the answer to its own question`() {
        val q = query(id = 0x4242)
        assertTrue(DnsMessage.answersQuery(q, 0, DnsMessage.answer(q, 0, 0)))
        // Anyone on the same network can send a datagram to the port we asked from; without the
        // id check the first one to arrive was relayed to the app as the answer.
        val impostor = DnsMessage.answer(query(id = 0x4243), 0, 0)
        assertFalse(DnsMessage.answersQuery(q, 0, impostor))
        // A question is not an answer either, however well its id matches.
        assertFalse(DnsMessage.answersQuery(q, 0, q))
        assertFalse(DnsMessage.answersQuery(q, 0, ByteArray(4)))
    }

    @Test
    fun `a refusal is worth asking the next resolver, a missing name is not`() {
        assertTrue(DnsMessage.isServerFailure(DnsMessage.RCODE_SERVER_FAILURE))
        assertTrue(DnsMessage.isServerFailure(DnsMessage.RCODE_REFUSED))
        // NXDOMAIN is a real answer: asking again only makes the same "no" slower.
        assertFalse(DnsMessage.isServerFailure(DnsMessage.RCODE_NAME_ERROR))
        assertFalse(DnsMessage.isServerFailure(DnsMessage.RCODE_NO_ERROR))
        assertFalse(DnsMessage.isServerFailure(null))
    }

    @Test
    fun `a message shorter than a header answers null rather than throwing`() {
        val stub = ByteArray(5)
        assertNull(DnsMessage.questionEnd(stub))
        assertFalse(DnsMessage.isStandardQuery(stub))
        assertNull(DnsMessage.rcode(ByteArray(2)))
        assertNull(DnsMessage.transactionId(ByteArray(1)))
    }
}
