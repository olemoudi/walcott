package dev.walcott.sim

import dev.walcott.sync.Bonus
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.DiagPayload
import dev.walcott.sync.FamilyCrypto
import dev.walcott.sync.IconPayload
import dev.walcott.sync.IncomingMessage
import dev.walcott.sync.LocationRequest
import dev.walcott.sync.PairingPayload
import dev.walcott.sync.ParentSnapshot
import dev.walcott.sync.RemoteCommand
import dev.walcott.sync.Resolution
import dev.walcott.sync.SyncProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A parent, as a program.
 *
 * A parent device does nothing a JVM cannot: it holds the family's keys, signs snapshots of the
 * rules, and reads what the children publish back. Everything that makes it feel like an app —
 * screens, notifications, DataStore — is presentation over that. The child is the half that has
 * to be Android, because the thing being tested there is Android: suspending packages, blocking
 * installs, counting usage, refusing to die.
 *
 * So this is the other side of every conversation the child can have, driven from a test:
 * enrol it ([pairingFor]), push rules ([pushPolicy]), answer what it asks ([resolve]),
 * command it ([sendCommand]), and wait for what it says back ([awaitChild], [awaitAck]).
 *
 * Deliberately NOT a re-implementation of the parent app's logic. It speaks the wire format and
 * keeps the little state the wire requires (a version counter, the commands still outstanding);
 * anything it decided for itself would be a second implementation to keep in sync, and a test
 * that passes against a fiction. Where a scenario needs the app's judgement, it asserts on what
 * the child did instead.
 */
