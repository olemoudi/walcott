package dev.walcott.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the one mistake that could brick every child device at once: bumping the schema
 * version without shipping the matching migration. Room only finds out when it opens the
 * database on the child's phone, and the app deliberately doesn't fall back to a destructive
 * migration — so this has to fail in CI instead.
 *
 * Pure reflection over the declared migrations; no Android runtime involved.
 */
class MigrationChainTest {

    private val migrations = WalcottDatabase.MIGRATIONS

    @Test
    fun `migrations cover every version step up to the current schema`() {
        val steps = migrations.associateBy { it.startVersion }
        for (from in 1 until WalcottDatabase.VERSION) {
            val step = steps[from]
            assertTrue(step != null, "no migration from schema $from (bump without a migration?)")
            assertEquals(
                from + 1, step!!.endVersion,
                "migration from $from must land on ${from + 1}, not ${step.endVersion}",
            )
        }
    }

    @Test
    fun `no migration points past the current schema version`() {
        val stray = migrations.filter { it.endVersion > WalcottDatabase.VERSION }
        assertTrue(stray.isEmpty(), "migrations end above VERSION=${WalcottDatabase.VERSION}: $stray")
    }

    @Test
    fun `there is exactly one migration per step`() {
        val duplicated = migrations.groupBy { it.startVersion }.filterValues { it.size > 1 }
        assertTrue(duplicated.isEmpty(), "more than one migration from the same version: ${duplicated.keys}")
    }
}
