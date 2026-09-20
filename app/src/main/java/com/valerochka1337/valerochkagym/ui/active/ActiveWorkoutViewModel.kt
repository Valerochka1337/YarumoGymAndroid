package com.valerochka1337.valerochkagym.ui.active

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.CoachDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutUnavailableException
import com.valerochka1337.valerochkagym.domain.CompletedSetEditResult
import com.valerochka1337.valerochkagym.domain.CompletedSetNumbers
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHint
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHintRepository
import com.valerochka1337.valerochkagym.domain.HintEditTarget
import com.valerochka1337.valerochkagym.domain.NoteSaveResult
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import com.valerochka1337.valerochkagym.domain.SetEffort
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutSetMutator
import com.valerochka1337.valerochkagym.domain.canAddSet
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.ui.permissions.LivePermissionState
import com.valerochka1337.valerochkagym.ui.permissions.PermissionRecoveryController
import com.valerochka1337.valerochkagym.ui.permissions.PermissionRecoveryDecision
import com.valerochka1337.valerochkagym.ui.permissions.PermissionRecoveryPolicy
import com.valerochka1337.valerochkagym.ui.permissions.PermissionSnapshot
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Состояние экрана активной тренировки. [loading] отличает «ещё не загрузили из БД» от «активной
 * тренировки нет» ([workout] == null && !loading). elapsedSeconds тикает от startedAt каждую
 * секунду, пока экран подписан. [previousByExercise] — сводка «прошлый: …» по exerciseId (пустая
 * строка = прошлого нет).
 */
data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    val workout: WorkoutFull? = null,
    val previousByExercise: Map<Long, String> = emptyMap(),
    val completedSetEdit: CompletedSetEditDraft? = null,
    val noteEdit: WorkoutNoteEditDraft? = null,
    val personalHintEdit: ActivePersonalHintEditDraft? = null,
    val hintsByExercise: Map<Long, ExercisePersonalHint> = emptyMap(),
    val isFinishing: Boolean = false,
    val unreadCoachMessages: Int = 0,
)

