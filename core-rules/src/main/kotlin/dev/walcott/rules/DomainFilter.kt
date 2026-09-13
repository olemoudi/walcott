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
     *
     * [allowedDomains] is the family answering a list back: names they want to reach whatever a
     * downloaded list says about them. It is the way out of a list without switching the list
     * off — the betting list carries a national sports daily, the adult one a video-link
     * shortener — and it answers ONLY to the lists: what the family typed as blocked, a per-app
     * rule and the curfew are all decided above it, because those are rules somebody here chose.
     */
    fun isBlocked(
        host: String,
        packageName: String?,
        familyDomains: DomainMatcher,
        lists: DomainMatcher,
        appRules: List<DomainAppRule>,
        listExemptApps: Set<String> = emptySet(),
        cutOff: Set<String> = emptySet(),
        allowedDomains: DomainMatcher = DomainMatcher.EMPTY,
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

        // What the family allowed back from the lists. Below their own blocks, so a name both
        // typed as blocked and allowed stays blocked: the explicit "no" is the safer reading of a
        // contradiction, and the screen that shows both is where it gets noticed.
        if (allowedDomains.matches(h)) return false

        // Below what this family decided and above what a list decided, which is the only place
        // it can go. The phone's own connectivity probes must survive a downloaded list: a list
        // that refuses one makes the phone declare a good Wi-Fi dead and leave it for mobile data,
        // silently and at the family's expense (see [BlocklistSource.CONNECTIVITY_CHECKS]).
        //
        // A parent who types one of these themselves still blocks it, and so does a per-app rule
        // and the curfew, all of which are decided above. What this refuses is a list nobody in
        // the family has read doing it on their behalf.
        if (BlocklistSource.isConnectivityCheck(h)) return false

        // The same guard for what the phone and the family cannot lose (see
        // [BlocklistSource.NEVER_BLOCK]), and here for the reason the probes are: it used to run
        // only when a list was ingested, where it removed entries AT or BELOW a spared name and
        // nothing else. A list carrying a parent of one — `clients.google.com`, say — still took
        // `android.clients.google.com` with it, and a list compiled by an older build was never
        // re-checked at all. Asked of the name being looked up, it covers both.
        if (BlocklistSource.isSpared(h)) return false

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
