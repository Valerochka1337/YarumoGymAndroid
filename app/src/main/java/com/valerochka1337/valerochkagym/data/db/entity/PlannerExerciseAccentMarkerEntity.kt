package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity

/**
 * Makes the v2 aggregate authoritative even when [planner_exercise_accents_v2] is empty. It is
 * never created by a schema migration: only a local edit or a valid remote v2 record adopts the
 * aggregate.
 */
@Entity(tableName = "planner_exercise_accent_markers", primaryKeys = ["scope"])
data class PlannerExerciseAccentMarkerEntity(val scope: String)
