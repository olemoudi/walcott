package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What a phone stops resolving while it is supposed to be shut, and — the half that matters more
 * — what it never stops resolving, and when it all comes back.
 */
class CurfewTest {

    private val browsers = setOf("com.android.chrome", "com.oem.browser")
    private val phone = setOf("com.android.dialer", "com.android.contacts")

    @Test
    fun `no window, nothing cut off`() {
        assertEquals(
            emptySet<String>(),
            Curfew.cutOff(windowOpen = false, browsers = browsers, lingering = setOf("com.oem.news"), spared = phone),
        )
    }

    @Test
    fun `the browsers go the moment the window opens`() {
        // No observation first: a browser at bedtime is the case this exists for, and waiting two
        // minutes to notice it would be two minutes of exactly what the rule forbids.
        assertEquals(
            browsers,
            Curfew.cutOff(windowOpen = true, browsers = browsers, lingering = emptySet(), spared = phone),
        )
    }

    @Test
    fun `the phone and contacts are never cut off, whatever else is`() {
        // The one promise that outranks every rule in this app: reaching a person has to be
        // possible at three in the morning, and half of reaching them is resolving a name.
        val cut = Curfew.cutOff(
            windowOpen = true,
            browsers = browsers + "com.android.dialer",
            lingering = setOf("com.android.contacts"),
            spared = phone,
        )
        assertEquals(browsers, cut)
    }

    @Test
    fun `an app that outstays two minutes is cut off, one that glances at the clock is not`() {
        var accrued = emptyMap<String, Long>()
        repeat(59) { accrued = Curfew.accrue(accrued, "com.oem.news", 2, windowOpen = true) }
        assertEquals(emptySet<String>(), Curfew.lingering(accrued), "cut off before its two minutes were up")
        accrued = Curfew.accrue(accrued, "com.oem.news", 2, windowOpen = true)
        assertEquals(setOf("com.oem.news"), Curfew.lingering(accrued))
    }

    @Test
    fun `the two minutes are cumulative, not continuous`() {
        // Otherwise the rule is a game: a minute here, a minute in something else, back again.
        var accrued = emptyMap<String, Long>()
        accrued = Curfew.accrue(accrued, "com.oem.news", 70, windowOpen = true)
        accrued = Curfew.accrue(accrued, "com.oem.store", 70, windowOpen = true)
        accrued = Curfew.accrue(accrued, "com.oem.news", 70, windowOpen = true)
        assertEquals(setOf("com.oem.news"), Curfew.lingering(accrued))
    }

    @Test
    fun `the window closing forgets everything`() {
        // This IS the lift. There is no expiry to run and nothing to remember to undo, which is
        // why a curfew cannot outlive the hour that created it.
        var accrued = Curfew.accrue(emptyMap(), "com.oem.news", 300, windowOpen = true)
        assertEquals(setOf("com.oem.news"), Curfew.lingering(accrued))
        accrued = Curfew.accrue(accrued, "com.oem.news", 2, windowOpen = false)
        assertEquals(emptyMap<String, Long>(), accrued)
        assertEquals(emptySet<String>(), Curfew.lingering(accrued))
    }

    @Test
    fun `nothing accrues to an app that is not in the foreground`() {
        assertEquals(emptyMap<String, Long>(), Curfew.accrue(emptyMap(), null, 60, windowOpen = true))
        assertEquals(emptyMap<String, Long>(), Curfew.accrue(emptyMap(), "com.oem.news", 0, windowOpen = true))
    }

    @Test
    fun `a counter stops once it is past the line`() {
        // How far past two minutes an app went is not a fact anybody needs, and an unbounded
        // counter in a loop that runs all night is one worth not having.
        var accrued = Curfew.accrue(emptyMap(), "com.oem.news", 600, windowOpen = true)
        assertEquals(Curfew.LINGER_SECONDS, accrued["com.oem.news"])
        accrued = Curfew.accrue(accrued, "com.oem.news", 600, windowOpen = true)
        assertEquals(Curfew.LINGER_SECONDS, accrued["com.oem.news"])
    }

    @Test
    fun `the watch list is bounded, and drops the weakest case`() {
        var accrued = emptyMap<String, Long>()
        // One that has nearly earned its cut-off, then a full house of newcomers behind it.
        accrued = Curfew.accrue(accrued, "com.oem.news", Curfew.LINGER_SECONDS - 1, windowOpen = true)
        repeat(Curfew.MAX_WATCHED + 4) { accrued = Curfew.accrue(accrued, "com.filler$it", 1, windowOpen = true) }
        assertTrue(accrued.size <= Curfew.MAX_WATCHED, "the watch list grew past its ceiling")
        assertEquals(
            Curfew.LINGER_SECONDS - 1, accrued["com.oem.news"],
            "the app closest to being cut off was the one evicted",
        )
    }

    @Test
    fun `the standing half is decided by the clock alone, so a reboot is not a way out`() {
        // The half that needs no observing, and the reason it is its own entry point: the filter
        // can be brought up by the system with no enforcement loop behind it (a reboot restores
        // the always-on VPN), and it has to be able to work this out for itself.
        val bedtime = FamilyConfig(
            version = 1,
            bedtime = DayType.entries.associateWith { TimeWindow(LocalTime.of(21, 0), LocalTime.of(7, 0)) },
            essentialPackages = phone,
        )
        val night = LocalDateTime.of(2026, 3, 4, 23, 30)
        assertEquals(browsers, Curfew.standing(bedtime, browsers + phone, night))

        // And the lift needs nothing to run: the same question, asked at a different hour.
        val morning = LocalDateTime.of(2026, 3, 4, 9, 30)
        assertEquals(emptySet<String>(), Curfew.standing(bedtime, browsers + phone, morning))
    }

    @Test
    fun `a family with no windows at all never cuts anything off`() {
        assertEquals(
            emptySet<String>(),
            Curfew.standing(FamilyConfig(version = 1), browsers, LocalDateTime.of(2026, 3, 4, 23, 30)),
        )
    }

    @Test
    fun `a cut-off app resolves nothing at all, whatever the rules say about the domain`() {
        // The wildcard, expressed as what it means: every destination, for that one app.
        assertTrue(
            DomainFilter.isBlocked(
                "anything.example", "com.android.chrome",
                DomainMatcher.EMPTY, DomainMatcher.EMPTY, emptyList(),
                cutOff = setOf("com.android.chrome"),
            ),
        )
        assertFalse(
            DomainFilter.isBlocked(
                "anything.example", "com.android.dialer",
                DomainMatcher.EMPTY, DomainMatcher.EMPTY, emptyList(),
                cutOff = setOf("com.android.chrome"),
            ),
        )
    }

    @Test
    fun `being waived from the blocklists is not permission to browse at midnight`() {
        // An exemption waives a list somebody downloaded. It was never an answer to the hour.
        assertTrue(
            DomainFilter.isBlocked(
                "anything.example", "com.android.chrome",
                DomainMatcher.EMPTY, DomainMatcher.of(setOf("anything.example")), emptyList(),
                listExemptApps = setOf("com.android.chrome"),
                cutOff = setOf("com.android.chrome"),
            ),
        )
    }

    @Test
    fun `a lookup nobody could attribute is not cut off`() {
        // Fail-open here on purpose: cutting off what could not be attributed would take the
        // whole phone's DNS down, including the calls this app promises never to limit.
        assertFalse(
            DomainFilter.isBlocked(
                "anything.example", null,
                DomainMatcher.EMPTY, DomainMatcher.EMPTY, emptyList(),
                cutOff = setOf("com.android.chrome"),
            ),
        )
    }
}
