package com.valerochka1337.valerochkagym.ui.active

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.domain.SetEffort
import com.valerochka1337.valerochkagym.domain.canAddSet
import com.valerochka1337.valerochkagym.domain.currentFocus
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.ui.common.formatRestClock
import com.valerochka1337.valerochkagym.ui.components.DragHandle
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.rememberGymReorderableLazyListState
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.permissions.AndroidPermissionPlatform
import com.valerochka1337.valerochkagym.ui.permissions.rememberPermissionRecoveryHost
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import kotlinx.coroutines.flow.StateFlow
import sh.calvin.reorderable.ReorderableItem

/** Крупный шаг веса (обычный тап), кг. */
private const val WEIGHT_STEP = 2.5

/** Точный шаг веса (долгое нажатие), кг. */
private const val WEIGHT_FINE_STEP = 0.5

private const val SPEED_STEP = 0.5
private const val INCLINE_STEP = 0.5
private const val DURATION_STEP = 15
private const val REPS_STEP = 1

/** Шаг правки таймера отдыха на пилюле, сек. */
private const val REST_TIMER_STEP = 15
private const val BLE_PENDING_KIND = "ble"

/**
 * Экран активной тренировки («вариант B» — фокус на текущем подходе). Тренировка уже создана (старт
 * на вкладке «Тренировки»); состояние приходит реактивно из [ActiveWorkoutViewModel]. [onFinished]
 * ведёт на итоги, [onDiscarded] — на главную, [onAddExercise] открывает библиотеку.
 */
@Composable
fun ActiveWorkoutScreen(
    onFinished: (workoutId: String) -> Unit,
    onDiscarded: () -> Unit,
    onNavigateBack: () -> Unit,
    onAddExercise: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    onOpenCoach: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ActiveWorkoutViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val liveCoachEnabled by viewModel.liveCoachEnabled.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val permissionPlatform = remember(context) { AndroidPermissionPlatform(context) }
  val permissionHost =
      rememberPermissionRecoveryHost(
          kind = BLE_PENDING_KIND,
          permissions =
              listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
          platform = permissionPlatform,
          decide = viewModel::decidePermissions,
          markRequestLaunched = viewModel::markPermissionRequestLaunched,
          onAction = { token, workoutId, _ ->
            viewModel.scanHeartRateForPermissionAction(token, workoutId)
          },
      )

  fun startHeartRateSearch() {
    val workoutId = state.workout?.workout?.id ?: return
    permissionHost.begin(workoutId)
  }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        is ActiveWorkoutEvent.NavigateToSummary -> onFinished(event.workoutId)
        ActiveWorkoutEvent.NavigateHome -> onDiscarded()
        is ActiveWorkoutEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
      }
    }
  }

  KeepScreenOn()

  val setActions =
      remember(viewModel) {
        SetActions(
            stepWeight = viewModel::stepWeight,
            stepReps = viewModel::stepReps,
            stepDuration = viewModel::stepDuration,
            stepSpeed = viewModel::stepSpeed,
            stepIncline = viewModel::stepIncline,
            setWeight = viewModel::setWeight,
            setEffort = viewModel::setEffort,
            setReps = viewModel::setReps,
            setDuration = viewModel::setDuration,
            setSpeed = viewModel::setSpeed,
            setIncline = viewModel::setIncline,
            complete = viewModel::completeSet,
            editCompleted = viewModel::openCompletedSetEdit,
            updateCompletedWeight = viewModel::updateCompletedSetWeight,
            updateCompletedReps = viewModel::updateCompletedSetReps,
            updateCompletedDuration = viewModel::updateCompletedSetDuration,
            updateCompletedSpeed = viewModel::updateCompletedSetSpeed,
            updateCompletedIncline = viewModel::updateCompletedSetIncline,
            updateCompletedEffort = viewModel::updateCompletedSetEffort,
            saveCompletedEdit = viewModel::saveCompletedSetEdit,
            cancelCompletedEdit = viewModel::cancelCompletedSetEdit,
            editNote = viewModel::openSetNote,
            updateNote = viewModel::updateNote,
            saveNoteEdit = viewModel::saveNoteEdit,
            cancelNoteEdit = viewModel::cancelNoteEdit,
            editPersonalHint = viewModel::openPersonalHintEdit,
            unpinPersonalHint = viewModel::unpinPersonalHint,
            updatePersonalHint = viewModel::updatePersonalHintEdit,
            savePersonalHint = viewModel::savePersonalHintEdit,
            cancelPersonalHint = viewModel::cancelPersonalHintEdit,
            uncomplete = viewModel::uncompleteSet,
            addSet = viewModel::addSet,
            deleteSet = viewModel::deleteSet,
        )
      }

  GlowBackground(modifier = modifier) {
    Box(Modifier.fillMaxSize()) {
      val workout = state.workout
      when {
        workout != null ->
            FadeInContent {
              ActiveWorkoutContent(
                  state = state,
                  elapsedSeconds = viewModel.elapsedSeconds,
                  restTimer = viewModel.restTimer,
                  heartRateState = viewModel.heartRateState,
                  heartRateReading = viewModel.heartRateReading,
                  setActions = setActions,
                  onDeleteExercise = viewModel::deleteExercise,
                  onReorderExercises = viewModel::reorderExercises,
                  onAddExercise = onAddExercise,
                  onExerciseClick = onExerciseClick,
                  onOpenCoach = onOpenCoach,
                  unreadCoachMessages = state.unreadCoachMessages,
                  liveCoachEnabled = liveCoachEnabled,
                  onFinish = viewModel::finish,
                  onDiscard = viewModel::discard,
                  onAddRestSeconds = viewModel::addRestSeconds,
                  onSkipRest = viewModel::skipRest,
                  onCoachPhase = viewModel::coachPhase,
                  onScanHeartRate = ::startHeartRateSearch,
                  onConnectHeartRate = viewModel::connectHeartRate,
                  onCancelHeartRateSelection = viewModel::cancelHeartRateSelection,
                  onEditWorkoutNote = viewModel::openWorkoutNote,
              )
            }

        state.loading ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }

        else -> FadeInContent { NoActiveWorkout(onNavigateBack = onNavigateBack) }
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
      )
    }
  }

  if (permissionHost.showDialog) {
    AlertDialog(
        onDismissRequest = permissionHost::cancel,
        title = { Text("Подключить датчик пульса?") },
        text = {
          Text(
              "Доступ к устройствам поблизости нужен только для поиска и подключения " +
                  "Bluetooth-датчика. Тренировка полностью работает и без него.",
          )
        },
        confirmButton = {
          TextButton(onClick = { permissionHost.confirm() }) {
            Text(if (permissionHost.offersSettings) "Открыть настройки" else "Продолжить")
          }
        },
        dismissButton = { TextButton(onClick = permissionHost::cancel) { Text("Не сейчас") } },
    )
  }
}

