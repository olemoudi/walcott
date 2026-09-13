package dev.walcott.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.rules.DomainAppRule
import dev.walcott.rules.DomainFilter
import dev.walcott.rules.DomainMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Local DNS filter over VpnService. The sentinel DNS server and the best-known public resolvers
 * (see [PublicResolvers]) are routed through the tun, in both address families, so we see every
 * DNS query, decide with [DomainFilter], and either answer NXDOMAIN (block) or forward to a real
 * upstream (allow). Everything else stays on the normal network.
 *
 * Fail-open by design: any parsing/attribution problem forwards the query rather than
 * dropping it, so the child never loses DNS resolution because of a bug here. One exception,
 * and it is not about a domain at all: an app the curfew has cut off resolves nothing, so a
 * question this loop cannot parse is not a way round it (see [dev.walcott.rules.Curfew]).
 * This blocks plain DNS only, over UDP or TCP — apps using DoH/QUIC or hard-coded IPs are not
 * caught (see README); the phone's own Private DNS setting is kept off strict mode by
 * [VpnController], because that one would route every lookup past this service in two taps.
 *
 * The packet-level work lives in [IpPackets], [DnsMessage] and [DnsTcpResponder], which are pure
 * and tested. What is left here is the part only a phone has: the descriptor, the sockets, and
 * the lifecycle.
 */
class WalcottVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Guards the tun descriptor for everyone who writes to it.
     *
     * The descriptor is nulled inside this lock before it is closed, and every writer reads it
     * inside the lock, because a file descriptor number is free the moment it closes and the
     * kernel hands it straight to the next thing this process opens. A forwarder still in flight
     * would otherwise write a DNS response into a DataStore file or the sync socket — rare,
     * silent, and impossible to diagnose from a bug report.
     */
    private val writeLock = Any()

    /**
     * Guards adopting a network: the resolver list, the remembered winner, and what is declared
     * to the platform as the thing underneath this tunnel.
     *
     * Those fields are volatile, which makes each write atomic and says nothing about the SET
     * being consistent — and that is what matters here. Two threads reach this: the network
     * callback, and any forwarder whose resolvers have all failed. Interleaved, they can leave one
     * network's resolvers next to another network's declaration, which is a phone where every
     * lookup times out and nothing anywhere says why.
     *
     * Ordering: nothing held under this lock ever waits, and nothing under it takes any other
     * lock. [writeLock] is taken inside it at most, never the reverse.
     */
    private val adoptLock = Any()

    /**
     * Serialises bringing the tunnel up and taking it down: [session], [retryJob], [attempts].
     *
     * Four things start or stop it — the service being started, a stop request, the retry after a
     * failure, and a reader whose descriptor died — on three kinds of thread. Unserialised, the
     * reader's clean-up (which has to run elsewhere, see [runLoop]) could land after a restart and
     * tear the NEW tunnel down, and a start and a retry could both see "not running" and establish
     * twice, leaking a descriptor and a reader thread for the life of the process.
     *
     * Ordering: this is the outermost lock. [adoptLock], [writeLock] and [dnsTcp]'s monitor are
     * taken inside it, never the reverse, and the reader thread never takes it — which is what
     * makes joining the reader while holding it safe.
     */
    private val lifecycleLock = Any()

    /**
     * How many queries may be in flight at once. Each one can block a thread of the IO pool
     * for up to three resolver timeouts, and that pool is shared with the sync transport, the
     * blocklist store and the enforcement loop's writes: a Wi-Fi whose resolvers black-hole
     * (a captive portal, a bad DHCP answer) had a few hundred queued lookups starve all of them.
     * Past the bound a query waits, briefly and without a thread, for a slot (see [submit]).
     */
    private val inFlight = kotlinx.coroutines.sync.Semaphore(MAX_IN_FLIGHT)

    /** Queries waiting for a slot of [inFlight], bounded by [MAX_WAITING]. */
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Compiled once per policy change, matched per query (see [DomainMatcher]).
     *
     * Kept apart because they are waived apart: [familyDomains] is what this family typed and
     * applies to every app, [lists] is what a blocklist decided and does not apply to the apps in
     * [listExemptApps] (see `DomainFilter`).
     */
    @Volatile private var familyDomains: DomainMatcher = DomainMatcher.EMPTY
    @Volatile private var lists: DomainMatcher = DomainMatcher.EMPTY
    @Volatile private var listExemptApps: Set<String> = emptySet()
    @Volatile private var appRules: List<DomainAppRule> = emptyList()

    /** What the family lets through whatever the lists say (see `PolicySettings.allowedDomains`). */
    @Volatile private var allowedDomains: DomainMatcher = DomainMatcher.EMPTY

    /**
     * One established tunnel: its descriptor, the pipe that wakes its reader, and the reader.
     *
     * [active] belongs to THIS tunnel rather than to the service. A shared flag let a reader that
     * outlived its tunnel be told to carry on by the tunnel that replaced it, and a clean-up meant
     * for one tunnel can recognise now that it is looking at another.
     */
    private class Session(
        val pfd: ParcelFileDescriptor,
        /** Written to when the reader should stop, so it is woken instead of waited out. */
        val wakeRead: FileDescriptor?,
        val wakeWrite: FileDescriptor?,
    ) {
        @Volatile var active = true
        var reader: Thread? = null
    }

    /** The tunnel that is up, or null. Guarded by [lifecycleLock]. */
    private var session: Session? = null

    /** Read inside [writeLock] by every writer; null means "there is no tunnel right now". */
    private var tunFd: FileDescriptor? = null

    /** Consecutive failed attempts to establish, for the backoff a revocation needs. */
    @Volatile private var attempts = 0

    /** The one pending retry, so two failures in a row cannot start two chains of them. */
    private var retryJob: kotlinx.coroutines.Job? = null

    private lateinit var cm: ConnectivityManager
    private lateinit var repository: dev.walcott.data.WalcottRepository
    private lateinit var syncManager: dev.walcott.sync.SyncManager

    /**
     * Where an allowed query is forwarded, newest network first (see [DnsUpstreams]). Followed
     * live rather than read per query: reading LinkProperties is a binder call, and this sits in
     * the path of every DNS lookup the device makes.
     */
    @Volatile private var upstreams: List<String> = DnsUpstreams.FALLBACKS

    /**
     * Which of [upstreams] the network itself offered, as opposed to the public last resort
     * appended after them. Only these may become [lastGoodUpstream] (see [DnsUpstreams.ordered]).
     */
    @Volatile private var ownUpstreams: Set<String> = emptySet()

    /**
     * The network's own resolver that answered last, tried first next time.
     *
     * A network whose first resolver is merely slow costs its whole timeout on every single
     * lookup otherwise — the child's phone feels like it has no internet, and the filter is the
     * reason. Remembering the winner costs one field.
     */
    @Volatile private var lastGoodUpstream: String? = null

    /**
     * Bumped every time the resolvers change under us.
     *
     * A lookup already in flight when the phone hands over from Wi-Fi to mobile data is asking
     * resolvers that have gone. Without this it spends its whole budget doing so — three resolvers
     * at [UPSTREAM_TIMEOUT_MS] each — and the client's own retry, which WOULD use the new ones, is
     * delayed by exactly that. On a weak Wi-Fi that hands over repeatedly, that is most of the
     * time the phone feels broken.
     */
    @Volatile private var networkGeneration = 0

    /** UID → package, for as long as this tunnel lives (see [ownerPackage]). */
    private val uidPackages = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * Groups the several questions one name resolution asks back into one lookup.
     *
     * Everything a parent is shown about this filter is a count, and every count was of DNS
     * QUERIES: Android asks A and AAAA in parallel for every resolution and a browser adds HTTPS,
     * so "blocked today" was two to three times the truth and the domain viewer said "seen 2 times"
     * about a name touched once (see [LookupBursts]).
     */
    private val bursts = LookupBursts()

    /** DNS over TCP to anything this tunnel routes (see [DnsTcpResponder]). */
    private val dnsTcp = DnsTcpResponder(MTU)

    /**
     * Which lookup failures reach the debug log (see [OutageLog]). Offline, every single lookup
     * fails the same way, and one line each filled the diagnostics ring in minutes.
     */
    private val failedLookups = OutageLog()
    private val failedHandling = OutageLog()

    /**
     * What was last declared to the platform as the network underneath us, and whether anything
     * has been declared at all.
     *
     * Two fields rather than one because null is a real answer here — "follow the system default"
     * — and has to be distinguishable from "we have not spoken yet", which is the state a freshly
     * built tunnel is in.
     */
    private var declaredUnderlying: android.net.Network? = null
    private var underlyingDeclared = false

    /**
     * When the resolvers were last re-read because every one of them had failed.
     *
     * Atomic, and a compare-and-set rather than a check-then-write: when a network dies, all
     * [MAX_IN_FLIGHT] forwarders fail within the same millisecond and each would go and re-read
     * the network — a burst of binder calls and concurrent adoptions at the worst possible moment.
     */
    private val lastRecheckMs = java.util.concurrent.atomic.AtomicLong(0)

    /** Where the network callbacks run. Never the main looper: see [callbackHandler]. */
    private var callbackThread: android.os.HandlerThread? = null

    /**
     * The handler both network callbacks are registered on.
     *
     * Its own thread on purpose. Each event costs half a dozen binder round trips — enumerating
     * the networks, reading capabilities and link properties, declaring what is underneath us —
     * and on a phone that is moving they arrive several times a minute. On the main looper that is
     * exactly the kind of work this project forbids there, in the process the child's own screens
     * are drawn from.
     */
    private var callbackHandler: android.os.Handler? = null

    /**
     * The network underneath this VPN — never the VPN itself.
     *
     * Once the tunnel is up, `registerDefaultNetworkCallback` starts describing the tunnel: its
     * "DNS servers" are then our own sentinel, which is excluded from the upstream list, so the
     * filter would quietly forward every lookup on the phone to the public fallback. On a school
     * or office Wi-Fi that blocks outbound 53 that is a phone with no DNS at all, and the app
     * would still report the filter as healthy.
     */
    private val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: android.net.Network, link: android.net.LinkProperties) =
            guarded("adopting a network") {
                // Below Android 12 every matching network reports itself and the last one to speak
                // would win, so the event is a prompt to go and decide again rather than an answer.
                val real = bestUnderlying(network)
                if (real == null) {
                    // Nothing real to name. Say so rather than leaving a dead network declared.
                    followSystemDefault()
                    return@guarded
                }
                val properties =
                    if (real == network) link else runCatching { cm.getLinkProperties(real) }.getOrNull()
                synchronized(adoptLock) {
                    properties?.let { adoptUpstreams(it) }
                    declareUnderlying(real)
                }
            }

        override fun onLost(network: android.net.Network) = guarded("losing a network") {
            // The replacement is adopted HERE rather than waited for. The platform announces a new
            // default before the old one has finished disappearing, so by now there usually is one
            // — and taking it now makes the gap one callback long instead of however long the new
            // network takes to get round to describing its link properties.
            val replacement = bestUnderlying(null)
            if (replacement == null) {
                // Nothing to name. The resolvers are KEPT even so: a query arriving between
                // networks is better served by the last known one than by nothing, and an empty
                // list is never adopted anyway.
                //
                // What is not kept is the declaration. Everything the platform knows about this
                // tunnel — what carries it, whether that is metered, whether it is validated —
                // comes from what we declare, and naming a network the phone has left is a tunnel
                // describing something that no longer exists.
                followSystemDefault()
                return@guarded
            }
            val properties = runCatching { cm.getLinkProperties(replacement) }.getOrNull()
            synchronized(adoptLock) {
                properties?.let { adoptUpstreams(it) }
                declareUnderlying(replacement)
            }
        }
    }

    /**
     * Runs [block], and survives anything it throws.
     *
     * A `NetworkCallback` body runs on a framework thread with no exception barrier of its own: a
     * throwable out of one reaches the default uncaught handler and takes the whole process down.
     * On a child's phone that process is the enforcement service, so the cost of an unlucky log
     * line or a missing resource is a phone that has stopped being supervised, with the app still
     * saying it is on. `Throwable` rather than `Exception` deliberately — an OEM framework path
     * throwing `NoClassDefFoundError` is exactly the case worth surviving.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            DebugLog.e(TAG, "$what failed", t)
        }
    }

    /** Declares that this tunnel follows whatever the system is using, because we cannot name it. */
    private fun followSystemDefault() {
        synchronized(adoptLock) {
            if (underlyingDeclared && declaredUnderlying == null) return
            DebugLog.w(TAG, "no real network to name under the tunnel; following the system default")
            declareUnderlying(null)
        }
    }

    /** Whether [network] is a real network rather than a VPN — ours or anybody's. */
    private fun isRealNetwork(network: android.net.Network): Boolean = runCatching {
        cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
    }.getOrDefault(false)

    /**
     * The network this phone really reaches the internet through.
     *
     * `cm.activeNetwork` answers for the CALLING app, and Walcott keeps itself outside its own
     * tunnel (see [startTunnel]), so for us it is the physical network even while the tunnel is up
     * — the right answer, and the first one taken. It is still checked to be a real network,
     * because the exclusion can be refused: then this app is inside its tunnel, `activeNetwork` IS
     * the tunnel, and something else has to decide. On Android 12 and up [reported] is the
     * platform's own best match and is taken as read. Below that the callback reports every
     * network that matches, so [reported] is only a prompt: the candidates are ranked instead (see
     * [UnderlyingNetworks]), because the alternative is that a phone holding Wi-Fi and mobile data
     * at once asks the resolvers of whichever last changed.
     */
    private fun bestUnderlying(reported: android.net.Network?): android.net.Network? {
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (active != null && isRealNetwork(active)) return active
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return reported?.takeIf { isRealNetwork(it) }
        }
        return rankedUnderlying() ?: reported?.takeIf { isRealNetwork(it) }
    }

    /** The best of the phone's real networks, ranked rather than taken from whoever spoke last. */
    private fun rankedUnderlying(): android.net.Network? = runCatching {
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            UnderlyingNetworks.Candidate(
                network = network,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }
        UnderlyingNetworks.best(candidates)
    }.getOrNull()

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        val repo = (application as WalcottApplication).repository
        repository = repo
        syncManager = (application as WalcottApplication).syncManager
        scope.launch {
            // Two inputs, one matcher: the rules (typed domains + the bundled lists) and the
            // public lists this device has downloaded (see BlocklistStore). Recompiled when
            // either moves — a list that finishes downloading has to reach the filter without
            // waiting for the parent to touch a rule. Off the main thread: reading the cache is
            // disk IO, and it can be a couple of megabytes of it.
            val store = BlocklistStore.get(this@WalcottVpnService)
            // What [lists] was last compiled from. Only collectLatest touches it, one block at a
            // time: a block is cancelled and joined before the next one starts.
            var compiledFrom: ListsIdentity? = null
            var lastSummary: String? = null
            kotlinx.coroutines.flow.combine(repo.settingsFlow, store.state) { settings, state ->
                settings to state
            }
                .collectLatest { (settings, state) ->
                    // The downloaded half only when what it is made of changed (see ListsIdentity).
                    // Both flows emit far more often than that — every refresh pass confirms the
                    // lists with a 304 — and each rebuild read every enabled list off the disk again.
                    val identity = ListsIdentity.of(settings.enabledBlocklists, settings.blocklistDomains(), state)
                    if (identity != compiledFrom) {
                        val built = try {
                            // Streamed into the builder rather than collected into a set first: the
                            // downloaded half can be a million domains, and this way none of them is
                            // ever a live String beyond the line it was read on (see
                            // DomainMatcher.Builder). TWO matchers, not one: an app can be exempted
                            // from the lists and never from the domains the family typed (see
                            // PolicySettings.blocklistExemptApps), so the two have to stay answerable
                            // apart in the packet loop.
                            val builder = DomainMatcher.builder(
                                settings.blocklistDomains(),
                                expectedHashed = state.domainsFor(settings.enabledBlocklists),
                            )
                            store.readInto(settings.enabledBlocklists) { builder.addNormalized(it) }
                            builder.build()
                        } catch (oom: OutOfMemoryError) {
                            // Uncaught, this killed the process — which restarted, compiled the
                            // same lists, and died again. The matcher already in hand is kept: the
                            // lists as they were beat no filter at all and a phone in a crash loop.
                            DebugLog.e(TAG, "not enough memory to compile the lists; keeping the previous filter", oom)
                            null
                        }
                        // Recorded even when it failed, so the same inputs are not retried — and do
                        // not fail and log again — on every emission until the lists really change.
                        // Not recorded when cancelled: nothing reaches this line then.
                        compiledFrom = identity
                        if (built != null) lists = built
                    }
                    familyDomains = DomainMatcher.of(settings.blockedDomains)
                    appRules = settings.toDomainAppRules()
                    listExemptApps = settings.blocklistExemptApps
                    allowedDomains = DomainMatcher.of(settings.allowedDomains)
                    val summary = "filter compiled: ${familyDomains.size} of this family's own + ${lists.size} from lists" +
                        if (listExemptApps.isEmpty()) "" else " (lists waived for ${listExemptApps.size} app(s))"
                    // Said when it says something new, not on every confirmation of the same lists.
                    if (summary != lastSummary) DebugLog.i(TAG, summary)
                    lastSummary = summary
                }
        }
        // Seed from the current network, then follow it. The request asks for the network the
        // phone really uses to reach the internet and excludes VPNs, so this keeps answering
        // after our own tunnel is up (see [underlyingCallback]).
        runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()?.let { adoptUpstreams(it) }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val thread = android.os.HandlerThread("walcott-net").apply { start() }
        callbackThread = thread
        val handler = android.os.Handler(thread.looper)
        callbackHandler = handler
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 and up names its best match and nothing else, which is the question.
                cm.registerBestMatchingNetworkCallback(request, underlyingCallback, handler)
            } else {
                cm.registerNetworkCallback(request, underlyingCallback, handler)
            }
        }.onFailure {
            // Never quietly: this is the only thing that notices a hand-off once the tunnel is
            // up, and a recovery path disabled by a caught exception looks exactly like one that
            // is working.
            DebugLog.e(TAG, "CANNOT WATCH THE NETWORK UNDER THE TUNNEL; hand-offs will be missed", it)
        }
    }

    /**
     * Recomputes [upstreams] from a network's resolvers, logging only real changes.
     *
     * Callers hold [adoptLock]; this is one half of the adoption, not a thing to do on its own.
     */
    private fun adoptUpstreams(link: android.net.LinkProperties, acceptEmpty: Boolean = false) {
        val offered = runCatching { link.dnsServers.mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
        // An empty list is a network that has not finished describing itself, not a network with no
        // resolvers, and a link whose only resolvers are our own sentinel is this tunnel describing
        // itself. Neither is worth adopting; both used to send every lookup on the phone to the
        // public fallback (see [DnsUpstreams.worthAdopting]).
        if (!DnsUpstreams.worthAdopting(offered, OURS, acceptEmpty)) return
        val chosen = DnsUpstreams.choose(offered, exclude = OURS)
        // Even when the list itself is unchanged: a network that offered 1.1.1.1 and one that
        // offers nothing end up with the same list, and only in the first is 1.1.1.1 its own.
        ownUpstreams = DnsUpstreams.usable(offered, exclude = OURS).toSet()
        if (chosen != upstreams) {
            DebugLog.i(TAG, "DNS upstreams: ${chosen.joinToString()}")
            upstreams = chosen
            lastGoodUpstream = null
            // Anything already asking the old list is now asking a network that has gone.
            networkGeneration++
        }
    }

    /**
     * Tells the platform which real network this tunnel rides on.
     *
     * Without it the system accounts our traffic to the VPN alone and cannot work out whether
     * the connection underneath is metered, which is half of the reason a tunnel makes a phone
     * believe it is on mobile data (see [Builder.setMetered]).
     */
    private fun declareUnderlying(network: android.net.Network?) {
        // Already current — but "we declared nothing yet" is not the same as "we declared null",
        // and a freshly built tunnel has to speak even to say it cannot name anything.
        if (underlyingDeclared && network == declaredUnderlying) return
        declaredUnderlying = network
        underlyingDeclared = true
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
            .onFailure { DebugLog.w(TAG, "could not declare what carries the tunnel", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            synchronized(lifecycleLock) {
                // A retry still pending would bring back the tunnel this was asked to take down.
                retryJob?.cancel()
                retryJob = null
                stopTunnel()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startTunnel()
        return START_STICKY
    }

    /** Brings the tunnel up unless it already is. Every start goes through here, under the lock. */
    private fun startTunnel() {
        synchronized(lifecycleLock) {
            val current = session
            if (current != null && current.active) return
            // A tunnel whose reader has died and whose clean-up has not run yet. Cleaned up HERE,
            // before anything is built, so that clean-up cannot land later on the tunnel built next.
            if (current != null) stopTunnel()
            establishTunnel()
        }
    }

    /** Caller holds [lifecycleLock] and has checked that no tunnel is up. */
    private fun establishTunnel() {
        val builder = Builder()
            .setSession("Walcott filter")
            .addAddress(TUN_ADDR, 32)
            .addDnsServer(SENTINEL_DNS)
            .addRoute(SENTINEL_DNS, 32)
            // Bigger than any DNS message that can arrive over UDP, and the ceiling every reply
            // written back is measured against.
            .setMtu(MTU)
        // An IPv6 address of its own, and it is not decoration. A tunnel with no IPv6 address or
        // route has IPv6 BLOCKED for every app in it (the platform installs `::/0 unreachable`), so
        // a filter that only wanted to read DNS took IPv6 away from the whole phone. With an address,
        // IPv6 goes where it always went, except to the resolvers routed below.
        //
        // Deliberately not `allowFamily(AF_INET6)` instead: that lets IPv6 through with no route of
        // ours in the way, so an app with a resolver's IPv6 address compiled in would get round the
        // filter and the curfew. And deliberately no IPv6 DNS server: the sentinel never leaves the
        // phone, so the family of the network underneath means nothing to it — the tun carries an
        // IPv4 query to it on an IPv6-only network as well as anywhere — and a second sentinel would
        // only give the system resolver two servers to split its retries between, for one filter.
        runCatching { builder.addAddress(TUN_ADDR6, 128) }
            .onFailure { DebugLog.w(TAG, "could not give the tunnel an IPv6 address", it) }
        // The public resolvers by address, so asking one directly is not a way past the filter
        // or the curfew (see PublicResolvers). One refused route must not cost the others.
        for (route in PublicResolvers.ROUTES) {
            runCatching { builder.addRoute(route.address, route.prefix) }
                .onFailure { DebugLog.w(TAG, "could not route ${route.address}/${route.prefix}", it) }
        }
        // A VPN is assumed METERED unless it says otherwise, and the phone believes it about the
        // whole connection: Play holds automatic updates, cloud and photo backups stop, Data
        // Saver restricts background data. This tunnel carries DNS and nothing else, so the
        // assumption is simply false — and it is self-inflicted here, because the blocklist
        // refresh asks for an unmetered network and would never see one again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        // Deliberately NOT allowBypass(): it lets an app opt out of the tunnel by binding to the
        // underlying network, which on an ad blocker is a courtesy and here is a way round the
        // rules. The cost is that an app which binds a socket to a specific network (Android
        // Auto, some cast SDKs) is refused; the route table is not what decides that, this is.
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { DebugLog.e(TAG, "could not keep Walcott outside its own tunnel", it) }
        // A filter that fails to come up must say so: without this the child simply stops
        // being filtered, and nothing anywhere records that it happened.
        val established = runCatching { builder.establish() }
            .onFailure { DebugLog.e(TAG, "could not establish the DNS tunnel", it) }
            .getOrNull()
        if (established == null) {
            DebugLog.w(TAG, "DNS tunnel not established (no VPN consent?); filtering is OFF")
            VpnStatus.set(false)
            scheduleRetry()
            return
        }
        val pipe = runCatching { Os.pipe() }.getOrNull()
        val started = Session(established, wakeRead = pipe?.getOrNull(0), wakeWrite = pipe?.getOrNull(1))
        session = started
        synchronized(writeLock) { tunFd = established.fileDescriptor }
        // A new tunnel has declared nothing yet, whatever the old one had said.
        synchronized(adoptLock) {
            declaredUnderlying = null
            underlyingDeclared = false
        }
        attempts = 0
        uidPackages.clear()
        DebugLog.i(TAG, "DNS tunnel established")
        VpnStatus.set(true, lockdown = lockdownNow())
        started.reader = Thread({ runLoop(started) }, "walcott-dns").apply { isDaemon = true; start() }
    }

    /**
     * Re-establishes after a failure, backing off.
     *
     * What this is for is the ten seconds another VPN app holds the tunnel while it connects and
     * lets go. Without it the filter stayed off until the watchdog next ran — up to a quarter of
     * an hour of unfiltered browsing per revocation, with nothing anywhere saying so.
     */
    private fun scheduleRetry() {
        synchronized(lifecycleLock) {
            if (retryJob?.isActive == true) return
            val attempt = attempts++
            val delayMs = (RETRY_BASE_MS shl attempt.coerceAtMost(RETRY_MAX_SHIFT)).coerceAtMost(RETRY_MAX_MS)
            DebugLog.i(TAG, "retrying the tunnel in $delayMs ms (attempt ${attempt + 1})")
            retryJob = scope.launch {
                delay(delayMs)
                synchronized(lifecycleLock) {
                    // A stop request cancels this under the same lock, and a cancel that lands after
                    // the delay has run out is only visible here — past it, nothing would notice.
                    if (!isActive) return@launch
                    // No longer pending. Left set, the attempt below that fails would find this
                    // job still active and schedule nothing: the backoff stopped after one retry.
                    retryJob = null
                    startTunnel()
                }
            }
        }
    }

    /**
     * The read loop for one tunnel.
     *
     * `establish()` hands back a NON-BLOCKING descriptor, so a plain stream read returns
     * immediately whenever no packet is waiting, and looping on that spins a core flat out on an
     * idle phone with the screen off. So the loop parks in `poll()` and is woken either by a
     * packet or by the pipe [stopTunnel] writes to.
     */
    private fun runLoop(tunnel: Session) {
        val fd = tunnel.pfd.fileDescriptor
        val wake = tunnel.wakeRead
        val buffer = ByteArray(MAX_PACKET)
        val polls = buildList {
            add(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
            if (wake != null) add(StructPollfd().apply { this.fd = wake; events = OsConstants.POLLIN.toShort() })
        }.toTypedArray()

        // With the pipe, a stop wakes this at once and the timeout is only a safety net. Without
        // it (a pipe this process could not create), the timeout IS how a stop is noticed — and
        // the descriptor is closed a few seconds after being asked for, so it has to be short.
        val pollTimeout = if (wake != null) POLL_TIMEOUT_MS else POLL_TIMEOUT_NO_PIPE_MS
        while (tunnel.active) {
            polls.forEach { it.revents = 0 }
            val ready = try {
                Os.poll(polls, pollTimeout)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                DebugLog.w(TAG, "the tunnel stopped being pollable", e)
                break
            }
            if (polls.size > 1 && polls[1].revents.toInt() != 0) break // asked to stop
            if (ready == 0) continue
            val events = polls[0].revents.toInt()
            if (events and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
                DebugLog.w(TAG, "tunnel closed underneath us; stopping the filter")
                break
            }
            if (events and OsConstants.POLLIN == 0) continue
            val length = try {
                Os.read(fd, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                DebugLog.w(TAG, "the tunnel stopped being readable", e)
                break
            } catch (e: java.io.IOException) {
                DebugLog.w(TAG, "the tunnel stopped being readable", e)
                break
            }
            if (length <= 0) continue
            dispatch(buffer, length)
        }
        if (tunnel.active) {
            // We left because the descriptor died rather than because we were told to. The
            // cleanup happens on another thread on purpose: it joins this one, and a thread
            // cannot wait for itself — doing it inline would leave the dead descriptor open
            // until the next establish() replaced the field pointing at it.
            //
            // Marked inactive first, so a start arriving before the clean-up replaces this tunnel
            // instead of believing it is up; and the clean-up only touches THIS tunnel, so if that
            // start got there first there is nothing left for it to do.
            tunnel.active = false
            scope.launch {
                synchronized(lifecycleLock) {
                    if (session !== tunnel) return@launch
                    stopTunnel()
                    scheduleRetry()
                }
            }
        }
    }

    /** Decides what a frame off the tun is, and answers whatever cannot be a DNS query. */
    private fun dispatch(buffer: ByteArray, length: Int) {
        // Null for anything this filter does not act on, which includes what the kernel sends of
        // its own accord on a tun with an IPv6 address (see IpPackets): dropped without a word.
        val frame = IpPackets.parse(buffer, length) ?: return
        val packet = buffer.copyOf(length)
        when (frame.protocol) {
            IpPackets.PROTO_UDP -> Unit
            // A resolver whose reply came back truncated falls back to TCP, and so do connectivity
            // checks that "ping" 8.8.8.8:53 with a connect. Served when it is DNS, refused at once
            // otherwise; the tunnel used to swallow the SYN and the app looked hung.
            IpPackets.PROTO_TCP -> {
                dispatchTcp(packet)
                return
            }
            // Something checking whether its own DNS server is alive.
            IpPackets.PROTO_ICMP, IpPackets.PROTO_ICMPV6 -> {
                IpPackets.echoReply(packet)?.let { writePacket(it) }
                return
            }
            else -> return
        }
        // Everything routed here is routed so that DNS can be read, and until now every datagram
        // that arrived was read as a query whatever port it was addressed to — so a QUIC or
        // HTTP/3 handshake aimed at the sentinel had its bytes walked as a question and then sent
        // on to a real resolver on port 53. It is refused instead, and refused rather than
        // dropped: a connectionless protocol cannot tell silence from a slow network, so the
        // asker waited out its whole handshake timeout before trying anything else.
        if (IpPackets.destinationPort(packet) != DNS_PORT) {
            IpPackets.portUnreachable(packet)?.let { writePacket(it) }
            return
        }
        val dnsStart = IpPackets.dnsStart(packet) ?: return
        submit(
            Lookup(
                message = packet.copyOfRange(dnsStart, IpPackets.dnsEnd(packet)),
                protocol = OsConstants.IPPROTO_UDP,
                source = IpPackets.sourceAddress(packet) ?: return,
                sourcePort = IpPackets.sourcePort(packet) ?: return,
                destination = IpPackets.destinationAddress(packet) ?: return,
                destinationPort = DNS_PORT,
                maxAnswer = MTU - if (frame.version == 4) IPV4_UDP_OVERHEAD else IPV6_UDP_OVERHEAD,
                send = { answer -> IpPackets.udpResponse(packet, answer)?.let { writePacket(it) } },
            ),
        )
    }

    /**
     * A TCP segment off the tun: DNS when it is addressed to port 53, a reset otherwise.
     *
     * Under the responder's monitor from the segment to the last write, so what it hands back
     * reaches the tun in the order it was handed back — an answer written from the IO pool for
     * the same connection takes the same monitor (see [tcpLookup]).
     */
    private fun dispatchTcp(packet: ByteArray) {
        if (IpPackets.tcpSegment(packet)?.destinationPort != DNS_PORT) {
            IpPackets.tcpReset(packet)?.let { writePacket(it) }
            return
        }
        synchronized(dnsTcp) {
            val result = dnsTcp.onSegment(packet, SystemClock.elapsedRealtime())
            result.packets.forEach { writePacket(it) }
            result.queries.forEach { submit(tcpLookup(it)) }
        }
    }

    private fun tcpLookup(query: DnsTcpResponder.Query) = Lookup(
        message = query.message,
        protocol = OsConstants.IPPROTO_TCP,
        source = query.source,
        sourcePort = query.sourcePort,
        destination = query.destination,
        destinationPort = query.destinationPort,
        // Length-prefixed in sixteen bits, and cut into segments: nothing else limits it.
        maxAnswer = MAX_DNS_MESSAGE,
        send = { answer ->
            synchronized(dnsTcp) {
                dnsTcp.answer(query, answer, SystemClock.elapsedRealtime()).forEach { writePacket(it) }
            }
        },
    )

    /**
     * One DNS question, whichever way it arrived — a datagram, or a message on a TCP connection —
     * and the way back for its answer.
     */
    private class Lookup(
        /** The DNS message alone: header, question, and whatever the asker put after it. */
        val message: ByteArray,
        /** `IPPROTO_UDP` or `IPPROTO_TCP`, then both ends as the asking app's socket sees them. */
        val protocol: Int,
        val source: ByteArray,
        val sourcePort: Int,
        val destination: ByteArray,
        val destinationPort: Int,
        /** The largest DNS message the way back can carry. */
        val maxAnswer: Int,
        val send: (ByteArray) -> Unit,
    )

    /**
     * Hands a lookup to the IO pool, holding it until one of [MAX_IN_FLIGHT] slots is free.
     *
     * The wait is what used to be missing. Past the bound a query was answered SERVFAIL on the
     * spot — and right after a network hand-over, with a resolver that is merely slow, the
     * seventeenth lookup of a page load is exactly that query: the browser said
     * ERR_NAME_NOT_RESOLVED about a page that would only have been slow. So a query waits for a
     * slot for up to [SLOT_WAIT_MS], counted from when it arrived, suspended rather than parked on
     * a thread — the reader never waits, and neither does the pool — and only then is refused. How
     * many may wait is bounded as well ([MAX_WAITING]), so a flood of lookups is not a flood of
     * held packets.
     *
     * Identical questions in flight are not merged. It would save upstream traffic in exactly this
     * situation, but every asker has its own transaction id and transport, and a shared answer
     * rewritten per asker is a new way to hand one app another's reply.
     */
    private fun submit(lookup: Lookup) {
        if (inFlight.tryAcquire()) {
            scope.launch { runHolding(lookup) }
            return
        }
        if (waiting.incrementAndGet() > MAX_WAITING) {
            waiting.decrementAndGet()
            refuseNow(lookup)
            return
        }
        val arrived = SystemClock.elapsedRealtime()
        scope.launch {
            // Set inside the timeout and read instead of its result: a timeout can land after
            // acquire() has already handed over a slot, and withTimeoutOrNull then says null about
            // a slot this coroutine holds. The flag cannot be wrong, because nothing between the
            // acquire returning and the assignment can be interrupted.
            var acquired = false
            try {
                withTimeoutOrNull(SLOT_WAIT_MS - (SystemClock.elapsedRealtime() - arrived)) {
                    inFlight.acquire()
                    acquired = true
                }
            } catch (e: CancellationException) {
                if (acquired) inFlight.release()
                throw e
            } finally {
                waiting.decrementAndGet()
            }
            if (acquired) runHolding(lookup) else refuseNow(lookup)
        }
    }

    /** Runs [lookup] in the slot the caller acquired, and gives the slot back whatever happens. */
    private suspend fun runHolding(lookup: Lookup) {
        try {
            runCatching { handleLookup(lookup) }
                .onSuccess { failedHandling.succeeded() }
                .onFailure {
                    failedHandling.failed(SystemClock.elapsedRealtime())?.let { heldBack ->
                        DebugLog.w(TAG, "a query could not be handled" + OutageLog.heldBackSuffix(heldBack), it)
                    }
                }
        } finally {
            inFlight.release()
        }
    }

    /** Answers a query this loop will not process right now with SERVFAIL, so the app does not wait. */
    private fun refuseNow(lookup: Lookup) {
        respond(lookup, DnsMessage.RCODE_SERVER_FAILURE)
    }

    private suspend fun handleLookup(lookup: Lookup) {
        // The curfew is asked per query rather than compiled into the matchers above: it turns
        // over on the clock, and this service can be running with no enforcement loop behind it
        // to tell it (see NetworkCurfew). Cached there, so this costs a field read most times.
        //
        // Asked BEFORE the question is parsed, because it does not depend on the question.
        // A rescue code opens the whole phone, browsers included (see Curfew.standing).
        val cutOff = NetworkCurfew.cutOffNow(repository, rescued = syncManager.rescueOpenNow())
        // Which app asked is two binder round trips, and on the ordinary family nothing needs
        // the answer: no per-app domain rule, no app waived from the lists, no curfew running
        // and nobody watching the monitor. Asked only when some decision actually turns on it.
        val pkg = if (needsAttribution(cutOff)) ownerPackage(lookup) else null
        val host = DnsMessage.questionName(lookup.message)
        if (host == null) {
            // A question this loop cannot read is forwarded — fail-open is the rule here, and a
            // parser that meets something it does not expect must never cost the child their
            // DNS. An app that is supposed to resolve NOTHING is the one exception: that verdict
            // is about the app and not about the name, so a name we cannot read is not a way
            // round it. Uncounted, deliberately: there is no domain to count it against.
            if (pkg != null && pkg in cutOff) {
                respond(lookup, DnsMessage.RCODE_NAME_ERROR)
            } else {
                forward(lookup)
            }
            return
        }
        // Whether this question begins a resolution or joins one already counted. Decided ONCE,
        // here, and handed to both things that count — the live viewer and the persisted totals —
        // because two answers to the same question would disagree by a factor of two.
        val firstOfLookup = bursts.beginsLookup(
            host, pkg, DnsMessage.questionType(lookup.message), SystemClock.elapsedRealtime(),
        )
        // Both halves are already in hand, so a monitoring session is only a window onto a
        // decision this loop was making anyway. Recorded before the verdict on purpose: "this
        // app keeps trying X" is worth seeing even when X is already blocked. No-op otherwise.
        DomainMonitor.record(host, pkg, counts = firstOfLookup)

        if (DomainFilter.isBlocked(
                host, pkg, familyDomains, lists, appRules, listExemptApps,
                cutOff = cutOff,
                allowedDomains = allowedDomains,
            )
        ) {
            // Counted in memory and flushed elsewhere: this is the packet loop (see BlockCounters).
            // Once per resolution, not once per question.
            if (firstOfLookup) dev.walcott.data.BlockCounters.recordNetworkBlock(host, pkg)
            respond(lookup, DnsMessage.RCODE_NAME_ERROR)
        } else {
            forward(lookup)
        }
    }

    /**
     * Forwards the query to a real resolver and relays its answer, trying each in turn.
     *
     * Three things here were each their own bug. The socket is CONNECTED, so the kernel drops
     * anything that did not come from the resolver we asked — an unconnected one relayed the
     * first datagram to arrive, which anybody on the same Wi-Fi could be. The transaction id is
     * checked, for the same reason and against a late answer to an earlier query. And a resolver
     * that answers SERVFAIL or REFUSED is treated as not having answered: a router that hands
     * out a resolver refusing everything used to end the lookup there, so the phone resolved
     * nothing with the filter on and everything with it off.
     *
     * An exhausted list answers SERVFAIL rather than nothing. Silence is the worst possible
     * reply here: the asking app waits out its own timeout — seconds, on every lookup — and the
     * child experiences a phone whose internet has mysteriously become slow, with no clue that a
     * filter is involved. SERVFAIL fails immediately and lets the app say so.
     */
    private fun forward(lookup: Lookup) {
        val query = lookup.message
        val own = ownUpstreams
        val candidates = DnsUpstreams.ordered(upstreams, own, lastGoodUpstream)
        // The list we are about to walk belongs to this generation of the network.
        val generation = networkGeneration
        var refusal: ByteArray? = null
        for ((index, upstream) in candidates.withIndex()) {
            // Checked between attempts, not before the first: a change that arrived before we
            // started costs nothing, because the list above was already read after it. Once an
            // attempt has been spent, though, everything left in this list belongs to a network
            // the phone no longer has, and SERVFAIL now beats silence for the rest of the budget —
            // the app retries at once, against the resolvers that do work.
            if (index > 0 && networkGeneration != generation) {
                DebugLog.i(TAG, "the network changed mid-lookup; SERVFAIL so the app asks again")
                respond(lookup, DnsMessage.RCODE_SERVER_FAILURE)
                return
            }
            val answer = exchange(upstream, query) ?: continue
            if (DnsMessage.isServerFailure(DnsMessage.rcode(answer))) {
                // Kept, in case nobody does better than a refusal.
                if (refusal == null) refusal = answer
                continue
            }
            // Remembered only when it is one of the network's own resolvers, and only while that
            // network is still the one in hand (see DnsUpstreams.ordered): a public last resort that
            // beat a slow router once must not take the router's place for the rest of the day.
            if (upstream in own && networkGeneration == generation) lastGoodUpstream = upstream
            failedLookups.succeeded()
            relay(lookup, completed(upstream, query, answer, lookup.maxAnswer))
            return
        }
        refusal?.let {
            relay(lookup, it)
            return
        }
        failedLookups.failed(SystemClock.elapsedRealtime())?.let { heldBack ->
            DebugLog.w(
                TAG,
                "no upstream answered (${upstreams.joinToString()}); returning SERVFAIL" +
                    OutageLog.heldBackSuffix(heldBack),
            )
        }
        recheckResolvers()
        respond(lookup, DnsMessage.RCODE_SERVER_FAILURE)
    }

    /**
     * Re-reads the network's resolvers, at most once every [RECHECK_INTERVAL_MS].
     *
     * The resolvers may have changed under us while every one of them was failing — but when a
     * network dies, every forwarder in flight fails within the same millisecond and each of them
     * used to come here. That is a burst of binder calls and concurrent adoptions at the worst
     * possible moment. A compare-and-set, not a check-then-write, so exactly one of them proceeds.
     */
    private fun recheckResolvers() {
        val now = SystemClock.elapsedRealtime()
        val last = lastRecheckMs.get()
        if (now - last < RECHECK_INTERVAL_MS || !lastRecheckMs.compareAndSet(last, now)) return
        val link = runCatching { cm.getLinkProperties(bestUnderlying(null) ?: cm.activeNetwork) }.getOrNull()
        // The one caller that may believe an empty answer: we are here because the list in hand
        // already failed, so "this network offers none" is real and the fallback is right.
        if (link != null) synchronized(adoptLock) { adoptUpstreams(link, acceptEmpty = true) }
    }

    /**
     * The whole answer where [answer] says there is more of it, or [answer] unchanged.
     *
     * A resolver sets TC when its reply did not fit a datagram, and so does [exchange] when the
     * socket cut one. The asking app's move is then to ask again over TCP; asking over TCP here
     * first saves it the round trip, and is the difference between a DNSSEC-signed zone or a long
     * TXT record resolving at once and resolving on a retry.
     *
     * If the full answer will not fit the way back — [maxAnswer], a datagram through the tunnel —
     * the truncation is relayed after all, and the app's own TCP retry is answered by
     * [DnsTcpResponder], which has room for the whole of it.
     */
    private fun completed(upstream: String, query: ByteArray, answer: ByteArray, maxAnswer: Int): ByteArray {
        if (!DnsMessage.isTruncated(answer)) return answer
        val full = exchangeOverTcp(upstream, query) ?: return answer
        if (full.size > maxAnswer) {
            DebugLog.w(TAG, "the whole answer from $upstream does not fit the way back; relaying the truncation")
            return answer
        }
        return full
    }

    /**
     * The same question to the same resolver over TCP, length-prefixed as RFC 1035 §4.2.2 asks.
     *
     * The two-byte prefix is read with `readFully` and not one `read`: TCP gives no guarantee that
     * both bytes arrive in the same segment, and `InputStream.read(ByteArray)` is entitled to
     * return one. Treating that as a failure is how this path silently does nothing under load.
     *
     * Every timeout is set from what is left of one budget, floored at a millisecond, because in
     * Java `soTimeout = 0` does not mean "no time left" — it means block for ever, which on a
     * bounded pool of forwarders is a lookup that never returns.
     */
    private fun exchangeOverTcp(upstream: String, query: ByteArray): ByteArray? = runCatching {
        java.net.Socket().use { socket ->
            protect(socket)
            val deadline = SystemClock.elapsedRealtime() + TCP_FALLBACK_BUDGET_MS
            fun left(): Int = (deadline - SystemClock.elapsedRealtime()).toInt().coerceAtLeast(1)
            socket.soTimeout = left()
            socket.connect(InetSocketAddress(InetAddress.getByName(upstream), DNS_PORT), left())
            socket.getOutputStream().apply {
                write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
                write(query)
                flush()
            }
            val input = java.io.DataInputStream(socket.getInputStream())
            val header = ByteArray(2)
            socket.soTimeout = left()
            input.readFully(header)
            val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            if (size !in 1..MAX_DNS_MESSAGE) return@use null
            val body = ByteArray(size)
            socket.soTimeout = left()
            input.readFully(body)
            body.takeIf { DnsMessage.answersQuery(query, 0, it) }
        }
    }.getOrNull()

    /** One question to one resolver, or null when it did not answer this question. */
    private fun exchange(upstream: String, query: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            // Connected, so the kernel refuses datagrams from anybody but this resolver.
            socket.connect(InetSocketAddress(InetAddress.getByName(upstream), 53))
            socket.send(DatagramPacket(query, query.size))
            val buf = ByteArray(UPSTREAM_BUFFER)
            val deadline = SystemClock.elapsedRealtime() + UPSTREAM_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                // `receive` discards whatever did not fit — no error, no flag — and then reports
                // the BUFFER's length rather than the datagram's, so a cut answer is
                // indistinguishable from one that just fits. Relaying it would pass on a message
                // whose header claims records it no longer contains and which ends mid-record: the
                // asker throws it away as malformed and, TC being clear, has no reason to ask
                // again. So it becomes an honest truncation, which [completed] then finishes over
                // TCP.
                if (reply.length >= buf.size) {
                    DebugLog.w(TAG, "an answer from $upstream filled the buffer; asking again over TCP")
                    val cut = DnsMessage.truncated(buf, reply.length)
                    if (DnsMessage.answersQuery(query, 0, cut)) return@use cut
                    continue
                }
                val answer = buf.copyOf(reply.length)
                // A late answer to an earlier query must not be relayed as the answer to this
                // one; keep waiting out the same deadline rather than starting a new one.
                if (DnsMessage.answersQuery(query, 0, answer)) return@use answer
            }
            null
        }
    }.getOrNull()

    /** Relays a resolver's answer to the app that asked, the way it asked. */
    private fun relay(lookup: Lookup, answer: ByteArray) {
        lookup.send(answer)
    }

    /** Answers the query in [lookup] with [rcode] and no records. */
    private fun respond(lookup: Lookup, rcode: Int) {
        lookup.send(DnsMessage.answer(lookup.message, 0, rcode))
    }

    /**
     * Whether anything about this query's verdict depends on WHICH app asked.
     *
     * On a family with no per-app rules this is false for every lookup the phone makes, and the
     * two binder calls behind [ownerPackage] are pure cost in an always-on process.
     */
    private fun needsAttribution(cutOff: Set<String>): Boolean =
        appRules.isNotEmpty() || listExemptApps.isNotEmpty() || cutOff.isNotEmpty() ||
            DomainMonitor.isActive()

    /**
     * Best-effort attribution of the querying app via the socket owner UID.
     *
     * Asked with both ends exactly as the packet names them. The platform finds the socket by its
     * whole four-tuple, and this used to ask about a socket connected to the sentinel whatever the
     * query had really been sent to — so a lookup sent straight to a routed public resolver
     * belonged to no app: the per-app curfew let it through and an app waived from the lists had
     * them applied to it anyway.
     *
     * Cached for the life of the tunnel: this used to be two binder round trips and two address
     * parses on every single DNS lookup the phone made, in an always-on process. The cache is
     * dropped when the tunnel is rebuilt and when a package is added or removed, so a recycled
     * UID cannot be attributed to an app that has been uninstalled.
     */
    private fun ownerPackage(lookup: Lookup): String? {
        val uid = runCatching {
            // getByAddress on raw bytes never resolves anything, so this is no lookup of its own.
            cm.getConnectionOwnerUid(
                lookup.protocol,
                InetSocketAddress(InetAddress.getByAddress(lookup.source), lookup.sourcePort),
                InetSocketAddress(InetAddress.getByAddress(lookup.destination), lookup.destinationPort),
            )
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) return null
        uidPackages[uid]?.let { return it }
        val name = runCatching { packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
        return name?.also { uidPackages[uid] = it }
    }

    /** Whether "block connections without VPN" is on; it would leave this phone with no network. */
    private fun lockdownNow(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isLockdownEnabled }.getOrDefault(false)
        } else {
            false
        }

    private fun writePacket(packet: ByteArray) {
        if (packet.size > MTU) {
            DebugLog.w(TAG, "an answer of ${packet.size} bytes does not fit the tunnel's $MTU; dropped")
            return
        }
        synchronized(writeLock) {
            val fd = tunFd ?: return
            runCatching { Os.write(fd, packet, 0, packet.size) }
                .onFailure { DebugLog.w(TAG, "could not write an answer back to the tunnel", it) }
        }
    }

    /**
     * Takes the tunnel down, in the one order that is safe: stop, wake the reader, take the
     * descriptor away from the writers, join the reader, and only then close.
     *
     * Under [lifecycleLock], which is re-entrant, so the callers that already hold it can call this.
     */
    private fun stopTunnel() {
        synchronized(lifecycleLock) {
            val tunnel = session
            session = null
            tunnel?.active = false
            synchronized(writeLock) { tunFd = null }
            runCatching { tunnel?.wakeWrite?.let { Os.write(it, byteArrayOf(1), 0, 1) } }
            tunnel?.reader?.takeIf { it != Thread.currentThread() }?.let { thread ->
                runCatching { thread.join(READER_JOIN_MS) }
                if (thread.isAlive) DebugLog.w(TAG, "the tunnel's reader did not stop in time")
            }
            runCatching { tunnel?.wakeRead?.let { Os.close(it) } }
            runCatching { tunnel?.wakeWrite?.let { Os.close(it) } }
            runCatching { tunnel?.pfd?.close() }
            uidPackages.clear()
            bursts.clear()
            dnsTcp.clear()
            VpnStatus.set(false, lockdown = lockdownNow())
        }
    }

    /**
     * The system revoked our tun (another VPN took over, or the user withdrew consent).
     *
     * Deliberately does NOT call `super`, whose implementation is `stopSelf()` — the filter would
     * then stay off until something else happened to restart it. Another VPN connecting for ten
     * seconds is the ordinary case, and the backoff brings the tunnel back on its own.
     */
    override fun onRevoke() {
        DebugLog.w(TAG, "VPN consent revoked")
        synchronized(lifecycleLock) {
            stopTunnel()
            scheduleRetry()
        }
    }

    override fun onDestroy() {
        synchronized(lifecycleLock) {
            retryJob?.cancel()
            retryJob = null
            stopTunnel()
        }
        runCatching { cm.unregisterNetworkCallback(underlyingCallback) }
        // After unregistering, so nothing is still being delivered to a looper that has gone.
        runCatching { callbackThread?.quitSafely() }
        callbackThread = null
        callbackHandler = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TUN_ADDR = "10.111.222.1"
        private const val SENTINEL_DNS = "10.111.222.2"

        /**
         * The tun's IPv6 address: a unique local address (RFC 4193), never routed off the phone.
         * The global id spells "walcot", as malachi's spells its own name, so two filters on one
         * device can never claim the same address.
         */
        private const val TUN_ADDR6 = "fd00:7761:6c63:6f74::1"

        /**
         * Per-resolver timeout. Short on purpose: with up to [DnsUpstreams.MAX_UPSTREAMS] to try,
         * this bounds a fully dead list at a few seconds rather than the ~15 s that trying three
         * resolvers at the old single-shot timeout would have cost every lookup.
         */
        private const val UPSTREAM_TIMEOUT_MS = 2000

        /** What the tunnel carries, and the ceiling every answer written back is measured against. */
        private const val MTU = 4096

        /** Room for the largest UDP DNS answer that can come back and still fit the tunnel. */
        private const val UPSTREAM_BUFFER = MTU - 48

        private const val MAX_PACKET = MTU

        /** Queries handled concurrently; see [inFlight]. */
        private const val MAX_IN_FLIGHT = 16

        /** How long a query may wait for one of those slots before it is refused (see [submit]). */
        private const val SLOT_WAIT_MS = 1_500L

        /** How many may wait at once; past this a query is refused on arrival. */
        private const val MAX_WAITING = 256

        /** The only port this tunnel serves. */
        private const val DNS_PORT = 53

        /** This tunnel's own addresses, which can never be an upstream. */
        private val OURS = setOf(TUN_ADDR, SENTINEL_DNS, TUN_ADDR6)

        /** IP + UDP headers, the overhead every answer relayed as a datagram is measured against. */
        private const val IPV4_UDP_OVERHEAD = 28
        private const val IPV6_UDP_OVERHEAD = 48

        /** The largest DNS message TCP can frame (a sixteen-bit length prefix). */
        private const val MAX_DNS_MESSAGE = 0xFFFF

        /** The whole TCP retry of a truncated answer, connect included (see [exchangeOverTcp]). */
        private const val TCP_FALLBACK_BUDGET_MS = 3_000L

        /** How often the resolvers may be re-read after a total failure (see [recheckResolvers]). */
        private const val RECHECK_INTERVAL_MS = 5_000L

        /** How long the reader parks before looking at [Session.active] again. */
        private const val POLL_TIMEOUT_MS = 60_000

        /** The same, when there is no pipe to wake it and the timeout is the only way out. */
        private const val POLL_TIMEOUT_NO_PIPE_MS = 500

        /** How long a stop waits for the reader to leave the descriptor alone. */
        private const val READER_JOIN_MS = 4_000L

        private const val RETRY_BASE_MS = 5_000L
        private const val RETRY_MAX_SHIFT = 6
        private const val RETRY_MAX_MS = 5 * 60_000L

        private const val ACTION_STOP = "dev.walcott.net.STOP"
        private const val TAG = "WalcottVpn"

        fun start(context: Context) {
            context.startService(Intent(context, WalcottVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WalcottVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
