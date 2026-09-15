package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Owner-scoped optional planner preferences. The baseline profile wire remains untouched. */
@Entity(tableName = "strength_planner_profiles")
data class StrengthPlannerProfileEntity(
    @androidx.room.PrimaryKey val scope: String,
    val syncId: String,
    val updatedAt: Long,
)

enum class KeyExercisePriority {
  HIGH,
  NORMAL,
}

@Entity(
    tableName = "strength_planner_key_exercises",
    primaryKeys = ["scope", "exerciseSyncId"],
    foreignKeys =
        [
            ForeignKey(
                entity = StrengthPlannerProfileEntity::class,
                parentColumns = ["scope"],
                childColumns = ["scope"],
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("exerciseSyncId")], // stale selections remain removable after a remote delete
)
data class StrengthPlannerKeyExerciseEntity(
    val scope: String,
    val exerciseSyncId: String,
    val priority: KeyExercisePriority,
)
