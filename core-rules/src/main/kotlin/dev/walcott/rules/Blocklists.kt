package dev.walcott.rules

/**
 * The blocklists a family can switch on, so a filter worth having costs one tap instead of a
 * hundred typed domains.
 *
 * **Ids travel, domains do not.** A policy is published in one ~4 KB message, so what the
 * parent's phone sends is `enabledBlocklists = ["adult"]` and both sides expand it from the
 * table below. That is also what lets a list grow in a later release without the parent having
 * to re-approve anything: the child updates, the list it already had turned on gets longer.
 * A child on an older build simply does not know a new id yet and keeps enforcing the ones it
 * does — never the reverse, so an update can add coverage but never silently drop it.
 *
 * **Every list has two halves.** The [Entry.seed] ships inside the APK: the well-known front
 * doors, hand-picked, enforced from the second the list is switched on and for ever after,
 * with no network involved. The [Entry.sources] are public curated lists the CHILD's phone
 * refreshes on its own (see `BlocklistStore`, on the family's chosen interval), which is where
 * the long tail lives — the hundreds of thousands of porn, gambling and proxy domains no
 * hand-written list will ever keep up with. A source that never downloads costs the family the
 * tail and never the front doors.
 *
 * **The sources are the real ones, at their real size.** oisd's NSFW list is 494 000 domains,
 * hagezi's gambling list is over 400 000, and switching every list on lands around 1.3 million.
 * That is affordable because of how they are stored, not because they were trimmed to fit: the
 * downloaded half of the filter is a sorted array of 64-bit hashes, 8 bytes a domain, so the lot
 * costs the child's always-on process ~10 MB (see [DomainMatcher]).
 *
 * **Two lists are deliberately seed-only.** For social networks and video the public lists we
 * looked at are noise: `blocklistproject/facebook` is 22 000 entries of which 79 are domains
 * anyone visits (the rest are Facebook's own backbone routers), and `youtube.txt` is 24 000
 * `r1---sn-….googlevideo.com` edge nodes that one `googlevideo.com` suffix covers. A curated
 * fifty beats a downloaded twenty-four thousand here, so those two say so on the row.
 *
 * **Conservative on purpose.** These lists are meant to be left on for years by a family that
 * will not be reading DNS logs. A filter that breaks a working app does not get investigated,
 * it gets switched off — taking the useful blocking with it. So [TRACKERS] deliberately leaves
 * alone everything an app needs in order to function, and the two lists that can plausibly get
 * in an app's way ([TRACKERS], [BYPASS]) are flagged [Entry.mayBreakApps] and say so in the UI.
 *
 * What this cannot do is not a property of the list: the filter is plain DNS, so an app using
 * DoH/DoQ or hard-coded addresses is not caught (see WalcottVpnService) — which is exactly what
 * [BYPASS] is for, since blocking the encrypted resolvers is what forces an app back onto the
 * DNS we can see. It raises the floor; it is not a wall.
 */
object Blocklists {

    const val ADULT = "adult"
    const val GAMBLING = "gambling"
    const val SOCIAL = "social"
    const val VIDEO = "video"
    const val PIRACY = "piracy"
    const val SCAM = "scam"
    const val BYPASS = "bypass"
    const val TRACKERS = "trackers"

    /**
     * One offerable list.
     *
     * [approxSourceDomains] is what the sources carried when they were last measured, and is
     * only ever shown as an approximation: the parent's phone does not download them (it filters
     * nothing), so this is the honest way to say how big a list is before the child reports what
     * it actually has. Update it when a source is changed, not every time one grows.
     */
    data class Entry(
        val id: String,
        val seed: List<String>,
        val sources: List<String> = emptyList(),
        val approxSourceDomains: Int = 0,
        val mayBreakApps: Boolean = false,
    )

    /**
     * What a family should be able to turn on without thinking about it: the two nobody argues
     * about, and neither of which can break an app a child legitimately uses.
     */
    val CONSERVATIVE: List<String> = listOf(ADULT, GAMBLING)

    /** How often a child re-downloads the public lists, unless the family says otherwise. */
    const val DEFAULT_REFRESH_HOURS = 24

