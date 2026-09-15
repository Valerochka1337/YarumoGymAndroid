package com.valerochka1337.valerochkagym.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.domain.PlannerDuration
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import com.valerochka1337.valerochkagym.ui.permissions.LivePermissionState
import com.valerochka1337.valerochkagym.ui.permissions.PermissionRecoveryController
import com.valerochka1337.valerochkagym.ui.permissions.PermissionRecoveryDecision
import com.valerochka1337.valerochkagym.ui.permissions.PermissionRecoveryPolicy
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Приблизительная длительность одного подхода без учёта отдыха, сек. */

/** Одна карточка программы в списке. [estimatedMinutes] — грубая оценка длительности. */
data class RoutineCardUi(
    val id: Long,
    val name: String,
    val exerciseCount: Int,
    val estimatedMinutes: Int,
    val gymNames: List<String> = emptyList(),
    val origin: String = "PERSONAL",
)

/**
 * Состояние вкладки «Тренировки». [routines] == null означает «ещё не загружено» (в отличие от
 * загруженного пустого списка), чтобы не мигало пустое состояние.
 */
data class WorkoutsUiState(
    val routines: List<RoutineCardUi>? = null,
    val selectedRoutineId: Long? = null,
) {
  val isEmpty: Boolean
    get() = routines?.isEmpty() == true
}

data class StartedWorkout(val actionToken: String, val workoutId: String)

/**
 * Бэкенд вкладки «Тренировки»: список программ с оценкой длительности, дублирование и удаление.
 * Оценка считается в памяти, т.к. plannedSets лежат в JSON. Планирование тренировок вынесено на
 * вкладку «Календарь».
 */
