package dev.walcott.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, run against real SQLite instead of trusted.
 *
 * A JVM test can check that [WalcottDatabase.MIGRATIONS] *chains* 1 → VERSION, which catches a
 * forgotten migration. It cannot catch a migration whose SQL is wrong, and that failure mode is
 * the worst this app has: Room opens lazily, so it surfaces inside the enforcement loop, on
 * every child at once, on an auto-update nobody asked for. What these tests assert is the
 * promise the release notes make — the child's counters survive the upgrade.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WalcottDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private companion object {
        const val DB = "migration-test.db"
    }

    @Test
    fun a_child_upgrading_from_the_first_schema_keeps_its_counters() {
        // What a family installed on day one, with a day's worth of screen time in it.
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO app_assignment (packageName, categoryId) VALUES ('com.game', 'games')")
            db.execSQL("INSERT INTO usage_counter (categoryId, epochDay, seconds) VALUES ('games', 20000, 1800)")
            db.execSQL("INSERT INTO extra_time (categoryId, epochDay, seconds) VALUES ('games', 20000, 300)")
        }

        val db = helper.runMigrationsAndValidate(DB, WalcottDatabase.VERSION, true, *WalcottDatabase.MIGRATIONS)

        db.query("SELECT categoryId, seconds FROM usage_counter WHERE epochDay = 20000").use { cursor ->
            assertTrue("the child's usage counter did not survive the upgrade", cursor.moveToFirst())
            assertEquals("games", cursor.getString(0))
            assertEquals(1800, cursor.getInt(1))
        }
        db.query("SELECT seconds FROM extra_time WHERE epochDay = 20000").use { cursor ->
            assertTrue("granted extra time did not survive the upgrade", cursor.moveToFirst())
            assertEquals(300, cursor.getInt(0))
        }
        db.query("SELECT categoryId FROM app_assignment WHERE packageName = 'com.game'").use { cursor ->
            assertTrue("app classifications did not survive the upgrade", cursor.moveToFirst())
            assertEquals("games", cursor.getString(0))
        }
    }

    @Test
    fun every_step_of_the_chain_lands_on_a_schema_Room_recognises() {
        // runMigrationsAndValidate compares the result against the exported schema, so walking
        // one version at a time is what proves each migration individually — a chain that only
        // works end to end still breaks the child who updates twice in a row.
        for (from in 1 until WalcottDatabase.VERSION) {
            val name = "step-$from.db"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, from + 1, true, *WalcottDatabase.MIGRATIONS).close()
        }
    }

    @Test
    fun the_location_trail_gains_its_columns_without_losing_its_rows() {
        // v2 added the trail, v3 added the mock-provider flag that spoof detection reads. A row
        // written before the flag existed has to come out as "not mocked", not as a crash.
        helper.createDatabase("trail.db", 2).use { db ->
            db.execSQL(
                "INSERT INTO location_point (epochMs, lat, lng, accuracyM) VALUES (1700000000000, 40.4, -3.7, 12.5)",
            )
        }
        val db = helper.runMigrationsAndValidate("trail.db", WalcottDatabase.VERSION, true, *WalcottDatabase.MIGRATIONS)
        db.query("SELECT lat, lng, mock FROM location_point").use { cursor ->
            assertTrue("the location trail was lost on upgrade", cursor.moveToFirst())
            assertEquals(40.4, cursor.getDouble(0), 0.0001)
            assertEquals(-3.7, cursor.getDouble(1), 0.0001)
            assertEquals("a pre-existing fix came out flagged as mocked", 0, cursor.getInt(2))
        }
    }
}
