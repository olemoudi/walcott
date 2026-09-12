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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Local DNS filter over VpnService. Only the sentinel DNS server is routed through the tun,
 * so we see every DNS query, decide with [DomainFilter], and either answer NXDOMAIN (block)
 * or forward to a real upstream (allow). Everything else stays on the normal network.
 *
 * Fail-open by design: any parsing/attribution problem forwards the query rather than
 * dropping it, so the child never loses DNS resolution because of a bug here. One exception,
 * and it is not about a domain at all: an app the curfew has cut off resolves nothing, so a
 * question this loop cannot parse is not a way round it (see [dev.walcott.rules.Curfew]).
 * This blocks plain DNS only — apps using DoH/QUIC or hard-coded IPs are not caught (see
 * README); the phone's own Private DNS setting is kept off strict mode by [VpnController],
 * because that one would route every lookup past this service in two taps.
 *
 * The packet-level work lives in [IpPackets] and [DnsMessage], which are pure and tested. What
 * is left here is the part only a phone has: the descriptor, the sockets, and the lifecycle.
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
     * How many queries may be in flight at once. Each one can block a thread of the IO pool
     * for up to three resolver timeouts, and that pool is shared with the sync transport, the
     * blocklist store and the enforcement loop's writes: a Wi-Fi whose resolvers black-hole
     * (a captive portal, a bad DHCP answer) had a few hundred queued lookups starve all of them.
     * Past the bound a query is answered SERVFAIL at once — the app retries, nothing waits.
     */
    private val inFlight = kotlinx.coroutines.sync.Semaphore(MAX_IN_FLIGHT)

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
    @Volatile private var running = false

    private var tunnel: ParcelFileDescriptor? = null

    /** Read inside [writeLock] by every writer; null means "there is no tunnel right now". */
    private var tunFd: FileDescriptor? = null

    /** Written to when the reader should stop, so it is woken instead of waited out. */
    private var wakeRead: FileDescriptor? = null
    private var wakeWrite: FileDescriptor? = null
    private var reader: Thread? = null

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
    @Volatile private var upstreams: List<String> = listOf(DnsUpstreams.FALLBACK)

    /**
     * The resolver that answered last, tried first next time.
     *
     * A network whose first resolver is merely slow costs its whole timeout on every single
     * lookup otherwise — the child's phone feels like it has no internet, and the filter is the
     * reason. Remembering the winner costs one field.
     */
    @Volatile private var lastGoodUpstream: String? = null

    /** UID → package, for as long as this tunnel lives (see [ownerPackage]). */
    private val uidPackages = java.util.concurrent.ConcurrentHashMap<Int, String>()

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
        override fun onLinkPropertiesChanged(network: android.net.Network, link: android.net.LinkProperties) {
            // Below Android 12 every matching network reports itself and the last one to speak
            // would win, so the event is a signal to go and decide again rather than an answer.
            val real = bestUnderlying(network) ?: return
            val properties = if (real == network) link else runCatching { cm.getLinkProperties(real) }.getOrNull()
            properties?.let { adoptUpstreams(it) }
            declareUnderlying(real)
        }

        override fun onLost(network: android.net.Network) {
            // Keep whatever we had: a query arriving between networks is better served by the
            // last known resolver than by nothing, and the next onLinkPropertiesChanged fixes it.
        }
    }

    /** Whether [network] is a real network rather than a VPN — ours or anybody's. */
    private fun isRealNetwork(network: android.net.Network): Boolean = runCatching {
        cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
    }.getOrDefault(false)

    /**
     * The network this phone really reaches the internet through, or [reported] when the active
     * one is a VPN — which, once our own tunnel is up, it always is.
     */
    private fun bestUnderlying(reported: android.net.Network?): android.net.Network? {
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (active != null && isRealNetwork(active)) return active
        return reported?.takeIf { isRealNetwork(it) }
    }

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
            kotlinx.coroutines.flow.combine(repo.settingsFlow, store.state) { settings, state ->
                settings to state
            }
                .collectLatest { (settings, state) ->
                    // Streamed into the builder rather than collected into a set first: the
                    // downloaded half can be a million domains, and this way none of them is ever
                    // a live String beyond the line it was read on (see DomainMatcher.Builder).
                    // TWO matchers, not one: an app can be exempted from the lists and never from
                    // the domains the family typed (see PolicySettings.blocklistExemptApps), so
                    // the two have to stay answerable apart in the packet loop.
                    val builder = DomainMatcher.builder(
                        settings.blocklistDomains(),
                        expectedHashed = state.domainsFor(settings.enabledBlocklists),
                    )
                    store.readInto(settings.enabledBlocklists) { builder.addNormalized(it) }
                    lists = builder.build()
                    familyDomains = DomainMatcher.of(settings.blockedDomains)
                    appRules = settings.toDomainAppRules()
                    listExemptApps = settings.blocklistExemptApps
                    DebugLog.i(
                        TAG,
                        "filter compiled: ${familyDomains.size} of this family's own + ${lists.size} from lists" +
                            if (listExemptApps.isEmpty()) "" else " (lists waived for ${listExemptApps.size} app(s))",
                    )
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
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
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

    /** Recomputes [upstreams] from a network's resolvers, logging only real changes. */
    private fun adoptUpstreams(link: android.net.LinkProperties) {
        val offered = runCatching { link.dnsServers.mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
        // Belt and braces on top of the NOT_VPN request: a link whose only resolvers are our own
        // sentinel is this tunnel describing itself, and adopting it would send every lookup on
        // the phone to the public fallback — or, on a network that blocks outbound 53, nowhere.
        if (offered.isNotEmpty() && offered.all { it.substringBefore('%') in setOf(TUN_ADDR, SENTINEL_DNS) }) {
            return
        }
        val chosen = DnsUpstreams.choose(offered, exclude = setOf(TUN_ADDR, SENTINEL_DNS))
        if (chosen != upstreams) {
            DebugLog.i(TAG, "DNS upstreams: ${chosen.joinToString()}")
            upstreams = chosen
            lastGoodUpstream = null
        }
    }

    /**
     * Tells the platform which real network this tunnel rides on.
     *
     * Without it the system accounts our traffic to the VPN alone and cannot work out whether
     * the connection underneath is metered, which is half of the reason a tunnel makes a phone
     * believe it is on mobile data (see [Builder.setMetered]).
     */
    private fun declareUnderlying(network: android.net.Network) {
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        val builder = Builder()
            .setSession("Walcott filter")
            .addAddress(TUN_ADDR, 32)
            .addDnsServer(SENTINEL_DNS)
            .addRoute(SENTINEL_DNS, 32)
            // Bigger than any DNS message that can arrive over UDP, and the ceiling every reply
            // written back is measured against.
            .setMtu(MTU)
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
            running = false
            VpnStatus.set(false)
            scheduleRetry()
            return
        }
        val pipe = runCatching { Os.pipe() }.getOrNull()
        tunnel = established
        wakeRead = pipe?.getOrNull(0)
        wakeWrite = pipe?.getOrNull(1)
        synchronized(writeLock) { tunFd = established.fileDescriptor }
        attempts = 0
        uidPackages.clear()
        DebugLog.i(TAG, "DNS tunnel established")
        VpnStatus.set(true, lockdown = lockdownNow())
        reader = Thread({ runLoop(established) }, "walcott-dns").apply { isDaemon = true; start() }
    }

    /**
     * Re-establishes after a failure, backing off.
     *
     * What this is for is the ten seconds another VPN app holds the tunnel while it connects and
     * lets go. Without it the filter stayed off until the watchdog next ran — up to a quarter of
     * an hour of unfiltered browsing per revocation, with nothing anywhere saying so.
     */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val attempt = attempts++
        val delayMs = (RETRY_BASE_MS shl attempt.coerceAtMost(RETRY_MAX_SHIFT)).coerceAtMost(RETRY_MAX_MS)
        DebugLog.i(TAG, "retrying the tunnel in $delayMs ms (attempt ${attempt + 1})")
        retryJob = scope.launch {
            delay(delayMs)
            if (!running && tunnel == null) {
                running = true
                startTunnel()
            }
        }
    }

    /**
     * The read loop.
     *
     * `establish()` hands back a NON-BLOCKING descriptor, so a plain stream read returns
     * immediately whenever no packet is waiting, and looping on that spins a core flat out on an
     * idle phone with the screen off. So the loop parks in `poll()` and is woken either by a
     * packet or by the pipe [stopTunnel] writes to.
     */
    private fun runLoop(pfd: ParcelFileDescriptor) {
        val fd = pfd.fileDescriptor
        val wake = wakeRead
        val buffer = ByteArray(MAX_PACKET)
        val polls = buildList {
            add(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
            if (wake != null) add(StructPollfd().apply { this.fd = wake; events = OsConstants.POLLIN.toShort() })
        }.toTypedArray()

        // With the pipe, a stop wakes this at once and the timeout is only a safety net. Without
        // it (a pipe this process could not create), the timeout IS how a stop is noticed — and
        // the descriptor is closed a few seconds after being asked for, so it has to be short.
        val pollTimeout = if (wake != null) POLL_TIMEOUT_MS else POLL_TIMEOUT_NO_PIPE_MS
        while (running) {
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
        if (running) {
            // We left because the descriptor died rather than because we were told to. The
            // cleanup happens on another thread on purpose: it joins this one, and a thread
            // cannot wait for itself — doing it inline would leave the dead descriptor open
            // until the next establish() replaced the field pointing at it.
            running = false
            scope.launch {
                stopTunnel()
                scheduleRetry()
            }
        }
    }

    /** Decides what a frame off the tun is, and answers whatever cannot be a DNS query. */
    private fun dispatch(buffer: ByteArray, length: Int) {
        val frame = IpPackets.parse(buffer, length)
        if (frame == null) return
        val packet = buffer.copyOf(length)
        when (frame.protocol) {
            IpPackets.PROTO_UDP -> Unit
            // A resolver whose reply came back truncated falls back to TCP; the tunnel used to
            // swallow the SYN and the app waited out its connect timeout looking hung.
            IpPackets.PROTO_TCP -> {
                IpPackets.tcpReset(packet)?.let { writePacket(it) }
                return
            }
            // Something checking whether its own DNS server is alive.
            IpPackets.PROTO_ICMP -> {
                IpPackets.echoReply(packet)?.let { writePacket(it) }
                return
            }
            else -> return
        }
        if (IpPackets.dnsStart(packet) == null) return
        if (!inFlight.tryAcquire()) {
            refuseNow(packet)
            return
        }
        scope.launch {
            try {
                runCatching { handleDnsPacket(packet) }
                    .onFailure { DebugLog.w(TAG, "a query could not be handled", it) }
            } finally {
                inFlight.release()
            }
        }
    }

    /** Answers a query this loop will not process right now with SERVFAIL, so the app does not wait. */
    private fun refuseNow(packet: ByteArray) {
        val dnsStart = IpPackets.dnsStart(packet) ?: return
        respond(packet, dnsStart, DnsMessage.RCODE_SERVER_FAILURE)
    }

    private suspend fun handleDnsPacket(packet: ByteArray) {
        val dnsStart = IpPackets.dnsStart(packet) ?: return
        val dnsEnd = IpPackets.dnsEnd(packet)
        val srcPort = IpPackets.sourcePort(packet) ?: return

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
        val pkg = if (needsAttribution(cutOff)) ownerPackage(srcPort) else null
        val host = DnsMessage.questionName(packet, dnsStart)
        if (host == null) {
            // A question this loop cannot read is forwarded — fail-open is the rule here, and a
            // parser that meets something it does not expect must never cost the child their
            // DNS. An app that is supposed to resolve NOTHING is the one exception: that verdict
            // is about the app and not about the name, so a name we cannot read is not a way
            // round it. Uncounted, deliberately: there is no domain to count it against.
            if (pkg != null && pkg in cutOff) {
                respond(packet, dnsStart, DnsMessage.RCODE_NAME_ERROR)
            } else {
                forward(packet, dnsStart, dnsEnd)
            }
            return
        }
        // Both halves are already in hand, so a monitoring session is only a window onto a
        // decision this loop was making anyway. Recorded before the verdict on purpose: "this
        // app keeps trying X" is worth seeing even when X is already blocked. No-op otherwise.
        DomainMonitor.record(host, pkg)

        if (DomainFilter.isBlocked(
                host, pkg, familyDomains, lists, appRules, listExemptApps,
                cutOff = cutOff,
            )
        ) {
            // Counted in memory and flushed elsewhere: this is the packet loop (see BlockCounters).
            dev.walcott.data.BlockCounters.recordNetworkBlock(host, pkg)
            respond(packet, dnsStart, DnsMessage.RCODE_NAME_ERROR)
        } else {
            forward(packet, dnsStart, dnsEnd)
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
    private fun forward(packet: ByteArray, dnsStart: Int, dnsEnd: Int) {
        val query = packet.copyOfRange(dnsStart, dnsEnd)
        var refusal: ByteArray? = null
        for (upstream in orderedUpstreams()) {
            val answer = exchange(upstream, query) ?: continue
            if (DnsMessage.isServerFailure(DnsMessage.rcode(answer))) {
                // Kept, in case nobody does better than a refusal.
                if (refusal == null) refusal = answer
                continue
            }
            lastGoodUpstream = upstream
            relay(packet, answer)
            return
        }
        refusal?.let {
            relay(packet, it)
            return
        }
        DebugLog.w(TAG, "no upstream answered (${upstreams.joinToString()}); returning SERVFAIL")
        // The resolvers may have changed under us while every one of them was failing.
        runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()?.let { adoptUpstreams(it) }
        respond(packet, dnsStart, DnsMessage.RCODE_SERVER_FAILURE)
    }

    /** The resolvers to try, the one that answered last time first. */
    private fun orderedUpstreams(): List<String> {
        val best = lastGoodUpstream ?: return upstreams
        if (upstreams.firstOrNull() == best || best !in upstreams) return upstreams
        return listOf(best) + upstreams.filterNot { it == best }
    }

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
                // A datagram that fills the buffer exactly may have been cut off by it, and a
                // truncated answer relayed without its TC bit is a lie the asking app cannot
                // detect. Refuse rather than pass on a message we cannot vouch for.
                if (reply.length >= buf.size) {
                    DebugLog.w(TAG, "an answer from $upstream was too big for the tunnel; refusing it")
                    return@use null
                }
                val answer = buf.copyOf(reply.length)
                // A late answer to an earlier query must not be relayed as the answer to this
                // one; keep waiting out the same deadline rather than starting a new one.
                if (DnsMessage.answersQuery(query, 0, answer)) return@use answer
            }
            null
        }
    }.getOrNull()

    /** Wraps a resolver's answer for the app that asked, refusing one too big for the tunnel. */
    private fun relay(request: ByteArray, answer: ByteArray) {
        IpPackets.udpResponse(request, answer)?.let { writePacket(it) }
    }

    /** Answers the query in [packet] with [rcode] and no records. */
    private fun respond(packet: ByteArray, dnsStart: Int, rcode: Int) {
        val dns = DnsMessage.answer(packet, dnsStart, rcode)
        IpPackets.udpResponse(packet, dns)?.let { writePacket(it) }
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
     * Cached for the life of the tunnel: this used to be two binder round trips and two address
     * parses on every single DNS lookup the phone made, in an always-on process. The cache is
     * dropped when the tunnel is rebuilt and when a package is added or removed, so a recycled
     * UID cannot be attributed to an app that has been uninstalled.
     */
    private fun ownerPackage(srcPort: Int): String? {
        val uid = runCatching {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(tunAddress, srcPort),
                InetSocketAddress(sentinelAddress, 53),
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
     */
    private fun stopTunnel() {
        running = false
        synchronized(writeLock) { tunFd = null }
        runCatching { wakeWrite?.let { Os.write(it, byteArrayOf(1), 0, 1) } }
        reader?.takeIf { it != Thread.currentThread() }?.let { thread ->
            runCatching { thread.join(READER_JOIN_MS) }
            if (thread.isAlive) DebugLog.w(TAG, "the tunnel's reader did not stop in time")
        }
        reader = null
        runCatching { wakeRead?.let { Os.close(it) } }
        runCatching { wakeWrite?.let { Os.close(it) } }
        wakeRead = null
        wakeWrite = null
        runCatching { tunnel?.close() }
        tunnel = null
        uidPackages.clear()
        VpnStatus.set(false, lockdown = lockdownNow())
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
        stopTunnel()
        scheduleRetry()
    }

    override fun onDestroy() {
        stopTunnel()
        runCatching { cm.unregisterNetworkCallback(underlyingCallback) }
        scope.cancel()
        super.onDestroy()
    }

    private val tunAddress: InetAddress by lazy { InetAddress.getByName(TUN_ADDR) }
    private val sentinelAddress: InetAddress by lazy { InetAddress.getByName(SENTINEL_DNS) }

    companion object {
        private const val TUN_ADDR = "10.111.222.1"
        private const val SENTINEL_DNS = "10.111.222.2"

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

        /** How long the reader parks before looking at [running] again. */
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
