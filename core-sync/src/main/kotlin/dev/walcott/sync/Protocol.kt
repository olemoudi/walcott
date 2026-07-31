package dev.walcott.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.PrivateKey
import java.security.PublicKey

// --- Payloads (the plaintext that travels inside the encrypted envelope) ---

@Serializable
data class UsageEntry(val categoryId: String, val seconds: Long)

@Serializable
data class ExtraTimeRequest(
    val requestId: String,
    /**
     * The extra-time target key: a category id, a package name (single app), or the
     * "all apps" sentinel (ExtraTime.ALL_APPS). Named for history; it is the generic key the
     * grant is applied under.
     */
    val categoryId: String,
    val minutes: Int,
    val reason: String = "",
    val createdAtEpochMs: Long,
    /** Human label when the target is an app or "all apps"; "" for a category (resolved by id). */
    val targetLabel: String = "",
)

@Serializable
data class Resolution(
    val requestId: String,
    val approved: Boolean,
    val grantedMinutes: Int,
    val resolvedAtEpochMs: Long,
)

/**
 * A child asking for something. [kind] is an open set: "app" (approval opens a timed
 * install window on the child's device) or "other" (free-form; resolving it is just an
 * acknowledgement — the actual conversation happens off-app).
 */
@Serializable
data class ChildRequest(
    val requestId: String,
    val kind: String,
    val text: String,
    val createdAtEpochMs: Long,
    /** The Play package for [KIND_INSTALL] (text carries the human label); "" otherwise. */
    val pkg: String = "",
) {
    companion object {
        const val KIND_APP = "app"
        const val KIND_OTHER = "other"

        /** Domains an app was seen resolving, sent for the parent to block (see [DomainAsk]). */
        const val KIND_DOMAINS = "domains"

        /**
         * One concrete app, shared from the Play Store on the child's phone. Approval pushes
         * an install of exactly [pkg] (the tight single-app window), never a blanket window.
         */
        const val KIND_INSTALL = "install"
    }
}

/**
 * One slice of a batch of domains a child is handing the parent.
 *
 * Every slice carries the shape of the whole batch ([chunks]), so it is its own manifest: the
 * parent learns what to expect from whichever slice arrives first, in any order, and no handshake
 * can wedge waiting for one. A slice is confirmed by its [DomainDelivery.ackId] appearing in
 * [ParentSnapshot.domainAcks]; anything still unconfirmed is simply sent again.
 */
@Serializable
data class DomainChunk(
    val batchId: String,
    val packageName: String,
    val label: String,
    val index: Int,
    val chunks: Int,
    val domains: List<String>,
)

/**
 * Splitting a selection of domains across messages, and putting it back together.
 *
 * The channel carries one small message at a time with no delivery guarantee, and a child
 * snapshot that outgrows the cap is rejected whole — which takes the child off the air
 * entirely, not just its domains (see [SnapshotFit]). So a selection travels in slices small
 * enough that a snapshot carrying a couple still fits, each resent until the parent confirms it.
 *
 * Pure, because "no domain lost, none duplicated, whatever order they arrive in" is the whole
 * promise being made to a parent who just ticked forty boxes.
 */
object DomainDelivery {

    /** Domains per slice. Ten plus the header is a few hundred bytes on the wire. */
    const val DOMAINS_PER_CHUNK = 10

    /** How many unconfirmed slices one snapshot offers to carry. */
    const val CHUNKS_PER_MESSAGE = 2

    /**
     * How many publishes in a row may go unconfirmed before the child stops trying. With the
     * nudge interval the child uses after a send, that is a couple of minutes — long past the
     * moment the parent is still holding the phone, and short enough that an undeliverable batch
     * can't resend for the rest of the day. Giving up is reported on the child's screen, not
     * swallowed: a selection that never arrived has to be visibly worth doing again.
     *
     * Counted *since the last confirmed slice*, not over the batch's life. A batch only offers
     * [CHUNKS_PER_MESSAGE] slices per publish, so a big selection legitimately needs many more
     * publishes than this to finish; a total-attempt bound would abandon it half-delivered on a
     * perfectly healthy channel.
     */
    const val MAX_ATTEMPTS = 8

    fun giveUp(roundsWithoutAck: Int): Boolean = roundsWithoutAck >= MAX_ATTEMPTS

    fun ackId(batchId: String, index: Int): String = "$batchId#$index"

