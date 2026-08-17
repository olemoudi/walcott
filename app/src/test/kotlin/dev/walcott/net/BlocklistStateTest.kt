package dev.walcott.net

import dev.walcott.rules.Blocklists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The half of [BlocklistStore] that decides what the parent is told. Everything else in that
 * class is disk and network, but this is the reporting, and the failure it guards against is a
 * family being told a filter is in force when it is not.
 */
class BlocklistStateTest {

    private fun state(vararg lists: Pair<String, BlocklistStore.ListState>) =
        BlocklistStore.State(lists.toMap())

    @Test
    fun `only what was really downloaded is counted`() {
        val s = state(
            Blocklists.ADULT to BlocklistStore.ListState(domains = 494_510, fetchedAtMs = 1),
            Blocklists.PIRACY to BlocklistStore.ListState(domains = 0),
        )
        assertEquals(
            494_510,
            s.domainsFor(listOf(Blocklists.ADULT, Blocklists.PIRACY, Blocklists.SCAM)),
        )
        assertEquals(0, s.domainsFor(emptyList()))
    }

    @Test
    fun `a list with a source and no cache is pending, in the order the parent saw them`() {
        val s = state(Blocklists.ADULT to BlocklistStore.ListState(domains = 10, fetchedAtMs = 1))
        // Asked for four; adult is here, the other three with sources are not.
        val pending = s.pending(
            listOf(Blocklists.SCAM, Blocklists.ADULT, Blocklists.GAMBLING, Blocklists.PIRACY),
        )
        assertEquals(listOf(Blocklists.GAMBLING, Blocklists.PIRACY, Blocklists.SCAM), pending)
    }

    @Test
    fun `a bundled-only list is never pending, because there is nothing to wait for`() {
        // Social and video ship inside the APK; reporting them as "not downloaded yet" would send
        // a parent looking for a problem that cannot exist.
        val pending = state().pending(listOf(Blocklists.SOCIAL, Blocklists.VIDEO))
        assertEquals(emptyList<String>(), pending)
    }

    @Test
    fun `an id this build does not know is not reported as pending`() {
        // A parent on a newer build can enable a list this child has never heard of. It cannot
        // enforce it, but it must not describe it either — it does not know what it is.
        assertEquals(emptyList<String>(), state().pending(listOf("list-from-the-future")))
        assertEquals(0, state().domainsFor(listOf("list-from-the-future")))
    }
}