/** A note draft is scoped to stable Room ids so a delayed result cannot affect another target. */
data class WorkoutNoteEditDraft(
    val workoutId: String,
    val setId: Long?,
    val token: Long,
    val text: String,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

data class ActivePersonalHintEditDraft(
    val target: HintEditTarget,
    val token: Long,
    val text: String,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

private data class ActiveWorkoutBaseState(
    val workout: WorkoutFull?,
    val previousByExercise: Map<Long, String>,
    val loaded: Boolean,
    val completedSetEdit: CompletedSetEditDraft?,
    val isFinishing: Boolean,
)

/** Immutable, saveable numeric draft for a completed set. Submission feedback is process-local. */
data class CompletedSetEditDraft(
    val setId: Long,
    val type: ExerciseType,
    val token: Long,
    val effort: SetEffort? = null,
    val weightKg: String = "",
    val reps: String = "",
    val durationSec: String = "",
    val speedKmh: String = "",
    val inclinePct: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

/** Навигационные события экрана активной тренировки. */
sealed interface ActiveWorkoutEvent {
  data class NavigateToSummary(val workoutId: String) : ActiveWorkoutEvent

  data object NavigateHome : ActiveWorkoutEvent

  data class ShowMessage(val message: String) : ActiveWorkoutEvent
}

/**
 * Бэкенд экрана активной тренировки. Старт тренировки происходит на вкладке «Тренировки» (см.
 * WorkoutsViewModel); сюда состояние приходит само через [ActiveWorkoutRepository.observeActive].
 * Шаговые правки значений и завершение/отмена делегируются в репозиторий, состояние перечитывается
 * реактивно.
 *
 * Правки подхода и закрытие подхода идут через [WorkoutSetMutator] и [WorkoutEditor]. ровно те же
 * операции доступны с кнопок уведомления в шторке, и писатель должен быть один на процесс (иначе
 * вернутся lost update'ы на быстрых тапах).
 */
@HiltViewModel
class ActiveWorkoutViewModel
constructor(
    private val repository: ActiveWorkoutRepository,
    private val previousSetsUseCase: PreviousSetsUseCase,
    private val setMutator: WorkoutSetMutator,
    private val completeSetFromUser: suspend (Long) -> Unit,
    private val restTimerEngine: RestTimerEngine,
    private val uploadScheduler: UploadScheduler,
    private val heartRateMonitor: HeartRateMonitor,
    private val savedStateHandle: SavedStateHandle,
    private val personalHintRepository: ExercisePersonalHintRepository,
    private val permissionRecoveryController: PermissionRecoveryController? = null,
    private val coachDao: CoachDao? = null,
    private val settings: com.valerochka1337.valerochkagym.data.settings.SettingsRepository? = null,
    private val updateCoachPhase: suspend (String, String) -> Boolean = { _, _ -> false },
) : ViewModel() {

  @Inject
  constructor(
      repository: ActiveWorkoutRepository,
      previousSetsUseCase: PreviousSetsUseCase,
      setMutator: WorkoutSetMutator,
      workoutEditor: WorkoutEditor,
      restTimerEngine: RestTimerEngine,
      uploadScheduler: UploadScheduler,
      heartRateMonitor: HeartRateMonitor,
      savedStateHandle: SavedStateHandle,
      personalHintRepository: ExercisePersonalHintRepository,
      permissionRecoveryController: PermissionRecoveryController? = null,
      coachDao: CoachDao,
      settings: com.valerochka1337.valerochkagym.data.settings.SettingsRepository,
      coach: com.valerochka1337.valerochkagym.service.CoachConversationService,
  ) : this(
      repository,
      previousSetsUseCase,
      setMutator,
      workoutEditor::completeSetFromUser,
      restTimerEngine,
      uploadScheduler,
      heartRateMonitor,
      savedStateHandle,
      personalHintRepository,
      permissionRecoveryController,
      coachDao,
      settings,
      coach::setPhase,
  )

  val liveCoachEnabled =
      (settings?.settings?.map { it.liveCoachEnabled } ?: flowOf(true)).stateIn(
          viewModelScope,
          SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
          true,
      )

  /** Состояние таймера отдыха (null = неактивен) — пилюля на экране подписана прямо на движок. */
  val restTimer: StateFlow<RestTimerState?> = restTimerEngine.state

  /** Live-канал не хранится в Room: он принадлежит только текущей тренировке. */
  val heartRateState: StateFlow<HeartRateConnectionState> = heartRateMonitor.state
  val heartRateReading: StateFlow<HeartRateReading?> = heartRateMonitor.reading

  private val loaded = MutableStateFlow(false)
  private val previousSummaries = MutableStateFlow<Map<Long, String>>(emptyMap())
  private val isFinishing = MutableStateFlow(false)
  private val finishInFlight = AtomicBoolean(false)
  private val consumedPermissionActionTokens = mutableSetOf<String>()
  private val loadingPrevious = mutableSetOf<Long>()
  private val completedSetEdit = MutableStateFlow(savedCompletedSetEdit())
  private val noteEdit = MutableStateFlow(savedNoteEdit())
  private val personalHintEdit = MutableStateFlow(savedPersonalHintEdit())
  private var restoredDraftNeedsValidation = completedSetEdit.value != null
  private var nextCompletedSetEditToken =
      savedStateHandle.get<Long>(COMPLETED_SET_EDIT_EPOCH) ?: completedSetEdit.value?.token ?: 0L
  private var nextNoteEditToken =
      savedStateHandle.get<Long>(NOTE_EDIT_EPOCH) ?: noteEdit.value?.token ?: 0L
  private var nextPersonalHintEditToken =
      savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_EPOCH) ?: personalHintEdit.value?.token ?: 0L

  private val activeWorkout: StateFlow<WorkoutFull?> =
      repository
          .observeActive()
          .onEach { workout ->
            loaded.value = true
            ensurePreviousLoaded(workout)
            validateRestoredCompletedSetEdit(workout)
            validateRestoredNoteEdit(workout)
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

  private val hintsByExercise: StateFlow<Map<Long, ExercisePersonalHint>> =
      activeWorkout
          .flatMapLatest { workout ->
            val ids = workout?.exercises?.map { it.exercise.id }?.distinct().orEmpty()
            if (ids.isEmpty()) {
              flowOf(emptyMap())
            } else {
              combine(ids.map { id -> personalHintRepository.observe(id).map { id to it } }) { rows
                ->
                rows.mapNotNull { (id, hint) -> hint?.let { id to it } }.toMap()
              }
            }
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
              emptyMap(),
          )

  private val unreadCoachMessages: StateFlow<Int> =
      activeWorkout
          .flatMapLatest { workout ->
            workout?.workout?.id?.let { id -> coachDao?.observeUnreadAssistantCount(id) }
                ?: flowOf(0)
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), 0)

  private val tickerFlow: Flow<Long> = flow {
    while (true) {
      emit(System.currentTimeMillis())
      delay(1000.milliseconds)
    }
  }

  private val baseUiState: StateFlow<ActiveWorkoutBaseState> =
      combine(
              activeWorkout,
              previousSummaries,
              loaded,
              completedSetEdit,
              isFinishing,
          ) { workout, previous, isLoaded, edit, finishing ->
            ActiveWorkoutBaseState(
                workout = workout,
                previousByExercise = previous,
                loaded = isLoaded,
                completedSetEdit = edit,
                isFinishing = finishing,
            )
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
              initialValue =
                  ActiveWorkoutBaseState(
                      workout = null,
                      previousByExercise = emptyMap(),
                      loaded = false,
                      completedSetEdit = null,
                      isFinishing = false,
                  ),
          )

  val uiState: StateFlow<ActiveWorkoutUiState> =
      combine(baseUiState, noteEdit, personalHintEdit, hintsByExercise, unreadCoachMessages) {
              base,
              note,
              hintEdit,
              hints,
              unread ->
            ActiveWorkoutUiState(
                loading = !base.loaded,
                workout = base.workout,
                previousByExercise = base.previousByExercise,
                completedSetEdit = base.completedSetEdit,
                noteEdit = note,
                personalHintEdit = hintEdit,
                hintsByExercise = hints,
                isFinishing = base.isFinishing,
                unreadCoachMessages = unread,
            )
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
              initialValue = ActiveWorkoutUiState(),
          )

  /**
   * Секунды с начала тренировки — отдельный поток, чтобы посекундный тик не перерисовывал весь
   * [uiState] (его собирает только шапка экрана).
   */
  val elapsedSeconds: StateFlow<Long> =
      combine(activeWorkout, tickerFlow) { workout, nowMillis ->
            workout?.let { ((nowMillis - it.workout.startedAt) / 1000).coerceAtLeast(0) } ?: 0L
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
              initialValue = 0L,
          )

  private val _events = Channel<ActiveWorkoutEvent>(Channel.BUFFERED)
  val events = _events.receiveAsFlow()

  // --- Шаговые изменения значений подхода (кнопки ± на карточке текущего подхода). ---

  /** Вес: обычный тап ±2.5, долгое нажатие ±0.5. Не уходит ниже нуля. */
  fun stepWeight(setId: Long, delta: Double) = setMutator.stepWeight(setId, delta)

  /** Повторы: ±1, не ниже нуля. */
  fun stepReps(setId: Long, delta: Int) = setMutator.stepReps(setId, delta)

  /** Длительность: ±15 сек, не ниже нуля. */
  fun stepDuration(setId: Long, delta: Int) = setMutator.stepDuration(setId, delta)

  /** Скорость: ±0.5, не ниже нуля. */
  fun stepSpeed(setId: Long, delta: Double) = setMutator.stepSpeed(setId, delta)

  /** Наклон: ±0.5, не ниже нуля. */
  fun stepIncline(setId: Long, delta: Double) = setMutator.stepIncline(setId, delta)

  // --- Клавиатурный ввод (NumberField): правит одно поле поверх свежего состояния подхода. ---

  fun setEffort(setId: Long, effort: SetEffort?) =
      setMutator.edit(setId) { set ->
        effort?.applyTo(set)
            ?: set.copy(actualRir = null, actualRirAtLeastFour = false, setType = "UNKNOWN")
      }

  fun setWeight(setId: Long, raw: String) = setMutator.setWeight(setId, raw)

  fun setReps(setId: Long, raw: String) = setMutator.setReps(setId, raw)

  fun setDuration(setId: Long, raw: String) = setMutator.setDuration(setId, raw)

  fun setSpeed(setId: Long, raw: String) = setMutator.setSpeed(setId, raw)

  fun setIncline(setId: Long, raw: String) = setMutator.setIncline(setId, raw)

  /** Отмечает подход выполненным и запускает отдых (та же операция, что кнопка в уведомлении). */
  fun coachPhase(phase: String) {
    val workoutId = uiState.value.workout?.workout?.id ?: return
    viewModelScope.launch {
      try {
        if (!updateCoachPhase(workoutId, phase))
            _events.send(
                ActiveWorkoutEvent.ShowMessage("Тренер недоступен без подключённого аккаунта")
            )
      } catch (_: Exception) {
        _events.send(ActiveWorkoutEvent.ShowMessage("Не удалось сохранить состояние тренера"))
      }
    }
  }

  fun completeSet(setId: Long) {
    viewModelScope.launch { completeSetFromUser(setId) }
  }

  /** Прибавить/убавить время текущего отдыха (кнопки ±15 на пилюле). */
  fun addRestSeconds(delta: Int) = restTimerEngine.addSeconds(delta)

  /** Пропустить отдых (тап по центру пилюли). */
  fun skipRest() = restTimerEngine.skip()

  /** Начать пользовательский сценарий подключения BLE Heart Rate Service. */
  fun scanHeartRate() = heartRateMonitor.scan()

  /** Starts BLE only while the permission request still belongs to the current Room workout. */
  fun scanHeartRateForWorkout(workoutId: String) {
    if (activeWorkout.value?.workout?.id == workoutId) heartRateMonitor.scan()
  }

  fun scanHeartRateForPermissionAction(actionToken: String, workoutId: String) {
    if (consumedPermissionActionTokens.add(actionToken)) scanHeartRateForWorkout(workoutId)
  }

  suspend fun decidePermissions(live: List<LivePermissionState>): PermissionRecoveryDecision =
      permissionRecoveryController?.decide(live)
          ?: PermissionRecoveryPolicy.decide(
              live.map { state ->
                PermissionSnapshot(
                    permission = state.permission,
                    granted = state.granted,
                    requestedBefore = false,
                    shouldShowRationale = state.shouldShowRationale,
                )
              }
          )

  suspend fun markPermissionRequestLaunched(permissions: Collection<String>) {
    permissionRecoveryController?.markRequestLaunched(permissions)
  }

  fun connectHeartRate(device: HeartRateDevice) = heartRateMonitor.connect(device)

  fun cancelHeartRateSelection() = heartRateMonitor.stop()

  fun uncompleteSet(setId: Long) {
    viewModelScope.launch { repository.toggleSetCompleted(setId, false) }
  }

  /**
   * Opens the type-specific editor only for a completed set still present in the loaded snapshot.
   */
  fun openCompletedSetEdit(setId: Long, type: ExerciseType) {
    val matching =
        activeWorkout.value
            ?.exercises
            ?.firstOrNull { exercise ->
              exercise.exercise.type == type &&
                  exercise.sets.any { it.id == setId && it.isCompleted }
            }
            ?.sets
            ?.firstOrNull { it.id == setId && it.isCompleted }
    if (matching == null) {
      _events.trySend(ActiveWorkoutEvent.ShowMessage(COMPLETED_SET_UNAVAILABLE_MESSAGE))
      return
    }
    val token = ++nextCompletedSetEditToken
    savedStateHandle[COMPLETED_SET_EDIT_EPOCH] = token
    updateCompletedSetEdit(
        CompletedSetEditDraft(
            setId = matching.id,
            type = type,
            token = token,
            effort = SetEffort.selectedFor(matching),
            weightKg = matching.weightKg.toDraftText(),
            reps = matching.reps.toDraftText(),
            durationSec = matching.durationSec.toDraftText(),
            speedKmh = matching.speedKmh.toDraftText(),
            inclinePct = matching.inclinePct.toDraftText(),
        ),
    )
  }

  fun updateCompletedSetWeight(raw: String) = updateCompletedSetEdit {
    it.copy(weightKg = raw, error = null)
  }

  fun updateCompletedSetReps(raw: String) = updateCompletedSetEdit {
    it.copy(reps = raw, error = null)
  }

  fun updateCompletedSetDuration(raw: String) = updateCompletedSetEdit {
    it.copy(durationSec = raw, error = null)
  }

  fun updateCompletedSetSpeed(raw: String) = updateCompletedSetEdit {
    it.copy(speedKmh = raw, error = null)
  }

  fun updateCompletedSetIncline(raw: String) = updateCompletedSetEdit {
    it.copy(inclinePct = raw, error = null)
  }

  fun updateCompletedSetEffort(effort: SetEffort) = updateCompletedSetEdit {
    it.copy(effort = effort, error = null)
  }

  fun cancelCompletedSetEdit() {
    clearCompletedSetEdit()
  }

  fun openWorkoutNote() {
    val workout = activeWorkout.value ?: return
    openNoteEdit(workout.workout.id, null, workout.workout.note)
  }

  fun openSetNote(setId: Long) {
    val workout = activeWorkout.value ?: return
    val set =
        workout.exercises
            .asSequence()
            .flatMap { it.sets.asSequence() }
            .firstOrNull { it.id == setId } ?: return
    openNoteEdit(workout.workout.id, set.id, set.note)
  }

  fun updateNote(text: String) = updateNoteEdit { it.copy(text = text, error = null) }

  fun cancelNoteEdit() = clearNoteEdit()

  fun saveNoteEdit() {
    val draft = noteEdit.value ?: return
    if (draft.isSubmitting) return
    val text = draft.text.trim()
    if (text.codePointCount(0, text.length) > MAX_NOTE_CODE_POINTS) {
      updateNoteEdit { it.copy(error = NOTE_TOO_LONG_MESSAGE) }
      return
    }
    updateNoteEdit { it.copy(text = text, isSubmitting = true, error = null) }
    viewModelScope.launch {
      val result =
          if (draft.setId == null) repository.saveWorkoutNote(draft.workoutId, text)
          else repository.saveSetNote(draft.workoutId, draft.setId, text)
      if (noteEdit.value?.token != draft.token) return@launch
      when (result) {
        NoteSaveResult.Saved -> clearNoteEdit()
        NoteSaveResult.MissingOrInactive ->
            updateNoteEdit { it.copy(isSubmitting = false, error = NOTE_UNAVAILABLE_MESSAGE) }
        NoteSaveResult.TooLong ->
            updateNoteEdit { it.copy(isSubmitting = false, error = NOTE_TOO_LONG_MESSAGE) }
      }
    }
  }

  fun openPersonalHintEdit(exerciseId: Long) {
    if (activeWorkout.value?.exercises?.any { it.exercise.id == exerciseId } != true) return
    val token = ++nextPersonalHintEditToken
    viewModelScope.launch {
      val target =
          personalHintRepository.editTarget(exerciseId)
              ?: run {
                clearPersonalHintEdit()
                return@launch
              }
      if (token != nextPersonalHintEditToken) return@launch
      if (activeWorkout.value?.exercises?.any { it.exercise.id == exerciseId } != true)
          return@launch
      savedStateHandle[PERSONAL_HINT_EDIT_EPOCH] = token
      updatePersonalHintEdit(
          ActivePersonalHintEditDraft(
              target = target,
              token = token,
              text = hintsByExercise.value[exerciseId]?.text.orEmpty(),
          ),
      )
    }
  }

  fun updatePersonalHintEdit(text: String) = updatePersonalHintEdit {
    it.copy(text = text, error = null)
  }

  fun cancelPersonalHintEdit() = clearPersonalHintEdit()

  fun savePersonalHintEdit() {
    val draft = personalHintEdit.value ?: return
    if (draft.isSubmitting) return
    val text = draft.text.trim()
    if (text.codePointCount(0, text.length) > MAX_NOTE_CODE_POINTS) {
      updatePersonalHintEdit { it.copy(error = HINT_TOO_LONG_MESSAGE) }
      return
    }
    updatePersonalHintEdit { it.copy(text = text, isSubmitting = true, error = null) }
    viewModelScope.launch {
      when (personalHintRepository.save(draft.target, text)) {
        NoteSaveResult.Saved,
        NoteSaveResult.MissingOrInactive -> {
          if (personalHintEdit.value?.token == draft.token) clearPersonalHintEdit()
        }
        NoteSaveResult.TooLong -> {
          if (personalHintEdit.value?.token == draft.token) {
            updatePersonalHintEdit { it.copy(isSubmitting = false, error = HINT_TOO_LONG_MESSAGE) }
          }
        }
      }
    }
  }

  fun unpinPersonalHint(exerciseId: Long) {
    if (activeWorkout.value?.exercises?.any { it.exercise.id == exerciseId } != true) return
    val token = ++nextPersonalHintEditToken
    clearPersonalHintEdit()
    viewModelScope.launch {
      val target =
          personalHintRepository.editTarget(exerciseId)
              ?: run {
                clearPersonalHintEdit()
                return@launch
              }
      if (token != nextPersonalHintEditToken) return@launch
      if (activeWorkout.value?.exercises?.any { it.exercise.id == exerciseId } != true)
          return@launch
      val result = personalHintRepository.unpin(target)
      if (result == NoteSaveResult.MissingOrInactive) clearPersonalHintEdit()
    }
  }

  fun saveCompletedSetEdit() {
    val draft = completedSetEdit.value ?: return
    if (draft.isSubmitting) return
    if (draft.type == ExerciseType.STRENGTH && draft.effort == null) return
    val values = draft.toNumbersOrNull()
    if (values == null) {
      updateCompletedSetEdit { it.copy(error = "Введите корректные числа") }
      return
    }
    updateCompletedSetEdit { it.copy(isSubmitting = true, error = null) }
    viewModelScope.launch {
      try {
        when (
            setMutator.editCompletedNumbers(
                draft.setId,
                draft.type,
                values,
                effort = draft.effort,
            )
        ) {
          CompletedSetEditResult.Saved -> {
            if (completedSetEdit.value?.token == draft.token) clearCompletedSetEdit()
          }

          CompletedSetEditResult.MissingOrInactive -> {
            if (completedSetEdit.value?.token == draft.token) {
              updateCompletedSetEdit {
                it.copy(isSubmitting = false, error = COMPLETED_SET_UNAVAILABLE_MESSAGE)
              }
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      }
    }
  }

  fun addSet(workoutExerciseId: Long) {
    val workout = activeWorkout.value ?: return
    if (!workout.canAddSet(workoutExerciseId)) return
    viewModelScope.launch { repository.addSet(workoutExerciseId) }
  }

  fun deleteSet(setId: Long) {
    viewModelScope.launch { repository.deleteSet(setId) }
  }

  /** Добавляет упражнение по id (после выбора в библиотеке-пикере). */
  fun addExerciseById(exerciseId: Long) {
    val workoutId = activeWorkout.value?.workout?.id ?: return
    viewModelScope.launch {
      try {
        repository.addExercise(workoutId, exerciseId)
      } catch (conflict: RoutineGymConflictException) {
        _events.send(
            ActiveWorkoutEvent.ShowMessage(
                "Упражнение недоступно во всех выбранных залах: " +
                    conflict.exerciseNames.joinToString(),
            ),
        )
      } catch (_: ActiveWorkoutUnavailableException) {
        _events.send(ActiveWorkoutEvent.ShowMessage("Тренировка уже завершена"))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _events.send(ActiveWorkoutEvent.ShowMessage("Не удалось добавить упражнение"))
      }
    }
  }

  fun deleteExercise(workoutExerciseId: Long) {
    viewModelScope.launch { repository.deleteExercise(workoutExerciseId) }
  }

  /** Сохраняет итоговый порядок упражнений после отпускания drag-handle. */
  fun reorderExercises(orderedWorkoutExerciseIds: List<Long>) {
    val workoutId = activeWorkout.value?.workout?.id ?: return
    viewModelScope.launch { repository.reorderExercises(workoutId, orderedWorkoutExerciseIds) }
  }

  fun finish() {
    val workoutId = activeWorkout.value?.workout?.id ?: return
    if (!finishInFlight.compareAndSet(false, true)) return
    isFinishing.value = true
    viewModelScope.launch {
      try {
        repository.finish(workoutId)
      } catch (cancelled: CancellationException) {
        finishInFlight.set(false)
        isFinishing.value = false
        throw cancelled
      } catch (_: Exception) {
        finishInFlight.set(false)
        isFinishing.value = false
        _events.send(ActiveWorkoutEvent.ShowMessage("Не удалось завершить тренировку"))
        return@launch
      }
      try {
        uploadScheduler.schedule(workoutId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _events.send(
            ActiveWorkoutEvent.ShowMessage(
                "Тренировка завершена, не удалось поставить выгрузку в очередь",
            ),
        )
      }
      _events.send(ActiveWorkoutEvent.NavigateToSummary(workoutId))
    }
  }

  fun discard() {
    val workoutId = activeWorkout.value?.workout?.id ?: return
    viewModelScope.launch {
      repository.discard(workoutId)
      _events.send(ActiveWorkoutEvent.NavigateHome)
    }
  }

  private fun ensurePreviousLoaded(workout: WorkoutFull?) {
    val exercises = workout?.exercises ?: return
    for (exercise in exercises) {
      val exerciseId = exercise.exercise.id
      if (previousSummaries.value.containsKey(exerciseId) || exerciseId in loadingPrevious) {
        continue
      }
      loadingPrevious += exerciseId
      viewModelScope.launch {
        val sets = previousSetsUseCase(exerciseId)
        val summary = previousSetsUseCase.formatSummary(sets, exercise.exercise.type)
        previousSummaries.update { it + (exerciseId to summary) }
        loadingPrevious -= exerciseId
      }
    }
  }

  private suspend fun validateRestoredCompletedSetEdit(workout: WorkoutFull?) {
    if (!restoredDraftNeedsValidation) return
    restoredDraftNeedsValidation = false
    val draft = completedSetEdit.value ?: return
    val exists =
        workout?.exercises?.any { exercise ->
          exercise.exercise.type == draft.type &&
              exercise.sets.any { it.id == draft.setId && it.isCompleted }
        } == true
    if (!exists) {
      clearCompletedSetEdit()
      _events.send(ActiveWorkoutEvent.ShowMessage(COMPLETED_SET_UNAVAILABLE_MESSAGE))
    }
  }

  private fun openNoteEdit(workoutId: String, setId: Long?, text: String) {
    val token = ++nextNoteEditToken
    savedStateHandle[NOTE_EDIT_EPOCH] = token
    updateNoteEdit(
        WorkoutNoteEditDraft(workoutId = workoutId, setId = setId, token = token, text = text)
    )
  }

  private fun validateRestoredNoteEdit(workout: WorkoutFull?) {
    val draft = noteEdit.value ?: return
    val stillExists =
        workout?.workout?.id == draft.workoutId &&
            (draft.setId == null ||
                workout.exercises.any { exercise -> exercise.sets.any { it.id == draft.setId } })
    if (!stillExists) clearNoteEdit()
  }

  private fun updateNoteEdit(transform: (WorkoutNoteEditDraft) -> WorkoutNoteEditDraft) {
    val current = noteEdit.value ?: return
    updateNoteEdit(transform(current))
  }

  private fun updateNoteEdit(draft: WorkoutNoteEditDraft) {
    noteEdit.value = draft
    savedStateHandle[NOTE_EDIT_WORKOUT_ID] = draft.workoutId
    savedStateHandle[NOTE_EDIT_SET_ID] = draft.setId
    savedStateHandle[NOTE_EDIT_TOKEN] = draft.token
    savedStateHandle[NOTE_EDIT_TEXT] = draft.text
  }

  private fun clearNoteEdit() {
    noteEdit.value = null
    listOf(NOTE_EDIT_WORKOUT_ID, NOTE_EDIT_SET_ID, NOTE_EDIT_TOKEN, NOTE_EDIT_TEXT).forEach { key ->
      savedStateHandle.remove<Any?>(key)
    }
  }

  private fun savedNoteEdit(): WorkoutNoteEditDraft? {
    val workoutId = savedStateHandle.get<String>(NOTE_EDIT_WORKOUT_ID) ?: return null
    val token = savedStateHandle.get<Long>(NOTE_EDIT_TOKEN) ?: return null
    return WorkoutNoteEditDraft(
        workoutId = workoutId,
        setId = savedStateHandle.get(NOTE_EDIT_SET_ID),
        token = token,
        text = savedStateHandle[NOTE_EDIT_TEXT] ?: "",
    )
  }

  private fun updatePersonalHintEdit(
      transform: (ActivePersonalHintEditDraft) -> ActivePersonalHintEditDraft,
  ) {
    val current = personalHintEdit.value ?: return
    updatePersonalHintEdit(transform(current))
  }

  private fun updatePersonalHintEdit(draft: ActivePersonalHintEditDraft) {
    personalHintEdit.value = draft
    savedStateHandle[PERSONAL_HINT_EDIT_EXERCISE_ID] = draft.target.exerciseId
    savedStateHandle[PERSONAL_HINT_EDIT_SYNC_ID] = draft.target.exerciseSyncId
    savedStateHandle[PERSONAL_HINT_EDIT_OWNER_SCOPE] = draft.target.ownerScope
    savedStateHandle[PERSONAL_HINT_EDIT_SESSION_EPOCH] = draft.target.sessionEpoch
    savedStateHandle[PERSONAL_HINT_EDIT_TOKEN] = draft.token
    savedStateHandle[PERSONAL_HINT_EDIT_TEXT] = draft.text
  }

  private fun clearPersonalHintEdit() {
    personalHintEdit.value = null
    listOf(
            PERSONAL_HINT_EDIT_EXERCISE_ID,
            PERSONAL_HINT_EDIT_SYNC_ID,
            PERSONAL_HINT_EDIT_OWNER_SCOPE,
            PERSONAL_HINT_EDIT_SESSION_EPOCH,
            PERSONAL_HINT_EDIT_TOKEN,
            PERSONAL_HINT_EDIT_TEXT,
        )
        .forEach { key -> savedStateHandle.remove<Any?>(key) }
  }

  private fun savedPersonalHintEdit(): ActivePersonalHintEditDraft? {
    val exerciseId = savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_EXERCISE_ID) ?: return null
    val syncId = savedStateHandle.get<String>(PERSONAL_HINT_EDIT_SYNC_ID) ?: return null
    val epoch = savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_SESSION_EPOCH) ?: return null
    val token = savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_TOKEN) ?: return null
    return ActivePersonalHintEditDraft(
        target =
            HintEditTarget(
                exerciseId = exerciseId,
                exerciseSyncId = syncId,
                ownerScope = savedStateHandle.get(PERSONAL_HINT_EDIT_OWNER_SCOPE),
                sessionEpoch = epoch,
            ),
        token = token,
        text = savedStateHandle[PERSONAL_HINT_EDIT_TEXT] ?: "",
    )
  }

  private fun updateCompletedSetEdit(transform: (CompletedSetEditDraft) -> CompletedSetEditDraft) {
    val current = completedSetEdit.value ?: return
    updateCompletedSetEdit(transform(current))
  }

  private fun updateCompletedSetEdit(draft: CompletedSetEditDraft) {
    completedSetEdit.value = draft
    savedStateHandle[COMPLETED_SET_EDIT_SET_ID] = draft.setId
    savedStateHandle[COMPLETED_SET_EDIT_TYPE] = draft.type.name
    savedStateHandle[COMPLETED_SET_EDIT_TOKEN] = draft.token
    savedStateHandle[COMPLETED_SET_EDIT_EFFORT] = draft.effort?.name
    savedStateHandle[COMPLETED_SET_EDIT_WEIGHT] = draft.weightKg
    savedStateHandle[COMPLETED_SET_EDIT_REPS] = draft.reps
    savedStateHandle[COMPLETED_SET_EDIT_DURATION] = draft.durationSec
    savedStateHandle[COMPLETED_SET_EDIT_SPEED] = draft.speedKmh
    savedStateHandle[COMPLETED_SET_EDIT_INCLINE] = draft.inclinePct
  }

  private fun clearCompletedSetEdit() {
    completedSetEdit.value = null
    listOf(
            COMPLETED_SET_EDIT_SET_ID,
            COMPLETED_SET_EDIT_TYPE,
            COMPLETED_SET_EDIT_TOKEN,
            COMPLETED_SET_EDIT_EFFORT,
            COMPLETED_SET_EDIT_WEIGHT,
            COMPLETED_SET_EDIT_REPS,
            COMPLETED_SET_EDIT_DURATION,
            COMPLETED_SET_EDIT_SPEED,
            COMPLETED_SET_EDIT_INCLINE,
        )
        .forEach { key -> savedStateHandle.remove<Any?>(key) }
  }

  private fun savedCompletedSetEdit(): CompletedSetEditDraft? {
    val setId = savedStateHandle.get<Long>(COMPLETED_SET_EDIT_SET_ID) ?: return null
    val type =
        savedStateHandle.get<String>(COMPLETED_SET_EDIT_TYPE)?.let {
          runCatching { ExerciseType.valueOf(it) }.getOrNull()
        } ?: return null
    val token = savedStateHandle.get<Long>(COMPLETED_SET_EDIT_TOKEN) ?: return null
    return CompletedSetEditDraft(
        setId = setId,
        type = type,
        token = token,
        effort =
            savedStateHandle.get<String>(COMPLETED_SET_EDIT_EFFORT)?.let {
              runCatching { SetEffort.valueOf(it) }.getOrNull()
            },
        weightKg = savedStateHandle[COMPLETED_SET_EDIT_WEIGHT] ?: "",
        reps = savedStateHandle[COMPLETED_SET_EDIT_REPS] ?: "",
        durationSec = savedStateHandle[COMPLETED_SET_EDIT_DURATION] ?: "",
        speedKmh = savedStateHandle[COMPLETED_SET_EDIT_SPEED] ?: "",
        inclinePct = savedStateHandle[COMPLETED_SET_EDIT_INCLINE] ?: "",
    )
  }
}

private fun CompletedSetEditDraft.toNumbersOrNull(): CompletedSetNumbers? {
  fun String.toOptionalDouble() = if (isBlank()) null else toFiniteDoubleOrNull()
  fun String.toOptionalInt() = if (isBlank()) null else toIntOrNull()
  return when (type) {
    ExerciseType.STRENGTH -> {
      val weight = weightKg.toOptionalDouble()
      val repetitions = reps.toOptionalInt()
      if ((weightKg.isNotBlank() && weight == null) || (reps.isNotBlank() && repetitions == null)) {
        null
      } else {
        CompletedSetNumbers(weightKg = weight, reps = repetitions)
      }
    }

    ExerciseType.TIMED -> {
      val duration = durationSec.toOptionalInt()
      if (durationSec.isNotBlank() && duration == null) null
      else CompletedSetNumbers(durationSec = duration)
    }

    ExerciseType.CARDIO -> {
      val duration = durationSec.toOptionalInt()
      val speed = speedKmh.toOptionalDouble()
      val incline = inclinePct.toOptionalDouble()
      if (
          (durationSec.isNotBlank() && duration == null) ||
              (speedKmh.isNotBlank() && speed == null) ||
              (inclinePct.isNotBlank() && incline == null)
      ) {
        null
      } else {
        CompletedSetNumbers(durationSec = duration, speedKmh = speed, inclinePct = incline)
      }
    }
  }
}

private fun Double?.toDraftText(): String = this?.toString().orEmpty()

private fun Int?.toDraftText(): String = this?.toString().orEmpty()

internal fun String.toFiniteDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun String.isValidOptionalFiniteDecimal(): Boolean =
    isBlank() || toFiniteDoubleOrNull() != null

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
private const val COMPLETED_SET_UNAVAILABLE_MESSAGE = "Подход уже недоступен для правки"
private const val COMPLETED_SET_EDIT_SET_ID = "completed_set_edit_set_id"
private const val COMPLETED_SET_EDIT_TYPE = "completed_set_edit_type"
private const val COMPLETED_SET_EDIT_TOKEN = "completed_set_edit_token"
private const val COMPLETED_SET_EDIT_EFFORT = "completed_set_edit_effort"
private const val COMPLETED_SET_EDIT_WEIGHT = "completed_set_edit_weight"
private const val COMPLETED_SET_EDIT_REPS = "completed_set_edit_reps"
private const val COMPLETED_SET_EDIT_DURATION = "completed_set_edit_duration"
private const val COMPLETED_SET_EDIT_SPEED = "completed_set_edit_speed"
private const val COMPLETED_SET_EDIT_INCLINE = "completed_set_edit_incline"
private const val COMPLETED_SET_EDIT_EPOCH = "completed_set_edit_epoch"
private const val NOTE_EDIT_WORKOUT_ID = "note_edit_workout_id"
private const val NOTE_EDIT_SET_ID = "note_edit_set_id"
private const val NOTE_EDIT_TOKEN = "note_edit_token"
private const val NOTE_EDIT_TEXT = "note_edit_text"
private const val NOTE_EDIT_EPOCH = "note_edit_epoch"
private const val MAX_NOTE_CODE_POINTS = 2_000
private const val NOTE_TOO_LONG_MESSAGE = "Заметка не длиннее 2000 символов"
private const val NOTE_UNAVAILABLE_MESSAGE = "Тренировка уже недоступна для заметки"
private const val PERSONAL_HINT_EDIT_EXERCISE_ID = "active_personal_hint_edit_exercise_id"
private const val PERSONAL_HINT_EDIT_SYNC_ID = "active_personal_hint_edit_sync_id"
private const val PERSONAL_HINT_EDIT_OWNER_SCOPE = "active_personal_hint_edit_owner_scope"
private const val PERSONAL_HINT_EDIT_SESSION_EPOCH = "active_personal_hint_edit_session_epoch"
private const val PERSONAL_HINT_EDIT_TOKEN = "active_personal_hint_edit_token"
private const val PERSONAL_HINT_EDIT_TEXT = "active_personal_hint_edit_text"
private const val PERSONAL_HINT_EDIT_EPOCH = "active_personal_hint_edit_epoch"
private const val HINT_TOO_LONG_MESSAGE = "Подсказка не длиннее 2000 символов"