    /** [domains] as slices, in order. An empty selection produces no slices. */
    fun chunk(batchId: String, packageName: String, label: String, domains: List<String>): List<DomainChunk> {
        val clean = domains.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return emptyList()
        val slices = clean.chunked(DOMAINS_PER_CHUNK)
        return slices.mapIndexed { index, slice ->
            DomainChunk(batchId, packageName, label, index, slices.size, slice)
        }
    }

    /**
     * The batch's domains once every slice has arrived, or null while any is missing — so a
     * parent is never shown half a request as though it were the whole of it.
     */
    fun assemble(received: Collection<DomainChunk>): List<String>? {
        val expected = received.firstOrNull()?.chunks ?: return null
        val byIndex = received.associateBy { it.index }
        if (byIndex.size != expected || (0 until expected).any { it !in byIndex }) return null
        return (0 until expected).flatMap { byIndex.getValue(it).domains }.distinct()
    }

    /** The batch to start for [domains], or null when there is nothing worth sending. */
    fun start(batchId: String, packageName: String, label: String, domains: List<String>): DomainBatch? =
        chunk(batchId, packageName, label, domains)
            .takeIf { it.isNotEmpty() }
            ?.let { DomainBatch(batchId, packageName, label, it) }

    /**
     * The slices to attach to the next publish: the oldest unconfirmed ones, bounded so the
     * snapshot still fits. Empty once the batch is delivered or abandoned, which is what stops
     * a finished batch from riding every message for the rest of the day.
     */
    fun forPublish(batch: DomainBatch?): List<DomainChunk> =
        if (batch == null || batch.delivered || batch.abandoned) emptyList()
        else batch.pending.take(CHUNKS_PER_MESSAGE)

    /** [batch] after a publish that carried slices — one more round with nothing confirmed yet. */
    fun published(batch: DomainBatch): DomainBatch = batch.copy(roundsWithoutAck = batch.roundsWithoutAck + 1)

    /**
     * [batch] with whatever [acks] confirms marked off. Any new confirmation resets the give-up
     * counter: the channel just proved it works, so the remaining slices deserve a full run of
     * attempts rather than inheriting the impatience built up before it recovered.
     */
    fun acked(batch: DomainBatch, acks: Collection<String>): DomainBatch {
        val confirmed = batch.slices
            .map { it.index }
            .filter { ackId(batch.batchId, it) in acks }
            .toSet()
        val fresh = confirmed - batch.ackedIndexes
        if (fresh.isEmpty()) return batch
        return batch.copy(ackedIndexes = batch.ackedIndexes + fresh, roundsWithoutAck = 0)
    }
}

/**
 * A batch of domains as the sending child tracks it: every slice, which ones the parent has
 * confirmed, and how many publishes have gone by since the last confirmation.
 *
 * Lives here rather than in the app's sync state because "when is this delivered, and when do we
 * stop trying" is exactly the logic that must not be discovered by tapping through an emulator.
 */
@Serializable
data class DomainBatch(
    val batchId: String,
    val packageName: String,
    val label: String,
    val slices: List<DomainChunk>,
    val ackedIndexes: Set<Int> = emptySet(),
    /** Publishes carrying slices since the last one the parent confirmed (see [DomainDelivery.MAX_ATTEMPTS]). */
    val roundsWithoutAck: Int = 0,
) {
    val pending: List<DomainChunk> get() = slices.filterNot { it.index in ackedIndexes }

    val delivered: Boolean get() = pending.isEmpty()

    /** Out of retries with slices still unconfirmed: the child stops, and says so. */
    val abandoned: Boolean get() = !delivered && DomainDelivery.giveUp(roundsWithoutAck)

    val domainCount: Int get() = slices.sumOf { it.domains.size }
}

/**
 * The summary line of a [ChildRequest.KIND_DOMAINS] ask, in the request's existing text field so
 * that a parent running an older build still reads a sentence instead of an empty request. The
 * domains themselves travel as [DomainChunk]s, which is what a selection of any size needs.
 *
 * Format: `Label (package): a.com, b.com`. Pure and symmetrical, because the parent's blocking
 * actions depend on getting the package back out intact.
 */
object DomainAsk {

    data class Parsed(val label: String, val packageName: String, val domains: List<String>)

    fun encode(label: String, packageName: String, domains: List<String>): String =
        "$label ($packageName): " + domains.joinToString(", ")

