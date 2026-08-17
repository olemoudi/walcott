package dev.walcott.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.walcott.debug.DebugLog

@Database(
    entities = [
        AppAssignmentEntity::class,
        UsageCounterEntity::class,
        ExtraTimeEntity::class,
        LocationPointEntity::class,
        BlockCounterEntity::class,
        NotificationEntity::class,
    ],
    version = WalcottDatabase.VERSION,
    exportSchema = true,
)
abstract class WalcottDatabase : RoomDatabase() {
    abstract fun assignments(): AppAssignmentDao
    abstract fun usage(): UsageDao
    abstract fun locations(): LocationDao
    abstract fun blocks(): BlockDao
    abstract fun notifications(): NotificationDao

    companion object {
        /**
         * Schema version. A named constant so a unit test can check that [MIGRATIONS] actually
         * chains 1 → [VERSION] — a forgotten migration is the classic way to brick every child
         * at once, and it must be caught in CI, not on a family's phone.
         */
        const val VERSION = 5

        private const val NAME = "walcott.db"
        private const val TAG = "WalcottDb"

        /**
         * Schema migrations, applied transparently on open. Add one for every schema version
         * bump so auto-updates never lose data — do NOT enable destructive migration.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // v2: child GPS location history for the parent map.
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `location_point` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`epochMs` INTEGER NOT NULL, " +
                            "`lat` REAL NOT NULL, " +
                            "`lng` REAL NOT NULL, " +
                            "`accuracyM` REAL NOT NULL)",
                    )
                }
            },
            // v3: mock-provider flag for spoof detection.
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `location_point` ADD COLUMN `mock` INTEGER NOT NULL DEFAULT 0")
                }
            },
            // v4: per-day block counters (web filter refusals and rule-closed apps).
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `block_counter` (" +
                            "`epochDay` INTEGER NOT NULL, " +
                            "`kind` TEXT NOT NULL, " +
                            "`key` TEXT NOT NULL, " +
                            "`count` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`epochDay`, `kind`, `key`))",
                    )
                }
            },
            // v5: the notification log an adult being helped can have kept for their family to
            // read on request (see NotificationLog). Empty on every device that never turns it on.
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `notification_log` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`postedAtMs` INTEGER NOT NULL, " +
                            "`packageName` TEXT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`text` TEXT NOT NULL, " +
                            "`key` TEXT NOT NULL DEFAULT '')",
                    )
                }
            },
        )

        @Volatile private var instance: WalcottDatabase? = null

        /**
         * True when the stored database had to be thrown away to get the app running again
         * (see [get]). Surfaced in the child's diagnostics so a lost day of counters is
         * visible rather than mysterious.
         */
        @Volatile var wasRebuilt: Boolean = false
            private set

        fun get(context: Context): WalcottDatabase = instance ?: synchronized(this) {
            instance ?: open(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): WalcottDatabase =
            Room.databaseBuilder(context, WalcottDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /**
         * Opens the database, and if it can't be opened at all, throws it away and starts over.
         *
         * Room opens lazily, so a missing migration or a corrupted file would otherwise surface
         * as an exception on the first query — deep inside the enforcement loop, which would
         * then crash-restart every few seconds forever: apps frozen in whatever state they were
         * last left in, and the child silently unable to publish, i.e. exactly the "bricked and
         * unreachable from the parent" outcome this app cannot afford. Everything in here is
         * regenerable (today's counters, granted extra, a 48h location trail); the rules live in
         * the policy store and come back from the parent. Losing a day of counters beats losing
         * the device, so the open is forced here, once, where the failure can be handled.
         */
        private fun open(context: Context): WalcottDatabase {
            val db = build(context)
            val opened = runCatching { db.openHelper.writableDatabase }
            if (opened.isSuccess) return db
            DebugLog.e(TAG, "database unusable; rebuilding from scratch", opened.exceptionOrNull())
            runCatching { db.close() }
            runCatching { context.deleteDatabase(NAME) }
            wasRebuilt = true
            return build(context)
        }
    }
}
