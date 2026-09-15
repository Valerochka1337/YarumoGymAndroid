package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffort
import kotlinx.coroutines.flow.Flow

sealed interface WorkoutEffortSaveResult {
  data object Saved : WorkoutEffortSaveResult
  data object Invalid : WorkoutEffortSaveResult
  data object StaleOwner : WorkoutEffortSaveResult
}

data class WorkoutEffortEditTarget(val workoutId: String, val scope: String, val sessionEpoch: Long)

interface WorkoutEffortRepository {
  fun captureTarget(workoutId: String): WorkoutEffortEditTarget?

  fun observe(target: WorkoutEffortEditTarget): Flow<WorkoutEffort?>

  /** Saves only a completed live workout in the session captured when the editor opens. */
  suspend fun save(target: WorkoutEffortEditTarget, effort: WorkoutEffort?): WorkoutEffortSaveResult
}
