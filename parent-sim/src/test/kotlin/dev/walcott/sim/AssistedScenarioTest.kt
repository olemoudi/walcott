package dev.walcott.sim

import dev.walcott.sync.ChildRequest
import dev.walcott.sync.NotificationQuery
import dev.walcott.sync.RemoteAction
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Supporting a phone rather than limiting it: the ringer, the lock screen, the notification log,
 * and the one button its owner has (see `MemberKind.ADULT`).
 *
 * All four are round trips through a real device, and three of them touch parts of Android that
 * no unit test can stand in for: the ringer is the platform's own audio state, the lock-screen
 * reset is a Device Owner token the system decides is active or not, and the notification log
 * needs a permission that only a human — or `adb` standing in for one — can grant.
 *
 * What is asserted throughout is the ANSWER, not the wish. A guard that cannot say when it is
 * losing is worse than no guard, so every case here checks that the device reported the state it
 * is actually in, including the two states where the honest answer is "I have nothing for you,
 * and this is why".
 */
class AssistedScenarioTest : DeviceScenario() {

    /** The PIN this suite sets and then always removes again. */
    private val PIN = "4291"

    /**
     * Posts a notification and asks for the log until something comes back.
     *
     * Retried rather than slept-through because of the one bit of platform latency this feature
     * cannot avoid: enabling a notification listener tells the system to BIND the service, and
     * anything posted before that binding completes is simply never seen. A single post two
     * seconds after granting access is a coin flip, and it flips differently depending on whether
     * the scenario before this one revoked the permission.
     *
     * The assertion is unchanged — "a notification this phone received can be read back" — and
     * each attempt is a fresh notification, so a page with entries is always a real round trip.
     */
    private fun awaitLoggedPage(
        title: String,
        text: String,
        timeoutMs: Long = 60_000,
    ): dev.walcott.sync.NotificationPayload {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            device.postNotification("walcott-e2e-${attempt++}", title, text)
            Thread.sleep(2_000)
            val seenPages = parent.notificationPages.size
            val ack = parent.sendCommand(
                deviceId,
                RemoteAction.NOTIFICATION_LOG,
                arg = NotificationQuery.encode(),
            )
            assertTrue(parent.awaitAck(ack).ok, "the device did not answer a request for its log")
            val page = parent.awaitNotifications(after = seenPages)
            if (page.entries.isNotEmpty()) return page
        }
        throw AssertionError("the device never recorded a notification it received within ${timeoutMs}ms")
    }

    // --- The lock screen ---

    @Test
    fun `the lock either changes and the card agrees, or it refuses and says why`() {
        // The promise is not that every phone can be rescued — a device whose reset token the
        // system has not activated cannot be, and that is Android's rule, not ours. The promise is
        // that the family is never MISLED about which of those they have. So: whatever happens,
        // the card's answer afterwards has to match what actually happened, and a refusal has to
        // name its reason rather than being a dead button.
        try {
            val ack = parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.SET_LOCK_PIN, arg = PIN))
            if (ack.ok) {
                assertEquals(RemoteAction.DETAIL_LOCK_SET, ack.detail)
                // It worked, so the card must not be telling the family it cannot. This is the
                // direction that strands somebody: a phone that IS rescuable, described as one
                // that is not, is a family who never tries.
                //
                // Checked against the whole history rather than the newest snapshot, and BEFORE
                // the cleanup below: removing the credential can deactivate the platform's reset
                // token, so a device asked about afterwards may honestly answer "not ready" — and
                // the claim under test is about the moment the lock actually changed.
                assertTrue(
                    parent.childHistory.any { it.deviceId == deviceId && it.lockResetReady },
                    "the lock was changed by a device that never once said it could be",
                )
            } else {
                assertTrue(
                    ack.detail in setOf(RemoteAction.DETAIL_LOCK_NOT_ARMED, RemoteAction.DETAIL_LOCK_REFUSED),
                    "a refusal must name its reason, not just fail: ${ack.detail}",
                )
                // And the card must have said so in advance, which is the whole point of reporting
                // readiness continuously rather than on demand.
                assertTrue(
                    parent.childHistory.none { it.deviceId == deviceId && it.lockResetReady },
                    "the device refused the change while reporting it was ready to make it",
                )
            }
        } finally {
            // Unconditionally: a PIN left behind locks every scenario after this one out of the
            // device, and the failure it produces has nothing to do with the case that caused it.
            runCatching {
                parent.awaitAck(parent.sendCommand(deviceId, RemoteAction.SET_LOCK_PIN, arg = ""))
            }
        }
    }

    @Test
    fun `a lock-screen command that is too old is refused rather than acted on`() {
        // Every other action here is harmless when it lands late. This one is not: a replayed
        // "set 1234" arriving next week would lock somebody out with a number nobody remembers
        // telling them. Sent with an issue time beyond the TTL, which is the same thing a
        // relay replaying its backlog would deliver.
        val stale = System.currentTimeMillis() - RemoteAction.LOCK_PIN_TTL_MS - 60_000
        val commandId = parent.sendCommand(
            deviceId,
            RemoteAction.SET_LOCK_PIN,
            arg = "4291",
            issuedAtMs = stale,
        )
        val ack = parent.awaitAck(commandId)
        assertFalse(ack.ok, "an expired lock-screen command was acted on")
        assertEquals(RemoteAction.DETAIL_EXPIRED, ack.detail)
    }

    @Test
    fun `a PIN nobody could be told down a phone line is refused before anything is tried`() {
        val commandId = parent.sendCommand(deviceId, RemoteAction.SET_LOCK_PIN, arg = "12")
        val ack = parent.awaitAck(commandId)
        assertFalse(ack.ok, "a two-digit PIN was accepted")
        assertEquals(RemoteAction.DETAIL_LOCK_REFUSED, ack.detail)
    }

    @Test
    fun `locking the phone now needs no token and is acknowledged`() {
        // The one lock-screen action that takes nothing away, so it works on a device whose
        // reset token was never armed — which is most of them, most of the time.
        val commandId = parent.sendCommand(deviceId, RemoteAction.LOCK_NOW)
        val ack = parent.awaitAck(commandId)
        assertTrue(ack.ok, "lock now should succeed: ${ack.detail}")
        assertEquals(RemoteAction.LOCK_NOW, ack.action)
    }

    // --- The ringer ---

    @Test
    fun `a phone left quiet is put back, and says how often that has happened`() {
        val pushed = parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                extra = mapOf("keepRingerAudible" to JsonPrimitive(true)),
            ),
        )
        // The guard is armed from the rules, so wait until this device has actually adopted them
        // before breaking anything — otherwise the failure is created on a phone that has not yet
        // been asked to care about it, and the test measures the wrong thing.
        childEventuallyReports { it.deviceId == deviceId && it.appliedPolicyVersion >= pushed.version }
        val before = parent.children[deviceId]?.ringerRestores ?: 0

        device.lowerRinger()
        // Give the broadcast path its chance first: RINGER_MODE_CHANGED reaches the receiver
        // within a moment, and that is how this normally heals on a live phone.
        Thread.sleep(4_000)
        // Then the pass that catches what a dead process missed. Running both is deliberate — the
        // promise is that the phone comes back, and the product has two ways of keeping it.
        device.assertRinger()

        val after = childEventuallyReports {
            it.deviceId == deviceId && it.ringerRestores > before
        }
        assertTrue(after.ringerAudible, "the ringer was put back but the device still reads silent")
        assertTrue(
            after.ringerRestores > before,
            "the device restored its ringer without counting it — a one-off and a daily habit " +
                "must not look the same to the family",
        )
    }

    // --- The notification log ---

    @Test
    fun `with no log asked for, the device says so rather than answering an empty day`() {
        // The default state of every family. The distinction matters: an empty list would read as
        // "nothing arrived", which is a different — and wrong — answer to "did it arrive?".
        val commandId = parent.sendCommand(
            deviceId,
            RemoteAction.NOTIFICATION_LOG,
            arg = NotificationQuery.encode(),
        )
        assertTrue(parent.awaitAck(commandId).ok)
        val page = parent.awaitNotifications()
        assertTrue(page.notEnabled, "a device with the log off must say so")
        assertTrue(page.entries.isEmpty())
        assertEquals(0, page.total)
    }

    @Test
    fun `with the log on but access never granted, the device says that instead`() {
        device.disallowNotificationListener()
        awaitDevice("notification access revoked") { !device.notificationListenerAllowed() }
        val pushed = parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                extra = mapOf("notificationLogEnabled" to JsonPrimitive(true)),
            ),
        )
        childEventuallyReports { it.deviceId == deviceId && it.appliedPolicyVersion >= pushed.version }

        val commandId = parent.sendCommand(
            deviceId,
            RemoteAction.NOTIFICATION_LOG,
            arg = NotificationQuery.encode(),
        )
        assertTrue(parent.awaitAck(commandId).ok)
        val page = parent.awaitNotifications()
        assertTrue(page.noAccess, "the one permission nobody can grant remotely must be reported")
        assertFalse(page.notEnabled, "the log IS enabled; the missing piece is the permission")

        // And the same fact reaches the parent continuously, not only when asked — which is what
        // puts "nothing is being recorded" on the card next to the switch.
        val snapshot = childEventuallyReports { it.deviceId == deviceId && !it.notificationAccess }
        assertFalse(snapshot.notificationAccess)
    }

    @Test
    fun `the log answers with what arrived, and can be narrowed to one app`() {
        device.allowNotificationListener()
        awaitDevice("notification access granted") { device.notificationListenerAllowed() }
        val pushed = parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                extra = mapOf("notificationLogEnabled" to JsonPrimitive(true)),
            ),
        )
        childEventuallyReports { it.deviceId == deviceId && it.appliedPolicyVersion >= pushed.version }

        val page = awaitLoggedPage("Clinic", "Your appointment is on Thursday")
        assertFalse(page.notEnabled, "the log was switched on")
        assertFalse(page.noAccess, "access was granted from adb, as a person would in Settings")
        assertTrue(page.entries.isNotEmpty(), "nothing came back for a phone that just got two")
        assertEquals("", page.pkg, "a page about every app must say so")
        assertTrue(
            page.entries.any { it.title == "Clinic" || it.text.contains("Thursday") },
            "the log did not carry what the notification actually said: ${page.entries}",
        )
        assertTrue(page.total >= page.entries.size, "total must count what the device HAS")
        assertTrue(page.oldestAtMs > 0, "a page with entries must carry the cursor to page back from")

        // The narrower question, which is the one a family actually asks. It travels as its own
        // page and must be answerable without reading a whole day of somebody's messages.
        val shell = page.entries.first().pkg
        val seenPages = parent.notificationPages.size
        val one = parent.sendCommand(
            deviceId,
            RemoteAction.NOTIFICATION_LOG,
            arg = NotificationQuery.encode(pkg = shell),
        )
        assertTrue(parent.awaitAck(one).ok)
        val narrowed = parent.awaitNotifications(after = seenPages)
        assertEquals(shell, narrowed.pkg, "a per-app page must name the app it answers for")
        assertTrue(
            narrowed.entries.all { it.pkg == shell },
            "a page asked about one app came back with others: ${narrowed.entries.map { it.pkg }}",
        )
    }

    @Test
    fun `switching the log off makes the device forget what it recorded`() {
        // The negative half of the switch, and the one that matters: a family turning this off has
        // to mean the rows go away, not that recording stops and yesterday stays readable.
        device.allowNotificationListener()
        awaitDevice("notification access granted") { device.notificationListenerAllowed() }
        val on = parent.pushPolicy(
            PolicyJson.build(version = 2, extra = mapOf("notificationLogEnabled" to JsonPrimitive(true))),
        )
        childEventuallyReports { it.deviceId == deviceId && it.appliedPolicyVersion >= on.version }
        assertTrue(
            awaitLoggedPage("Clinic", "Your appointment is on Thursday").entries.isNotEmpty(),
            "nothing was recorded to forget",
        )

        val off = parent.pushPolicy(PolicyJson.build(version = 3))
        childEventuallyReports { it.deviceId == deviceId && it.appliedPolicyVersion >= off.version }
        // Ask again with the log back ON, so the answer cannot be "off, so nothing" — the rows
        // themselves have to be gone.
        val backOn = parent.pushPolicy(
            PolicyJson.build(version = 4, extra = mapOf("notificationLogEnabled" to JsonPrimitive(true))),
        )
        childEventuallyReports { it.deviceId == deviceId && it.appliedPolicyVersion >= backOn.version }

        val after = parent.sendCommand(deviceId, RemoteAction.NOTIFICATION_LOG, arg = NotificationQuery.encode())
        parent.awaitAck(after)
        val page = parent.awaitNotifications(after = parent.notificationPages.size - 1, timeoutMs = 30_000)
        assertEquals(0, page.total, "switching the log off left the recorded rows on the device")
        assertTrue(page.entries.isEmpty())
    }

    // --- The one button on an assisted phone ---

    @Test
    fun `pressing the help button reaches the family as its own kind of ask`() {
        // Its own kind rather than free text, because nothing should have to be typed, spelled or
        // explained by the person least able to do it right then. The parent's side keys its
        // whole treatment of this — card, notification, wording — off that kind.
        device.ask(ChildRequest.KIND_HELP, "Asked for help from their phone")

        val snapshot = childEventuallyReports { child ->
            child.deviceId == deviceId && child.asks.any { it.kind == ChildRequest.KIND_HELP }
        }
        val ask = snapshot.asks.first { it.kind == ChildRequest.KIND_HELP }
        assertTrue(ask.text.isNotBlank(), "an ask with no text reads as an empty row on the parent")

        // And it resolves like any other, which is what stops it sitting there for two days after
        // somebody has already been helped.
        parent.resolve(ask.requestId, approved = true)
        val cleared = childEventuallyReports { child ->
            child.deviceId == deviceId && child.asks.none { it.requestId == ask.requestId }
        }
        assertTrue(cleared.asks.none { it.requestId == ask.requestId })
    }
}
