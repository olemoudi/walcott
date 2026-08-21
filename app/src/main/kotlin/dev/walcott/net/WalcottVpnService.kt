package dev.walcott.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.rules.DomainAppRule
import dev.walcott.rules.DomainFilter
import dev.walcott.rules.DomainMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
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
 * dropping it, so the child never loses DNS resolution because of a bug here. This blocks
 * plain DNS only — apps using DoH/QUIC or hard-coded IPs are not caught (see README).
 */
class WalcottVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()

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
    private lateinit var cm: ConnectivityManager
    private lateinit var repository: dev.walcott.data.WalcottRepository

    /**
     * Where an allowed query is forwarded, newest network first (see [DnsUpstreams]). Followed
     * live rather than read per query: reading LinkProperties is a binder call, and this sits in
     * the path of every DNS lookup the device makes.
     */
    @Volatile private var upstreams: List<String> = listOf(DnsUpstreams.FALLBACK)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: android.net.Network, link: android.net.LinkProperties) {
            adoptUpstreams(link)
        }

        override fun onLost(network: android.net.Network) {
            // Keep whatever we had: a query arriving between networks is better served by the
            // last known resolver than by nothing, and the next onLinkPropertiesChanged fixes it.
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        val repo = (application as WalcottApplication).repository
        repository = repo
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
        // Seed from the current network, then follow it. registerDefaultNetworkCallback reports
        // the network the device actually uses, which is the one whose resolvers we want.
        runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()?.let { adoptUpstreams(it) }
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { DebugLog.w(TAG, "could not follow the network's DNS servers", it) }
    }

    /** Recomputes [upstreams] from a network's resolvers, logging only real changes. */
    private fun adoptUpstreams(link: android.net.LinkProperties) {
        val offered = runCatching { link.dnsServers.mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
        val chosen = DnsUpstreams.choose(offered, exclude = setOf(TUN_ADDR, SENTINEL_DNS))
        if (chosen != upstreams) {
            DebugLog.i(TAG, "DNS upstreams: ${chosen.joinToString()}")
            upstreams = chosen
        }
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
        runCatching { builder.addDisallowedApplication(packageName) }
        // A filter that fails to come up must say so: without this the child simply stops
        // being filtered, and nothing anywhere records that it happened.
        tunnel = runCatching { builder.establish() }
            .onFailure { DebugLog.e(TAG, "could not establish the DNS tunnel", it) }
            .getOrNull()
            ?: run {
                DebugLog.w(TAG, "DNS tunnel not established (no VPN consent?); filtering is OFF")
                running = false
                VpnStatus.set(false)
                return
            }
        DebugLog.i(TAG, "DNS tunnel established")
        VpnStatus.set(true)
        scope.launch { runLoop(tunnel!!) }
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(MAX_PACKET)
        while (running) {
            val length = runCatching { input.read(packet) }.getOrDefault(-1)
            // End of stream means the tun is gone — revoked by the user, replaced by another
            // VPN app, or torn down by the system. Reading a dead descriptor returns instantly,
            // so continuing here spun this thread at 100% CPU on the child's phone until the
            // process died. Stand down instead; the watchdog re-establishes the filter.
            if (length < 0) {
                DebugLog.w(TAG, "tunnel closed underneath us; stopping the filter")
                break
            }
            if (length == 0) continue
            val copy = packet.copyOf(length)
            scope.launch { runCatching { handleDnsPacket(copy, output) } }
        }
        stopTunnel()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private suspend fun handleDnsPacket(packet: ByteArray, output: FileOutputStream) {
        // IPv4 + UDP only; anything else shouldn't reach the tun given our routes.
        if (packet.size < 28) return
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet[9].toInt() and 0xFF != OsConstants.IPPROTO_UDP) return

        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val udp = ihl
        val srcPort = ((packet[udp].toInt() and 0xFF) shl 8) or (packet[udp + 1].toInt() and 0xFF)
        val dnsStart = udp + 8
        if (dnsStart >= packet.size) return

        val host = parseDnsQuestion(packet, dnsStart) ?: return forward(packet, dnsStart, srcIp, srcPort, output)
        val pkg = ownerPackage(srcPort)
        // Both halves are already in hand, so a monitoring session is only a window onto a
        // decision this loop was making anyway. Recorded before the verdict on purpose: "this
        // app keeps trying X" is worth seeing even when X is already blocked. No-op otherwise.
        DomainMonitor.record(host, pkg)

        // The curfew is asked per query rather than compiled into the matchers above: it turns
        // over on the clock, and this service can be running with no enforcement loop behind it
        // to tell it (see NetworkCurfew). Cached there, so this costs a field read most times.
        if (DomainFilter.isBlocked(
                host, pkg, familyDomains, lists, appRules, listExemptApps,
                cutOff = NetworkCurfew.cutOffNow(repository),
            )
        ) {
            // Counted in memory and flushed elsewhere: this is the packet loop (see BlockCounters).
            dev.walcott.data.BlockCounters.recordNetworkBlock(host, pkg)
            writePacket(output, buildResponse(packet, dnsStart, nxDomain(packet, dnsStart)))
        } else {
            forward(packet, dnsStart, srcIp, srcPort, output)
        }
    }

    /**
     * Forwards the raw DNS query to a real upstream and relays the answer back to the tun,
     * trying each resolver in turn.
     *
     * An exhausted list answers SERVFAIL rather than nothing. Silence is the worst possible
     * reply here: the asking app waits out its own timeout — seconds, on every lookup — and the
     * child experiences a phone whose internet has mysteriously become slow, with no clue that a
     * filter is involved. SERVFAIL fails immediately and lets the app say so.
     */
    private fun forward(packet: ByteArray, dnsStart: Int, srcIp: InetAddress, srcPort: Int, output: FileOutputStream) {
        val query = packet.copyOfRange(dnsStart, packet.size)
        for (upstream in upstreams) {
            val answer = runCatching {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByName(upstream), 53))
                    val buf = ByteArray(MAX_PACKET)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    buf.copyOf(reply.length)
                }
            }.getOrNull()
            if (answer != null) {
                writePacket(output, buildResponse(packet, dnsStart, answer))
                return
            }
        }
        DebugLog.w(TAG, "no upstream answered (${upstreams.joinToString()}); returning SERVFAIL")
        writePacket(output, buildResponse(packet, dnsStart, servFail(packet, dnsStart)))
    }

    /** Best-effort attribution of the querying app via the socket owner UID. */
    private fun ownerPackage(srcPort: Int): String? {
        val uid = runCatching {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByName(TUN_ADDR), srcPort),
                InetSocketAddress(InetAddress.getByName(SENTINEL_DNS), 53),
            )
        }.getOrDefault(Process.INVALID_UID)
        if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) return null
        return runCatching { packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
    }

    private fun parseDnsQuestion(data: ByteArray, dnsStart: Int): String? {
        var i = dnsStart + 12 // skip the 12-byte DNS header
        val sb = StringBuilder()
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null // compression pointer not expected in a question
            i++
            if (i + len > data.size) return null
            for (j in 0 until len) sb.append((data[i + j].toInt() and 0xFF).toChar())
            sb.append('.')
            i += len
        }
        val name = sb.toString().trimEnd('.')
        return name.ifEmpty { null }
    }

    /** Turns the query bytes into an NXDOMAIN response (QR=1, RCODE=3). */
    private fun nxDomain(packet: ByteArray, dnsStart: Int): ByteArray {
        val dns = packet.copyOfRange(dnsStart, packet.size)
        dns[2] = (dns[2].toInt() or 0x80).toByte() // QR = 1
        dns[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        return dns
    }

    /**
     * Turns the query bytes into a SERVFAIL response (QR=1, RCODE=2) — "this resolver is
     * broken", not "this name does not exist". The distinction matters: NXDOMAIN is cached and
     * would make a passing network problem look like a permanently missing domain.
     */
    private fun servFail(packet: ByteArray, dnsStart: Int): ByteArray {
        val dns = packet.copyOfRange(dnsStart, packet.size)
        dns[2] = (dns[2].toInt() or 0x80).toByte() // QR = 1
        dns[3] = 0x82.toByte() // RA=1, RCODE=2 (SERVFAIL)
        return dns
    }

    /** Wraps [dns] in a fresh IPv4+UDP packet from the sentinel back to the original sender. */
    private fun buildResponse(request: ByteArray, dnsStart: Int, dns: ByteArray): ByteArray {
        val udpReq = (request[0].toInt() and 0x0F) * 4
        val srcPort = ((request[udpReq].toInt() and 0xFF) shl 8) or (request[udpReq + 1].toInt() and 0xFF)
        val total = 20 + 8 + dns.size
        val out = ByteArray(total)
        // IPv4 header
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64 // TTL
        out[9] = OsConstants.IPPROTO_UDP.toByte()
        System.arraycopy(request, 16, out, 12, 4) // src = original dst (sentinel)
        System.arraycopy(request, 12, out, 16, 4) // dst = original src
        val ipChecksum = checksum(out, 0, 20)
        out[10] = (ipChecksum shr 8).toByte(); out[11] = ipChecksum.toByte()
        // UDP header (checksum 0 = allowed for IPv4)
        out[20] = 0x00; out[21] = 53.toByte() // src port 53
        out[22] = (srcPort shr 8).toByte(); out[23] = srcPort.toByte()
        val udpLen = 8 + dns.size
        out[24] = (udpLen shr 8).toByte(); out[25] = udpLen.toByte()
        System.arraycopy(dns, 0, out, 28, dns.size)
        return out
    }

    private fun checksum(buf: ByteArray, start: Int, len: Int): Int {
        var sum = 0L
        var i = start
        var remaining = len
        while (remaining > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2; remaining -= 2
        }
        if (remaining == 1) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun writePacket(output: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) { runCatching { output.write(packet) } }
    }

    private fun stopTunnel() {
        running = false
        runCatching { tunnel?.close() }
        tunnel = null
        VpnStatus.set(false)
    }

    /**
     * The system revoked our tun (another VPN took over, or the user withdrew consent). Without
     * this the service kept "running" over a dead descriptor; [runLoop] would spin and the
     * filter would look alive while filtering nothing.
     */
    override fun onRevoke() {
        DebugLog.w(TAG, "VPN consent revoked")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TUN_ADDR = "10.111.222.1"
        private const val SENTINEL_DNS = "10.111.222.2"
        /**
         * Per-resolver timeout. Short on purpose: with up to [DnsUpstreams.MAX_UPSTREAMS] to try,
         * this bounds a fully dead list at a few seconds rather than the ~15 s that trying three
         * resolvers at the old single-shot timeout would have cost every lookup.
         */
        private const val UPSTREAM_TIMEOUT_MS = 2000
        private const val MAX_PACKET = 32767
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
