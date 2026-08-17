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

    @Query("SELECT * FROM usage_counter WHERE epochDay BETWEEN :start AND :end")
    suspend fun getRange(start: Long, end: Long): List<UsageCounterEntity>

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

    /** Drops counters older than [cutoffDay]; nothing reads past the weekly report. */
    @Query("DELETE FROM usage_counter WHERE epochDay < :cutoffDay")
    suspend fun deleteUsageBefore(cutoffDay: Long)

    @Query("DELETE FROM extra_time WHERE epochDay < :cutoffDay")
    suspend fun deleteExtraBefore(cutoffDay: Long)
}

/** One (day, kind) pair's total, for the cheap "how many that day" queries. */
data class BlockDayTotal(val epochDay: Long, val kind: String, val total: Long)

@Dao
interface BlockDao {

    /** Atomic add to the (day, kind, key) counter. */
    @Query(
        """
        INSERT INTO block_counter (epochDay, kind, `key`, count)
        VALUES (:epochDay, :kind, :key, :count)
        ON CONFLICT(epochDay, kind, `key`)
        DO UPDATE SET count = count + :count
        """,
    )
    suspend fun add(epochDay: Long, kind: String, key: String, count: Long)

    @Query("SELECT * FROM block_counter WHERE epochDay = :epochDay AND kind = :kind ORDER BY count DESC")
    suspend fun getDayKind(epochDay: Long, kind: String): List<BlockCounterEntity>

    @Query(
        """
        SELECT epochDay, kind, SUM(count) AS total FROM block_counter
        WHERE epochDay BETWEEN :start AND :end GROUP BY epochDay, kind
        """,
    )
    suspend fun totalsBetween(start: Long, end: Long): List<BlockDayTotal>

    @Query("SELECT COUNT(*) FROM block_counter WHERE epochDay = :epochDay AND kind = :kind")
    suspend fun keyCount(epochDay: Long, kind: String): Int

    @Query("DELETE FROM block_counter WHERE epochDay = :epochDay AND kind = :kind AND `key` IN (:keys)")
    suspend fun deleteKeys(epochDay: Long, kind: String, keys: List<String>)

    @Query("DELETE FROM block_counter WHERE epochDay < :cutoffDay")
    suspend fun deleteBefore(cutoffDay: Long)
}

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(point: LocationPointEntity)

    @Query("SELECT * FROM location_point WHERE epochMs >= :sinceMs ORDER BY epochMs")
    suspend fun getSince(sinceMs: Long): List<LocationPointEntity>

    /**
     * Newest fix only, for children reporting just their current position. A dedicated query
     * because the 48h window can hold hundreds of rows and this runs on every check-in.
     */
    @Query("SELECT * FROM location_point WHERE epochMs >= :sinceMs ORDER BY epochMs DESC LIMIT 1")
    suspend fun getLatestSince(sinceMs: Long): LocationPointEntity?

    @Query("DELETE FROM location_point WHERE epochMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}

/** The notification log on the device that received them (see [NotificationEntity]). */
@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NotificationEntity)

    /** Replaces the row for an updated notification instead of adding a second one. */
    @Query("DELETE FROM notification_log WHERE key = :key AND key != ''")
    suspend fun deleteByKey(key: String)

    /**
     * A page, newest first: everything posted in [sinceMs, beforeMs). The parent asks for the last
     * 24 h and then pages backwards from the oldest it received, so a window too big for one
     * message is not a window it can never see.
     */
    @Query(
        """
        SELECT * FROM notification_log
        WHERE postedAtMs >= :sinceMs AND postedAtMs < :beforeMs
        ORDER BY postedAtMs DESC LIMIT :limit
        """,
    )
    suspend fun page(sinceMs: Long, beforeMs: Long, limit: Int): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notification_log WHERE postedAtMs >= :sinceMs AND postedAtMs < :beforeMs")
    suspend fun countBetween(sinceMs: Long, beforeMs: Long): Int

    /**
     * The same page, about ONE app — the query a family actually makes ("did the message from the
     * clinic arrive?"). Both a smaller message and a smaller intrusion than reading a whole day of
     * somebody's private messages to answer one question.
     */
    @Query(
        """
        SELECT * FROM notification_log
        WHERE packageName = :pkg AND postedAtMs >= :sinceMs AND postedAtMs < :beforeMs
        ORDER BY postedAtMs DESC LIMIT :limit
        """,
    )
    suspend fun pageForApp(pkg: String, sinceMs: Long, beforeMs: Long, limit: Int): List<NotificationEntity>

    @Query(
        """
        SELECT COUNT(*) FROM notification_log
        WHERE packageName = :pkg AND postedAtMs >= :sinceMs AND postedAtMs < :beforeMs
        """,
    )
    suspend fun countForApp(pkg: String, sinceMs: Long, beforeMs: Long): Int

    @Query("DELETE FROM notification_log WHERE postedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    /** Keeps the newest [keep] rows and drops the rest — the cap that bounds a busy phone. */
    @Query(
        """
        DELETE FROM notification_log WHERE id NOT IN
        (SELECT id FROM notification_log ORDER BY postedAtMs DESC LIMIT :keep)
        """,
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM notification_log")
    suspend fun clear()
}
