package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BonusTest {

    private val parent = ParentSnapshot(
        version = 1,
        policyJson = "{}",
        bonuses = listOf(
            Bonus("b1", "dev-1", "games", 15, 20_000),
            Bonus("b2", "dev-2", "games", 30, 20_000),
        ),
    )

    @Test
    fun `newBonuses returns only this device's unapplied bonuses`() {
        val fresh = SyncEngine.newBonuses(parent, deviceId = "dev-1", alreadyApplied = emptySet())
        assertEquals(listOf("b1"), fresh.map { it.id })
    }

    @Test
    fun `already applied bonuses are excluded`() {
        val fresh = SyncEngine.newBonuses(parent, deviceId = "dev-1", alreadyApplied = setOf("b1"))
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `bonuses for another device are ignored`() {
        val fresh = SyncEngine.newBonuses(parent, deviceId = "dev-3", alreadyApplied = emptySet())
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `child snapshot history round-trips through an envelope`() {
        val familyKey = FamilyCrypto.generateFamilyKey()
        val parentKeys = FamilyCrypto.generateSigningKeyPair()
        val snapshot = ChildSnapshot(
            deviceId = "dev-1",
            displayName = "Ana",
            version = 1,
            epochDay = 20_000,
            history = listOf(
                DayUsage(19_999, listOf(UsageEntry("games", 600))),
                DayUsage(20_000, listOf(UsageEntry("education", 1200))),
            ),
        )
        val wire = SyncProtocol.encodeChild(snapshot, familyKey)
        val decoded = SyncProtocol.decode(wire, familyKey, parentKeys.public)
        assertEquals(snapshot, (decoded as IncomingMessage.FromChild).snapshot)
    }

    @Test
    fun `a bonus from another day is not credited, whatever its id`() {
        // Minutes for the day they were given: one from last month in a replayed envelope —
        // its id long gone from the bounded applied ledger — is minutes nobody granted.
        // Yesterday's still counts: the parent's day and the child's can differ by one.
        val today = 20_000L
        val stale = parent.copy(
            bonuses = listOf(
                Bonus("old", "dev-1", "games", 15, today - 30),
                Bonus("yesterday", "dev-1", "games", 15, today - 1),
                Bonus("today", "dev-1", "games", 15, today),
            ),
        )
        assertEquals(
            listOf("yesterday", "today"),
            SyncEngine.newBonuses(stale, "dev-1", emptySet(), todayEpochDay = today).map { it.id },
        )
        // Without a day to judge against, nothing changes for a caller that has none.
        assertEquals(3, SyncEngine.newBonuses(stale, "dev-1", emptySet()).size)
    }
}
