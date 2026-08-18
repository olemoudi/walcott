package dev.walcott.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The policy half of waiving the blocklists for an app: what the filter is handed, and what a
 * child that has never heard of the field does with it.
 */
class BlocklistExemptTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Typed by hand, and deliberately not something any bundled list could also carry. */
    private val typed = "portal.example-school.test"

    private val policy = PolicySettings(
        blockedDomains = setOf(typed),
        enabledBlocklists = setOf("social"),
        blocklistExemptApps = setOf("com.example.bank"),
    )

    @Test
    fun `the lists are readable apart from what the family typed`() {
        // The split the whole feature rests on: a waiver of the lists must not be able to reach
        // the domains a person chose (see DomainFilter).
        val fromLists = policy.blocklistDomains()
        assertTrue(fromLists.isNotEmpty(), "the bundled social list should carry domains")
        assertFalse(typed in fromLists, "a hand-typed domain is not part of the lists half")
        assertTrue(policy.blockedDomainsResolved().containsAll(fromLists + typed))
    }

    @Test
    fun `nothing is exempt unless a family says so`() {
        assertEquals(emptySet<String>(), PolicySettings().blocklistExemptApps)
    }

    @Test
    fun `the exemption survives the wire`() {
        val wire = json.encodeToString(PolicySettings.serializer(), policy)
        val back = json.decodeFromString(PolicySettings.serializer(), wire)
        assertEquals(setOf("com.example.bank"), back.blocklistExemptApps)
    }

    @Test
    fun `a child too old to know the field over-blocks rather than under-blocks`() {
        // Decoding with ignoreUnknownKeys is what every child does, and a build predating this
        // field simply does not see it: the app stays under the lists. That is the safe
        // direction — a filter doing more than asked until the device updates itself, never less.
        val wire = json.encodeToString(PolicySettings.serializer(), policy)
        val asOldChildSees = json.decodeFromString(
            PolicySettings.serializer(),
            wire.replace("\"blocklistExemptApps\"", "\"someFieldFromTheFuture\""),
        )
        assertEquals(emptySet<String>(), asOldChildSees.blocklistExemptApps)
        // Everything else it needs is still there.
        assertEquals(setOf(typed), asOldChildSees.blockedDomains)
        assertEquals(setOf("social"), asOldChildSees.enabledBlocklists)
    }
}