    /**
     * The refresh intervals a parent can pick between: daily, every three days, weekly.
     *
     * Deliberately coarse. The sources are rebuilt as often as hourly, so anything under a day
     * buys a family nothing they would ever notice and spends a child's data to buy it; and the
     * cost of the slowest option is only ever "a domain that appeared this week is not blocked
     * until next week", on top of a list that already holds hundreds of thousands.
     */
    val REFRESH_HOUR_CHOICES: List<Int> = listOf(24, 72, 168)

    /** Every list this build knows, in the order they are offered. */
    val ALL: List<String> get() = ENTRIES.map { it.id }

    /** The list called [id], or null when this build does not know it. */
    fun entry(id: String): Entry? = BY_ID[id]

    /** The bundled domains behind [ids], ignoring ids this build does not know. */
    fun domains(ids: Collection<String>): Set<String> =
        ids.flatMapTo(mutableSetOf()) { BY_ID[it]?.seed.orEmpty() }

    /** How many domains list [id] ships inside the APK (0 for an unknown id). */
    fun seedSize(id: String): Int = BY_ID[id]?.seed?.size ?: 0

    /** Roughly what list [id] blocks once its sources have downloaded (see [Entry.approxSourceDomains]). */
    fun approxDomains(id: String): Int = BY_ID[id]?.let { it.seed.size + it.approxSourceDomains } ?: 0

    /** The public lists the child refreshes for [id]; empty when the list is bundled only. */
    fun sources(id: String): List<String> = BY_ID[id]?.sources.orEmpty()

    /** True when this list can plausibly get in the way of an app the child legitimately uses. */
    fun mayBreakApps(id: String): Boolean = BY_ID[id]?.mayBreakApps == true

    /** Ids this build can actually enforce, of whatever the policy asked for. */
    fun known(ids: Collection<String>): Set<String> = ids.filterTo(mutableSetOf()) { it in BY_ID }

    /** The ids of every list with a public source, of whatever the policy asked for. */
    fun withSources(ids: Collection<String>): List<String> =
        ALL.filter { it in ids && BY_ID.getValue(it).sources.isNotEmpty() }

    /**
     * hagezi's lists, in their plain-domain ("wildcard") form.
     *
     * Straight from GitHub rather than through a CDN: jsDelivr answers "Failed to fetch … from
     * GitHub" for this repository often enough that it is not the safer choice, and this app
     * already trusts GitHub for its own updates. A rate-limited or otherwise unhappy answer is
     * handled where it has to be anyway — a source that returns an error page instead of a list
     * is refused by `BlocklistStore`, which keeps the copy it already had.
     */
    private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard"

