package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * The v2 personal planner override. A missing row means inherit the server-owned default; in
 * particular it is deliberately different from an explicit [NORMAL] row.
 */
enum class PlannerExerciseAccent {
  MORE,
  NORMAL,
  LESS,
  NEVER,
}

@Entity(
    tableName = "planner_exercise_accents_v2",
    primaryKeys = ["scope", "exerciseSyncId"],
    indices = [Index("exerciseSyncId")],
)
data class PlannerExerciseAccentV2Entity(
    val scope: String,
    val exerciseSyncId: String,
    val preference: PlannerExerciseAccent,
)
