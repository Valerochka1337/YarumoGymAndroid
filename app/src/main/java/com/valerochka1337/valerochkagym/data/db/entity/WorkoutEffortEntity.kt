package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class WorkoutEffort {
  EASY,
  MODERATE,
  HARD,
}

/** Optional owner-bound assessment. A null [effort] is the durable user clear. */
@Entity(
    tableName = "workout_efforts",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkoutEntity::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["syncId"], unique = true), Index("scope")],
)
data class WorkoutEffortEntity(
    @androidx.room.PrimaryKey val workoutId: String,
    val scope: String,
    val syncId: String,
    val updatedAt: Long,
    val effort: WorkoutEffort?,
)
