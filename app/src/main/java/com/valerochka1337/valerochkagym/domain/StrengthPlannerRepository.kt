package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccent
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreference
import kotlinx.coroutines.flow.Flow

/** [exerciseId] is null only for a locally retained choice whose catalog record was deleted. */
data class KeyExerciseChoice(
    val exerciseId: Long?,
    val exerciseSyncId: String,
    val priority: KeyExercisePriority,
)

data class StrengthExerciseCandidate(val id: Long, val syncId: String, val name: String)

/** A nullable local id keeps a remote/deleted selection removable without treating it as live. */
data class PlannerExerciseChoice(
    val exerciseId: Long?,
    val exerciseSyncId: String,
    val preference: PlannerExercisePreference,
)

/** A v2 override may explicitly be NORMAL; absence is represented by no choice at all. */
data class PlannerExerciseAccentChoice(
    val exerciseId: Long?,
    val exerciseSyncId: String,
    val preference: PlannerExerciseAccent,
)

/** A user action, not a replacement snapshot; null restores the inherited default. */
data class PlannerExerciseAccentEdit(
    val exerciseSyncId: String,
    val preference: PlannerExerciseAccent?,
)

sealed interface StrengthPlannerSaveResult {
  data object Saved : StrengthPlannerSaveResult

  data object Invalid : StrengthPlannerSaveResult

  data object StaleTarget : StrengthPlannerSaveResult
}

/** Separate from [ProfileRepository] so baseline profile validation and wire shape cannot drift. */
interface StrengthPlannerRepository {
  fun observeLiveStrengthExercises(): Flow<List<StrengthExerciseCandidate>>

  fun observe(target: ProfileEditTarget): Flow<List<KeyExerciseChoice>?>

  fun observePlannerPreferences(target: ProfileEditTarget): Flow<List<PlannerExerciseChoice>?> =
      kotlinx.coroutines.flow.flowOf(emptyList())

  fun observePlannerAccents(target: ProfileEditTarget): Flow<List<PlannerExerciseAccentChoice>?> =
      kotlinx.coroutines.flow.flowOf(emptyList())

  fun observeLivePlannerExercises(): Flow<List<StrengthExerciseCandidate>> =
      observeLiveStrengthExercises()

  suspend fun savePlannerPreferences(
      target: ProfileEditTarget,
      choices: List<PlannerExerciseChoice>,
  ): StrengthPlannerSaveResult = StrengthPlannerSaveResult.Invalid

  suspend fun save(
      target: ProfileEditTarget,
      profileGoal: TrainingGoal?,
      choices: List<KeyExerciseChoice>,
  ): StrengthPlannerSaveResult
}
