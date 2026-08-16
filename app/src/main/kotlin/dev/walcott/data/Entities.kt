package dev.walcott.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Category assigned by the parent to a package. No row = unclassified app. */
@Entity(tableName = "app_assignment")
data class AppAssignmentEntity(
    @PrimaryKey val packageName: String,
    val categoryId: String,
)

/**
 * Accumulated usage of a category on a specific local day (seconds).
 * Keying by day makes "today" a plain query by epochDay, with no resets.
 */
@Entity(tableName = "usage_counter", primaryKeys = ["categoryId", "epochDay"])
data class UsageCounterEntity(
    val categoryId: String,
    val epochDay: Long,
    val seconds: Long,
)

/** Extra time granted to a category on a specific day (seconds). */
@Entity(tableName = "extra_time", primaryKeys = ["categoryId", "epochDay"])
data class ExtraTimeEntity(
    val categoryId: String,
    val epochDay: Long,
    val seconds: Long,
)

/**
 * How many times something was blocked on this device on one local day.
 *
 * [kind] is one of [BlockKinds] and [key] is a domain or a package, so one table answers three
 * questions ("which sites", "which app was asking", "which app hit a rule") without three
 * schemas. Counters only ever grow within their day, which is what makes a replayed report
 * harmless on the parent's side.
 *
 * Bounded on both axes and deliberately: old days are pruned, and a day that sees more distinct
 * keys than [BlockKinds.MAX_KEYS_PER_DAY] folds its tail into [BlockKinds.OTHER] rather than
 * growing a row per domain a tracker-happy app ever asked for.
 */
@Entity(tableName = "block_counter", primaryKeys = ["epochDay", "kind", "key"])
data class BlockCounterEntity(
    val epochDay: Long,
    val kind: String,
    val key: String,
    val count: Long,
)

/** A GPS fix captured on the child device (only ever populated on the child). */
@Entity(tableName = "location_point")
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    @ColumnInfo(defaultValue = "0") val mock: Boolean = false,
)
