package dev.walcott.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [AppAssignmentEntity::class, UsageCounterEntity::class, ExtraTimeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WalcottDatabase : RoomDatabase() {
    abstract fun assignments(): AppAssignmentDao
    abstract fun usage(): UsageDao

    companion object {
        /**
         * Schema migrations, applied transparently on open. Add one for every schema version
         * bump so auto-updates never lose data — do NOT enable destructive migration.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

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
