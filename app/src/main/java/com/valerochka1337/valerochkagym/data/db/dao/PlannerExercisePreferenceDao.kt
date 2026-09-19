package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannerExercisePreferenceDao {
  @Query("SELECT * FROM planner_exercise_preferences WHERE scope=:scope ORDER BY exerciseSyncId")
  fun observe(scope: String): Flow<List<PlannerExercisePreferenceEntity>>

  @Query("SELECT * FROM planner_exercise_preferences WHERE scope=:scope ORDER BY exerciseSyncId")
  suspend fun get(scope: String): List<PlannerExercisePreferenceEntity>

  @Query("DELETE FROM planner_exercise_preferences WHERE scope=:scope")
  suspend fun delete(scope: String)

  @Upsert suspend fun upsert(items: List<PlannerExercisePreferenceEntity>)
}
