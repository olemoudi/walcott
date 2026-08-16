package dev.walcott.rules

/**
 * The blocklists that ship inside the app, so a family gets a filter worth having without
 * typing domains one at a time.
 *
 * **Ids travel, domains do not.** A policy is published in one ~4 KB message, so what the
 * parent's phone sends is `enabledBlocklists = ["adult"]` and both sides expand it from the
 * table below. That is also what lets a list grow in a later release without the parent having
 * to re-approve anything: the child updates, the list it already had turned on gets longer.
 * A child on an older build simply does not know a new id yet and keeps enforcing the ones it
 * does — never the reverse, so an update can add coverage but never silently drop it.
 *
 * **Conservative on purpose.** These lists are meant to be left on for years by a family that
 * will not be reading DNS logs. A filter that breaks a working app does not get investigated,
 * it gets switched off — taking the useful blocking with it. So [TRACKERS] deliberately leaves
 * alone everything an app needs in order to function (push, crash reporting, deep links, app
 * distribution, Firebase installations) and covers advertising and analytics endpoints only.
 *
 * What this cannot do is not a property of the list: the filter is plain DNS, so an app using
 * DoH/DoQ or hard-coded addresses is not caught (see WalcottVpnService). It raises the floor;
 * it is not a wall.
 */
object Blocklists {

    const val ADULT = "adult"
    const val GAMBLING = "gambling"
    const val TRACKERS = "trackers"

    /**
     * What a family should be able to turn on without thinking about it: the two nobody argues
     * about, and neither of which can break an app a child legitimately uses.
     */
    val CONSERVATIVE: List<String> = listOf(ADULT, GAMBLING)

    /** Every list this build knows, in the order they are offered. */
    val ALL: List<String> = listOf(ADULT, GAMBLING, TRACKERS)

    /** The domains behind [ids], ignoring ids this build does not know. */
    fun domains(ids: Collection<String>): Set<String> =
        ids.flatMapTo(mutableSetOf()) { LISTS[it].orEmpty() }

    /** How many domains list [id] carries (0 for an unknown id) — the UI says so on the row. */
    fun size(id: String): Int = LISTS[id].orEmpty().size

    /** Ids this build can actually enforce, of whatever the policy asked for. */
    fun known(ids: Collection<String>): Set<String> = ids.filterTo(mutableSetOf()) { it in LISTS }

    private val LISTS: Map<String, List<String>> = mapOf(
        ADULT to listOf(
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
        GAMBLING to listOf(
            "888.com", "888casino.es", "888poker.es", "bet365.com", "bet365.es", "betfair.com",
            "betfair.es", "betsson.es", "betway.com", "bwin.es", "casumo.com", "circus.es",
            "codere.es", "csgoempire.com", "csgoroll.com", "duelbits.com", "efbet.es",
            "gamdom.com", "ggpoker.com", "gratorama.com", "jackpotcity.com", "juegging.es",
            "leovegas.com", "luckia.es", "marathonbet.es", "marcaapuestas.es", "mrgreen.com",
            "netbet.es", "paf.es", "pokerstars.com", "pokerstars.es", "retabet.es",
            "rollbit.com", "roobet.com", "sisal.es", "sportium.es", "stake.com", "unibet.com",
            "versus.es", "williamhill.es", "winamax.es", "wplay.co", "zebet.es",
        ),
        TRACKERS to listOf(
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
    )
}