/** Колбэки правок подходов, прокинутые от [ActiveWorkoutViewModel] в карточки текущего подхода. */
internal class SetActions(
    val stepWeight: (Long, Double) -> Unit,
    val stepReps: (Long, Int) -> Unit,
    val stepDuration: (Long, Int) -> Unit,
    val stepSpeed: (Long, Double) -> Unit,
    val stepIncline: (Long, Double) -> Unit,
    val setWeight: (Long, String) -> Unit,
    val setReps: (Long, String) -> Unit,
    val setDuration: (Long, String) -> Unit,
    val setSpeed: (Long, String) -> Unit,
    val setIncline: (Long, String) -> Unit,
    val complete: (Long) -> Unit,
    val uncomplete: (Long) -> Unit,
    val addSet: (Long) -> Unit,
    val deleteSet: (Long) -> Unit,
    val setEffort: (Long, SetEffort?) -> Unit = { _, _ -> },
    val editCompleted: (Long, ExerciseType) -> Unit = { _, _ -> },
    val updateCompletedWeight: (String) -> Unit = {},
    val updateCompletedReps: (String) -> Unit = {},
    val updateCompletedDuration: (String) -> Unit = {},
    val updateCompletedSpeed: (String) -> Unit = {},
    val updateCompletedIncline: (String) -> Unit = {},
    val updateCompletedEffort: (SetEffort) -> Unit = {},
    val saveCompletedEdit: () -> Unit = {},
    val cancelCompletedEdit: () -> Unit = {},
    val editNote: (Long) -> Unit = {},
    val updateNote: (String) -> Unit = {},
    val saveNoteEdit: () -> Unit = {},
    val cancelNoteEdit: () -> Unit = {},
    val editPersonalHint: (Long) -> Unit = {},
    val unpinPersonalHint: (Long) -> Unit = {},
    val updatePersonalHint: (String) -> Unit = {},
    val savePersonalHint: () -> Unit = {},
    val cancelPersonalHint: () -> Unit = {},
)

