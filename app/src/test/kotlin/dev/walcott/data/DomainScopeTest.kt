package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The four rules one reviewed selection can become: family or one child, one app or every app.
 *
 * Worth its own suite because the per-child answers write into an override that *replaces* the
 * family value wholesale. Getting that wrong doesn't fail loudly — it quietly unblocks everything
 * the child had inherited, which is the one direction a parental control must never move.
 */
class DomainScopeTest {

    private val base = PolicySettings(
        blockedDomains = setOf("casino.com"),
        domainAppRules = listOf(DomainAppRuleDto("ads.net", "com.game", allowOnlyFromApp = false)),
        children = listOf(ChildEntry(childId = "c1", name = "Ana")),
    )
    private val picked = listOf("tracker.io", "promo.tv")

    @Test
    fun `family plus any app lands in the global blocklist`() {
        val out = base.withFamilyDomainRules(picked, scopeToApp = null)
        assertEquals(setOf("casino.com", "tracker.io", "promo.tv"), out.blockedDomains)
        assertEquals(base.domainAppRules, out.domainAppRules) { "the per-app list is not the target" }
    }

    @Test
    fun `family plus one app lands in the per-app rules, scoped to that app`() {
        val out = base.withFamilyDomainRules(picked, scopeToApp = "com.social")
        assertEquals(base.blockedDomains, out.blockedDomains)
        assertEquals(
            listOf("ads.net" to "com.game", "tracker.io" to "com.social", "promo.tv" to "com.social"),
            out.domainAppRules.map { it.domain to it.packageName },
        )
        assertTrue(out.domainAppRules.none { it.allowOnlyFromApp }) { "this is a block, not an allow-list" }
    }

    @Test
    fun `blocking the same domain in the same app twice is a no-op`() {
        val once = base.withFamilyDomainRules(picked, scopeToApp = "com.social")
        assertEquals(once, once.withFamilyDomainRules(picked, scopeToApp = "com.social"))
    }

    @Test
    fun `the same domain can be blocked in a second app`() {
        val out = base.withFamilyDomainRules(listOf("ads.net"), scopeToApp = "com.social")
        assertEquals(2, out.domainAppRules.size)
        assertEquals(setOf("com.game", "com.social"), out.domainAppRules.map { it.packageName }.toSet())
    }

    @Test
    fun `one child plus any app keeps what that child already inherited`() {
        // The bug this pins: seeding the override from nothing turns "also block these for Ana"
        // into "block only these for Ana", silently unblocking casino.com for her.
        val out = base.withChildDomainRules("c1", picked, scopeToApp = null)
        val overrides = out.children.single().overrides
        assertEquals(setOf("casino.com", "tracker.io", "promo.tv"), overrides.blockedDomains)
        assertEquals(setOf("casino.com"), out.blockedDomains) { "the family list must not move" }
        assertEquals(setOf("casino.com", "tracker.io", "promo.tv"), out.resolveForChild("c1").blockedDomains)
    }

    @Test
    fun `one child plus one app keeps the per-app rules that child already inherited`() {
        val out = base.withChildDomainRules("c1", picked, scopeToApp = "com.social")
        val overrides = out.children.single().overrides
        assertEquals(
            listOf("ads.net", "tracker.io", "promo.tv"),
            overrides.domainAppRules!!.map { it.domain },
        )
        assertEquals(base.domainAppRules, out.domainAppRules) { "the family list must not move" }
        assertEquals(3, out.resolveForChild("c1").domainAppRules.size)
    }

    @Test
    fun `a child override does not touch the other children`() {
        val two = base.copy(children = base.children + ChildEntry(childId = "c2", name = "Leo"))
        val out = two.withChildDomainRules("c1", picked, scopeToApp = null)
        assertTrue(out.children.first { it.childId == "c2" }.overrides.isEmpty)
        assertEquals(setOf("casino.com"), out.resolveForChild("c2").blockedDomains)
    }

    @Test
    fun `a second child-scoped block adds to the first`() {
        val once = base.withChildDomainRules("c1", listOf("tracker.io"), scopeToApp = null)
        val twice = once.withChildDomainRules("c1", listOf("promo.tv"), scopeToApp = null)
        assertEquals(setOf("casino.com", "tracker.io", "promo.tv"), twice.children.single().overrides.blockedDomains)
    }

    @Test
    fun `an unknown child falls back to the family scope rather than doing nothing`() {
        // A legacy device has no registry entry and so no override slot. Widening is recoverable;
        // a button that silently does nothing on a parental control is not.
        val out = base.withChildDomainRules("nobody", picked, scopeToApp = null)
        assertEquals(setOf("casino.com", "tracker.io", "promo.tv"), out.blockedDomains)
        assertTrue(out.children.single().overrides.isEmpty)
    }

    @Test
    fun `per-app domain rules resolve per child and default to inheriting`() {
        assertNull(base.children.single().overrides.domainAppRules)
        assertEquals(base.domainAppRules, base.resolveForChild("c1").domainAppRules)
        assertTrue(base.children.single().overrides.isEmpty)
    }

    @Test
    fun `an empty selection changes nothing`() {
        assertEquals(base, base.withFamilyDomainRules(emptyList(), scopeToApp = null))
        assertEquals(base, base.withFamilyDomainRules(emptyList(), scopeToApp = "com.social"))
    }
}
