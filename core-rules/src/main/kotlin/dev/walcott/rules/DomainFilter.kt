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
     * The hot-path form: the blocklists arrive already compiled ([DomainMatcher]), because they
     * are up to a million domains long (see [Blocklists]) and this runs per DNS query. The
     * per-app rules stay a plain list — they are written one at a time by a parent, and stay
     * short.
     *
     * **Two matchers, because they answer to different people.** [familyDomains] is what this
     * family decided: domains the parent typed, and this device is asked about them whatever app
     * is asking. [lists] is what a published blocklist decided — hundreds of thousands of entries
     * nobody in the family has read — and an app in [listExemptApps] is not judged by them.
     *
     * That asymmetry is the whole feature. A public list occasionally takes down an app that
     * needs some CDN it happens to carry, and the family cannot find which of 494 000 domains it
     * was; letting them say "the lists do not apply to the bank app" is the fix that does not
     * require knowing. Letting the same switch also waive the domains the parent typed by hand
     * would be a different thing entirely — a rule somebody chose, silently not applying.
     *
     * An exemption needs the lookup attributed to an app, and attribution is best-effort (see
     * `WalcottVpnService.ownerPackage`). An unattributed query is therefore NOT exempt: the same
     * fail-closed rule the allow-only-from-app case follows, and for the same reason.
     *
     * [cutOff] is the one input here that is not about a domain at all (see [Curfew]): the phone
     * is shut, and these apps resolve nothing until it opens again.
     */
    fun isBlocked(
        host: String,
        packageName: String?,
        familyDomains: DomainMatcher,
        lists: DomainMatcher,
        appRules: List<DomainAppRule>,
        listExemptApps: Set<String> = emptySet(),
        cutOff: Set<String> = emptySet(),
    ): Boolean {
        // First, and above the exemptions in particular. Everything below this line is a
        // judgement about a destination; this is a judgement about the hour. An app waived from
        // the public lists is waived from a list somebody downloaded — it was never permission
        // to carry on browsing at one in the morning.
        //
        // Attributed queries only, like every other per-app rule here. Cutting off what could
        // not be attributed would take the whole phone's DNS down with it, including the calls
        // and the apps this app promises never to limit.
        if (packageName != null && packageName in cutOff) return true

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

        if (familyDomains.matches(h)) return true

        // Exemptions apply to the lists and to nothing above this line.
        if (packageName != null && packageName in listExemptApps) return false
        return lists.matches(h)
    }

    /**
     * The single-matcher form, for callers with nothing to exempt from: everything in [blocked]
     * applies to every app. Kept because "is this host on this list" is a question several
     * screens and tests ask without a filter's worth of context around it.
     */
    fun isBlocked(
        host: String,
        packageName: String?,
        blocked: DomainMatcher,
        appRules: List<DomainAppRule>,
    ): Boolean = isBlocked(host, packageName, blocked, DomainMatcher.EMPTY, appRules)

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