@Composable
internal fun ActiveWorkoutContent(
    state: ActiveWorkoutUiState,
    elapsedSeconds: StateFlow<Long>,
    restTimer: StateFlow<RestTimerState?>,
    heartRateState: StateFlow<HeartRateConnectionState>,
    heartRateReading: StateFlow<HeartRateReading?>,
    setActions: SetActions,
    onDeleteExercise: (Long) -> Unit,
    onReorderExercises: (List<Long>) -> Unit,
    onAddExercise: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    onOpenCoach: () -> Unit = {},
    unreadCoachMessages: Int = 0,
    liveCoachEnabled: Boolean = true,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onAddRestSeconds: (Int) -> Unit,
    onSkipRest: () -> Unit,
    onScanHeartRate: () -> Unit,
    onConnectHeartRate: (HeartRateDevice) -> Unit,
    onCancelHeartRateSelection: () -> Unit,
    onEditWorkoutNote: () -> Unit = {},
    onCoachPhase: (String) -> Unit = {},
) {
  val workout = state.workout ?: return
  val roomExercises = workout.exercises
  val roomOrder = roomExercises.map { it.workoutExercise.id }
  var localOrder by remember(workout.workout.id) { mutableStateOf(roomOrder) }
  var draggingExerciseId by remember(workout.workout.id) { mutableStateOf<Long?>(null) }
  var pendingPersistedOrder by remember(workout.workout.id) { mutableStateOf<List<Long>?>(null) }
  val haptics = gymHaptics()

  // Room обновляет дерево после каждого изменения подхода. Пока идёт drag или ждём его единую
  // запись в БД, сохраняем локальный порядок и лишь подмешиваем появившиеся/исчезнувшие id.
  LaunchedEffect(roomOrder, draggingExerciseId, pendingPersistedOrder) {
    val pending = pendingPersistedOrder
    when {
      pending != null && roomOrder.filter { it in pending } == pending -> {
        pendingPersistedOrder = null
        localOrder = mergeExerciseOrder(pending, roomOrder)
      }

      draggingExerciseId != null || pending != null -> {
        localOrder = mergeExerciseOrder(localOrder, roomOrder)
      }

      else -> localOrder = roomOrder
    }
  }

  val exercisesById = roomExercises.associateBy { it.workoutExercise.id }
  val exercises = localOrder.mapNotNull(exercisesById::get)
  val lazyListState = rememberLazyListState()
  val reorderableLazyListState =
      rememberGymReorderableLazyListState(lazyListState) { from, to ->
        if (
            from.index in localOrder.indices &&
                to.index in localOrder.indices &&
                from.index != to.index
        ) {
          // Reorderable ждёт синхронного обновления backing list, иначе dragged item моргает.
          localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
          haptics.stepFrequent()
        }
      }

  fun moveByAccessibilityAction(fromIndex: Int, toIndex: Int) {
    if (
        fromIndex !in localOrder.indices || toIndex !in localOrder.indices || fromIndex == toIndex
    ) {
      return
    }
    val reordered = localOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    localOrder = reordered
    pendingPersistedOrder = reordered
    haptics.stepFrequent()
    onReorderExercises(reordered)
  }

  // currentFocus — единое правило для экрана, уведомления и Wear. Передаём ему локальный
  // порядок, чтобы фокус немедленно следовал за карточкой ещё до записи перестановки в Room.
  val currentFocus = workout.copy(exercises = exercises).currentFocus()
  val activeSetId = currentFocus?.set?.id
  var missingRirSetId by rememberSaveable(workout.workout.id) { mutableStateOf<Long?>(null) }
  LaunchedEffect(
      activeSetId,
      currentFocus?.set?.setType,
      currentFocus?.set?.actualRir,
      currentFocus?.set?.actualRirAtLeastFour,
  ) {
    if (missingRirSetId != activeSetId || currentFocus?.set?.let(SetEffort::selectedFor) != null) {
      missingRirSetId = null
    }
  }
  val selectEffort: (Long, SetEffort) -> Unit = { setId, effort ->
    if (missingRirSetId == setId) missingRirSetId = null
    setActions.setEffort(setId, effort)
  }
  val currentIndex =
      activeSetId?.let { setId ->
        exercises.indexOfFirst { exercise -> exercise.sets.any { it.id == setId } }
      } ?: -1
  val currentNumber = if (currentIndex >= 0) currentIndex + 1 else exercises.size
  val showBottomFinish = roomExercises.areAllSetsCompleted()

  var showFinishDialog by rememberSaveable { mutableStateOf(false) }
  var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
  var pendingDeleteExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
  val requestFinish = {
    if (!state.isFinishing) {
      haptics.tap()
      showFinishDialog = true
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    ActiveWorkoutHeader(
        name = workout.workout.name,
        note = workout.workout.note,
        elapsedSeconds = elapsedSeconds,
        currentNumber = currentNumber,
        total = exercises.size,
        heartRateState = heartRateState,
        heartRateReading = heartRateReading,
        onScanHeartRate = onScanHeartRate,
        onConnectHeartRate = onConnectHeartRate,
        onCancelHeartRateSelection = onCancelHeartRateSelection,
        isFinishing = state.isFinishing,
        onFinish = requestFinish,
        onDiscard = { showDiscardDialog = true },
        onEditNote = onEditWorkoutNote,
        onOpenCoach = onOpenCoach,
    )

    LazyColumn(
        modifier = Modifier.weight(1f),
        state = lazyListState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (liveCoachEnabled)
          item("coach-phase") {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              listOf(
                      "Начал подход" to "IN_SET",
                      "Готов к диалогу" to "READY",
                      "Пауза общения" to "PAUSED",
                      "Продолжить общение" to "RESUME",
                  )
                  .forEach { (label, phase) ->
                    TextButton(
                        onClick = {
                          haptics.tap()
                          onCoachPhase(phase)
                        }
                    ) {
                      Text(label)
                    }
                  }
            }
          }
      items(exercises, key = { it.workoutExercise.id }) { exercise ->
        val index = exercises.indexOf(exercise)
        ReorderableItem(
            state = reorderableLazyListState,
            key = exercise.workoutExercise.id,
        ) { isDragging ->
          val reorderableItemScope = this
          val moveActions = buildList {
            if (index > 0) {
              add(
                  CustomAccessibilityAction("Переместить выше") {
                    moveByAccessibilityAction(index, index - 1)
                    true
                  },
              )
            }
            if (index < exercises.lastIndex) {
              add(
                  CustomAccessibilityAction("Переместить ниже") {
                    moveByAccessibilityAction(index, index + 1)
                    true
                  },
              )
            }
          }
          ExerciseSection(
              modifier =
                  Modifier.animateItem(placementSpec = GymMotion.spatialDefault())
                      .then(
                          if (isDragging) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = GymCardShape,
                            )
                          } else {
                            Modifier
                          },
                      )
                      .semantics { customActions = moveActions },
              exercise = exercise,
              previous = state.previousByExercise[exercise.exercise.id].orEmpty(),
              actions = setActions,
              activeSetId = activeSetId,
              missingRirSetId = missingRirSetId,
              onEffortSelect = selectEffort,
              showAddSet =
                  localOrder == roomOrder && workout.canAddSet(exercise.workoutExercise.id),
              onAddSet = { setActions.addSet(exercise.workoutExercise.id) },
              dragHandle = {
                DragHandle(
                    reorderableItemScope = reorderableItemScope,
                    onDragStarted = {
                      draggingExerciseId = exercise.workoutExercise.id
                      haptics.dragStart()
                    },
                    onDragStopped = {
                      haptics.dragEnd()
                      draggingExerciseId = null
                      if (localOrder != roomOrder) {
                        pendingPersistedOrder = localOrder
                        onReorderExercises(localOrder)
                      }
                    },
                )
              },
              onDeleteExercise = { pendingDeleteExerciseId = exercise.workoutExercise.id },
              onExerciseClick = { onExerciseClick(exercise.exercise.id) },
          )
        }
      }

      item {
        TextButton(onClick = onAddExercise, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Упражнение")
        }
      }
    }

    Column(
        modifier =
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (showBottomFinish) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          PillButton(
              text = "Завершить тренировку",
              onClick = requestFinish,
              enabled = !state.isFinishing,
              leadingIcon = Icons.Default.Check,
              modifier = Modifier.weight(1f),
          )
          if (liveCoachEnabled)
              CoachActionButton(
                  unreadCoachMessages = unreadCoachMessages,
                  onOpenCoach = onOpenCoach,
              )
        }
      } else {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            WorkoutPrimaryAction(
                restTimer = restTimer,
                heartRateReading = heartRateReading,
                activeSetId = activeSetId,
                onAddRestSeconds = onAddRestSeconds,
                onSkipRest = onSkipRest,
                onComplete = { setId ->
                  val focus = currentFocus
                  if (
                      focus?.set?.id == setId &&
                          focus.type == ExerciseType.STRENGTH &&
                          SetEffort.selectedFor(focus.set) == null
                  ) {
                    missingRirSetId = setId
                    haptics.reject()
                  } else {
                    missingRirSetId = null
                    haptics.confirm()
                    setActions.complete(setId)
                  }
                },
            )
          }
          if (liveCoachEnabled)
              CoachActionButton(
                  unreadCoachMessages = unreadCoachMessages,
                  onOpenCoach = onOpenCoach,
              )
        }
      }
    }
  }

  if (showFinishDialog) {
    FinishWorkoutConfirmDialog(
        isFinishing = state.isFinishing,
        onConfirm = {
          haptics.success()
          showFinishDialog = false
          onFinish()
        },
        onDismiss = { showFinishDialog = false },
    )
  }

  if (showDiscardDialog) {
    ConfirmDialog(
        title = "Отменить тренировку?",
        text = "Тренировка будет удалена без возможности восстановления.",
        confirmText = "Удалить",
        destructive = true,
        onConfirm = {
          haptics.reject()
          showDiscardDialog = false
          onDiscard()
        },
        onDismiss = { showDiscardDialog = false },
    )
  }

  val deleteExerciseId = pendingDeleteExerciseId
  if (deleteExerciseId != null) {
    val name =
        exercises
            .firstOrNull { it.workoutExercise.id == deleteExerciseId }
            ?.exercise
            ?.name
            .orEmpty()
    ConfirmDialog(
        title = "Убрать упражнение?",
        text = "«$name» и его подходы будут удалены из тренировки.",
        confirmText = "Убрать",
        destructive = true,
        onConfirm = {
          pendingDeleteExerciseId = null
          onDeleteExercise(deleteExerciseId)
        },
        onDismiss = { pendingDeleteExerciseId = null },
    )
  }

  state.completedSetEdit?.let { draft ->
    CompletedSetEditDialog(
        draft = draft,
        onWeightChange = setActions.updateCompletedWeight,
        onRepsChange = setActions.updateCompletedReps,
        onDurationChange = setActions.updateCompletedDuration,
        onSpeedChange = setActions.updateCompletedSpeed,
        onInclineChange = setActions.updateCompletedIncline,
        onEffortChange = setActions.updateCompletedEffort,
        onSave = setActions.saveCompletedEdit,
        onCancel = setActions.cancelCompletedEdit,
        onUncomplete = {
          setActions.cancelCompletedEdit()
          setActions.uncomplete(draft.setId)
        },
    )
  }
  state.noteEdit?.let { draft ->
    WorkoutNoteEditDialog(
        draft = draft,
        onTextChange = setActions.updateNote,
        onSave = setActions.saveNoteEdit,
        onCancel = setActions.cancelNoteEdit,
    )
  }
  state.personalHintEdit?.let { draft ->
    ActivePersonalHintEditDialog(
        draft = draft,
        onTextChange = setActions.updatePersonalHint,
        onSave = setActions.savePersonalHint,
        onCancel = setActions.cancelPersonalHint,
    )
  }
}

