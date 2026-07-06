package dev.walcott.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppAssignmentDao {
    @Query("SELECT * FROM app_assignment")
    fun observeAll(): Flow<List<AppAssignmentEntity>>

    @Query("SELECT * FROM app_assignment")
    suspend fun getAll(): List<AppAssignmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: AppAssignmentEntity)

    @Query("DELETE FROM app_assignment WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_counter WHERE epochDay = :epochDay")
    fun observeDay(epochDay: Long): Flow<List<UsageCounterEntity>>

    @Query("SELECT * FROM usage_counter WHERE epochDay = :epochDay")
    suspend fun getDay(epochDay: Long): List<UsageCounterEntity>

    /** Atomic add to the (category, day) counter. */
    @Query(
        """
        INSERT INTO usage_counter (categoryId, epochDay, seconds)
        VALUES (:categoryId, :epochDay, :seconds)
        ON CONFLICT(categoryId, epochDay)
        DO UPDATE SET seconds = seconds + :seconds
        """,
    )
    suspend fun addSeconds(categoryId: String, epochDay: Long, seconds: Long)

    @Query("SELECT * FROM extra_time WHERE epochDay = :epochDay")
    fun observeExtraDay(epochDay: Long): Flow<List<ExtraTimeEntity>>

    @Query("SELECT * FROM extra_time WHERE epochDay = :epochDay")
    suspend fun getExtraDay(epochDay: Long): List<ExtraTimeEntity>

    @Query(
        """
        INSERT INTO extra_time (categoryId, epochDay, seconds)
        VALUES (:categoryId, :epochDay, :seconds)
        ON CONFLICT(categoryId, epochDay)
        DO UPDATE SET seconds = seconds + :seconds
        """,
    )
    suspend fun addExtraSeconds(categoryId: String, epochDay: Long, seconds: Long)
}
