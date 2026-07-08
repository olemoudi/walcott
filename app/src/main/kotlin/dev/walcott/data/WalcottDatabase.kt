package dev.walcott.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppAssignmentEntity::class,
        UsageCounterEntity::class,
        ExtraTimeEntity::class,
        LocationPointEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class WalcottDatabase : RoomDatabase() {
    abstract fun assignments(): AppAssignmentDao
    abstract fun usage(): UsageDao
    abstract fun locations(): LocationDao

    companion object {
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
        )

        @Volatile private var instance: WalcottDatabase? = null

        fun get(context: Context): WalcottDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WalcottDatabase::class.java,
                "walcott.db",
            )
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}