@Composable
private fun CoachActionButton(unreadCoachMessages: Int, onOpenCoach: () -> Unit) {
  val haptics = gymHaptics()
  val effectsMotion: FiniteAnimationSpec<Float> = GymMotion.effectsDefault()
  val countText = if (unreadCoachMessages > 99) "99+" else unreadCoachMessages.toString()
  val description =
      if (unreadCoachMessages > 0)
          "Открыть Live Coach, $unreadCoachMessages непрочитанных сообщений"
      else "Открыть Live Coach"
  BadgedBox(
      badge = {
        if (unreadCoachMessages > 0)
            Badge {
              AnimatedContent(
                  targetState = countText,
                  transitionSpec = { fadeIn(effectsMotion) togetherWith fadeOut(effectsMotion) },
                  label = "coachUnreadCount",
              ) {
                Text(it)
              }
            }
      },
      modifier = Modifier.testTag("open-live-coach"),
  ) {
    androidx.compose.material3.FilledIconButton(
        onClick = {
          haptics.tap()
          onOpenCoach()
        },
        modifier = Modifier.size(56.dp).semantics { contentDescription = description },
    ) {
      Icon(Icons.Default.AutoAwesome, contentDescription = null)
    }
  }
}

/** Сохраняет пользовательский порядок известных id и добавляет новые строки в порядок Room. */
private fun mergeExerciseOrder(localOrder: List<Long>, roomOrder: List<Long>): List<Long> {
  val roomIds = roomOrder.toSet()
  return localOrder.filter { it in roomIds } + roomOrder.filterNot { it in localOrder }
}

/** Кликабельный live-пульс: занимает одну строку с названием тренировки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeartRateBubble(
    state: StateFlow<HeartRateConnectionState>,
    reading: StateFlow<HeartRateReading?>,
    onScan: () -> Unit,
    onSelectDevice: (HeartRateDevice) -> Unit,
    onDismissSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val connectionState by state.collectAsStateWithLifecycle()
  val liveReading by reading.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  val bpm = liveReading?.bpm
  val description =
      bpm?.let { "Пульс $it ударов в минуту. Нажмите, чтобы сменить датчик" }
          ?: "Подключить пульсометр"

  GymCard(
      modifier =
          modifier.animateContentSize(GymMotion.spatialFast()).semantics {
            contentDescription = description
          },
      shape = CircleShape,
      onClick = {
        haptics.tap()
        onScan()
      },
      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Default.Favorite,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(5.dp))
      Text(
          text = bpm?.toString() ?: "—",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color =
              if (bpm != null) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
  }

  val selection = connectionState as? HeartRateConnectionState.Selection
  if (selection != null) {
    ModalBottomSheet(onDismissRequest = onDismissSelection) {
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 24.dp)
                  .padding(bottom = 32.dp),
      ) {
        Text(
            text = "Выберите пульсометр",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text =
                "Найдено несколько источников Heart Rate. Выберите тот, который сейчас передаёт пульс.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        selection.devices.forEach { device ->
          TextButton(
              onClick = {
                haptics.tap()
                onSelectDevice(device)
              },
              modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                  text = device.label,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                  text = "Сигнал ${device.rssi} dBm",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Один стабильный слот главного действия. При завершении отдыха меняет таймер на кнопку подхода в
 * тех же границах, чтобы две кнопки не меняли высоту нижней панели во время exit-анимации.
 */
