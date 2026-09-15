package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffortEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutEffortDao {
  @Query("SELECT * FROM workout_efforts WHERE workoutId=:workoutId AND scope=:scope")
  fun observe(workoutId: String, scope: String): Flow<WorkoutEffortEntity?>

  @Query("SELECT * FROM workout_efforts WHERE workoutId=:workoutId AND scope=:scope")
  suspend fun get(workoutId: String, scope: String): WorkoutEffortEntity?

  @Upsert suspend fun upsert(effort: WorkoutEffortEntity)
}
