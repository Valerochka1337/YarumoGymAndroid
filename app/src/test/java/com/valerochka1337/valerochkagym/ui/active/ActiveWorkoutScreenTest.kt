package com.valerochka1337.valerochkagym.ui.active

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHint
import com.valerochka1337.valerochkagym.domain.SetEffort
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Проверяет пользовательское правило: развёрнутым остаётся только один текущий подход. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h4000dp-xhdpi")
class ActiveWorkoutScreenTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `effort selector styles every allowed value at large font scale`() {
    val set = mutableStateOf(WorkoutSetEntity(workoutExerciseId = 1, setIndex = 0))
    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        GymTheme {
          SetEffortField(
              set.value,
              onSelect = { effort -> set.value = effort.applyTo(set.value) },
          )
        }
      }
    }
    fun open() {
      composeRule
          .onNodeWithContentDescription("Запас повторений, подход 1")
          .assertHeightIsAtLeast(48.dp)
          .performClick()
    }
    open()
    listOf("Отказ", "1", "2", "3", "4+", "Разминка").forEach {
      composeRule.onNodeWithText(it).assertIsDisplayed()
    }
    composeRule.onNodeWithText("Не указано").assertDoesNotExist()
    composeRule.onNodeWithText("4+").performClick()
    composeRule.onNodeWithText("4+").assertIsDisplayed()
    assertTrue(set.value.actualRirAtLeastFour)
    assertEquals("WORK", set.value.setType)
    open()
    composeRule.onNodeWithText("Разминка").performClick()
    assertFalse(set.value.actualRirAtLeastFour)
    assertEquals("WARMUP", set.value.setType)
  }

  @Test
  fun `completing a strength set requires RIR and highlights its selector`() {
    val workout =
        mutableStateOf(
            workoutWithIncompleteExercises().updateSet(FIRST_SET_ID) {
              it.copy(setType = "UNKNOWN", actualRir = null, actualRirAtLeastFour = false)
            }
        )
    val completedSetIds = mutableListOf<Long>()
    val actions =
        noOpSetActions(
            complete = { completedSetIds += it },
            setEffort = { setId, effort ->
              requireNotNull(effort)
              workout.value = workout.value.updateSet(setId, effort::applyTo)
            },
        )
    renderActiveWorkout(workout, setActions = actions)

    completeFocusedSet()

    assertTrue(completedSetIds.isEmpty())
    composeRule.onNodeWithText("Выберите RIR").assertDoesNotExist()
    assertEquals(
        "Выберите RIR",
        composeRule
            .onNodeWithTag("set-effort-$FIRST_SET_ID")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Error],
    )

    composeRule.onNodeWithTag("set-effort-$FIRST_SET_ID").performClick()
    composeRule.onNodeWithText("2").performClick()
    completeFocusedSet()

    assertEquals(listOf(FIRST_SET_ID), completedSetIds)
  }

  @Test
  fun `RIR keeps the same column in completed and future sets`() {
    val workout =
        mutableStateOf(
            workoutWithIncompleteExercises()
                .updateSet(FIRST_SET_ID, SetEffort.WARMUP::applyTo)
                .updateSet(THIRD_SET_ID, SetEffort.ONE::applyTo)
                .markSetCompleted(FIRST_SET_ID),
        )
    renderActiveWorkout(workout)
    composeRule.onNodeWithContentDescription("Подходы: Жим лёжа").performClick()
    composeRule.onNodeWithContentDescription("Подходы: Присед").performClick()

    val completedRir =
        composeRule
            .onNodeWithTag("set-effort-$FIRST_SET_ID", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
    val futureRir =
        composeRule.onNodeWithTag("set-effort-$THIRD_SET_ID").fetchSemanticsNode().boundsInRoot

    assertEquals(futureRir.left, completedRir.left, 0.5f)
    assertEquals(futureRir.right, completedRir.right, 0.5f)
    assertEquals(
        composeRule
            .onNodeWithText("RIR:1", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left,
        composeRule
            .onNodeWithText("RIR:Разминка", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left,
        0.5f,
    )
    assertFalse(
        composeRule
            .onNodeWithTag("set-effort-$FIRST_SET_ID", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .contains(SemanticsActions.OnClick),
    )
  }

  @Test
  fun `active screen exposes a set note and its edit action`() {
    val workout =
        workoutWithIncompleteExercises()
            .copy(
                exercises =
                    workoutWithIncompleteExercises().exercises.map { exercise ->
                      exercise.copy(
                          sets =
                              exercise.sets.map { set ->
                                if (set.id == FIRST_SET_ID) set.copy(note = "Не спешить") else set
                              },
                      )
                    },
            )
    composeRule.setContent {
      GymTheme {
        ActiveWorkoutContent(
            state = ActiveWorkoutUiState(loading = false, workout = workout),
            elapsedSeconds = MutableStateFlow(0L),
            restTimer = MutableStateFlow<RestTimerState?>(null),
            heartRateState =
                MutableStateFlow<HeartRateConnectionState>(HeartRateConnectionState.Idle),
            heartRateReading = MutableStateFlow<HeartRateReading?>(null),
            setActions = noOpSetActions(),
            onDeleteExercise = {},
            onReorderExercises = {},
            onAddExercise = {},
            onExerciseClick = {},
            onFinish = {},
            onDiscard = {},
            onAddRestSeconds = {},
            onSkipRest = {},
            onScanHeartRate = {},
            onConnectHeartRate = {},
            onCancelHeartRateSelection = {},
        )
      }
    }

    composeRule.onNodeWithText("Не спешить").assertIsDisplayed()
    composeRule
        .onNodeWithContentDescription("Заметка к подходу 1")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun `set note remains reachable at font scale two`() {
    val workout =
        workoutWithIncompleteExercises()
            .copy(
                exercises =
                    workoutWithIncompleteExercises().exercises.map { exercise ->
                      exercise.copy(
                          sets =
                              exercise.sets.map { set ->
                                if (set.id == FIRST_SET_ID) set.copy(note = "Не спешить") else set
                              },
                      )
                    },
            )
    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        GymTheme {
          ActiveWorkoutContent(
              state = ActiveWorkoutUiState(loading = false, workout = workout),
              elapsedSeconds = MutableStateFlow(0L),
              restTimer = MutableStateFlow<RestTimerState?>(null),
              heartRateState =
                  MutableStateFlow<HeartRateConnectionState>(HeartRateConnectionState.Idle),
              heartRateReading = MutableStateFlow<HeartRateReading?>(null),
              setActions = noOpSetActions(),
              onDeleteExercise = {},
              onReorderExercises = {},
              onAddExercise = {},
              onExerciseClick = {},
              onFinish = {},
              onDiscard = {},
              onAddRestSeconds = {},
              onSkipRest = {},
              onScanHeartRate = {},
              onConnectHeartRate = {},
              onCancelHeartRateSelection = {},
          )
        }
      }
    }

    composeRule.onNodeWithText("Не спешить").assertIsDisplayed()
    composeRule
        .onNodeWithContentDescription("Заметка к подходу 1")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun `active screen hides personal hints and their actions`() {
    val edited = mutableListOf<Long>()
    val unpinned = mutableListOf<Long>()
    val workout = workoutWithIncompleteExercises()
    composeRule.setContent {
      GymTheme {
        ActiveWorkoutContent(
            state =
                ActiveWorkoutUiState(
                    loading = false,
                    workout = workout,
                    hintsByExercise = mapOf(111L to ExercisePersonalHint("Лопатки вместе", 1L)),
                ),
            elapsedSeconds = MutableStateFlow(0L),
            restTimer = MutableStateFlow<RestTimerState?>(null),
            heartRateState =
                MutableStateFlow<HeartRateConnectionState>(HeartRateConnectionState.Idle),
            heartRateReading = MutableStateFlow<HeartRateReading?>(null),
            setActions =
                noOpSetActions(
                    editPersonalHint = { edited += it },
                    unpinPersonalHint = { unpinned += it },
                ),
            onDeleteExercise = {},
            onReorderExercises = {},
            onAddExercise = {},
            onExerciseClick = {},
            onFinish = {},
            onDiscard = {},
            onAddRestSeconds = {},
            onSkipRest = {},
            onScanHeartRate = {},
            onConnectHeartRate = {},
            onCancelHeartRateSelection = {},
        )
      }
    }

    composeRule.onNodeWithText("Лопатки вместе").assertDoesNotExist()
    composeRule.onNodeWithText("Добавить подсказку").assertDoesNotExist()
    composeRule.onNodeWithText("Изменить подсказку").assertDoesNotExist()
    composeRule.onNodeWithText("Убрать подсказку").assertDoesNotExist()
    assertTrue(edited.isEmpty())
    assertTrue(unpinned.isEmpty())
  }

  @Test
  fun `exercise sets collapse and expand without losing their values`() {
    renderActiveWorkout(mutableStateOf(workoutWithIncompleteExercises()))
    composeRule.onNodeWithText("ПОДХОД 1").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Подходы: Жим лёжа").performClick()
    composeRule.onNodeWithText("ПОДХОД 1").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Заметка к подходу 1").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Подходы: Жим лёжа").performClick()
    composeRule.onNodeWithText("ПОДХОД 1").assertIsDisplayed()
    composeRule.onNodeWithText("60").assertIsDisplayed()
    composeRule.onNodeWithText("8").assertIsDisplayed()
  }

  @Test
  fun `only the active set exposes a note action`() {
    val edited = mutableListOf<Long>()
    val workout = mutableStateOf(workoutWithIncompleteExercises())
    renderActiveWorkout(workout, setActions = noOpSetActions(editNote = { edited += it }))
    composeRule.onNodeWithContentDescription("Заметка к подходу 1").performClick()
    assertEquals(listOf(FIRST_SET_ID), edited)
    composeRule.runOnIdle { workout.value = workout.value.markSetCompleted(FIRST_SET_ID) }
    composeRule.onNodeWithContentDescription("Заметка к подходу 1").performClick()
    assertEquals(listOf(FIRST_SET_ID, SECOND_SET_ID), edited)
    composeRule.onNodeWithContentDescription("Подходы: Тяга блока").performClick()
    composeRule.onNodeWithContentDescription("Подходы: Присед").performClick()
    composeRule.onNodeWithContentDescription("Заметка к подходу 1").assertDoesNotExist()
  }

  @Test
  fun `one completion button follows the focused set`() {
    val workout = mutableStateOf(workoutWithIncompleteExercises())
    val completedSetIds = mutableListOf<Long>()
    val addedSetTo = mutableListOf<Long>()
    val actions =
        SetActions(
            stepWeight = { _, _ -> },
            stepReps = { _, _ -> },
            stepDuration = { _, _ -> },
            stepSpeed = { _, _ -> },
            stepIncline = { _, _ -> },
            setWeight = { _, _ -> },
            setReps = { _, _ -> },
            setDuration = { _, _ -> },
            setSpeed = { _, _ -> },
            setIncline = { _, _ -> },
            complete = { setId ->
              completedSetIds += setId
              workout.value = workout.value.markSetCompleted(setId)
            },
            uncomplete = { _ -> },
            addSet = { workoutExerciseId -> addedSetTo += workoutExerciseId },
            deleteSet = { _ -> },
        )

    composeRule.setContent {
      GymTheme {
        ActiveWorkoutContent(
            state = ActiveWorkoutUiState(loading = false, workout = workout.value),
            elapsedSeconds = MutableStateFlow(0L),
            restTimer = MutableStateFlow<RestTimerState?>(null),
            heartRateState =
                MutableStateFlow<HeartRateConnectionState>(
                    HeartRateConnectionState.Idle,
                ),
            heartRateReading = MutableStateFlow<HeartRateReading?>(null),
            setActions = actions,
            onDeleteExercise = {},
            onReorderExercises = {},
            onAddExercise = {},
            onExerciseClick = {},
            onFinish = {},
            onDiscard = {},
            onAddRestSeconds = {},
            onSkipRest = {},
            onScanHeartRate = {},
            onConnectHeartRate = {},
            onCancelHeartRateSelection = {},
        )
      }
    }

    assertAddSetIsAvailable()
    completeFocusedSet()
    assertEquals(listOf(FIRST_SET_ID), completedSetIds)
    composeRule
        .onNodeWithContentDescription("Выполнено, нажмите чтобы изменить фактические значения")
        .assertIsDisplayed()
    assertAddSetIsAvailable()

    completeFocusedSet()
    assertEquals(listOf(FIRST_SET_ID, SECOND_SET_ID), completedSetIds)
    assertAddSetIsAvailable()

    completeFocusedSet()
    assertEquals(listOf(FIRST_SET_ID, SECOND_SET_ID, THIRD_SET_ID), completedSetIds)
    composeRule.onAllNodesWithText("Подход выполнен").assertCountEquals(0)
    composeRule.onAllNodesWithText("Подход").assertCountEquals(0)
    assertEquals(emptyList<Long>(), addedSetTo)
  }

  @Test
  fun `skipping rest keeps the outgoing timer in the primary action slot`() {
    val restTimer =
        MutableStateFlow<RestTimerState?>(
            RestTimerState.Timed(totalSec = 90, remainingSec = 45, endsAtMillis = 45_000L)
        )
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      GymTheme {
        ActiveWorkoutContent(
            state =
                ActiveWorkoutUiState(
                    loading = false,
                    workout = workoutWithIncompleteExercises(),
                ),
            elapsedSeconds = MutableStateFlow(0L),
            restTimer = restTimer,
            heartRateState =
                MutableStateFlow<HeartRateConnectionState>(HeartRateConnectionState.Idle),
            heartRateReading = MutableStateFlow<HeartRateReading?>(null),
            setActions = noOpSetActions(),
            onDeleteExercise = {},
            onReorderExercises = {},
            onAddExercise = {},
            onExerciseClick = {},
            onFinish = {},
            onDiscard = {},
            onAddRestSeconds = {},
            onSkipRest = { restTimer.value = null },
            onScanHeartRate = {},
            onConnectHeartRate = {},
            onCancelHeartRateSelection = {},
        )
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    val initialTimerBounds =
        composeRule
            .onNodeWithContentDescription("Пропустить отдых")
            .fetchSemanticsNode()
            .boundsInRoot

    composeRule.onNodeWithContentDescription("Пропустить отдых").performClick()
    composeRule.mainClock.advanceTimeByFrame()

    val outgoingTimerBounds =
        composeRule
            .onNodeWithContentDescription("Пропустить отдых")
            .fetchSemanticsNode()
            .boundsInRoot
    assertEquals(initialTimerBounds.top, outgoingTimerBounds.top, 0.5f)
    assertEquals(initialTimerBounds.bottom, outgoingTimerBounds.bottom, 0.5f)
    composeRule.onNodeWithText("Подход выполнен").assertIsDisplayed()
    composeRule.mainClock.autoAdvance = true
  }

  @Test
  fun `adding a set stays unavailable until local reorder reaches Room`() {
    val workout = mutableStateOf(workoutWithIncompleteExercises())
    val addedSetTo = mutableListOf<Long>()
    val persistedOrders = mutableListOf<List<Long>>()
    val actions =
        SetActions(
            stepWeight = { _, _ -> },
            stepReps = { _, _ -> },
            stepDuration = { _, _ -> },
            stepSpeed = { _, _ -> },
            stepIncline = { _, _ -> },
            setWeight = { _, _ -> },
            setReps = { _, _ -> },
            setDuration = { _, _ -> },
            setSpeed = { _, _ -> },
            setIncline = { _, _ -> },
            complete = { _ -> },
            uncomplete = { _ -> },
            addSet = { workoutExerciseId -> addedSetTo += workoutExerciseId },
            deleteSet = { _ -> },
        )

    composeRule.setContent {
      GymTheme {
        ActiveWorkoutContent(
            state = ActiveWorkoutUiState(loading = false, workout = workout.value),
            elapsedSeconds = MutableStateFlow(0L),
            restTimer = MutableStateFlow<RestTimerState?>(null),
            heartRateState =
                MutableStateFlow<HeartRateConnectionState>(
                    HeartRateConnectionState.Idle,
                ),
            heartRateReading = MutableStateFlow<HeartRateReading?>(null),
            setActions = actions,
            onDeleteExercise = {},
            onReorderExercises = { persistedOrders += it },
            onAddExercise = {},
            onExerciseClick = {},
            onFinish = {},
            onDiscard = {},
            onAddRestSeconds = {},
            onSkipRest = {},
            onScanHeartRate = {},
            onConnectHeartRate = {},
            onCancelHeartRateSelection = {},
        )
      }
    }

    customActionsFor("Жим лёжа")
        .single { it.label == "Переместить ниже" }
        .also { action -> composeRule.runOnIdle { action.action() } }
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("Подход").assertCountEquals(0)
    assertEquals(emptyList<Long>(), addedSetTo)
    assertEquals(listOf(listOf(12L, 11L, 13L)), persistedOrders)

    composeRule.runOnIdle { workout.value = workout.value.reorderExercises(listOf(12L, 11L, 13L)) }
    composeRule.waitForIdle()

    assertAddSetIsAvailable()
    composeRule.onNodeWithText("Подход").performClick()
    assertEquals(listOf(12L), addedSetTo)
  }

  @Test
  fun `strength edit fields show prefilled weight and repetitions`() {
    renderCompletedEditFields(
        CompletedSetEditDraft(
            setId = FIRST_SET_ID,
            type = ExerciseType.STRENGTH,
            token = 1L,
            effort = SetEffort.TWO,
            weightKg = "72.5",
            reps = "8",
        ),
    )

    composeRule.onNodeWithText("Вес, кг").assertIsDisplayed()
    composeRule.onNodeWithText("Повторы").assertIsDisplayed()
    composeRule.onNodeWithText("72.5").assertIsDisplayed()
    composeRule.onNodeWithText("8").assertIsDisplayed()
  }

  @Test
  fun `strength edit fields change RIR together with completed values`() {
    var selected: SetEffort? = null
    renderCompletedEditFields(
        draft =
            CompletedSetEditDraft(
                setId = FIRST_SET_ID,
                type = ExerciseType.STRENGTH,
                token = 1L,
                effort = SetEffort.TWO,
            ),
        onEffortChange = { selected = it },
    )

    composeRule.onNodeWithTag("completed-set-effort").performClick()
    composeRule.onNodeWithText("Разминка").performClick()

    assertEquals(SetEffort.WARMUP, selected)
  }

  @Test
  fun `timed edit fields show only prefilled duration`() {
    renderCompletedEditFields(
        CompletedSetEditDraft(
            setId = FIRST_SET_ID,
            type = ExerciseType.TIMED,
            token = 1L,
            durationSec = "75",
        ),
    )

    composeRule.onNodeWithText("Длительность, секунды").assertIsDisplayed()
    composeRule.onNodeWithText("75").assertIsDisplayed()
    composeRule.onAllNodesWithText("Скорость, км/ч").assertCountEquals(0)
  }

  @Test
  fun `cardio edit fields show all prefilled actual values`() {
    renderCompletedEditFields(
        CompletedSetEditDraft(
            setId = FIRST_SET_ID,
            type = ExerciseType.CARDIO,
            token = 1L,
            durationSec = "360",
            speedKmh = "10.5",
            inclinePct = "4",
        ),
    )

    composeRule.onNodeWithText("Длительность, секунды").assertIsDisplayed()
    composeRule.onNodeWithText("Скорость, км/ч").assertIsDisplayed()
    composeRule.onNodeWithText("Наклон, %").assertIsDisplayed()
    composeRule.onNodeWithText("360").assertIsDisplayed()
    composeRule.onNodeWithText("10.5").assertIsDisplayed()
    composeRule.onNodeWithText("4").assertIsDisplayed()
  }

  @Test
  fun `nonfinite decimal draft disables saving`() {
    assertFalse(
        CompletedSetEditDraft(
                setId = FIRST_SET_ID,
                type = ExerciseType.CARDIO,
                token = 1L,
                speedKmh = "9".repeat(400),
            )
            .isValidNumericInput(),
    )
  }

  @Test
  fun `overflow menu exposes a labelled finish action with a 48 dp target`() {
    renderActiveWorkout(mutableStateOf(workoutWithIncompleteExercises()))

    composeRule.onNodeWithContentDescription("Завершить тренировку").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Действия тренировки").performClick()
    composeRule
        .onNodeWithText("Завершить тренировку")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun `bottom finish action follows the current Room completion snapshot`() {
    val workout = mutableStateOf(workoutWithIncompleteExercises().withoutSets())
    renderActiveWorkout(workout)

    composeRule.onAllNodesWithText("Завершить тренировку").assertCountEquals(0)

    composeRule.runOnIdle { workout.value = workoutWithIncompleteExercises() }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Завершить тренировку").assertCountEquals(0)

    composeRule.runOnIdle {
      workout.value =
          workout.value
              .markSetCompleted(FIRST_SET_ID)
              .markSetCompleted(SECOND_SET_ID)
              .markSetCompleted(THIRD_SET_ID)
    }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Завершить тренировку").assertCountEquals(1)

    composeRule.runOnIdle {
      workout.value =
          workout.value.copy(
              exercises =
                  workout.value.exercises.map { exercise ->
                    exercise.copy(
                        sets =
                            exercise.sets.map { set ->
                              if (set.id == FIRST_SET_ID) set.copy(isCompleted = false) else set
                            },
                    )
                  },
          )
    }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Завершить тренировку").assertCountEquals(0)
  }

  @Test
  fun `new incomplete Room exercise blocks finish before local order catches up`() {
    val completedWorkout =
        workoutWithIncompleteExercises()
            .markSetCompleted(FIRST_SET_ID)
            .markSetCompleted(SECOND_SET_ID)
            .markSetCompleted(THIRD_SET_ID)
    assertTrue(completedWorkout.exercises.areAllSetsCompleted())

    val updatedRoomWorkout =
        completedWorkout.copy(
            exercises =
                completedWorkout.exercises +
                    exercise(
                        position = 3,
                        workoutExerciseId = 14L,
                        setId = 104L,
                        name = "Свежий подход",
                    ),
        )

    assertFalse(updatedRoomWorkout.exercises.areAllSetsCompleted())
  }

  @Test
  fun `finish confirmation actions keep cancel separate and disable confirm in flight`() {
    val actions = mutableListOf<String>()
    val enabled = mutableStateOf(true)
    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        GymTheme {
          FinishWorkoutConfirmationActions(
              enabled = enabled.value,
              onConfirm = { actions += "finish" },
              onDismiss = { actions += "cancel" },
          )
        }
      }
    }

    composeRule.onNodeWithText("Отмена").performClick()
    composeRule.onNodeWithText("Завершить").performClick()
    assertEquals(listOf("cancel", "finish"), actions)

    composeRule.runOnIdle { enabled.value = false }
    composeRule.onNodeWithText("Отмена").assertIsNotEnabled()
    composeRule.onNodeWithText("Завершить").assertIsNotEnabled()
  }

  @Test
  fun `finishing state disables both finish initiators`() {
    val workout =
        workoutWithIncompleteExercises()
            .markSetCompleted(FIRST_SET_ID)
            .markSetCompleted(SECOND_SET_ID)
            .markSetCompleted(THIRD_SET_ID)
    renderActiveWorkout(mutableStateOf(workout), isFinishing = true)

    composeRule.onNodeWithText("Завершить тренировку").assertIsNotEnabled()
    composeRule.onNodeWithContentDescription("Действия тренировки").performClick()
    composeRule.onAllNodesWithText("Завершить тренировку").assertCountEquals(2)
    composeRule.onAllNodesWithText("Завершить тренировку")[0].assertIsNotEnabled()
    composeRule.onAllNodesWithText("Завершить тренировку")[1].assertIsNotEnabled()
  }

  private fun completeFocusedSet() {
    composeRule.onAllNodesWithText("Подход выполнен").assertCountEquals(1)
    composeRule.onNodeWithText("Подход выполнен").performClick()
    composeRule.waitForIdle()
  }

  private fun renderCompletedEditFields(
      draft: CompletedSetEditDraft,
      onEffortChange: (SetEffort) -> Unit = {},
  ) {
    composeRule.setContent {
      GymTheme {
        CompletedSetEditFields(
            draft = draft,
            onWeightChange = {},
            onRepsChange = {},
            onDurationChange = {},
            onSpeedChange = {},
            onInclineChange = {},
            onEffortChange = onEffortChange,
        )
      }
    }
  }

  private fun renderActiveWorkout(
      workout: androidx.compose.runtime.State<WorkoutFull>,
      isFinishing: Boolean = false,
      setActions: SetActions = noOpSetActions(),
  ) {
    composeRule.setContent {
      GymTheme {
        ActiveWorkoutContent(
            state =
                ActiveWorkoutUiState(
                    loading = false,
                    workout = workout.value,
                    isFinishing = isFinishing,
                ),
            elapsedSeconds = MutableStateFlow(0L),
            restTimer = MutableStateFlow<RestTimerState?>(null),
            heartRateState =
                MutableStateFlow<HeartRateConnectionState>(HeartRateConnectionState.Idle),
            heartRateReading = MutableStateFlow<HeartRateReading?>(null),
            setActions = setActions,
            onDeleteExercise = {},
            onReorderExercises = {},
            onAddExercise = {},
            onExerciseClick = {},
            onFinish = {},
            onDiscard = {},
            onAddRestSeconds = {},
            onSkipRest = {},
            onScanHeartRate = {},
            onConnectHeartRate = {},
            onCancelHeartRateSelection = {},
        )
      }
    }
  }

  private fun noOpSetActions(
      editNote: (Long) -> Unit = {},
      editPersonalHint: (Long) -> Unit = {},
      unpinPersonalHint: (Long) -> Unit = {},
      complete: (Long) -> Unit = {},
      setEffort: (Long, SetEffort?) -> Unit = { _, _ -> },
  ) =
      SetActions(
          stepWeight = { _, _ -> },
          stepReps = { _, _ -> },
          stepDuration = { _, _ -> },
          stepSpeed = { _, _ -> },
          stepIncline = { _, _ -> },
          setWeight = { _, _ -> },
          setReps = { _, _ -> },
          setDuration = { _, _ -> },
          setSpeed = { _, _ -> },
          setIncline = { _, _ -> },
          complete = complete,
          uncomplete = { _ -> },
          addSet = { _ -> },
          deleteSet = { _ -> },
          setEffort = setEffort,
          editNote = editNote,
          editPersonalHint = editPersonalHint,
          unpinPersonalHint = unpinPersonalHint,
      )

  private fun assertAddSetIsAvailable() {
    composeRule.onAllNodesWithText("Подход").assertCountEquals(1)
  }

  private fun customActionsFor(name: String) =
      generateSequence(
              composeRule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
          ) {
            it.parent
          }
          .first { it.config.contains(SemanticsActions.CustomActions) }
          .config[SemanticsActions.CustomActions]

  private fun workoutWithIncompleteExercises(): WorkoutFull =
      WorkoutFull(
          workout = WorkoutEntity(id = "active", name = "Тестовая тренировка", startedAt = 0L),
          exercises =
              listOf(
                  exercise(
                      position = 0,
                      workoutExerciseId = 11L,
                      setId = FIRST_SET_ID,
                      name = "Жим лёжа",
                  ),
                  exercise(
                      position = 1,
                      workoutExerciseId = 12L,
                      setId = SECOND_SET_ID,
                      name = "Тяга блока",
                  ),
                  exercise(
                      position = 2,
                      workoutExerciseId = 13L,
                      setId = THIRD_SET_ID,
                      name = "Присед",
                  ),
              ),
      )

  private fun exercise(
      position: Int,
      workoutExerciseId: Long,
      setId: Long,
      name: String,
  ): WorkoutExerciseWithSets =
      WorkoutExerciseWithSets(
          workoutExercise =
              WorkoutExerciseEntity(
                  id = workoutExerciseId,
                  workoutId = "active",
                  exerciseId = workoutExerciseId + 100L,
                  position = position,
              ),
          exercise =
              ExerciseEntity(
                  id = workoutExerciseId + 100L,
                  name = name,
                  muscleGroup = MuscleGroup.CHEST,
                  type = ExerciseType.STRENGTH,
              ),
          sets =
              listOf(
                  WorkoutSetEntity(
                      id = setId,
                      workoutExerciseId = workoutExerciseId,
                      setIndex = 0,
                      weightKg = 60.0,
                      reps = 8,
                      setType = "WORK",
                      actualRir = 2,
                  ),
              ),
      )

  private fun WorkoutFull.markSetCompleted(setId: Long): WorkoutFull =
      copy(
          exercises =
              exercises.map { exercise ->
                exercise.copy(
                    sets =
                        exercise.sets.map { set ->
                          if (set.id == setId) set.copy(isCompleted = true) else set
                        },
                )
              },
      )

  private fun WorkoutFull.updateSet(
      setId: Long,
      transform: (WorkoutSetEntity) -> WorkoutSetEntity,
  ): WorkoutFull =
      copy(
          exercises =
              exercises.map { exercise ->
                exercise.copy(
                    sets =
                        exercise.sets.map { set -> if (set.id == setId) transform(set) else set },
                )
              },
      )

  private fun WorkoutFull.withoutSets(): WorkoutFull =
      copy(exercises = exercises.map { exercise -> exercise.copy(sets = emptyList()) })

  private fun WorkoutFull.reorderExercises(orderedWorkoutExerciseIds: List<Long>): WorkoutFull =
      copy(
          exercises =
              orderedWorkoutExerciseIds.mapIndexed { position, workoutExerciseId ->
                val exercise = exercises.first { it.workoutExercise.id == workoutExerciseId }
                exercise.copy(
                    workoutExercise = exercise.workoutExercise.copy(position = position),
                )
              },
      )

  private companion object {
    const val FIRST_SET_ID = 101L
    const val SECOND_SET_ID = 102L
    const val THIRD_SET_ID = 103L
  }
}
