package dev.walcott.data

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

/** A GPS fix captured on the child device (only ever populated on the child). */
@Entity(tableName = "location_point")
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
)
