package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import kotlinx.coroutines.flow.Flow

/** [exerciseId] is null only for a locally retained choice whose catalog record was deleted. */
data class KeyExerciseChoice(
    val exerciseId: Long?,
    val exerciseSyncId: String,
    val priority: KeyExercisePriority,
)

data class StrengthExerciseCandidate(val id: Long, val syncId: String, val name: String)

sealed interface StrengthPlannerSaveResult {
  data object Saved : StrengthPlannerSaveResult
  data object Invalid : StrengthPlannerSaveResult
  data object StaleTarget : StrengthPlannerSaveResult
}

/** Separate from [ProfileRepository] so baseline profile validation and wire shape cannot drift. */
interface StrengthPlannerRepository {
  fun observeLiveStrengthExercises(): Flow<List<StrengthExerciseCandidate>>

  fun observe(target: ProfileEditTarget): Flow<List<KeyExerciseChoice>?>

  suspend fun save(
      target: ProfileEditTarget,
      profileGoal: TrainingGoal?,
      choices: List<KeyExerciseChoice>,
  ): StrengthPlannerSaveResult
}