@Composable
private fun WorkoutPrimaryAction(
    restTimer: StateFlow<RestTimerState?>,
    heartRateReading: StateFlow<HeartRateReading?>,
    activeSetId: Long?,
    onAddRestSeconds: (Int) -> Unit,
    onSkipRest: () -> Unit,
    onComplete: (Long) -> Unit,
) {
  val rest by restTimer.collectAsStateWithLifecycle()
  val reading by heartRateReading.collectAsStateWithLifecycle()
  var lastRest by remember { mutableStateOf(rest) }
  rest?.let { lastRest = it }
  val effectsMotion: FiniteAnimationSpec<Float> = GymMotion.effectsFast()

  AnimatedContent(
      targetState = rest != null,
      transitionSpec = { fadeIn(effectsMotion) togetherWith fadeOut(effectsMotion) },
      contentAlignment = Alignment.Center,
      modifier = Modifier.fillMaxWidth().height(56.dp).testTag("workout-primary-action"),
      label = "workoutPrimaryAction",
  ) { isResting ->
    val state = lastRest
    if (isResting && state != null) {
      RestTimerPill(
          state = state,
          reading = reading,
          onAddRestSeconds = onAddRestSeconds,
          onSkipRest = onSkipRest,
      )
    } else {
      activeSetId?.let { setId ->
        PillButton(
            text = "Подход выполнен",
            onClick = { onComplete(setId) },
            leadingIcon = Icons.Default.Check,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Пилюля отдыха. В таймерном режиме показывает «−15с / M:SS / +15с», а в режиме пульса — текущий
 * BPM и порог; тап по центру всегда пропускает отдых.
 */
@Composable
private fun RestTimerPill(
    state: RestTimerState,
    reading: HeartRateReading?,
    onAddRestSeconds: (Int) -> Unit,
    onSkipRest: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary)
              .height(56.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    val haptics = gymHaptics()
    when (state) {
      is RestTimerState.Timed -> {
        RestPillSide(symbol = "−15с", contentDescription = "убавить отдых") {
          haptics.step()
          onAddRestSeconds(-REST_TIMER_STEP)
        }
        SkipRestButton(onSkipRest) {
          Text(
              text = "⏱ ${formatRestClock(state.remainingSec)}",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimary,
          )
        }
        RestPillSide(symbol = "+15с", contentDescription = "прибавить отдых") {
          haptics.step()
          onAddRestSeconds(REST_TIMER_STEP)
        }
      }

      is RestTimerState.HeartRate ->
          SkipRestButton(onSkipRest) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                  imageVector = Icons.Default.Favorite,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.background,
                  modifier = Modifier.size(14.dp),
              )
              Spacer(Modifier.width(5.dp))
              Text(
                  text = "${reading?.bpm ?: "—"} · ≤ ${state.thresholdBpm} BPM",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }
    }
  }
}

@Composable
private fun RowScope.SkipRestButton(onSkipRest: () -> Unit, content: @Composable () -> Unit) {
  val haptics = gymHaptics()
  Row(
      modifier =
          Modifier.weight(1f)
              .fillMaxHeight()
              .clickable(
                  role = Role.Button,
                  onClick = {
                    haptics.tap()
                    onSkipRest()
                  },
              )
              .semantics { contentDescription = "Пропустить отдых" },
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

@Composable
private fun RestPillSide(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
  Box(
      modifier =
          Modifier.fillMaxHeight()
              .clip(CircleShape)
              .clickable(role = Role.Button, onClick = onClick)
              .semantics { this.contentDescription = contentDescription }
              .padding(horizontal = 20.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = symbol,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
    )
  }
}

@Composable
private fun ActiveWorkoutHeader(
    name: String,
    note: String,
    elapsedSeconds: StateFlow<Long>,
    currentNumber: Int,
    total: Int,
    heartRateState: StateFlow<HeartRateConnectionState>,
    heartRateReading: StateFlow<HeartRateReading?>,
    onScanHeartRate: () -> Unit,
    onConnectHeartRate: (HeartRateDevice) -> Unit,
    onCancelHeartRateSelection: () -> Unit,
    isFinishing: Boolean,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onOpenCoach: () -> Unit,
    onEditNote: () -> Unit,
) {
  // Собираем таймер только здесь, чтобы посекундный тик не рекомпозил список подходов.
  val elapsed by elapsedSeconds.collectAsStateWithLifecycle()
  var menuExpanded by remember { mutableStateOf(false) }
  Column(
      modifier =
          Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = name,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.width(12.dp))
      HeartRateBubble(
          state = heartRateState,
          reading = heartRateReading,
          onScan = onScanHeartRate,
          onSelectDevice = onConnectHeartRate,
          onDismissSelection = onCancelHeartRateSelection,
      )
      Box {
        IconButton(onClick = { menuExpanded = true }) {
          Icon(
              imageVector = Icons.Default.MoreVert,
              contentDescription = "Действия тренировки",
          )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
          DropdownMenuItem(
              text = { Text("Завершить тренировку") },
              enabled = !isFinishing,
              onClick = {
                menuExpanded = false
                onFinish()
              },
          )
          DropdownMenuItem(
              text = { Text("Заметка к тренировке") },
              onClick = {
                menuExpanded = false
                onEditNote()
              },
          )
          DropdownMenuItem(
              text = {
                Text(
                    text = "Отменить тренировку",
                    color = MaterialTheme.colorScheme.error,
                )
              },
              onClick = {
                menuExpanded = false
                onDiscard()
              },
          )
        }
      }
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = formatElapsed(elapsed),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(12.dp))
      if (total > 0) {
        Text(
            text = "упражнение $currentNumber из $total",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (note.isNotBlank()) {
      Text(
          text = note,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExerciseSection(
    exercise: WorkoutExerciseWithSets,
    previous: String,
    actions: SetActions,
    activeSetId: Long?,
    missingRirSetId: Long?,
    onEffortSelect: (Long, SetEffort) -> Unit,
    showAddSet: Boolean,
    onAddSet: () -> Unit,
    dragHandle: @Composable () -> Unit,
    onDeleteExercise: () -> Unit,
    onExerciseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val type = exercise.exercise.type
  val haptics = gymHaptics()

  val hasActiveSet = exercise.sets.any { it.id == activeSetId }
  var expanded by rememberSaveable(exercise.workoutExercise.id) { mutableStateOf(hasActiveSet) }
  var previouslyActive by
      rememberSaveable(exercise.workoutExercise.id) { mutableStateOf(hasActiveSet) }
  LaunchedEffect(hasActiveSet) {
    if (hasActiveSet && !previouslyActive) expanded = true
    previouslyActive = hasActiveSet
  }

  // Когда текущий подход схлопывается в пилюлю, высота секции меняется плавно (expressive-спек).
  Column(
      modifier = modifier.fillMaxWidth().animateContentSize(GymMotion.spatialDefault()),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Row(
          modifier =
              Modifier.weight(1f).heightIn(min = 48.dp).clickable {
                haptics.tap()
                onExerciseClick()
              },
          verticalAlignment = Alignment.CenterVertically,
      ) {
        ExerciseAvatar(exercise = exercise.exercise)
        Spacer(Modifier.width(12.dp))
        Column {
          Text(
              text = exercise.exercise.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onBackground,
          )
          if (previous.isNotEmpty()) {
            Text(
                text = "прошлый: $previous",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      dragHandle()
      IconButton(onClick = onDeleteExercise) {
        Icon(
            Icons.Default.Delete,
            contentDescription = "Убрать упражнение",
            tint = MaterialTheme.colorScheme.error,
        )
      }
    }

    Spacer(Modifier.height(8.dp))

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Подходы: ${exercise.exercise.name}",
                ) {
                  haptics.tap()
                  expanded = !expanded
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = "Подходы: ${exercise.sets.count { it.isCompleted }}/${exercise.sets.size}",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelLarge,
      )
      if (showAddSet) {
        IconButton(
            onClick = {
              haptics.step()
              onAddSet()
            }
        ) {
          Icon(Icons.Rounded.Add, contentDescription = "Добавить подход: ${exercise.exercise.name}")
        }
      }
      Icon(
          if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = "Подходы: ${exercise.exercise.name}",
      )
    }

    if (expanded) {
      exercise.sets.forEach { set ->
        when {
          set.id == activeSetId ->
              CurrentSetCard(
                  set = set,
                  type = type,
                  actions = actions,
                  effortError = set.id == missingRirSetId,
                  onEffortSelect = { onEffortSelect(set.id, it) },
              )

          set.isCompleted -> {
            val haptics = gymHaptics()
            CompletedSetPill(
                set = set,
                type = type,
                onClick = {
                  haptics.step()
                  actions.editCompleted(set.id, type)
                },
            )
          }

          else ->
              FutureSetPill(
                  set = set,
                  type = type,
                  onEffortSelect = { onEffortSelect(set.id, it) },
              )
        }
        if (set.note.isNotBlank()) {
          Text(
              text = set.note,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(8.dp))
      }
    }
  }
}

@Composable
private fun SetNoteButton(set: WorkoutSetEntity, onClick: () -> Unit) {
  val haptics = gymHaptics()
  IconButton(
      modifier = Modifier.size(48.dp),
      onClick = {
        haptics.tap()
        onClick()
      },
  ) {
    Icon(
        Icons.Rounded.EditNote,
        contentDescription = "Заметка к подходу ${set.setIndex + 1}",
        tint =
            if (set.note.isNotBlank()) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CurrentSetCard(
    set: WorkoutSetEntity,
    type: ExerciseType,
    actions: SetActions,
    effortError: Boolean,
    onEffortSelect: (SetEffort) -> Unit,
) {
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = "ПОДХОД ${set.setIndex + 1}",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
      )
      SetNoteButton(set, onClick = { actions.editNote(set.id) })
      IconButton(onClick = { actions.deleteSet(set.id) }) {
        Icon(
            Icons.Default.Delete,
            contentDescription = "Удалить подход",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Spacer(Modifier.height(4.dp))

    when (type) {
      ExerciseType.STRENGTH -> {
        StepperField(
            label = "кг",
            value = set.weightKg.toField(),
            onValueChange = { actions.setWeight(set.id, it) },
            onStepDown = { actions.stepWeight(set.id, -WEIGHT_STEP) },
            onStepUp = { actions.stepWeight(set.id, WEIGHT_STEP) },
            onFineDown = { actions.stepWeight(set.id, -WEIGHT_FINE_STEP) },
            onFineUp = { actions.stepWeight(set.id, WEIGHT_FINE_STEP) },
            decimal = true,
        )
        Spacer(Modifier.height(10.dp))
        StepperField(
            label = "повторы",
            value = set.reps.toField(),
            onValueChange = { actions.setReps(set.id, it) },
            onStepDown = { actions.stepReps(set.id, -REPS_STEP) },
            onStepUp = { actions.stepReps(set.id, REPS_STEP) },
        )
        Spacer(Modifier.height(10.dp))
        SetEffortField(set = set, onSelect = onEffortSelect, isError = effortError)
      }

      ExerciseType.TIMED -> {
        StepperField(
            label = "секунды",
            value = set.durationSec.toField(),
            onValueChange = { actions.setDuration(set.id, it) },
            onStepDown = { actions.stepDuration(set.id, -DURATION_STEP) },
            onStepUp = { actions.stepDuration(set.id, DURATION_STEP) },
        )
      }

      ExerciseType.CARDIO -> {
        StepperField(
            label = "км/ч",
            value = set.speedKmh.toField(),
            onValueChange = { actions.setSpeed(set.id, it) },
            onStepDown = { actions.stepSpeed(set.id, -SPEED_STEP) },
            onStepUp = { actions.stepSpeed(set.id, SPEED_STEP) },
            decimal = true,
        )
        Spacer(Modifier.height(10.dp))
        StepperField(
            label = "наклон %",
            value = set.inclinePct.toField(),
            onValueChange = { actions.setIncline(set.id, it) },
            onStepDown = { actions.stepIncline(set.id, -INCLINE_STEP) },
            onStepUp = { actions.stepIncline(set.id, INCLINE_STEP) },
            decimal = true,
        )
        Spacer(Modifier.height(10.dp))
        StepperField(
            label = "секунды",
            value = set.durationSec.toField(),
            onValueChange = { actions.setDuration(set.id, it) },
            onStepDown = { actions.stepDuration(set.id, -DURATION_STEP) },
            onStepUp = { actions.stepDuration(set.id, DURATION_STEP) },
        )
      }
    }
  }
}

/**
 * Пара кнопок ± вокруг числового поля. Обычный тап — [onStepDown]/[onStepUp]; долгое нажатие, если
 * задано, — [onFineDown]/[onFineUp] (используется для точного шага веса ±0.5).
 */
@Composable
private fun StepperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
    modifier: Modifier = Modifier,
    onFineDown: (() -> Unit)? = null,
    onFineUp: (() -> Unit)? = null,
    decimal: Boolean = false,
) {
  Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StepButton(
        symbol = "−",
        contentDescription = "уменьшить $label",
        onClick = onStepDown,
        onLongClick = onFineDown,
    )
    NumberField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.weight(1f),
        label = label,
        decimal = decimal,
    )
    StepButton(
        symbol = "+",
        contentDescription = "увеличить $label",
        onClick = onStepUp,
        onLongClick = onFineUp,
    )
  }
}

@Composable
private fun StepButton(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
  // Тактильный шаг на каждом ±: long-press (точная подстройка) отбивается мягче — это
  // единственное, что отличает его от обычного тапа до того, как изменится число.
  val haptics = gymHaptics()
  Box(
      modifier =
          Modifier.size(48.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainerHighest)
              .combinedClickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = ripple(),
                  role = Role.Button,
                  onClick = {
                    haptics.step()
                    onClick()
                  },
                  onLongClick =
                      onLongClick?.let { fine ->
                        {
                          haptics.stepFrequent()
                          fine()
                        }
                      },
              )
              .semantics { this.contentDescription = contentDescription },
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = symbol,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompletedSetPill(
    set: WorkoutSetEntity,
    type: ExerciseType,
    onClick: () -> Unit,
) {
  // Короткий scale-панч на галочке при появлении пилюли (подход только что отмечен выполненным).
  val punchSpec = GymMotion.spatialDefault<Float>()
  val checkScale = remember { Animatable(0.6f) }
  LaunchedEffect(Unit) { checkScale.animateTo(1f, punchSpec) }
  SetPill(
      set = set,
      values = formatSetValues(set, type),
      showEffort = type == ExerciseType.STRENGTH,
      onEffortSelect = null,
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      onClick = onClick,
      trailing = {
        Icon(
            Icons.Default.Check,
            contentDescription = "Выполнено, нажмите чтобы изменить фактические значения",
            modifier =
                Modifier.size(18.dp).graphicsLayer {
                  scaleX = checkScale.value
                  scaleY = checkScale.value
                },
        )
      },
  )
}

@Composable
private fun CompletedSetEditDialog(
    draft: CompletedSetEditDraft,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onSpeedChange: (String) -> Unit,
    onInclineChange: (String) -> Unit,
    onEffortChange: (SetEffort) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onUncomplete: () -> Unit,
) {
  val haptics = gymHaptics()
  AlertDialog(
      onDismissRequest = onCancel,
      title = { Text("Фактические значения") },
      text = {
        CompletedSetEditFields(
            draft = draft,
            onWeightChange = onWeightChange,
            onRepsChange = onRepsChange,
            onDurationChange = onDurationChange,
            onSpeedChange = onSpeedChange,
            onInclineChange = onInclineChange,
            onEffortChange = onEffortChange,
        )
      },
      confirmButton = {
        TextButton(
            enabled = !draft.isSubmitting && draft.isValidNumericInput(),
            onClick = {
              haptics.confirm()
              onSave()
            },
        ) {
          Text(if (draft.isSubmitting) "Сохранение…" else "Сохранить")
        }
      },
      dismissButton = {
        Column(horizontalAlignment = Alignment.End) {
          TextButton(enabled = !draft.isSubmitting, onClick = onCancel) { Text("Отмена") }
          TextButton(
              enabled = !draft.isSubmitting,
              onClick = {
                haptics.toggle(on = false)
                onUncomplete()
              },
          ) {
            Text("Отметить невыполненным")
          }
        }
      },
  )
}

@Composable
internal fun CompletedSetEditFields(
    draft: CompletedSetEditDraft,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onSpeedChange: (String) -> Unit,
    onInclineChange: (String) -> Unit,
    onEffortChange: (SetEffort) -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    when (draft.type) {
      ExerciseType.STRENGTH -> {
        SetEffortSelector(
            selected = draft.effort,
            onSelect = onEffortChange,
            contentDescription = "Запас повторений",
            testTag = "completed-set-effort",
            modifier = Modifier.fillMaxWidth(),
            enabled = !draft.isSubmitting,
        )
        NumberField(
            value = draft.weightKg,
            onValueChange = onWeightChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Вес, кг",
            decimal = true,
            enabled = !draft.isSubmitting,
        )
        NumberField(
            value = draft.reps,
            onValueChange = onRepsChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Повторы",
            enabled = !draft.isSubmitting,
        )
      }

      ExerciseType.TIMED ->
          NumberField(
              value = draft.durationSec,
              onValueChange = onDurationChange,
              modifier = Modifier.fillMaxWidth(),
              label = "Длительность, секунды",
              enabled = !draft.isSubmitting,
          )

      ExerciseType.CARDIO -> {
        NumberField(
            value = draft.durationSec,
            onValueChange = onDurationChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Длительность, секунды",
            enabled = !draft.isSubmitting,
        )
        NumberField(
            value = draft.speedKmh,
            onValueChange = onSpeedChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Скорость, км/ч",
            decimal = true,
            enabled = !draft.isSubmitting,
        )
        NumberField(
            value = draft.inclinePct,
            onValueChange = onInclineChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Наклон, %",
            decimal = true,
            enabled = !draft.isSubmitting,
        )
      }
    }
    draft.error?.let {
      Text(
          text = it,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

internal fun CompletedSetEditDraft.isValidNumericInput(): Boolean {
  fun String.isOptionalInt() = isBlank() || toIntOrNull() != null
  return when (type) {
    ExerciseType.STRENGTH ->
        effort != null && weightKg.isValidOptionalFiniteDecimal() && reps.isOptionalInt()
    ExerciseType.TIMED -> durationSec.isOptionalInt()
    ExerciseType.CARDIO ->
        durationSec.isOptionalInt() &&
            speedKmh.isValidOptionalFiniteDecimal() &&
            inclinePct.isValidOptionalFiniteDecimal()
  }
}

@Composable
private fun FutureSetPill(
    set: WorkoutSetEntity,
    type: ExerciseType,
    onEffortSelect: (SetEffort) -> Unit,
) {
  SetPill(
      set = set,
      values = formatSetValues(set, type),
      showEffort = type == ExerciseType.STRENGTH,
      onEffortSelect = onEffortSelect.takeIf { type == ExerciseType.STRENGTH },
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      onClick = null,
      trailing = null,
  )
}

@Composable
private fun SetPill(
    set: WorkoutSetEntity,
    values: String,
    containerColor: Color,
    contentColor: Color,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    showEffort: Boolean,
    onEffortSelect: ((SetEffort) -> Unit)?,
) {
  val clickable = if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(containerColor)
              .then(clickable)
              .heightIn(min = if (onClick != null) 48.dp else 36.dp)
              .padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = "Подход ${set.setIndex + 1}",
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
        modifier = Modifier.weight(1f),
    )
    Text(
        text = values.ifEmpty { "—" },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
        modifier = Modifier.weight(1f),
    )
    if (showEffort) {
      Spacer(Modifier.width(8.dp))
      SetEffortField(
          set = set,
          onSelect = onEffortSelect,
          compact = true,
          modifier = Modifier.weight(1f),
          contentColor = contentColor,
      )
    }
    Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.CenterEnd) {
      trailing?.invoke()
    }
  }
}

@Composable
private fun NoActiveWorkout(onNavigateBack: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = "Нет активной тренировки",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onNavigateBack) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
      Spacer(Modifier.width(8.dp))
      Text("Назад")
    }
  }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
    dismissEnabled: Boolean = true,
) {
  AlertDialog(
      onDismissRequest = { if (dismissEnabled) onDismiss() },
      title = { Text(title) },
      text = { Text(text) },
      confirmButton = {
        if (actions != null) {
          actions()
        } else {
          TextButton(onClick = onConfirm) {
            Text(
                text = confirmText,
                color =
                    if (destructive) {
                      MaterialTheme.colorScheme.error
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
            )
          }
        }
      },
      dismissButton = {
        if (actions == null) {
          TextButton(onClick = onDismiss, enabled = dismissEnabled) { Text("Отмена") }
        }
      },
  )
}

@Composable
private fun FinishWorkoutConfirmDialog(
    isFinishing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  ConfirmDialog(
      title = "Завершить тренировку?",
      text = "Пустые невыполненные подходы будут отброшены, тренировка попадёт в историю.",
      confirmText = "Завершить",
      onConfirm = onConfirm,
      onDismiss = onDismiss,
      dismissEnabled = !isFinishing,
      actions = {
        FinishWorkoutConfirmationActions(
            enabled = !isFinishing,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
      },
  )
}

/**
 * Shared dialog actions, kept separately so their disabled and cancel semantics remain testable.
 */
@Composable
internal fun FinishWorkoutConfirmationActions(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(onClick = onDismiss, enabled = enabled) { Text("Отмена") }
    TextButton(onClick = onConfirm, enabled = enabled) { Text("Завершить") }
  }
}

internal fun List<WorkoutExerciseWithSets>.areAllSetsCompleted(): Boolean {
  val roomSets = flatMap { it.sets }
  return roomSets.isNotEmpty() && roomSets.all { it.isCompleted }
}

/** Пока экран на переднем плане, экран устройства не гаснет. */
@Composable
private fun KeepScreenOn() {
  val context = LocalContext.current
  DisposableEffect(context) {
    val window = context.findActivity()?.window
    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }

/** Краткое представление значений подхода для свёрнутых пилюль. */
private fun formatSetValues(set: WorkoutSetEntity, type: ExerciseType): String =
    when (type) {
      ExerciseType.STRENGTH -> {
        val weight = set.weightKg.toField()
        val reps = set.reps.toField()
        when {
          weight.isNotEmpty() && reps.isNotEmpty() -> "$weight×$reps"
          weight.isNotEmpty() -> "$weight кг"
          reps.isNotEmpty() -> "$reps повт"
          else -> ""
        }
      }

      ExerciseType.TIMED -> set.durationSec?.let { "$it сек" }.orEmpty()

      ExerciseType.CARDIO ->
          buildList {
                set.speedKmh?.let { add("${it.toField()} км/ч") }
                set.inclinePct?.let { add("${it.toField()}%") }
                set.durationSec?.let { add("${it / 60} мин") }
              }
              .joinToString(" · ")
    }

private fun formatElapsed(totalSeconds: Long): String {
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    "%d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%02d:%02d".format(minutes, seconds)
  }
}

private fun Double?.toField(): String =
    when {
      this == null -> ""
      this % 1.0 == 0.0 -> toInt().toString()
      else -> toString()
    }

private fun Int?.toField(): String = this?.toString() ?: ""

@Composable
private fun WorkoutNoteEditDialog(
    draft: WorkoutNoteEditDraft,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = { if (!draft.isSubmitting) onCancel() },
      title = { Text(if (draft.setId == null) "Заметка к тренировке" else "Заметка к подходу") },
      text = {
        Column {
          OutlinedTextField(
              value = draft.text,
              onValueChange = onTextChange,
              label = { Text("Заметка") },
              supportingText = { Text("До 2000 символов") },
              isError = draft.error != null,
              enabled = !draft.isSubmitting,
              modifier = Modifier.fillMaxWidth(),
          )
          draft.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onSave, enabled = !draft.isSubmitting) { Text("Сохранить") }
      },
      dismissButton = {
        TextButton(onClick = onCancel, enabled = !draft.isSubmitting) { Text("Отмена") }
      },
  )
}

@Composable
private fun ActivePersonalHintEditDialog(
    draft: ActivePersonalHintEditDraft,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = { if (!draft.isSubmitting) onCancel() },
      title = { Text("Моя подсказка") },
      text = {
        Column {
          OutlinedTextField(
              value = draft.text,
              onValueChange = onTextChange,
              label = { Text("Подсказка") },
              supportingText = { Text("До 2000 символов") },
              isError = draft.error != null,
              enabled = !draft.isSubmitting,
              modifier = Modifier.fillMaxWidth(),
          )
          draft.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onSave, enabled = !draft.isSubmitting) { Text("Сохранить") }
      },
      dismissButton = {
        TextButton(onClick = onCancel, enabled = !draft.isSubmitting) { Text("Отмена") }
      },
  )
}

@Composable
internal fun SetEffortField(
    set: WorkoutSetEntity,
    onSelect: ((SetEffort) -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    isError: Boolean = false,
) {
  SetEffortSelector(
      selected = SetEffort.selectedFor(set),
      onSelect = onSelect,
      contentDescription = "Запас повторений, подход ${set.setIndex + 1}",
      testTag = "set-effort-${set.id}",
      modifier = modifier,
      compact = compact,
      contentColor = contentColor,
      isError = isError,
  )
}

@Composable
private fun SetEffortSelector(
    selected: SetEffort?,
    onSelect: ((SetEffort) -> Unit)?,
    contentDescription: String,
    testTag: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
  var expanded by remember(testTag) { mutableStateOf(false) }
  val haptics = gymHaptics()
  val label = selected?.label ?: "Выберите"
  val interactive = enabled && onSelect != null
  val openMenu = {
    if (interactive) {
      haptics.tap()
      expanded = true
    }
  }
  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (!compact) {
        Text("RIR:", style = MaterialTheme.typography.bodyMedium, color = contentColor)
        Spacer(Modifier.width(12.dp))
      }
      Box(modifier = if (compact) Modifier.fillMaxWidth() else Modifier.weight(1f)) {
        val anchor =
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag).semantics {
              this.contentDescription = contentDescription
              stateDescription = selected?.label ?: "Не выбран"
              if (isError) error("Выберите RIR")
            }
        if (compact) {
          Box(
              modifier =
                  if (interactive) anchor.clickable(role = Role.Button, onClick = openMenu)
                  else anchor,
              contentAlignment = Alignment.CenterStart,
          ) {
            Text(
                text = "RIR:${selected?.label ?: "—"}",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
          }
        } else {
          androidx.compose.material3.OutlinedButton(
              onClick = openMenu,
              enabled = interactive,
              modifier = anchor,
              shape = MaterialTheme.shapes.medium,
              border =
                  BorderStroke(
                      width = if (isError) 2.dp else 1.dp,
                      color =
                          if (isError) MaterialTheme.colorScheme.error
                          else MaterialTheme.colorScheme.outline,
                  ),
              colors =
                  ButtonDefaults.outlinedButtonColors(
                      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                      contentColor = contentColor,
                  ),
          ) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, contentDescription = null)
          }
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded && interactive,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
          SetEffort.entries.forEach { effort ->
            val isSelected = effort == selected
            androidx.compose.material3.DropdownMenuItem(
                text = {
                  Text(
                      text = effort.label,
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                  )
                },
                leadingIcon = {
                  Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null)
                  }
                },
                modifier =
                    Modifier.background(
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                colors =
                    MenuDefaults.itemColors(
                        textColor =
                            if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                onClick = {
                  haptics.step()
                  onSelect?.invoke(effort)
                  expanded = false
                },
            )
          }
        }
      }
    }
  }
}
