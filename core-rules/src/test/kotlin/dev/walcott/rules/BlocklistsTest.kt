package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlocklistsTest {

    @Test
    fun `every list has something behind it, bundled or public`() {
        for (id in Blocklists.ALL) {
            val entry = Blocklists.entry(id)!!
            assertTrue(
                entry.seed.isNotEmpty() || entry.sources.isNotEmpty(),
                "$id blocks nothing at all",
            )
            assertEquals(entry.seed.size, Blocklists.seedSize(id), "$id: seedSize() disagrees")
            assertEquals(
                entry.seed.size + entry.approxSourceDomains,
                Blocklists.approxDomains(id),
                "$id: approxDomains() disagrees",
            )
        }
    }

    @Test
    fun `a list with a public source says how big it is, and one without does not pretend to`() {
        for (id in Blocklists.ALL) {
            val entry = Blocklists.entry(id)!!
            if (entry.sources.isEmpty()) {
                assertEquals(0, entry.approxSourceDomains, "$id has no source but claims domains from one")
            } else {
                // The number drives the parent's row AND the store's "this answer is too small to
                // be the real list" guard, so a source with no measurement disables the guard.
                assertTrue(entry.approxSourceDomains > 0, "$id has a source with no measured size")
            }
        }
    }

    @Test
    fun `ids this build does not know are ignored rather than breaking the rest`() {
        // A parent on a newer build can enable a list this child has never heard of; the child
        // must keep enforcing what it does know.
        val domains = Blocklists.domains(listOf(Blocklists.ADULT, "list-from-the-future"))
        assertEquals(Blocklists.seedSize(Blocklists.ADULT), domains.size)
        assertEquals(setOf(Blocklists.ADULT), Blocklists.known(listOf(Blocklists.ADULT, "nope")))
        assertEquals(emptyList<String>(), Blocklists.withSources(listOf("list-from-the-future")))
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
    fun `nothing bundled is something the downloaded lists are forbidden to block`() {
        // NEVER_BLOCK guards what a source says, not what we ship, so a seed containing one of
        // these would take effect — and take out the family's own channel to the child.
        for (id in Blocklists.ALL) {
            for (domain in Blocklists.domains(listOf(id))) {
                assertFalse(
                    BlocklistSource.isSpared(domain),
                    "$id ships $domain, which is on the never-block list",
                )
            }
        }
    }

    @Test
    fun `sources are distinct https urls`() {
        val seen = mutableMapOf<String, String>()
        for (id in Blocklists.ALL) {
            for (url in Blocklists.sources(id)) {
                assertTrue(url.startsWith("https://"), "$id downloads $url over plain http")
                val previous = seen.put(url, id)
                assertTrue(previous == null, "$url is downloaded twice, for $previous and $id")
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
    fun `the social list does not take whatsapp down with instagram`() {
        // A parent blocking social networks has not asked to stop being able to reach their kid.
        val social = DomainMatcher.of(Blocklists.domains(listOf(Blocklists.SOCIAL)))
        assertFalse(social.matches("whatsapp.com"))
        assertFalse(social.matches("mmg.whatsapp.net"))
        assertTrue(social.matches("www.instagram.com"))
        assertTrue(social.matches("tiktok.com"))
    }

    @Test
    fun `what the wizard recommends cannot get in an app's way`() {
        assertEquals(listOf(Blocklists.ADULT, Blocklists.GAMBLING), Blocklists.CONSERVATIVE)
        assertTrue(Blocklists.ALL.containsAll(Blocklists.CONSERVATIVE))
        for (id in Blocklists.CONSERVATIVE) {
            assertFalse(Blocklists.mayBreakApps(id), "$id is recommended but flagged as breaking apps")
        }
    }

    @Test
    fun `the refresh interval a family can choose includes the one they get by default`() {
        assertTrue(Blocklists.DEFAULT_REFRESH_HOURS in Blocklists.REFRESH_HOUR_CHOICES)
        assertTrue(Blocklists.REFRESH_HOUR_CHOICES.all { it >= 1 })
    }
}
