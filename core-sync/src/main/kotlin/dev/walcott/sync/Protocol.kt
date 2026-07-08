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
)

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
 * key) can read them but cannot forge them.
 */
object SyncProtocol {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeParent(snapshot: ParentSnapshot, familyKey: javax.crypto.SecretKey, parentPrivateKey: PrivateKey): String {
        val payload = json.encodeToString(ParentSnapshot.serializer(), snapshot).toByteArray()
        val ciphertext = FamilyCrypto.encrypt(familyKey, payload)
        val signature = FamilyCrypto.sign(parentPrivateKey, ciphertext)
        return json.encodeToString(
            Envelope.serializer(),
            Envelope("parent", "parent", snapshot.version, FamilyCrypto.toB64(ciphertext), FamilyCrypto.toB64(signature)),
        )
    }

    fun encodeChild(snapshot: ChildSnapshot, familyKey: javax.crypto.SecretKey): String {
        val payload = json.encodeToString(ChildSnapshot.serializer(), snapshot).toByteArray()
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
        val plaintext = runCatching { FamilyCrypto.decrypt(familyKey, ciphertext) }.getOrNull() ?: return null
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
}
