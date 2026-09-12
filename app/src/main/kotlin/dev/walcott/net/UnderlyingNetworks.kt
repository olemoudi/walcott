package dev.walcott.net

/**
 * Which of the phone's real networks this tunnel is riding on.
 *
 * Pure, and separate from the service, because the question is a ranking and the answer decides
 * where every DNS lookup on the phone goes. Getting it wrong is not visible: the filter reports
 * itself healthy while asking the resolvers of a network the phone is not using, and every lookup
 * times out.
 *
 * On Android 12 and up the platform answers this itself — `registerBestMatchingNetworkCallback`
 * names its best match and nothing else. Below that, `registerNetworkCallback` reports EVERY
 * network that matches the request, so the last one to speak would win: a phone holding both
 * Wi-Fi and mobile data could end up asking the mobile network's resolvers while browsing over
 * Wi-Fi. That is what this is for.
 */
object UnderlyingNetworks {

    /**
     * A network as far as this ranking cares. Generic in [T] so the service can pass its
     * `android.net.Network` objects and a test can pass names.
     */
    data class Candidate<T>(
        val network: T,
        /** Whether the platform has confirmed this network actually reaches the internet. */
        val validated: Boolean,
        val wifi: Boolean = false,
        val ethernet: Boolean = false,
        val cellular: Boolean = false,
    )

    /**
     * The best of [candidates], or null when there are none.
     *
     * Validation first, because a network the platform has confirmed is the one the phone is
     * actually using. Then the transport, cheapest to the family first: a wire, then Wi-Fi, then
     * mobile data.
     *
     * An unvalidated network is still chosen when it is all there is. That is the half of this
     * that was a bug in the project this was read against: filtering candidates BY validation
     * meant a weak Wi-Fi the phone had stopped vouching for produced no answer at all, so the
     * filter kept the resolvers of a network that had gone. Validation decides which of several to
     * prefer; it never decides whether to have an answer.
     */
    fun <T> best(candidates: List<Candidate<T>>): T? = candidates
        .sortedWith(
            compareByDescending<Candidate<T>> { it.validated }
                .thenBy { transportRank(it) },
        )
        .firstOrNull()
        ?.network

    /** A wire, then Wi-Fi, then mobile data, then anything this build has no opinion about. */
    private fun <T> transportRank(candidate: Candidate<T>): Int = when {
        candidate.ethernet -> 0
        candidate.wifi -> 1
        candidate.cellular -> 2
        else -> 3
    }
}