class ParentSim(
    /** Where this process reaches the relay. */
    private val relayBase: String,
    /**
     * Where the CHILD reaches the same relay, written into the pairing payload.
     *
     * These are two different strings for one server whenever the child is not this machine: an
     * emulator's host loopback is 10.0.2.2, which on the host itself is nothing at all. Pointing
     * the parent at the address it hands out produces a family whose two halves are each talking
     * to a server that isn't there, and the symptom is silence — the child pairs, reports it
     * paired, and never appears.
     */
    private val advertisedRelay: String = relayBase,
    val topic: String = "walcott-sim-" + UUID.randomUUID().toString().take(12),
    val familyName: String = "Sim Family",
) {

    private var familyKey = FamilyCrypto.generateFamilyKey()

    /**
     * The key this parent signs with, and the hand-over it presents when that is no longer the key
     * children were paired with. Both are `var` for one reason: [restoreFromBackup], which is the
     * only thing that ever changes them.
     */
    private var signingPair = FamilyCrypto.generateSigningKeyPair()
    private var rotationCert: dev.walcott.sync.RotationCert? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val version = AtomicLong(0)
    private var policyJson: String = PolicyJson.minimal(version = 1)
    private val resolutions = CopyOnWriteArrayList<Resolution>()
    private val bonuses = CopyOnWriteArrayList<Bonus>()
    private val commands = CopyOnWriteArrayList<RemoteCommand>()
    private val locationRequests = CopyOnWriteArrayList<LocationRequest>()
    private val iconRequests = CopyOnWriteArrayList<String>()

    /** Every child snapshot received, oldest first — the record a scenario asserts against. */
    val childHistory = CopyOnWriteArrayList<ChildSnapshot>()
    val iconPayloads = CopyOnWriteArrayList<IconPayload>()
    val diagReports = CopyOnWriteArrayList<DiagPayload>()

    /** Notification-log pages the child has answered with (see RemoteAction.NOTIFICATION_LOG). */
    val notificationPages = CopyOnWriteArrayList<dev.walcott.sync.NotificationPayload>()

    /** Newest snapshot per child device. */
    val children = ConcurrentHashMap<String, ChildSnapshot>()

    private val arrivals = Object()
    @Volatile private var socket: WebSocket? = null

    /** The QR a child scans. Carries the relay, so the child talks to us and nobody else. */
    fun pairingFor(childId: String = "c1", childName: String = "Sim Child"): String =
        PairingPayload(
            topic = topic,
            familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
            parentPublicKeyB64 = FamilyCrypto.toB64(signingPair.public.encoded),
            ntfyServer = advertisedRelay,
            childId = childId,
            childName = childName,
            familyName = familyName,
        ).encode()

    /**
     * Subscribes to the family topic, and does not return until the socket is open. Idempotent.
     *
     * The wait is the point. Opening is asynchronous, and a parent that returns from `start()`
     * unsubscribed will miss whatever is published in the next few milliseconds — on a loaded
     * machine, reliably the child's first check-in. The symptom is a device that paired
     * perfectly and appears never to have spoken.
     */
    fun start(): ParentSim {
        if (socket != null) return this
        val wsUrl = relayBase.trimEnd('/').replaceFirst("http", "ws") + "/$topic/ws"
        val opened = CountDownLatch(1)
        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
                override fun onMessage(webSocket: WebSocket, text: String) = onEvent(text)
            },
        )
        check(opened.await(SOCKET_OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            "the parent's socket never opened against $relayBase"
        }
        return this
    }

    fun stop() {
        socket?.cancel()
        socket = null
    }

    // --- What the parent says ---

    /**
     * Publishes the current rules. Every push bumps the version, because the child refuses a
     * snapshot whose version has not gone up (see SyncEngine.adoptsPolicy) — a sim that forgot
     * that would look like a broken child.
     */
    fun pushPolicy(policy: String = policyJson): ParentSnapshot {
        policyJson = policy
        return publishSnapshot()
    }

    /** Answers a child's request. [grantedMinutes] > 0 adds time; 0 approves without any. */
    fun resolve(requestId: String, approved: Boolean, grantedMinutes: Int = 0): ParentSnapshot {
        resolutions += Resolution(requestId, approved, grantedMinutes, System.currentTimeMillis())
        return publishSnapshot()
    }

    /** Queues a remote fix for [deviceId] and returns its id, so a scenario can await the ack. */
    /**
     * @param issuedAtMs when the parent claims to have issued this. Settable because one command
     *   — the lock-screen PIN — deliberately expires (see `RemoteAction.LOCK_PIN_TTL_MS`), and a
     *   scenario cannot wait half an hour to prove it. Everything else ignores the field.
     */
    fun sendCommand(
        deviceId: String,
        action: String,
        arg: String = "",
        label: String = "",
        issuedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val id = UUID.randomUUID().toString()
        commands += RemoteCommand(id, deviceId, action, issuedAtMs, arg, label)
        publishSnapshot()
        return id
    }

    fun grantBonus(deviceId: String, categoryId: String, minutes: Int, epochDay: Long): String {
        val id = UUID.randomUUID().toString()
        bonuses += Bonus(id, deviceId, categoryId, minutes, epochDay)
        publishSnapshot()
        return id
    }

    fun requestLocation(deviceId: String): ParentSnapshot {
        locationRequests.removeIf { it.deviceId == deviceId }
        locationRequests += LocationRequest(deviceId, System.currentTimeMillis())
        return publishSnapshot()
    }

    fun requestIcons(packages: List<String>): ParentSnapshot {
        iconRequests.clear()
        iconRequests += packages
        return publishSnapshot()
    }

    /**
     * Re-publishes the CURRENT snapshot without bumping the version — what the parent app's
     * periodic re-emit does. A child must accept the resolutions it carries and still refuse to
     * re-adopt the policy.
     */
    fun reEmit(): ParentSnapshot = publishSnapshot(bumpVersion = false)

    /**
     * Publishes DIFFERENT rules under a version the child has already seen — a properly signed
     * message from the real parent key, which is what makes it interesting: the only thing
     * standing between it and the child's rule set is the replay gate. A stale snapshot
     * resurfacing from the relay's backlog looks exactly like this, and so does an attacker
     * who kept a copy of an older, laxer policy.
     *
     * Nothing in the parent app can produce this, which is precisely why nothing could test it.
     */
    fun pushPolicyAtVersion(policy: String, atVersion: Long): ParentSnapshot {
        policyJson = policy
        val snapshot = ParentSnapshot(
            version = atVersion,
            policyJson = policy,
            resolutions = resolutions.toList(),
            bonuses = bonuses.toList(),
            locationRequests = locationRequests.toList(),
            commands = commands.toList(),
            iconRequests = iconRequests.toList(),
            parentVersionCode = PARENT_VERSION_CODE,
        )
        publishRaw(SyncProtocol.encodeParent(snapshot, familyKey, signingPair.private, rotationCert))
        return snapshot
    }

    /** The version the next [pushPolicy] will use — for scenarios that need to aim at it. */
    fun currentVersion(): Long = version.get()

    /**
     * The same parent, reached on a DIFFERENT relay: same topic, same keys, same version counter.
     *
     * What a family looks like after a migration (see `RemoteAction.SET_RELAY`). A scenario needs
     * both halves at once — the old relay to send the instruction on and to hear the
     * acknowledgement, the new one to prove the child actually went there — and they are the same
     * parent, so they cannot be two independently generated sims.
     */
    fun sameFamilyOn(relayBase: String, advertisedRelay: String = relayBase): ParentSim {
        val moved = ParentSim(relayBase, advertisedRelay, topic, familyName)
        moved.adoptIdentityOf(this)
        return moved
    }

    /** Copies the family's identity into [this] (see [sameFamilyOn]); keys are what make it one family. */
    private fun adoptIdentityOf(other: ParentSim) {
        familyKey = other.familyKey
        signingPair = other.signingPair
        rotationCert = other.rotationCert
        version.set(other.version.get())
    }

    /**
     * Becomes the parent a family gets back after the original phone is lost: same family, same
     * key material out of the backup file, a version counter that restarts, and — for a family
     * whose signing key could never be exported — a brand new key vouched for by the old one.
     *
     * This is the half of disaster recovery that has to be proved against a REAL child: the file
     * format and the rotation maths are unit-tested, but whether a phone that has been enforcing
     * rules for days actually accepts a parent it has never heard from is a question about the
     * device, not about the arithmetic.
     *
     * @param rotate true for a legacy family (new key + [RotationCert]), false for one whose
     *   software key the backup carries verbatim.
     * @param versionLeap what `SyncManager.restoreBackup` adds on top of the backup's counter, so
     *   the restored parent outranks anything the lost phone published after the file was written.
     */
    fun restoreFromBackup(rotate: Boolean, versionLeap: Long = RESTORE_VERSION_LEAP): ParentSim {
        if (rotate) {
            val recovery = FamilyCrypto.generateSigningKeyPair()
            rotationCert = dev.walcott.sync.KeyRotation.create(recovery.public, signingPair.private)
            signingPair = recovery
            // A restored legacy parent starts its counter from the backup, which is BELOW what the
            // children have applied — the case the rotation exists to rebase. Zero, so the very
            // next push is version 1: anything higher could pass the replay gate on its own and
            // the scenario would prove nothing about the rotation.
            version.set(0)
        } else {
            version.set(version.get() + versionLeap)
        }
        return this
    }

    private fun publishSnapshot(bumpVersion: Boolean = true): ParentSnapshot {
        val snapshot = ParentSnapshot(
            version = if (bumpVersion) version.incrementAndGet() else version.get(),
            policyJson = policyJson,
            resolutions = resolutions.toList(),
            bonuses = bonuses.toList(),
            locationRequests = locationRequests.toList(),
            commands = commands.toList(),
            iconRequests = iconRequests.toList(),
            parentVersionCode = PARENT_VERSION_CODE,
        )
        publishRaw(SyncProtocol.encodeParent(snapshot, familyKey, signingPair.private, rotationCert))
        return snapshot
    }

    /** Publishes an arbitrary body — for the hostile cases (garbage, forged, replayed). */
    fun publishRaw(body: String) {
        val request = Request.Builder()
            .url("${relayBase.trimEnd('/')}/$topic")
            .post(body.toByteArray().toRequestBody())
            .build()
        client.newCall(request).execute().use { response: Response ->
            check(response.isSuccessful) { "relay refused publish: HTTP ${response.code}" }
        }
    }

    // --- What the parent hears ---

    private fun onEvent(text: String) {
        val body = NtfyEvent.messageBody(text) ?: return
        val decoded = SyncProtocol.decode(body, familyKey, signingPair.public) ?: return
        when (decoded) {
            is IncomingMessage.FromChild -> {
                childHistory += decoded.snapshot
                children.merge(decoded.snapshot.deviceId, decoded.snapshot) { old, new ->
                    // Last-write-wins by version, like the parent app: the relay can replay, and
                    // a scenario asserting on "the child's state" must not see it walk backwards.
                    if (new.version >= old.version) new else old
                }
            }
            is IncomingMessage.FromChildIcons -> iconPayloads += decoded.payload
            is IncomingMessage.FromChildDiag -> diagReports += decoded.payload
            is IncomingMessage.FromChildNotifications -> notificationPages += decoded.payload
            // Our own snapshot, echoed back by the relay.
            is IncomingMessage.FromParent -> return
        }
        synchronized(arrivals) { arrivals.notifyAll() }
    }

    /**
     * Waits for a child snapshot satisfying [predicate], and returns it.
     *
     * Blocking with a deadline rather than polling with sleeps: every scenario here is waiting
     * on a real device doing real work, and the difference between "took 400 ms" and "never
     * happened" is the only thing a test needs to distinguish.
     */
    fun awaitChild(timeoutMs: Long = DEFAULT_TIMEOUT_MS, predicate: (ChildSnapshot) -> Boolean): ChildSnapshot {
        val found = awaitOrNull(timeoutMs) { children.values.firstOrNull(predicate) }
        return found ?: throw AssertionError(
            "no child snapshot matched within ${timeoutMs}ms; last seen: ${describeChildren()}",
        )
    }

    /**
     * Waits for the child to acknowledge [commandId], and returns the ack.
     *
     * A snapshot carries only the LAST ack, so this scans the history rather than the current
     * state: two commands answered in quick succession leave only the second visible in
     * `children`, and a scenario that awaited the first would hang on a command that was in
     * fact executed.
     */
    fun awaitAck(commandId: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): dev.walcott.sync.CommandAck {
        val ack = awaitOrNull(timeoutMs) {
            childHistory.asReversed().firstNotNullOfOrNull { it.lastCommand?.takeIf { a -> a.id == commandId } }
        }
        return ack ?: throw AssertionError(
            "command $commandId was never acknowledged within ${timeoutMs}ms; " +
                "acks seen: ${childHistory.mapNotNull { it.lastCommand }.map { it.id.take(8) + "=" + it.detail }}",
        )
    }

    fun awaitDiag(timeoutMs: Long = DEFAULT_TIMEOUT_MS): DiagPayload =
        awaitOrNull(timeoutMs) { diagReports.lastOrNull() }
            ?: throw AssertionError("no health report within ${timeoutMs}ms")

    fun awaitIcons(timeoutMs: Long = DEFAULT_TIMEOUT_MS): IconPayload =
        awaitOrNull(timeoutMs) { iconPayloads.lastOrNull() }
            ?: throw AssertionError("no icons within ${timeoutMs}ms")

    /**
     * The next notification-log page the child sends, counting only pages that arrive AFTER
     * [after] have already been seen.
     *
     * A count rather than "the last one": the log is asked for repeatedly in one scenario (all
     * apps, then one app, then a page older than that), and every request produces a page — so
     * "the newest page" would happily return the answer to the previous question.
     */
    fun awaitNotifications(
        after: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): dev.walcott.sync.NotificationPayload =
        awaitOrNull(timeoutMs) { notificationPages.getOrNull(after) }
            ?: throw AssertionError("no notification page #$after within ${timeoutMs}ms")

    /**
     * Asserts that nothing matching [predicate] shows up within [windowMs]. Needed as often as
     * the positive form: "the child did NOT adopt the replayed policy" is the whole point of
     * half these scenarios, and a test that only ever waits for things to happen cannot say it.
     */
    fun assertNoChild(windowMs: Long = QUIET_WINDOW_MS, predicate: (ChildSnapshot) -> Boolean) {
        val offender = awaitOrNull(windowMs) { children.values.firstOrNull(predicate) }
        if (offender != null) throw AssertionError("expected no matching snapshot, got: $offender")
    }

    private fun <T : Any> awaitOrNull(timeoutMs: Long, probe: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(arrivals) {
            while (true) {
                probe()?.let { return it }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return probe()
                arrivals.wait(remaining.coerceAtMost(250))
            }
        }
    }

    private fun describeChildren(): String =
        children.values.joinToString { "${it.deviceId}@v${it.version}" }.ifBlank { "(nothing)" }

    /** Blocks until [latch] fires, for scenarios that wait on something outside the channel. */
    fun await(latch: CountDownLatch, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        /**
         * Generous on purpose: these wait on a device that may be dozing, re-connecting a socket
         * with backoff, or doing a real package install. A tight timeout here would produce
         * flaky failures that read as product bugs.
         */
        const val DEFAULT_TIMEOUT_MS = 45_000L

        /** How long "nothing happened" has to hold to count as nothing happening. */
        const val QUIET_WINDOW_MS = 6_000L

        /**
         * What this sim claims to be running. The child gates a few things on the parent being
         * new enough (see PanicProtocol), so a sim reporting 0 would silently disable them.
         */
        const val PARENT_VERSION_CODE = 9_999

        /** How long [start] waits for its subscription before calling the relay unreachable. */
        const val SOCKET_OPEN_TIMEOUT_SEC = 15L

        /**
         * What a restored parent adds to the backup's version counter, mirroring
         * `SyncManager.RESTORE_VERSION_LEAP`. Kept in step by [restoreFromBackup]'s scenario,
         * which is the point: if the app ever stopped leaping, the child would refuse the
         * restored parent's rules and this is where that shows up.
         */
        const val RESTORE_VERSION_LEAP = 1_000_000L
    }
}
