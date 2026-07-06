package dev.walcott.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import dev.walcott.WalcottApplication
import dev.walcott.rules.DomainAppRule
import dev.walcott.rules.DomainFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Volatile private var blockedDomains: Set<String> = emptySet()
    @Volatile private var appRules: List<DomainAppRule> = emptyList()
    @Volatile private var running = false
    private var tunnel: ParcelFileDescriptor? = null
    private lateinit var cm: ConnectivityManager

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        val repo = (application as WalcottApplication).repository
        scope.launch {
            repo.settingsFlow.collect { settings ->
                blockedDomains = settings.blockedDomains
                appRules = settings.toDomainAppRules()
            }
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
        tunnel = runCatching { builder.establish() }.getOrNull() ?: run { running = false; return }
        scope.launch { runLoop(tunnel!!) }
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(MAX_PACKET)
        while (running) {
            val length = runCatching { input.read(packet) }.getOrDefault(-1)
            if (length <= 0) continue
            val copy = packet.copyOf(length)
            scope.launch { runCatching { handleDnsPacket(copy, output) } }
        }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun handleDnsPacket(packet: ByteArray, output: FileOutputStream) {
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

        if (DomainFilter.isBlocked(host, pkg, blockedDomains, appRules)) {
            writePacket(output, buildResponse(packet, dnsStart, nxDomain(packet, dnsStart)))
        } else {
            forward(packet, dnsStart, srcIp, srcPort, output)
        }
    }

    /** Forwards the raw DNS query to a real upstream and relays the answer back to the tun. */
    private fun forward(packet: ByteArray, dnsStart: Int, srcIp: InetAddress, srcPort: Int, output: FileOutputStream) {
        val query = packet.copyOfRange(dnsStart, packet.size)
        val answer = runCatching {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(UPSTREAM_DNS), 53))
                val buf = ByteArray(MAX_PACKET)
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                buf.copyOf(reply.length)
            }
        }.getOrNull() ?: return
        writePacket(output, buildResponse(packet, dnsStart, answer))
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
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TUN_ADDR = "10.111.222.1"
        private const val SENTINEL_DNS = "10.111.222.2"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val UPSTREAM_TIMEOUT_MS = 5000
        private const val MAX_PACKET = 32767
        private const val ACTION_STOP = "dev.walcott.net.STOP"

        fun start(context: Context) {
            context.startService(Intent(context, WalcottVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WalcottVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
