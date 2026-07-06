package dev.walcott.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppAssignmentEntity::class, UsageCounterEntity::class, ExtraTimeEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WalcottDatabase : RoomDatabase() {
    abstract fun assignments(): AppAssignmentDao
    abstract fun usage(): UsageDao

    companion object {
        @Volatile private var instance: WalcottDatabase? = null

        fun get(context: Context): WalcottDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WalcottDatabase::class.java,
                "walcott.db",
            ).build().also { instance = it }
        }
    }
}
