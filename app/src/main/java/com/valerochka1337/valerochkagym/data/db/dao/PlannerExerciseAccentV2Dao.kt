package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentMarkerEntity
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentV2Entity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannerExerciseAccentV2Dao {
  @Query("SELECT * FROM planner_exercise_accents_v2 WHERE scope=:scope ORDER BY exerciseSyncId")
  fun observe(scope: String): Flow<List<PlannerExerciseAccentV2Entity>>

  @Query("SELECT * FROM planner_exercise_accents_v2 WHERE scope=:scope ORDER BY exerciseSyncId")
  suspend fun get(scope: String): List<PlannerExerciseAccentV2Entity>

  @Query("SELECT EXISTS(SELECT 1 FROM planner_exercise_accent_markers WHERE scope=:scope)")
  fun observeMarker(scope: String): Flow<Boolean>

  @Query("SELECT EXISTS(SELECT 1 FROM planner_exercise_accent_markers WHERE scope=:scope)")
  suspend fun hasMarker(scope: String): Boolean

  @Query("DELETE FROM planner_exercise_accents_v2 WHERE scope=:scope")
  suspend fun deleteRows(scope: String)

  @Query("DELETE FROM planner_exercise_accent_markers WHERE scope=:scope")
  suspend fun deleteMarker(scope: String)

  @Upsert suspend fun upsertRows(items: List<PlannerExerciseAccentV2Entity>)

  @Upsert suspend fun upsertMarker(marker: PlannerExerciseAccentMarkerEntity)
}
