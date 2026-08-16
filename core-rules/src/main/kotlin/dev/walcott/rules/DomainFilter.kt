package dev.walcott.rules

/**
 * A per-app domain rule.
 * - [allowOnlyFromApp] true  → "allow this domain only from [packageName]" (blocked elsewhere).
 * - [allowOnlyFromApp] false → "block this domain in [packageName]" (allowed elsewhere).
 */
data class DomainAppRule(
    val domain: String,
    val packageName: String,
    val allowOnlyFromApp: Boolean,
)

/**
 * Decides whether a DNS lookup should be blocked, given the global blocklist and the
 * per-app rules. Pure and deterministic; the VPN service calls it per query.
 *
 * Domain matching is suffix-based: a rule for `youtube.com` matches `youtube.com` and any
 * `*.youtube.com`.
 *
 * [packageName] is the app that made the lookup, or null when it couldn't be attributed.
 * "allow-only-from-app" rules fail closed (block) when the app is unknown, honouring the
 * parent's intent that the domain is generally off-limits.
 */
object DomainFilter {

    /**
     * The hot-path form: the blocklist arrives already compiled ([DomainMatcher]), because it is
     * now hundreds of domains long (see [Blocklists]) and this runs per DNS query. The per-app
     * rules stay a plain list — they are written one at a time by a parent, and stay short.
     */
    fun isBlocked(
        host: String,
        packageName: String?,
        blocked: DomainMatcher,
        appRules: List<DomainAppRule>,
    ): Boolean {
        val h = DomainMatcher.normalize(host)

        val allowOnlyForHost = appRules.filter { it.allowOnlyFromApp && matches(h, it.domain) }
        if (allowOnlyForHost.isNotEmpty()) {
            // Domain is restricted to a set of apps; block unless this app is one of them.
            return allowOnlyForHost.none { it.packageName == packageName }
        }

        val blockedInThisApp = appRules.any {
            !it.allowOnlyFromApp && it.packageName == packageName && matches(h, it.domain)
        }
        if (blockedInThisApp) return true

        return blocked.matches(h)
    }

    /**
     * Convenience for callers holding a plain set (tests, and anything asking a one-off
     * question). Compiles a matcher per call, so it does not belong in the packet loop.
     */
    fun isBlocked(
        host: String,
        packageName: String?,
        blockedDomains: Set<String>,
        appRules: List<DomainAppRule>,
    ): Boolean = isBlocked(host, packageName, DomainMatcher.of(blockedDomains), appRules)

    private fun matches(host: String, domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        return host == d || host.endsWith(".$d")
    }
}