    /** The payload, or null when [text] isn't one — an ask of another kind, or a mangled one. */
    fun decode(text: String): Parsed? {
        val split = text.lastIndexOf("): ")
        if (split <= 0) return null
        val head = text.substring(0, split)
        val open = head.lastIndexOf(" (")
        if (open <= 0) return null
        val packageName = head.substring(open + 2)
        if (packageName.isEmpty() || '(' in packageName) return null
        val domains = text.substring(split + 3)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (domains.isEmpty()) return null
        return Parsed(head.substring(0, open), packageName, domains)
    }
}

/** An unsolicited reward the parent grants to a specific child (chores, good behaviour…). */
@Serializable
data class Bonus(
    val id: String,
    val targetDeviceId: String,
    val categoryId: String,
    val minutes: Int,
    val epochDay: Long,
)

/** One day of usage, for the weekly report. */
@Serializable
data class DayUsage(val epochDay: Long, val usage: List<UsageEntry> = emptyList())

/** A user app installed on a child device, reported so the parent can classify it. */
@Serializable
data class InstalledAppInfo(val packageName: String, val label: String)

/**
 * A child's pending emergency-release request (see [PanicProtocol]). Travels in every
 * [ChildSnapshot] while it is alive, so the parent sees it on any check-in — not only on the
 * two-hourly notice — and can refuse it with [RemoteAction.DENY_PANIC].
 */
@Serializable
data class PanicRequest(
    val id: String,
    /** Server second when the child started it (the local clock is never trusted here). */
    val startedAtSec: Long,
    /** Server second of the last proven connectivity checkpoint. */
    val lastCheckpointSec: Long,
    /** Checkpoints proven so far; [PanicProtocol.REQUIRED_CHECKPOINTS] releases the device. */
    val checkpoints: Int = 0,
)

/** A GPS fix reported by a child device (WGS84). */
@Serializable
data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val epochMs: Long,
    val accuracyM: Float = 0f,
    /** True if the fix came from a mock provider (possible spoofing). */
    val mock: Boolean = false,
)

/** Parent asks a specific device to report its current location on its next check-in. */
@Serializable
data class LocationRequest(val deviceId: String, val requestedAtMs: Long)

/**
 * A one-shot instruction the parent sends to a specific child device, applied on the next
 * check-in and acknowledged in [ChildSnapshot.lastCommand]. Applied idempotently by [id],
 * like bonuses and resolutions, so a replayed parent snapshot can't run it twice.
 */
@Serializable
data class RemoteCommand(
    val id: String,
    val deviceId: String,
    /** One of [RemoteAction]; unknown actions are ignored so old children degrade cleanly. */
    val action: String,
    val issuedAtMs: Long,
    /** Action payload (e.g. the package name for [RemoteAction.INSTALL_APP]); "" when unused. */
    val arg: String = "",
)

/** Actions a parent can trigger remotely on a child device. */
object RemoteAction {
    /** Run the self-update now (silent install on a Device Owner child). */
    const val UPDATE_NOW = "update_now"
    /** Re-grant location, re-apply device restrictions and restart the enforcement service. */
    const val REAPPLY_POLICY = "reapply_policy"
    /**
     * Ask the child device to show a guided notification for a permission only its user can
     * grant (usage access, network location). Nothing else can fix those remotely.
     */
    const val REQUEST_PERMISSIONS = "request_permissions"

    /**
     * Assisted install of a Play app ([RemoteCommand.arg] = package). The child opens a tight,
     * self-closing install window and prompts the user to tap Install in Play. Play cannot be
     * driven silently, so one tap on the child is unavoidable.
     */
    const val INSTALL_APP = "install_app"

    /**
     * Ask the child to publish a [DiagPayload] health report (its own message kind, so the
     * log lines never ride in the regular snapshot). The ack only confirms it was sent.
     */
    const val DIAGNOSE = "diagnose"

    /**
     * Refuse the child's pending emergency release ([ChildSnapshot.panic]): the request dies
     * and the child can't ask again for [PanicProtocol.DENIAL_COOLDOWN_SEC]. [RemoteCommand.arg]
     * carries the [PanicRequest.id] being refused, so a command that arrives after the child
     * already cancelled can't silently punish the next, unrelated request.
     */
    const val DENY_PANIC = "deny_panic"

