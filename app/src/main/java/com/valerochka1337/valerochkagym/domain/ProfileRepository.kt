package com.valerochka1337.valerochkagym.domain

import kotlinx.coroutines.flow.Flow

enum class TrainingGoal {
  STRENGTH,
  MUSCLE_GAIN,
  FAT_LOSS,
  GENERAL_FITNESS,
  ENDURANCE,
  OTHER,
}

enum class ProfileSex {
  FEMALE,
  MALE,
  PREFER_NOT_TO_SAY,
}

enum class ExperienceLevel {
  BEGINNER,
  INTERMEDIATE,
  ADVANCED,
}

/** Editable profile fields. Absence means unknown; it never implies a product default. */
data class BasicProfile(
    val trainingGoal: TrainingGoal? = null,
    val sex: ProfileSex? = null,
    /** Exact local calendar date in ISO-8601 yyyy-MM-dd form. */
    val birthDate: String? = null,
    val experienceLevel: ExperienceLevel? = null,
    val plannedSessionsPerWeek: Int? = null,
    val preferredSessionDurationMinutes: Int? = null,
    val equipmentIds: Set<String> = emptySet(),
    val manualConstraints: String? = null,
    val preferredRepMin: Int? = null,
    val preferredRepMax: Int? = null,
) {
  companion object {
    /** Начальный ориентир применяется только пока нет сохранённого профиля. */
    fun initial() = BasicProfile(preferredRepMin = 8, preferredRepMax = 14)
  }
}

/**
 * Captured when the editor opens so a later account/cache transition cannot write another scope.
 */
data class ProfileEditTarget(
    val scope: String,
    val ownerId: String?,
    val sessionEpoch: Long,
)

data class ProfileEditorSnapshot(val target: ProfileEditTarget, val profile: BasicProfile)

sealed interface ProfileSaveResult {
  data object Saved : ProfileSaveResult

  data object Invalid : ProfileSaveResult

  data object StaleTarget : ProfileSaveResult
}

interface ProfileRepository {
  /** The current scope only; a scope transition never emits another owner's profile in-place. */
  fun observeCurrent(): Flow<ProfileEditorSnapshot?>

  /** An absent stored row is returned as the valid, non-persisted empty profile. */
  suspend fun openEditor(): ProfileEditorSnapshot?

  /** Emits null when [target] is no longer the current owner/session scope. */
  fun observe(target: ProfileEditTarget): Flow<BasicProfile?>

  suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult

  /** Baseline profile and optional strength draft share one owner-guarded Room transaction. */
  suspend fun saveWithStrength(
      target: ProfileEditTarget,
      profile: BasicProfile,
      keyExercises: List<KeyExerciseChoice>,
      plannerPreferences: List<PlannerExerciseChoice>? = null,
  ): ProfileSaveResult = save(target, profile)
}
