package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.StrengthPlannerKeyExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.StrengthPlannerProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StrengthPlannerProfileDao {
  @Query("SELECT * FROM strength_planner_profiles WHERE scope=:scope")
  fun observe(scope: String): Flow<StrengthPlannerProfileEntity?>

  @Query("SELECT * FROM strength_planner_profiles WHERE scope=:scope")
  suspend fun get(scope: String): StrengthPlannerProfileEntity?

  @Query("SELECT * FROM strength_planner_key_exercises WHERE scope=:scope ORDER BY CASE priority WHEN 'HIGH' THEN 0 ELSE 1 END, exerciseSyncId")
  fun observeKeyExercises(scope: String): Flow<List<StrengthPlannerKeyExerciseEntity>>

  @Query("SELECT * FROM strength_planner_key_exercises WHERE scope=:scope ORDER BY CASE priority WHEN 'HIGH' THEN 0 ELSE 1 END, exerciseSyncId")
  suspend fun keyExercises(scope: String): List<StrengthPlannerKeyExerciseEntity>

  @Upsert suspend fun upsert(profile: StrengthPlannerProfileEntity)

  @Query("DELETE FROM strength_planner_key_exercises WHERE scope=:scope")
  suspend fun deleteKeyExercises(scope: String)

  @Upsert suspend fun upsertKeyExercises(items: List<StrengthPlannerKeyExerciseEntity>)

  @Query("DELETE FROM strength_planner_profiles WHERE scope=:scope") suspend fun delete(scope: String)
}