    /**
     * [CommandAck.detail] lifecycle of an [INSTALL_APP]: "opened" means the prompt reached the
     * child (nothing installed yet); a second ack with "installed" follows when the pushed
     * package actually lands. "already_installed" short-circuits both.
     */
    const val DETAIL_INSTALL_OPENED = "opened"
    const val DETAIL_INSTALLED = "installed"
    const val DETAIL_ALREADY_INSTALLED = "already_installed"
}

/** How a child device says a [RemoteCommand] went, echoed back in its snapshot. */
@Serializable
data class CommandAck(
    val id: String,
    val action: String,
    val ok: Boolean,
    val detail: String = "",
    val completedAtMs: Long,
    /** The command's [RemoteCommand.arg] echoed back (the package for an install); "" if none. */
    val arg: String = "",
)

/** Published by each child device; the parent aggregates the latest per device. */
@Serializable
data class ChildSnapshot(
    val deviceId: String,
    val displayName: String,
    val version: Long,
    val epochDay: Long,
    val usage: List<UsageEntry> = emptyList(),
    val extra: List<UsageEntry> = emptyList(),
    val requests: List<ExtraTimeRequest> = emptyList(),
    val history: List<DayUsage> = emptyList(),
    /** Registry id from the per-child enrollment QR; "" for legacy/anonymous children. */
    val childId: String = "",
    /** Pending generic asks (resolved through the same [Resolution] channel). */
    val asks: List<ChildRequest> = emptyList(),
    /**
     * Slices of domain batches still waiting to be confirmed. Bounded per message and resent
     * until acknowledged; see [DomainDelivery].
     */
    val domainChunks: List<DomainChunk> = emptyList(),
    /** User apps installed on this device, so the parent classifies the real list. */
    val apps: List<InstalledAppInfo> = emptyList(),
    /** Recent GPS fixes (last 12h) for the parent's map, newest last. */
    val locations: List<LocationPoint> = emptyList(),
    /**
     * Whether the network location provider (Wi-Fi/cell) is enabled on this device. A Device
     * Owner can't force it on (it's the GMS "Google Location Accuracy" setting), so when it's
     * off the parent is warned that indoor tracking won't work. Defaults true so legacy children
     * that don't report it don't raise a false alarm.
     */
    val networkLocationOn: Boolean = true,
    /**
     * Whether usage access (screen-time counting) is granted on this device. When false,
     * budget-based limits silently stop counting, so the parent must be told. Defaults true
     * so legacy children raise no false alarm.
     */
    val usageAccessOn: Boolean = true,
    /** The child app's build (BuildConfig versionCode/Name); 0/"" = unknown/legacy. */
    val appVersionCode: Int = 0,
    val appVersionName: String = "",
    /** Active enforcement backend on this device: "device_owner" | "accessibility" | "none". */
    val enforcement: String = EnforcementStatus.UNKNOWN,
    /** Cumulative wrong parent-PIN attempts on this device, and the last one's wall-clock time. */
    val pinWrongTotal: Int = 0,
    val lastWrongPinMs: Long = 0,
    /** Result of the most recent [RemoteCommand] this device ran, so the parent sees it landed. */
    val lastCommand: CommandAck? = null,
    /**
     * requestedAtMs of the newest "locate now" this device has answered, so the parent can tell
     * a pending location request from a fulfilled one. 0 = legacy child that doesn't report it.
     */
    val answeredLocationRequestMs: Long = 0,
    /**
     * [ParentSnapshot.version] of the newest rules this device has adopted, so the parent can
     * tell "rule change still in flight" from "received". 0 = legacy child that doesn't report it.
     */
    val appliedPolicyVersion: Long = 0,
    /** Battery level 0–100, or -1 when unknown/legacy. Lets the parent be warned before a child dies. */
    val batteryPercent: Int = -1,
    /** Whether the device is plugged in / charging (a low level while charging is not worth alerting). */
    val charging: Boolean = false,
    /**
     * Why this device's last self-update attempt failed, or "" when the last check was clean.
     * Makes a child stuck on an old build diagnosable without touching the phone.
     */
    val updateError: String = "",
    /**
     * Packages the heartbeat self-test found NOT actually suspended although the rules say
     * they should be (capped — the child's debug log has the full list). Empty = the last
     * self-test passed (or the backend can't measure suspension). Catches the scariest
     * failure: everything looks healthy but the OS isn't blocking.
     */
    val enforcementGaps: List<String> = emptyList(),
    /**
     * Local clock minus the sync server's clock, in ms, as last measured by [ClockGuard].
     * 0 = in sync / legacy child. A large skew means the child moved the device clock
     * (walking past bedtime or daily budgets).
     */
    val clockSkewMs: Long = 0,
    /**
     * The child's pending emergency-release request, or null when there is none. Null on every
     * legacy child, so an old device can never look like it is asking for one.
     */
    val panic: PanicRequest? = null,
    /**
     * Wall-clock ms until which app installs are temporarily allowed on this device (a PIN
     * window or an approved install), so the parent can see — and be reminded about — a
     * window that is still open. 0 = closed / legacy child that doesn't report it.
     */
    val installExemptionUntilMs: Long = 0,
    /**
     * The device's UTC offset in minutes when it published (Madrid in winter = 60). [epochDay]
     * and every counter beside it are keyed to the child's OWN calendar day, which stops being
     * the parent's the moment either of them flies somewhere: read with the parent's clock, a
     * travelling child's usage silently reads as zero for up to a day.
     *
     * Null on legacy children that don't report it, and the parent falls back to its own clock —
     * the right answer whenever the family shares a timezone, which is nearly always.
     */
    val tzOffsetMinutes: Int? = null,
)

