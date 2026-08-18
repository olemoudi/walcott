package dev.walcott.sync

import dev.walcott.data.AppPolicyDto
import dev.walcott.data.ChildEntry
import dev.walcott.data.ChildOverrides
import dev.walcott.data.DomainAppRuleDto
import dev.walcott.data.PolicySettings
import dev.walcott.data.WindowDto
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How much room a real family's rules actually leave in one relay message.
 *
 * [ParentFit] decides what to trade away when the message is too big, but the field it can never
 * trade is the policy — so this measures a demanding-but-ordinary family against the real cap and
 * says how close it is. It is the alarm that goes off in CI long before a family discovers, with
 * no explanation at all, that their rule changes have stopped reaching anybody.
 *
 * Lives in `:app` because that is where [PolicySettings] lives, and it is that class — not a
 * hand-written JSON string — whose growth this has to track.
 */
class ParentSnapshotSizeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val signing = FamilyCrypto.generateSigningKeyPair()

    private fun window(start: Int, end: Int) = WindowDto(start, end, days = listOf(1, 2, 3, 4, 5))

    /**
     * More than the author's own family runs, and all of it plausible: four members, a bedtime per
     * day type, screen-free windows, thirty apps with their own limits, a couple of children with
     * their own overridden rules, and forty blocked domains typed by hand.
     */
    private fun demandingFamily(): PolicySettings = PolicySettings(
        familyName = "The Fitzgeralds",
        defaultAppBudget = mapOf("SCHOOL" to 60, "WEEKEND" to 120, "HOLIDAY" to 150),
        bedtime = mapOf(
            "SCHOOL" to WindowDto(21 * 60, 7 * 60),
            "WEEKEND" to WindowDto(23 * 60, 9 * 60),
            "HOLIDAY" to WindowDto(23 * 60 + 30, 10 * 60),
        ),
        allAppsBlockedWindows = mapOf(
            "SCHOOL" to listOf(window(9 * 60, 14 * 60), window(17 * 60, 18 * 60 + 30)),
            "WEEKEND" to listOf(window(14 * 60, 15 * 60)),
        ),
        holidays = (1..12).map { 20_000L + it * 30 }.toSet(),
        appPolicies = (1..30).associate { index ->
            "com.example.vendor$index.application" to AppPolicyDto(
                budgets = mapOf("SCHOOL" to 30, "WEEKEND" to 60),
                blockedWindows = mapOf("SCHOOL" to listOf(window(20 * 60, 21 * 60))),
            )
        },
        blockedDomains = (1..40).map { "tracker-number-$it.example.com" }.toSet(),
        domainAppRules = (1..10).map {
            DomainAppRuleDto("api-$it.example.com", "com.example.vendor$it.application", allowOnlyFromApp = true)
        },
        enabledBlocklists = setOf("adult", "gambling", "ads"),
        deviceRestrictions = setOf("datetime", "vpn", "apps_control", "unknown_sources", "installs", "uninstall"),
        children = (1..4).map { index ->
            ChildEntry(
                childId = "00000000-0000-4000-8000-00000000000$index",
                name = "Member $index",
                addedAtMs = 1_720_000_000_000,
                overrides = if (index <= 2) {
                    ChildOverrides(
                        defaultAppBudget = mapOf("SCHOOL" to 45),
                        bedtime = mapOf("SCHOOL" to WindowDto(20 * 60 + 30, 7 * 60)),
                        trackingIntervalMinutes = 15,
                    )
                } else {
                    ChildOverrides()
                },
            )
        },
        pinHash = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyaGFzaA==",
        pinSalt = "c2FsdHNhbHRzYWx0c2FsdA==",
    )

    private fun snapshotOf(policy: PolicySettings) = ParentSnapshot(
        version = 412,
        policyJson = json.encodeToString(PolicySettings.serializer(), policy),
        // What a live family carries alongside the rules: a couple of answers still in flight and
        // one queued command.
        resolutions = listOf(Resolution("r1", true, 15, System.currentTimeMillis())),
        bonuses = listOf(Bonus("b1", "device-1", "com.example.vendor1.application", 20, 20_000)),
        commands = listOf(RemoteCommand("c1", "device-1", RemoteAction.UPDATE_NOW, System.currentTimeMillis())),
        parentVersionCode = 116,
    )

    @Test
    fun `a demanding family's rules fit in one message with room to spare`() {
        val result = ParentFit.encode(snapshotOf(demandingFamily()), familyKey, signing.private)
        println("demanding family: ${result.encoded.length} of ${SnapshotFit.MAX_BYTES} bytes")

        assertFalse(result.oversize, "these rules no longer fit at all")
        assertNull(result.degraded, "nothing should have to be traded away for an ordinary family")
        // The tripwire, not the ceiling. This family sits at about two thirds of the message
        // today, so the room left is real but not generous: crossing this line means the next
        // per-app or per-child field is what tips a live family into a channel that silently
        // refuses everything, and the answer then is to shrink or chunk the policy — not to
        // move this number.
        assertTrue(
            result.encoded.length < SIZE_TRIPWIRE,
            "a demanding family now uses ${result.encoded.length} of ${SnapshotFit.MAX_BYTES} bytes; " +
                "the policy is running out of room and needs to shrink or be chunked",
        )
    }

    /** Where the policy stops having comfortable room; see the assertion for why it is here. */
    private val SIZE_TRIPWIRE = 3_000

    @Test
    fun `the rules survive the round trip they are measured on`() {
        val policy = demandingFamily()
        val result = ParentFit.encode(snapshotOf(policy), familyKey, signing.private)
        val decoded = SyncProtocol.decode(result.encoded, familyKey, signing.public) as IncomingMessage.FromParent
        val back = json.decodeFromString(PolicySettings.serializer(), decoded.snapshot.policyJson)

        assertTrue(back == policy)
    }
}
