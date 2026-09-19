package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A generation-only preference. It deliberately does not share the key-exercise table: keys are
 * strength-only focus while these choices can apply to every live exercise type.
 */
enum class PlannerExercisePreference {
  MORE,
  LESS,
  NEVER,
}

@Entity(
    tableName = "planner_exercise_preferences",
    primaryKeys = ["scope", "exerciseSyncId"],
    indices = [Index("exerciseSyncId")],
)
data class PlannerExercisePreferenceEntity(
    val scope: String,
    val exerciseSyncId: String,
    val preference: PlannerExercisePreference,
)
