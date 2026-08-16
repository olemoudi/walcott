package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlocklistsTest {

    @Test
    fun `every list has domains and a size that matches`() {
        for (id in Blocklists.ALL) {
            val domains = Blocklists.domains(listOf(id))
            assertTrue(domains.isNotEmpty(), "$id is empty")
            assertEquals(Blocklists.size(id), domains.size, "$id: size() disagrees with domains()")
        }
    }

    @Test
    fun `ids this build does not know are ignored rather than breaking the rest`() {
        // A parent on a newer build can enable a list this child has never heard of; the child
        // must keep enforcing what it does know.
        val domains = Blocklists.domains(listOf(Blocklists.ADULT, "list-from-the-future"))
        assertEquals(Blocklists.size(Blocklists.ADULT), domains.size)
        assertEquals(setOf(Blocklists.ADULT), Blocklists.known(listOf(Blocklists.ADULT, "nope")))
    }

    @Test
    fun `entries are already normalised, so a typo cannot silently never match`() {
        // A list entry that needs normalising still works (the matcher normalises), but it
        // would mean the file says one thing and blocks another. Catch it here instead.
        for (id in Blocklists.ALL) {
            for (domain in Blocklists.domains(listOf(id))) {
                assertEquals(domain, DomainMatcher.normalize(domain), "$id: $domain is not in canonical form")
                assertTrue(domain.contains('.'), "$id: $domain has no dot")
            }
        }
    }

    @Test
    fun `no list contains a duplicate of another list's domain`() {
        // Overlap would be harmless to match but makes the counts lie about what each list adds.
        val seen = mutableMapOf<String, String>()
        for (id in Blocklists.ALL) {
            for (domain in Blocklists.domains(listOf(id))) {
                val previous = seen.put(domain, id)
                assertTrue(previous == null, "$domain is in both $previous and $id")
            }
        }
    }

    @Test
    fun `the trackers list leaves alone everything an app needs to work`() {
        // The whole bet of shipping this list is that a family can leave it on for years. These
        // are the hosts whose blocking breaks push, crash reporting, logins or deep links — a
        // future edit that adds one of them should fail here rather than on a child's phone.
        val trackers = DomainMatcher.of(Blocklists.domains(listOf(Blocklists.TRACKERS)))
        val mustWork = listOf(
            "googleapis.com", "firebaseinstallations.googleapis.com", "crashlytics.com",
            "firebase.googleapis.com", "gstatic.com", "graph.facebook.com", "accounts.google.com",
            "apple.com", "icloud.com", "cloudflare.com", "branch.io", "sentry.io",
            "play.googleapis.com", "android.clients.google.com",
        )
        for (host in mustWork) {
            assertFalse(trackers.matches(host), "$host must not be on the trackers list")
        }
    }

    @Test
    fun `the conservative pair is the two content lists`() {
        assertEquals(listOf(Blocklists.ADULT, Blocklists.GAMBLING), Blocklists.CONSERVATIVE)
        assertTrue(Blocklists.ALL.containsAll(Blocklists.CONSERVATIVE))
    }
}
