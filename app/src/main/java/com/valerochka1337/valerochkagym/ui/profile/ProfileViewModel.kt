package com.valerochka1337.valerochkagym.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.StrengthExerciseCandidate
import com.valerochka1337.valerochkagym.domain.StrengthPlannerRepository
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.service.WallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class ProfileEditorUiState(
    val isLoading: Boolean = true,
    val target: ProfileEditTarget? = null,
    val trainingGoal: TrainingGoal? = null,
    val sex: ProfileSex? = null,
    val birthDate: String = "",
    val experienceLevel: ExperienceLevel? = null,
    val plannedSessionsPerWeek: String = "",
    val preferredSessionDurationMinutes: String = "",
    val equipmentIds: Set<String> = emptySet(),
    val manualConstraints: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val promptDisabled: Boolean = false,
    val keyExercises: List<KeyExerciseChoice> = emptyList(),
    val strengthExercises: List<StrengthExerciseCandidate> = emptyList(),
    val showKeyExercises: Boolean = false,
)

@HiltViewModel
class ProfileViewModel
@Inject
constructor(
    private val profileRepository: ProfileRepository,
    private val promptGate: AiProfilePromptGate,
    private val savedStateHandle: SavedStateHandle,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
    private val strengthPlannerRepository: StrengthPlannerRepository? = null,
) : ViewModel() {
  private val _uiState = MutableStateFlow(ProfileEditorUiState())
  val uiState: StateFlow<ProfileEditorUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val snapshot = profileRepository.openEditor()
      if (snapshot == null) {
        _uiState.value =
            ProfileEditorUiState(isLoading = false, error = "Профиль больше недоступен")
        return@launch
      }
      val promptDisabled = promptGate.isDisabledForCurrentScope()
      combine(
          profileRepository.observe(snapshot.target),
          strengthPlannerRepository?.observe(snapshot.target) ?: flowOf(emptyList()),
          strengthPlannerRepository?.observeLiveStrengthExercises() ?: flowOf(emptyList()),
      ) { profile, choices, candidates ->
        if (profile == null || choices == null) {
          ProfileEditorUiState(isLoading = false, error = "Профиль больше недоступен")
        } else {
          val draft = profileDraftFrom(savedStateHandle, snapshot.target)
          val ids = candidates.associateBy { it.syncId }
          profile.toUi(snapshot.target, savedStateHandle, promptDisabled).copy(
              keyExercises = (draft?.keyExercises ?: choices).map {
                it.copy(exerciseId = ids[it.exerciseSyncId]?.id)
              },
              strengthExercises = candidates,
          )
        }
      }.collectLatest { next ->
        if (!_uiState.value.isSaving || next.target == null) _uiState.value = next
      }
    }
  }

  fun setGoal(value: TrainingGoal?) = update { copy(trainingGoal = value, error = null) }

  fun setSex(value: ProfileSex?) = update { copy(sex = value, error = null) }

  fun setBirthDate(value: String) = update { copy(birthDate = value, error = null) }

  fun setExperience(value: ExperienceLevel?) = update {
    copy(experienceLevel = value, error = null)
  }

  fun setSessions(value: String) = update { copy(plannedSessionsPerWeek = value, error = null) }

  fun setDuration(value: String) = update {
    copy(preferredSessionDurationMinutes = value, error = null)
  }

  fun setConstraints(value: String) = update { copy(manualConstraints = value, error = null) }

  fun toggleEquipment(id: String) {
    if (!LocalEquipmentCatalog.isKnown(id)) return
    update {
      copy(
          equipmentIds =
              equipmentIds.toMutableSet().also { ids -> if (!ids.add(id)) ids.remove(id) }
      )
    }
  }

  fun setPromptDisabled(disabled: Boolean) {
    viewModelScope.launch {
      promptGate.setDisabledForCurrentScope(disabled)
      _uiState.value = _uiState.value.copy(promptDisabled = disabled)
    }
  }

  fun toggleKeyExercise(exerciseId: Long) = update {
    val current = keyExercises.associateBy { it.exerciseId }.toMutableMap()
    if (current.remove(exerciseId) == null && current.size < 5)
        current[exerciseId] =
            KeyExerciseChoice(
                exerciseId = exerciseId,
                exerciseSyncId =
                    strengthExercises.firstOrNull { it.id == exerciseId }?.syncId
                        ?: return@update this,
                priority = KeyExercisePriority.NORMAL,
            )
    copy(keyExercises = current.values.sortedWith(keyExerciseComparator), error = null)
  }

  fun setKeyExercisePriority(exerciseId: Long, priority: KeyExercisePriority) = update {
    copy(
        keyExercises =
            keyExercises.map { if (it.exerciseId == exerciseId) it.copy(priority = priority) else it }
                .sortedWith(keyExerciseComparator),
    )
  }

  fun removeKeyExercise(exerciseSyncId: String) = update {
    copy(keyExercises = keyExercises.filterNot { it.exerciseSyncId == exerciseSyncId })
  }

  fun setKeyExerciseSheet(visible: Boolean) = update { copy(showKeyExercises = visible) }

  fun save() {
    val state = _uiState.value
    val target = state.target ?: return
    val profile = state.toProfileOrNull(clock.nowMillis())
    if (profile == null) {
      _uiState.value = state.copy(error = "Проверьте дату и числовые значения")
      return
    }
    _uiState.value = state.copy(isSaving = true, error = null)
    viewModelScope.launch {
      val saveResult =
          if (profile.trainingGoal == TrainingGoal.STRENGTH)
              profileRepository.saveWithStrength(target, profile, state.keyExercises)
          else profileRepository.save(target, profile)
      when (saveResult) {
        ProfileSaveResult.Saved -> {
          _uiState.value = _uiState.value.copy(isSaving = false)
        }
        ProfileSaveResult.Invalid ->
            _uiState.value =
                _uiState.value.copy(isSaving = false, error = "Проверьте данные профиля")
        ProfileSaveResult.StaleTarget ->
            _uiState.value =
                ProfileEditorUiState(isLoading = false, error = "Профиль больше недоступен")
      }
    }
  }

  private fun update(block: ProfileEditorUiState.() -> ProfileEditorUiState) {
    if (_uiState.value.isLoading || _uiState.value.isSaving) return
    val next = _uiState.value.block()
    next.saveDraft(savedStateHandle)
    _uiState.value = next
  }
}