    private val ENTRIES: List<Entry> = listOf(
        Entry(
            id = ADULT,
            // oisd's NSFW list: ~494 000 domains, rebuilt hourly from dozens of feeds, and
            // curated under a stated "Block. Don't break." rule. Hashed it costs the child 4 MB
            // (see DomainMatcher), which is what makes a list this size the obvious choice rather
            // than an extravagance.
            sources = listOf("https://nsfw.oisd.nl/domainswild"),
            approxSourceDomains = 494_000,
            seed = listOf(
                "adultfriendfinder.com", "ashleymadison.com", "bangbros.com", "beeg.com",
                "bongacams.com", "brazzers.com", "camsoda.com", "chaturbate.com", "clips4sale.com",
                "cam4.com", "dofantasy.com", "e-hentai.org", "eporner.com", "erome.com",
                "escortdirectory.com", "fakku.net", "fapello.com", "fetlife.com", "gelbooru.com",
                "hanime.tv", "hentaihaven.xxx", "hqporner.com", "iwara.tv", "javhd.com",
                "julesjordan.com", "livejasmin.com", "manyvids.com", "motherless.com",
                "myfreecams.com", "naughtyamerica.com", "nhentai.net", "onlyfans.com",
                "pornhub.com", "porntrex.com", "porn.com", "realitykings.com", "redtube.com",
                "rule34.xxx", "sex.com", "sexyandfunny.com", "spankbang.com", "stripchat.com",
                "streamate.com", "thumbzilla.com", "tnaflix.com", "tube8.com", "txxx.com",
                "xhamster.com", "xhamsterlive.com", "xnxx.com", "xvideos.com", "youjizz.com",
                "youporn.com", "4chan.org", "8kun.top",
            ),
        ),
        Entry(
            id = GAMBLING,
            sources = listOf("$HAGEZI/gambling-onlydomains.txt"),
            approxSourceDomains = 425_000,
            // The seed stays worth having next to the source: the public list is US-shaped and
            // these are the houses that advertise on Spanish television.
            seed = listOf(
                "888.com", "888casino.es", "888poker.es", "bet365.com", "bet365.es", "betfair.com",
                "betfair.es", "betsson.es", "betway.com", "bwin.es", "casumo.com", "circus.es",
                "codere.es", "csgoempire.com", "csgoroll.com", "duelbits.com", "efbet.es",
                "gamdom.com", "ggpoker.com", "gratorama.com", "jackpotcity.com", "juegging.es",
                "leovegas.com", "luckia.es", "marathonbet.es", "marcaapuestas.es", "mrgreen.com",
                "netbet.es", "paf.es", "pokerstars.com", "pokerstars.es", "retabet.es",
                "rollbit.com", "roobet.com", "sisal.es", "sportium.es", "stake.com", "unibet.com",
                "versus.es", "williamhill.es", "winamax.es", "wplay.co", "zebet.es",
            ),
        ),
        Entry(
            id = SOCIAL,
            // Bundled only, and see the class comment for why. WhatsApp is deliberately absent:
            // it is how this family talks to each other, and the public social lists that do
            // include it would take the parent's own channel to the child down with Instagram.
            seed = listOf(
                "ask.fm", "bereal.com", "cdninstagram.com", "chatroulette.com", "discord.com",
                "discord.gg", "discord.media", "discordapp.com", "discordapp.net", "facebook.com",
                "facebook.net", "fb.com", "fb.me", "fbcdn.net", "fbsbx.com", "instagram.com",
                "m.me", "messenger.com", "musical.ly", "omegle.com", "pinimg.com", "pinterest.com",
                "pinterest.es", "redd.it", "reddit.com", "redditmedia.com", "redditstatic.com",
                "sc-cdn.net", "snap.com", "snapchat.com", "t.co", "threads.com", "threads.net",
                "tiktok.com", "tiktokcdn.com", "tiktokv.com", "tumblr.com", "twimg.com",
                "twitter.com", "twttr.com", "vk.com", "x.com",
            ),
        ),
        Entry(
            id = VIDEO,
            // Bundled only. One `googlevideo.com` suffix does what 24 000 downloaded edge-node
            // hostnames do, and the Spanish catch-up services no international list carries.
            seed = listOf(
                "atresplayer.com", "bamgrid.com", "crunchyroll.com", "dailymotion.com",
                // No ggpht.com here, tempting as it looks: it serves YouTube avatars AND the Play
                // Store's app icons, so it is a shared Google static host, not a video one.
                "disneyplus.com", "dssott.com", "googlevideo.com", "hbo.com",
                "hbomax.com", "jtvnw.net", "kick.com", "max.com", "mitele.es", "movistarplus.es",
                "netflix.com", "nflxext.com", "nflximg.net", "nflxvideo.net", "primevideo.com",
                "ttvnw.net", "twitch.tv", "vimeo.com", "youtu.be", "youtube-nocookie.com",
                "youtube.com", "ytimg.com",
            ),
        ),
        Entry(
            id = PIRACY,
            sources = listOf("$HAGEZI/anti.piracy-onlydomains.txt"),
            approxSourceDomains = 42_000,
            seed = listOf(
                "1337x.to", "dontorrent.org", "eztv.re", "limetorrents.lol", "nyaa.si",
                "rarbg.to", "thepiratebay.org", "torrentgalaxy.to", "yts.mx",
            ),
        ),
        Entry(
            id = SCAM,
            // The only list with nothing bundled, and honestly so: scam and phishing hosts are
            // registered and abandoned within days, so a domain hand-picked for a release is
            // dead before the release ships. Everything here comes from the sources, and until
            // they have downloaded once the child reports the list as pending, not as enforced.
            //
            // hagezi's threat-intelligence feed (the "medium" cut: phishing, malware and scam
            // hosts, ~390 000, without the paranoid tail that breaks things) plus their fake-shop
            // list (~16 000), which is the one a teenager actually meets — counterfeit sneakers,
            // not botnets.
            sources = listOf(
                "$HAGEZI/tif.medium-onlydomains.txt",
                "$HAGEZI/fake-onlydomains.txt",
            ),
            approxSourceDomains = 406_000,
            seed = emptyList(),
        ),
        Entry(
            id = BYPASS,
            // Why a parental filter wants this: our filter sees plain DNS, so a child who
            // installs a VPN, turns on a browser's secure DNS or points the phone at a public
            // DoH resolver stops being filtered at all. Blocking the resolvers and the VPN
            // front doors is what forces the lookup back onto the DNS this app can see.
            //
            // hagezi's VPN/proxy/Tor/encrypted-DNS list, plus the small one covering the hosts a
            // browser uses to slip past SafeSearch.
            sources = listOf(
                "$HAGEZI/doh-vpn-proxy-bypass-onlydomains.txt",
                "$HAGEZI/nosafesearch-onlydomains.txt",
            ),
            approxSourceDomains = 17_000,
            mayBreakApps = true,
            seed = listOf(
                "4everproxy.com", "cloudflare-dns.com", "croxyproxy.com", "cyberghostvpn.com",
                "dns.adguard-dns.com", "dns.adguard.com", "dns.google", "dns.nextdns.io",
                "dns.quad9.net", "dns.sb", "doh.cleanbrowsing.org", "doh.opendns.com",
                "expressvpn.com", "hide.me", "hidemyass.com", "hola.org", "kproxy.com",
                "mullvad.net", "nordvpn.com", "privateinternetaccess.com", "protonvpn.com",
                "proxysite.com", "psiphon.ca", "surfshark.com", "torproject.org",
                "tunnelbear.com", "windscribe.com",
            ),
        ),
        Entry(
            id = TRACKERS,
            // oisd's SMALL list (57 285), not their big one, and this is the one place where the
            // smaller list is the better list rather than the cheaper one: this row promises that
            // nothing an app needs to work is on it, and "small" is the cut curated to keep that
            // promise. The big cut adds malware and phishing hosts, which is the SCAM list's job.
            sources = listOf("https://small.oisd.nl/domainswild"),
            approxSourceDomains = 57_000,
            mayBreakApps = true,
            seed = listOf(
                // Advertising exchanges and networks.
                "2mdn.net", "adcolony.com", "adnxs.com", "adsafeprotected.com", "adsrvr.org",
                "adtelligent.com", "amazon-adsystem.com", "applovin.com", "applvn.com",
                "bidswitch.net", "casalemedia.com", "chartboost.com", "criteo.com", "criteo.net",
                "doubleclick.net", "doubleverify.com", "googleadservices.com",
                "googlesyndication.com", "googletagservices.com", "inmobi.com", "ironsrc.com",
                "moatads.com", "mopub.com", "openx.net", "outbrain.com", "pubmatic.com",
                "rubiconproject.com", "sharethrough.com", "smaato.net", "smartadserver.com",
                "taboola.com", "teads.tv", "unityads.unity3d.com", "vungle.com", "yieldmo.com",
                // Analytics, attribution and profiling.
                "adjust.com", "amplitude.com", "app-measurement.com", "appmetrica.yandex.net",
                "appsflyer.com", "bluekai.com", "braze.com", "chartbeat.com", "clarity.ms",
                "demdex.net", "flurry.com", "fullstory.com", "google-analytics.com",
                "googletagmanager.com", "hotjar.com", "kochava.com", "mc.yandex.ru",
                "mixpanel.com", "omtrdc.net", "quantserve.com", "scorecardresearch.com",
                "segment.com", "segment.io", "singular.net", "tenjin.io", "umeng.com",
                "analytics.tiktok.com", "connect.facebook.net", "an.facebook.com",
            ),
        ),
    )

    private val BY_ID: Map<String, Entry> = ENTRIES.associateBy { it.id }
}