@HiltViewModel
class WorkoutsViewModel
@Inject
constructor(
    private val routineDao: RoutineDao,
    settingsRepository: SettingsRepository,
    private val activeWorkoutRepository: ActiveWorkoutRepository,
    private val routineUploadScheduler: RoutineUploadScheduler = NoOpRoutineUploadScheduler,
    private val gymRepository: GymRepository = NoOpGymRepository,
    private val permissionRecoveryController: PermissionRecoveryController? = null,
) : ViewModel() {

  private val selectedRoutineId = MutableStateFlow<Long?>(null)

  /**
   * Одиночный полёт старта: пока создание тренировки в процессе, повторные запросы (в т.ч. двойной
   * тап) игнорируются — гард репозитория «проверить-и-вставить» сам по себе гонку не закрывает
   * полностью.
   */
  private var startInFlight = false
  private val consumedPermissionActionTokens = mutableSetOf<String>()

  private val _startEvents = Channel<StartedWorkout>(Channel.BUFFERED)

  /** Событие «тренировка создана» — экран навигирует на активную тренировку. */
  val startEvents = _startEvents.receiveAsFlow()

  private val _startReadyEvents = Channel<String>(Channel.BUFFERED)
  val startReadyEvents = _startReadyEvents.receiveAsFlow()

  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  private val defaultRestSeconds = settingsRepository.settings.map { it.defaultRestSeconds }

  val uiState: StateFlow<WorkoutsUiState> =
      combine(
              routineDao.observeRoutinesFull(),
              defaultRestSeconds,
              selectedRoutineId,
          ) { routines, defaultRest, selected ->
            val cards = routines.map { it.toCardUi(defaultRest) }
            val validSelected = selected?.takeIf { id -> cards.any { it.id == id } }
            WorkoutsUiState(routines = cards, selectedRoutineId = validSelected)
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = WorkoutsUiState(),
          )

  /** Повторный тап по выбранной программе снимает выбор. */
  fun onRoutineSelected(id: Long) {
    selectedRoutineId.value = if (selectedRoutineId.value == id) null else id
  }

  /** Старт тренировки по программе. Событие [startEvents] шлётся только при успешном создании. */
  fun startFromRoutine(routineId: Long) = launchStart {
    try {
      sendStartedWorkout(activeWorkoutRepository.startFromRoutine(routineId))
    } catch (conflict: RoutineGymConflictException) {
      _messages.send(
          "Нельзя начать: недоступно во всех залах — ${conflict.exerciseNames.joinToString()}",
      )
    }
  }

  /** Старт пустой тренировки. */
  fun startEmpty() = launchStart { sendStartedWorkout(activeWorkoutRepository.startEmpty()) }

  /** Revalidates that the exact workout which requested permission is still the active Room row. */
  fun continueStartedWorkout(actionToken: String, workoutId: String) {
    if (!consumedPermissionActionTokens.add(actionToken)) return
    viewModelScope.launch {
      if (activeWorkoutRepository.observeActive().first()?.workout?.id == workoutId) {
        _startReadyEvents.send(workoutId)
      }
    }
  }

  private suspend fun sendStartedWorkout(workoutId: String) {
    _startEvents.send(
        StartedWorkout(actionToken = UUID.randomUUID().toString(), workoutId = workoutId)
    )
  }

  suspend fun decidePermissions(live: List<LivePermissionState>): PermissionRecoveryDecision =
      permissionRecoveryController?.decide(live)
          ?: PermissionRecoveryPolicy.decide(
              live.map { state ->
                com.valerochka1337.valerochkagym.ui.permissions.PermissionSnapshot(
                    state.permission,
                    state.granted,
                    requestedBefore = false,
                    state.shouldShowRationale,
                )
              }
          )

  suspend fun markPermissionRequestLaunched(permissions: Collection<String>) {
    permissionRecoveryController?.markRequestLaunched(permissions)
  }

  /**
   * Единый single-flight старта: пока [block] выполняется, повторные запросы (двойной тап)
   * игнорируются. Общий для [startFromRoutine] и [startEmpty].
   */
  private inline fun launchStart(crossinline block: suspend () -> Unit) {
    if (startInFlight) return
    startInFlight = true
    viewModelScope.launch {
      try {
        block()
      } finally {
        startInFlight = false
      }
    }
  }

  fun duplicate(id: Long) {
    viewModelScope.launch {
      if (gymRepository !== NoOpGymRepository) {
        val copy = gymRepository.duplicateRoutine(id)
        if (copy == null) {
          _messages.send("Не удалось создать копию программы")
          return@launch
        }
        routineUploadScheduler.schedule(copy.syncId)
        return@launch
      }
      val source = routineDao.getRoutineWithExercises(id) ?: return@launch
      val copy =
          RoutineEntity(
              name = "${source.routine.name} (копия)",
              note = source.routine.note,
          )
      val newId = routineDao.upsertRoutine(copy)
      val copiedExercises =
          source.exercises.mapIndexed { index, item ->
            RoutineExerciseEntity(
                routineId = newId,
                exerciseId = item.routineExercise.exerciseId,
                position = index,
                restSeconds = item.routineExercise.restSeconds,
                plannedSets = item.routineExercise.plannedSets,
            )
          }
      routineDao.replaceRoutineExercises(newId, copiedExercises)
      routineUploadScheduler.schedule(copy.syncId)
    }
  }

  fun delete(id: Long) {
    viewModelScope.launch {
      if (gymRepository !== NoOpGymRepository) {
        val deletion = gymRepository.deleteRoutine(id)
        if (deletion == null) {
          _messages.send("Не удалось удалить программу")
          return@launch
        }
        routineUploadScheduler.scheduleDeletion(deletion.syncId, deletion.updatedAt)
        return@launch
      }
      val routine = routineDao.getRoutineWithExercises(id)?.routine ?: return@launch
      val deletedAt = routine.withNextUpdatedAt().updatedAt
      routineDao.deleteRoutine(id)
      routineUploadScheduler.scheduleDeletion(routine.syncId, deletedAt)
    }
  }
}

/** Общая с AI-планом приблизительная оценка перечисленных подходов. */
private fun RoutineWithExercises.toCardUi(defaultRestSeconds: Int): RoutineCardUi {
  val totalSeconds =
      PlannerDuration.seconds(
          exercises.map { item ->
            PlannerDuration.Exercise(
                item.routineExercise.plannedSets.map { it.durationSec },
                item.routineExercise.restSeconds,
            )
          },
          defaultRestSeconds,
      )
  return RoutineCardUi(
      id = routine.id,
      name = routine.name,
      origin = routine.origin,
      exerciseCount = exercises.size,
      estimatedMinutes = PlannerDuration.minutes(totalSeconds).toInt(),
      gymNames = gyms.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER),
  )
}