private fun BasicProfile.toUi(
    target: ProfileEditTarget,
    handle: SavedStateHandle,
    promptDisabled: Boolean,
): ProfileEditorUiState {
  val draft = profileDraftFrom(handle, target)
  return (draft
          ?: ProfileEditorUiState(
              isLoading = false,
              target = target,
              trainingGoal = trainingGoal,
              sex = sex,
              birthDate = birthDate.orEmpty(),
              experienceLevel = experienceLevel,
              plannedSessionsPerWeek = plannedSessionsPerWeek?.toString().orEmpty(),
              preferredSessionDurationMinutes =
                  preferredSessionDurationMinutes?.toString().orEmpty(),
              equipmentIds = equipmentIds,
              manualConstraints = manualConstraints.orEmpty(),
          ))
      .copy(promptDisabled = promptDisabled)
}

private fun profileDraftFrom(
    handle: SavedStateHandle,
    target: ProfileEditTarget,
): ProfileEditorUiState? {
  if (
      handle.get<String>("profile_draft_scope") != target.scope ||
          handle.get<String>("profile_draft_owner") != target.ownerId ||
          handle.get<Long>("profile_draft_epoch") != target.sessionEpoch
  )
      return null
  return ProfileEditorUiState(
      isLoading = false,
      target = target,
      trainingGoal = handle.get<String>("profile_draft_goal")?.let { TrainingGoal.valueOf(it) },
      sex = handle.get<String>("profile_draft_sex")?.let { ProfileSex.valueOf(it) },
      birthDate = handle.get<String>("profile_draft_birth").orEmpty(),
      experienceLevel =
          handle.get<String>("profile_draft_experience")?.let { ExperienceLevel.valueOf(it) },
      plannedSessionsPerWeek = handle.get<String>("profile_draft_sessions").orEmpty(),
      preferredSessionDurationMinutes = handle.get<String>("profile_draft_duration").orEmpty(),
      equipmentIds = handle.get<ArrayList<String>>("profile_draft_equipment").orEmpty().toSet(),
      manualConstraints = handle.get<String>("profile_draft_constraints").orEmpty(),
      keyExercises =
          handle.get<ArrayList<String>>("profile_draft_key_sync").orEmpty().mapIndexed { index, syncId ->
            KeyExerciseChoice(
                exerciseId = null,
                exerciseSyncId = syncId,
                priority =
                    handle.get<ArrayList<String>>("profile_draft_key_priority")
                        ?.getOrNull(index)
                        ?.let(KeyExercisePriority::valueOf) ?: KeyExercisePriority.NORMAL,
            )
          },
      showKeyExercises = handle.get<Boolean>("profile_draft_key_sheet") ?: false,
  )
}

private fun ProfileEditorUiState.saveDraft(handle: SavedStateHandle) {
  val target = target ?: return
  handle["profile_draft_scope"] = target.scope
  handle["profile_draft_owner"] = target.ownerId
  handle["profile_draft_epoch"] = target.sessionEpoch
  handle["profile_draft_goal"] = trainingGoal?.name
  handle["profile_draft_sex"] = sex?.name
  handle["profile_draft_birth"] = birthDate
  handle["profile_draft_experience"] = experienceLevel?.name
  handle["profile_draft_sessions"] = plannedSessionsPerWeek
  handle["profile_draft_duration"] = preferredSessionDurationMinutes
  handle["profile_draft_equipment"] = ArrayList(equipmentIds.sorted())
  handle["profile_draft_constraints"] = manualConstraints
  handle["profile_draft_key_sync"] = ArrayList(keyExercises.map { it.exerciseSyncId })
  handle["profile_draft_key_priority"] = ArrayList(keyExercises.map { it.priority.name })
  handle["profile_draft_key_sheet"] = showKeyExercises
}

private fun ProfileEditorUiState.toProfileOrNull(nowMillis: Long): BasicProfile? {
  val date = birthDate.trim().ifBlank { null }
  val todayUtc = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
  if (
      date != null &&
          runCatching { LocalDate.parse(date) }
              .getOrNull()
              ?.let { it >= LocalDate.of(1900, 1, 1) && it <= todayUtc } != true
  )
      return null
  val sessions = plannedSessionsPerWeek.trim().ifBlank { null }?.toIntOrNull()
  if (plannedSessionsPerWeek.isNotBlank() && sessions !in 1..7) return null
  val duration = preferredSessionDurationMinutes.trim().ifBlank { null }?.toIntOrNull()
  if (preferredSessionDurationMinutes.isNotBlank() && duration !in 10..240) return null
  val constraints = manualConstraints.trim().ifBlank { null }
  if (constraints != null && constraints.codePointCount(0, constraints.length) > 2000) return null
  return BasicProfile(
      trainingGoal,
      sex,
      date,
      experienceLevel,
      sessions,
      duration,
      equipmentIds,
      constraints,
  )
}

private val keyExerciseComparator =
    compareBy<KeyExerciseChoice>({ if (it.priority == KeyExercisePriority.HIGH) 0 else 1 }, { it.exerciseId })
