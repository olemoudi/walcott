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
    val categoryId: String,
    val minutes: Int,
    val reason: String = "",
    val createdAtEpochMs: Long,
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
) {
    companion object {
        const val KIND_APP = "app"
        const val KIND_OTHER = "other"
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
}

/** How a child device says a [RemoteCommand] went, echoed back in its snapshot. */
@Serializable
data class CommandAck(
    val id: String,
    val action: String,
    val ok: Boolean,
    val detail: String = "",
    val completedAtMs: Long,
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
     * Why this device's last self-update attempt failed, or "" when the last check was clean.
     * Makes a child stuck on an old build diagnosable without touching the phone.
     */
    val updateError: String = "",
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
)

// --- Envelope on the wire ---

@Serializable
private data class Envelope(
    val kind: String, // "parent" | "child"
    val senderId: String,
    val version: Long,
    val ciphertext: String, // base64url of AES-GCM(familyKey, payloadJson)
    val signature: String? = null, // base64url of ECDSA(privateKey, ciphertext bytes)
)

sealed interface IncomingMessage {
    data class FromParent(val snapshot: ParentSnapshot) : IncomingMessage
    data class FromChild(val snapshot: ChildSnapshot) : IncomingMessage
}

/**
 * Encodes/decodes envelopes. Parent messages are signed so a child (who holds the family
 * key) can read them but cannot forge them. Payloads are gzipped before encryption so a
 * full child snapshot (app list + locations + history) stays under ntfy's message size cap;
 * decode transparently accepts both gzipped and legacy uncompressed payloads.
 */
object SyncProtocol {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeParent(snapshot: ParentSnapshot, familyKey: javax.crypto.SecretKey, parentPrivateKey: PrivateKey): String {
        val payload = gzip(json.encodeToString(ParentSnapshot.serializer(), snapshot).toByteArray())
        val ciphertext = FamilyCrypto.encrypt(familyKey, payload)
        val signature = FamilyCrypto.sign(parentPrivateKey, ciphertext)
        return json.encodeToString(
            Envelope.serializer(),
            Envelope("parent", "parent", snapshot.version, FamilyCrypto.toB64(ciphertext), FamilyCrypto.toB64(signature)),
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

    /** Returns null if the message can't be decrypted or a parent signature doesn't verify. */
    fun decode(envelopeJson: String, familyKey: javax.crypto.SecretKey, parentPublicKey: PublicKey): IncomingMessage? {
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), envelopeJson) }.getOrNull() ?: return null
        val ciphertext = runCatching { FamilyCrypto.fromB64(envelope.ciphertext) }.getOrNull() ?: return null

        if (envelope.kind == "parent") {
            val sig = envelope.signature?.let { FamilyCrypto.fromB64(it) } ?: return null
            if (!FamilyCrypto.verify(parentPublicKey, ciphertext, sig)) return null
        }
        val decrypted = runCatching { FamilyCrypto.decrypt(familyKey, ciphertext) }.getOrNull() ?: return null
        val plaintext = runCatching { gunzipIfNeeded(decrypted) }.getOrNull() ?: return null
        val text = String(plaintext)

        return when (envelope.kind) {
            "parent" -> runCatching {
                IncomingMessage.FromParent(json.decodeFromString(ParentSnapshot.serializer(), text))
            }.getOrNull()
            "child" -> runCatching {
                IncomingMessage.FromChild(json.decodeFromString(ChildSnapshot.serializer(), text))
            }.getOrNull()
            else -> null
        }
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