/** Enforcement backend a child reports so the parent knows if blocking is actually active. */
object EnforcementStatus {
    const val DEVICE_OWNER = "device_owner"
    const val ACCESSIBILITY = "accessibility"
    const val NONE = "none"
    const val UNKNOWN = "unknown"
}

/**
 * Published by the parent. Carries the rules as an opaque JSON blob (the app owns the
 * concrete type; the sync layer stays agnostic) plus resolutions and bonuses.
 */
@Serializable
data class ParentSnapshot(
    val version: Long,
    val policyJson: String,
    val resolutions: List<Resolution> = emptyList(),
    val bonuses: List<Bonus> = emptyList(),
    /** Pending "locate now" asks, at most one per target device. */
    val locationRequests: List<LocationRequest> = emptyList(),
    /** Pending remote fixes, applied once per [RemoteCommand.id] by the target device. */
    val commands: List<RemoteCommand> = emptyList(),
    /**
     * Packages whose icons the parent still wants (shown in the app list but not cached yet).
     * Bounded and empty in steady state, so it costs the parent message nothing once caught up.
     * Any child that has one of these answers with a [IconPayload]. See [IconSync].
     */
    val iconRequests: List<String> = emptyList(),
    /**
     * The parent app's own build ([versionCode]), making the parent the fleet's update canary:
     * a child only self-updates up to the version the parent is already running, so one bad
     * build can't take down every child at once. 0 = legacy parent (children don't wait).
     */
    val parentVersionCode: Int = 0,
    /**
     * Slice ids ([DomainDelivery.ackId]) the parent has taken in, so the child stops resending
     * them. Bounded to the recent ones: a slice whose ack has aged out is simply delivered again,
     * and reassembly is idempotent.
     */
    val domainAcks: List<String> = emptyList(),
)

/** One app icon, compressed small (WebP) and base64'd, sent child→parent on request. */
@Serializable
data class AppIconData(val packageName: String, val webpB64: String)

/**
 * A trickle of app icons a child sends in reply to [ParentSnapshot.iconRequests], in its own
 * message so the (already large) [ChildSnapshot] never carries image bytes. Bounded per
 * message so the initial burst at enrollment spreads across the channel politely.
 */
@Serializable
data class IconPayload(val deviceId: String, val icons: List<AppIconData> = emptyList())

/**
 * A child's health report, sent on request ([RemoteAction.DIAGNOSE]) in its own message kind
 * (like [IconPayload]) so the log lines never bloat the regular [ChildSnapshot]. Everything a
 * parent needs to diagnose a misbehaving device without physically holding it.
 */
@Serializable
data class DiagPayload(
    val deviceId: String,
    /** Wall-clock ms when the report was taken. */
    val atMs: Long,
    /** Active enforcement backend, one of [EnforcementStatus]. */
    val enforcement: String = EnforcementStatus.UNKNOWN,
    val deviceOwner: Boolean = false,
    val usageAccess: Boolean = false,
    val gpsOn: Boolean = false,
    val networkLocationOn: Boolean = false,
    val locationPermission: Boolean = false,
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** Why the last self-update attempt failed; "" = clean. */
    val updateError: String = "",
    /** Packages the OS recently refused to suspend (a real enforcement gap). */
    val suspendFailures: List<String> = emptyList(),
    val appVersionCode: Int = 0,
    val appVersionName: String = "",
    /** Tail of the child's debug log, oldest first, trimmed to fit the message cap. */
    val logLines: List<String> = emptyList(),
)

