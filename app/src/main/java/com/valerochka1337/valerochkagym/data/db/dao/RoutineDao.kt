package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {

  @Query(
      """
        SELECT r.*,
            (SELECT COUNT(*) FROM routine_exercises re WHERE re.routineId = r.id) AS exerciseCount
        FROM routines r
        ORDER BY r.name COLLATE NOCASE ASC
        """,
  )
  fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>>

  /**
   * Полное дерево всех программ (программа + её упражнения c plannedSets/restSeconds). Используется
   * списком программ для оценки длительности в памяти — считать её в SQL нельзя, т.к. plannedSets
   * хранятся как JSON.
   */
  @Transaction
  @Query("SELECT * FROM routines WHERE archived=0 ORDER BY name COLLATE NOCASE ASC")
  fun observeRoutinesFull(): Flow<List<RoutineWithExercises>>

  @Transaction
  @Query("SELECT * FROM routines WHERE archived=0 ORDER BY name COLLATE NOCASE ASC")
  suspend fun routinesFullOnce(): List<RoutineWithExercises>

  @Transaction
  @Query("SELECT * FROM routines WHERE id = :id")
  suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises?

  /** Только название программы — для заголовка события календаря (Стадия 21). */
  @Query("SELECT name FROM routines WHERE id = :id") suspend fun getRoutineName(id: Long): String?

  /**
   * Stable create-operation key used to replay a save safely after process death. The caller must
   * use this only for a new routine, never for editor updates.
   */
  @Query("SELECT * FROM routines WHERE syncId = :syncId LIMIT 1")
  suspend fun getRoutineBySyncId(syncId: String): RoutineEntity?

  @Upsert suspend fun upsertRoutine(routine: RoutineEntity): Long

  @Query("DELETE FROM routines WHERE id = :id") suspend fun deleteRoutine(id: Long)

  @Insert
  suspend fun insertRoutineExercises(routineExercises: List<RoutineExerciseEntity>): List<Long>

  @Query("DELETE FROM routine_exercises WHERE routineId = :routineId")
  suspend fun deleteRoutineExercises(routineId: Long)

  @Transaction
  suspend fun replaceRoutineExercises(routineId: Long, list: List<RoutineExerciseEntity>) {
    deleteRoutineExercises(routineId)
    insertRoutineExercises(list)
  }
}
