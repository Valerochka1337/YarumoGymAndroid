package com.valerochka1337.valerochkagym.ui.routineshare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.routineshare.RoutineShareDataSource
import com.valerochka1337.valerochkagym.data.routineshare.RoutineShareLink
import com.valerochka1337.valerochkagym.data.routineshare.RoutineSharePreview
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.routine.RoutineDetailExercise
import com.valerochka1337.valerochkagym.ui.routine.RoutineDetailRoutine
import com.valerochka1337.valerochkagym.ui.routine.toDetailRoutine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoutineShareOwnerUiState(
    val loading: Boolean = true,
    val routine: RoutineDetailRoutine? = null,
    val links: List<RoutineShareLink> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

data class RoutineSharePreviewUiState(
    val loading: Boolean = true,
    val preview: RoutineSharePreview? = null,
    val importing: Boolean = false,
    val importedRoutineId: Long? = null,
    val signInRequested: Boolean = false,
    val error: String? = null,
)

sealed interface RoutineShareEffect {
  data class OpenSystemShare(val url: String) : RoutineShareEffect

  data class CopyLink(val url: String) : RoutineShareEffect
}

@HiltViewModel
class RoutineShareViewModel
@Inject
constructor(
    private val savedState: SavedStateHandle,
    private val routineDao: RoutineDao,
    private val repository: RoutineShareDataSource,
    private val sessions: BackendSessionStore,
) : ViewModel() {
  private val routineId = savedState.get<Long>(GymRoutes.ROUTINE_ID_ARG)
  private val _ownerState = MutableStateFlow(RoutineShareOwnerUiState())
  val ownerState: StateFlow<RoutineShareOwnerUiState> = _ownerState.asStateFlow()

  private val _previewState = MutableStateFlow(RoutineSharePreviewUiState())
  val previewState: StateFlow<RoutineSharePreviewUiState> = _previewState.asStateFlow()

  private val _effects =
      kotlinx.coroutines.channels.Channel<RoutineShareEffect>(
          kotlinx.coroutines.channels.Channel.BUFFERED
      )
  val effects = _effects.receiveAsFlow()

  init {
    val token = savedState.get<String>(GymRoutes.ROUTINE_SHARE_TOKEN)
    if (token == null) loadOwner() else loadPreview(token)
  }

  fun loadOwner() {
    val id =
        routineId
            ?: run {
              _ownerState.value =
                  RoutineShareOwnerUiState(loading = false, error = "Программа не найдена")
              return
            }
    viewModelScope.launch {
      _ownerState.value = _ownerState.value.copy(loading = true, error = null)
      try {
        val routine = routineDao.getRoutineWithExercises(id)?.toDetailRoutine()
        if (routine == null || routine.origin == "STANDARD") {
          _ownerState.value =
              RoutineShareOwnerUiState(loading = false, error = "Этой программой нельзя поделиться")
          return@launch
        }
        val links = repository.list(routine.syncId)
        _ownerState.value =
            RoutineShareOwnerUiState(loading = false, routine = routine, links = links)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        _ownerState.value =
            _ownerState.value.copy(
                loading = false,
                error = error.message ?: "Не удалось загрузить ссылки",
            )
      }
    }
  }

  fun create() {
    val routine = _ownerState.value.routine ?: return
    if (_ownerState.value.busy) return
    viewModelScope.launch {
      _ownerState.value = _ownerState.value.copy(busy = true, error = null)
      try {
        val created =
            repository.create(
                routine.syncId,
                operation("create_operation"),
                savedState.get<Long>("create_expected_revision"),
                savedState.get<Long>("create_catalog_revision"),
                onRequestPrepared = { revision, catalogRevision ->
                  savedState["create_expected_revision"] = revision
                  savedState["create_catalog_revision"] = catalogRevision
                },
            )
        clearCreateOperation()
        _ownerState.value =
            _ownerState.value.copy(busy = false, links = repository.list(routine.syncId))
        _effects.send(RoutineShareEffect.OpenSystemShare(created.url))
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        // A stale context is a definitive precondition failure, so its operation could not have
        // created a link. Network failures remain ambiguous even when another active link appears
        // in the recovery list: replaying their exact idempotency tuple avoids a duplicate.
        if (error.invalidatesCreateContext()) clearCreateOperation()
        val recovered = runCatching { repository.list(routine.syncId) }.getOrNull()
        _ownerState.value =
            _ownerState.value.copy(
                busy = false,
                links = recovered ?: _ownerState.value.links,
                error = error.message ?: "Не удалось создать ссылку",
            )
      }
    }
  }

  fun copy(url: String) {
    viewModelScope.launch { _effects.send(RoutineShareEffect.CopyLink(url)) }
  }

  fun share(url: String) {
    viewModelScope.launch { _effects.send(RoutineShareEffect.OpenSystemShare(url)) }
  }

  fun revoke(shareId: String) {
    val routine = _ownerState.value.routine ?: return
    if (_ownerState.value.busy) return
    viewModelScope.launch {
      _ownerState.value = _ownerState.value.copy(busy = true, error = null)
      try {
        repository.revoke(shareId, operation("revoke_$shareId"))
        savedState["revoke_$shareId"] = UUID.randomUUID().toString()
        _ownerState.value =
            _ownerState.value.copy(busy = false, links = repository.list(routine.syncId))
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        _ownerState.value =
            _ownerState.value.copy(
                busy = false,
                error = error.message ?: "Не удалось отозвать ссылку",
            )
      }
    }
  }

  fun refreshPreview() = savedState.get<String>(GymRoutes.ROUTINE_SHARE_TOKEN)?.let(::loadPreview)

  private fun loadPreview(token: String) {
    if (_previewState.value.importing) return
    viewModelScope.launch {
      _previewState.value = _previewState.value.copy(loading = true, error = null)
      try {
        val preview = repository.preview(token)
        _previewState.update { current -> current.copy(loading = false, preview = preview) }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        _previewState.value =
            RoutineSharePreviewUiState(loading = false, error = "Ссылка недоступна или отозвана")
      }
    }
  }

  fun import() {
    val token = savedState.get<String>(GymRoutes.ROUTINE_SHARE_TOKEN) ?: return
    if (_previewState.value.importing) return
    if (sessions.snapshot() == null) {
      _previewState.value = _previewState.value.copy(signInRequested = true)
      return
    }
    viewModelScope.launch {
      _previewState.value =
          _previewState.value.copy(importing = true, error = null, signInRequested = false)
      try {
        val imported = repository.import(token, operation("import_operation"))
        val local =
            routineDao.getRoutineBySyncId(imported.routineId)
                ?: throw IllegalStateException("Импорт ещё не появился в приложении")
        savedState["import_operation"] = UUID.randomUUID().toString()
        _previewState.value =
            _previewState.value.copy(importing = false, importedRoutineId = local.id)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        _previewState.value =
            _previewState.value.copy(
                importing = false,
                error = error.message ?: "Не удалось сохранить программу",
            )
      }
    }
  }

  /**
   * AccountGate calls this after login; the saved token and import operation survive recreation.
   */
  fun resumeAfterSignIn() {
    if (_previewState.value.signInRequested && sessions.snapshot() != null) import()
  }

  private fun operation(key: String): String =
      savedState.get<String>(key) ?: UUID.randomUUID().toString().also { savedState[key] = it }

  private fun clearCreateOperation() {
    savedState["create_operation"] = UUID.randomUUID().toString()
    savedState["create_expected_revision"] = null
    savedState["create_catalog_revision"] = null
  }

  private fun Exception.invalidatesCreateContext(): Boolean =
      this is BackendException &&
          code in setOf("routine_share_stale", "catalog_stale", "revision_conflict")
}

internal fun RoutineSharePreview.toDetailRoutine(): RoutineDetailRoutine =
    RoutineDetailRoutine(
        id = 0L,
        syncId = "",
        origin = "SHARED",
        name = title,
        note = "",
        gymNames = emptyList(),
        exercises =
            exercises.mapIndexed { index, exercise ->
              RoutineDetailExercise(
                  id = index.toLong(),
                  name = exercise.name,
                  type = ExerciseType.entries.first { it.name == exercise.type },
                  restSeconds = exercise.restSeconds,
                  plannedSets =
                      exercise.sets.map { set ->
                        PlannedSet(
                            set.weightKg,
                            set.reps,
                            set.durationSec,
                            set.speedKmh,
                            set.inclinePct,
                        )
                      },
              )
            },
    )