// --- Envelope on the wire ---

@Serializable
private data class Envelope(
    val kind: String, // "parent" | "child"
    val senderId: String,
    val version: Long,
    val ciphertext: String, // base64url of AES-GCM(familyKey, payloadJson)
    val signature: String? = null, // base64url of ECDSA(privateKey, ciphertext bytes)
    /**
     * Present after a parent restore whose signing key differs from the one children trust:
     * a [RotationCert] minted by the old key. See [SyncProtocol.decodeVerbose].
     */
    val rotation: RotationCert? = null,
)

sealed interface IncomingMessage {
    data class FromParent(val snapshot: ParentSnapshot) : IncomingMessage
    data class FromChild(val snapshot: ChildSnapshot) : IncomingMessage
    data class FromChildIcons(val payload: IconPayload) : IncomingMessage
    data class FromChildDiag(val payload: DiagPayload) : IncomingMessage
}

/**
 * Encodes/decodes envelopes. Parent messages are signed so a child (who holds the family
 * key) can read them but cannot forge them. Payloads are gzipped before encryption so a
 * full child snapshot (app list + locations + history) stays under ntfy's message size cap;
 * decode transparently accepts both gzipped and legacy uncompressed payloads.
 */
/**
 * Encoding and decoding of everything that travels over the family's ntfy topic.
 *
 * ## Who is authenticated, and how
 *
 * Two different guarantees, deliberately not the same in both directions:
 *
 * - **Family boundary, both directions.** Every message body is AES-GCM under the shared
 *   `familyKey`. That is an AEAD, so a stranger who merely knows the topic name cannot produce
 *   anything that decrypts. They *can* publish arbitrary bytes to it — the topic is a bearer
 *   secret in a URL with no ntfy account behind it — which is why [decodeVerbose] treats every
 *   step as hostile input and returns null rather than throwing (see ProtocolHostileTest).
 *
 * - **Parent → child, additionally signed.** ECDSA over the ciphertext, verified against the
 *   parent's public key from the pairing QR. Children hold the family key but never the parent's
 *   private key, so a child cannot mint rules for itself. This is the direction where a forgery
 *   would be a control bypass, and it is closed.
 *
 * **Child → parent carries no per-child signature, and that is a decision, not an oversight.**
 * Any holder of the family key can emit a child message with any `senderId`, so one enrolled
 * device can impersonate another to the parent. Per-child signing keys were considered and
 * rejected, because they buy less than they look like they do:
 *
 *  - Nothing a child sends *commands* another device. Resolutions, bonuses and remote commands
 *    all originate in [ParentSnapshot] and are signed. A forged child message only ever misinforms
 *    the parent — a lie about usage, a request in a sibling's name, a fake health report.
 *  - Signing would not stop the case that actually matters. A device compromised enough to forge
 *    would hold its own private key and would sign its lies just as validly. Per-child keys stop
 *    a sibling impersonating a sibling; they do nothing about a child lying about itself.
 *  - Reaching the family key at all needs root on a Device Owner device whose seeded restrictions
 *    already block clearing app data and sideloading, and where unlocking the bootloader wipes
 *    userdata — taking the key with it. A child who has got that far has defeated the enforcement
 *    outright and has no need to forge anything.
 *
 * Revisit this if a child message ever starts *driving* an action on another device rather than
 * informing the parent, or if the topic is ever shared beyond the family.
 */
object SyncProtocol {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeParent(
        snapshot: ParentSnapshot,
        familyKey: javax.crypto.SecretKey,
        parentPrivateKey: PrivateKey,
        rotation: RotationCert? = null,
    ): String {
        val payload = gzip(json.encodeToString(ParentSnapshot.serializer(), snapshot).toByteArray())
        val ciphertext = FamilyCrypto.encrypt(familyKey, payload)
        val signature = FamilyCrypto.sign(parentPrivateKey, ciphertext)
        return json.encodeToString(
            Envelope.serializer(),
            Envelope(
                "parent", "parent", snapshot.version,
                FamilyCrypto.toB64(ciphertext), FamilyCrypto.toB64(signature),
                rotation = rotation,
            ),
        )
    }

    fun encodeChildIcons(payload: IconPayload, familyKey: javax.crypto.SecretKey): String {
        val bytes = gzip(json.encodeToString(IconPayload.serializer(), payload).toByteArray())
        val ciphertext = FamilyCrypto.encrypt(familyKey, bytes)
        return json.encodeToString(
            Envelope.serializer(),
            Envelope("icons", payload.deviceId, 0, FamilyCrypto.toB64(ciphertext), null),
        )
    }

    fun encodeChildDiag(payload: DiagPayload, familyKey: javax.crypto.SecretKey): String {
        val bytes = gzip(json.encodeToString(DiagPayload.serializer(), payload).toByteArray())
        val ciphertext = FamilyCrypto.encrypt(familyKey, bytes)
        return json.encodeToString(
            Envelope.serializer(),
            Envelope("diag", payload.deviceId, 0, FamilyCrypto.toB64(ciphertext), null),
        )
    }

    fun encodeChild(snapshot: ChildSnapshot, familyKey: javax.crypto.SecretKey): String {
        val payload = gzip(json.encodeToString(ChildSnapshot.serializer(), snapshot).toByteArray())
        val ciphertext = FamilyCrypto.encrypt(familyKey, payload)
        return json.encodeToString(
            Envelope.serializer(),
            Envelope("child", snapshot.deviceId, snapshot.version, FamilyCrypto.toB64(ciphertext), null),
        )
    }

    /** [decodeVerbose]'s result: the message, plus the rotated parent key when one was adopted. */
    data class Decoded(val message: IncomingMessage, val rotatedParentPublicKeyB64: String? = null)

    /** Returns null if the message can't be decrypted or a parent signature doesn't verify. */
    fun decode(envelopeJson: String, familyKey: javax.crypto.SecretKey, parentPublicKey: PublicKey): IncomingMessage? =
        decodeVerbose(envelopeJson, familyKey, parentPublicKey)?.message

    /**
     * Like [decode], but also reports a verified parent-key rotation. A parent envelope whose
     * signature fails against [parentPublicKey] is still accepted when it carries a
     * [RotationCert] signed by that trusted key AND its own signature verifies against the
     * cert's new key — the restored-parent case. The caller must then persist the returned
     * key as the new trust root, since the old key is gone with the old phone.
     */
    fun decodeVerbose(envelopeJson: String, familyKey: javax.crypto.SecretKey, parentPublicKey: PublicKey): Decoded? {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), envelopeJson) }.getOrNull() ?: return null
        val ciphertext = runCatching { FamilyCrypto.fromB64(envelope.ciphertext) }.getOrNull() ?: return null

        var rotatedKeyB64: String? = null
        if (envelope.kind == "parent") {
            val sig = envelope.signature?.let { runCatching { FamilyCrypto.fromB64(it) }.getOrNull() } ?: return null
            if (!FamilyCrypto.verify(parentPublicKey, ciphertext, sig)) {
                val cert = envelope.rotation ?: return null
                val rotatedKey = KeyRotation.verify(cert, parentPublicKey) ?: return null
                if (!FamilyCrypto.verify(rotatedKey, ciphertext, sig)) return null
                rotatedKeyB64 = FamilyCrypto.toB64(rotatedKey.encoded)
            }
        }
        val decrypted = runCatching { FamilyCrypto.decrypt(familyKey, ciphertext) }.getOrNull() ?: return null
        val plaintext = runCatching { gunzipIfNeeded(decrypted) }.getOrNull() ?: return null
        val text = String(plaintext)

        val message = when (envelope.kind) {
            "parent" -> runCatching {
                IncomingMessage.FromParent(json.decodeFromString(ParentSnapshot.serializer(), text))
            }.getOrNull()
            "child" -> runCatching {
                IncomingMessage.FromChild(json.decodeFromString(ChildSnapshot.serializer(), text))
            }.getOrNull()
            "icons" -> runCatching {
                IncomingMessage.FromChildIcons(json.decodeFromString(IconPayload.serializer(), text))
            }.getOrNull()
            "diag" -> runCatching {
                IncomingMessage.FromChildDiag(json.decodeFromString(DiagPayload.serializer(), text))
            }.getOrNull()
            else -> null
        } ?: return null
        return Decoded(message, rotatedKeyB64)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * Gunzips if the payload carries the gzip magic (0x1f 0x8b); passes legacy uncompressed
     * JSON through untouched (JSON starts with '{' = 0x7b, so there is no ambiguity).
     */
    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
        val gzipped = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!gzipped) return bytes
        return java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }
}
