package com.valerochka1337.valerochkagym.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.ui.workouts.WorkoutsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [WorkoutsViewModel]. Duration estimation and selection happen in memory in the
 * view model, so a [FakeRoutineDao] backed by a [MutableStateFlow] plus a real [SettingsRepository]
 * over an in-memory [DataStore] is enough — no Room, no Robolectric. `uiState` is produced by
 * `stateIn(WhileSubscribed(5000))`, which stays cold until it has a subscriber; every test that
 * reads it first attaches a live collector via [collectUiState] so the upstream `combine` keeps
 * running and `uiState.value` reflects the latest routines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  // region card mapping

  @Test
  fun `routines are mapped to cards with name count and estimated minutes`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        // 3 sets, rest 60 -> 3*45 + 3*60 = 315; 2 sets, default rest 90 -> 2*45 + 2*90 = 270; total
        // 585s -> 10 min.
        val routine =
            routineWithExercises(
                id = 1,
                name = "День ног",
                exercises =
                    listOf(
                        routineExercise(
                            exercise(1, "Приседания"),
                            position = 0,
                            restSeconds = 60,
                            sets = 3,
                        ),
                        routineExercise(
                            exercise(2, "Выпады"),
                            position = 1,
                            restSeconds = null,
                            sets = 2,
                        ),
                    ),
                gyms =
                    listOf(
                        GymEntity(id = 2, syncId = "gym-second", name = "Яблоко"),
                        GymEntity(id = 1, syncId = "gym-first", name = "Альфа"),
                    ),
            )
        val viewModel =
            WorkoutsViewModel(
                FakeRoutineDao(listOf(routine)),
                settingsRepository(defaultRestSeconds = 90),
                FakeActiveWorkoutRepository(),
            )
        collectUiState(viewModel)

        val card = viewModel.uiState.value.routines!!.single()
        assertEquals("День ног", card.name)
        assertEquals(2, card.exerciseCount)
        assertEquals(9, card.estimatedMinutes)
        assertEquals(listOf("Альфа", "Яблоко"), card.gymNames)
      }

  // endregion

  // region selection

  @Test
  fun `selecting a routine and reselecting it clears the selection`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            WorkoutsViewModel(
                FakeRoutineDao(listOf(routineWithExercises(id = 1, name = "День ног"))),
                settingsRepository(),
                FakeActiveWorkoutRepository(),
            )
        collectUiState(viewModel)

        viewModel.onRoutineSelected(1)
        assertEquals(1L, viewModel.uiState.value.selectedRoutineId)

        viewModel.onRoutineSelected(1)
        assertNull(viewModel.uiState.value.selectedRoutineId)
      }

  @Test
  fun `deleting the selected routine clears the selection`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao(listOf(routineWithExercises(id = 1, name = "День ног")))
        val viewModel =
            WorkoutsViewModel(routineDao, settingsRepository(), FakeActiveWorkoutRepository())
        collectUiState(viewModel)
        viewModel.onRoutineSelected(1)

        viewModel.delete(1)

        assertNull(viewModel.uiState.value.selectedRoutineId)
      }

  // endregion

  // region duplicate and delete

  @Test
  fun `duplicate copies the whole tree with a suffixed name reset ids and reindexed positions`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squatSets = listOf(PlannedSet(reps = 5), PlannedSet(reps = 5))
        val lungeSets = listOf(PlannedSet(reps = 8))
        val routine =
            routineWithExercises(
                id = 4,
                name = "День ног",
                note = "разминка",
                exercises =
                    listOf(
                        routineExerciseFull(
                            exercise(1, "Приседания"),
                            position = 0,
                            restSeconds = 90,
                            plannedSets = squatSets,
                        ),
                        routineExerciseFull(
                            exercise(2, "Выпады"),
                            position = 1,
                            restSeconds = null,
                            plannedSets = lungeSets,
                        ),
                    ),
            )
        val routineDao = FakeRoutineDao(listOf(routine))
        val viewModel =
            WorkoutsViewModel(
                routineDao = routineDao,
                settingsRepository = settingsRepository(),
                activeWorkoutRepository = FakeActiveWorkoutRepository(),
            )

        viewModel.duplicate(4)

        assertEquals("День ног (копия)", routineDao.lastUpsertedRoutine?.name)
        assertEquals("разминка", routineDao.lastUpsertedRoutine?.note)
        assertEquals(0L, routineDao.lastUpsertedRoutine?.id)
        val copied = routineDao.lastReplacedExercises
        assertEquals(
            routineDao.lastReplacedRoutineId,
            copied.map { it.routineId }.distinct().single(),
        )
        assertTrue(copied.all { it.id == 0L })
        assertEquals(listOf(0, 1), copied.map { it.position })
        assertEquals(listOf(1L, 2L), copied.map { it.exerciseId })
        assertEquals(listOf(90, null), copied.map { it.restSeconds })
        assertEquals(listOf(squatSets, lungeSets), copied.map { it.plannedSets })
      }

  @Test
  fun `delete forwards the id to the dao`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao(listOf(routineWithExercises(id = 3, name = "День ног")))

        val viewModel =
            WorkoutsViewModel(routineDao, settingsRepository(), FakeActiveWorkoutRepository())

        viewModel.delete(3)

        assertEquals(listOf(3L), routineDao.deletedIds)
      }

  @Test
  fun `permission recovery continues each action once while allowing a new action for the same workout`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val matchingRepository = FakeActiveWorkoutRepository(activeWorkoutId = "expected")
        val matching = WorkoutsViewModel(FakeRoutineDao(), settingsRepository(), matchingRepository)
        val matchingEvents = collectStartReadyEvents(matching)

        matching.continueStartedWorkout("11", "expected")
        matching.continueStartedWorkout("11", "expected")
        matching.continueStartedWorkout("12", "expected")
        runCurrent()

        assertEquals(listOf("expected", "expected"), matchingEvents)

        val different =
            WorkoutsViewModel(
                FakeRoutineDao(),
                settingsRepository(),
                FakeActiveWorkoutRepository(activeWorkoutId = "different"),
            )
        val missing =
            WorkoutsViewModel(FakeRoutineDao(), settingsRepository(), FakeActiveWorkoutRepository())
        val differentEvents = collectStartReadyEvents(different)
        val missingEvents = collectStartReadyEvents(missing)

        different.continueStartedWorkout("13", "expected")
        missing.continueStartedWorkout("14", "expected")
        runCurrent()

        assertTrue(differentEvents.isEmpty())
        assertTrue(missingEvents.isEmpty())
      }

  // endregion

  private fun TestScope.collectUiState(viewModel: WorkoutsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
  }

  private fun TestScope.collectStartReadyEvents(viewModel: WorkoutsViewModel): List<String> {
    val events = mutableListOf<String>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      viewModel.startReadyEvents.collect { events += it }
    }
    return events
  }

  private fun settingsRepository(defaultRestSeconds: Int? = null): SettingsRepository {
    val prefs =
        if (defaultRestSeconds == null) {
          emptyPreferences()
        } else {
          mutablePreferencesOf(intPreferencesKey("default_rest_seconds") to defaultRestSeconds)
        }
    return SettingsRepository(FakeDataStore(prefs))
  }

  private fun exercise(id: Long, name: String): ExerciseEntity =
      ExerciseEntity(
          id = id,
          name = name,
          muscleGroup = MuscleGroup.LEGS,
          type = ExerciseType.STRENGTH,
      )

  private fun routineWithExercises(
      id: Long,
      name: String,
      note: String = "",
      exercises: List<RoutineExerciseWithExercise> = emptyList(),
      gyms: List<GymEntity> = emptyList(),
  ): RoutineWithExercises =
      RoutineWithExercises(
          routine = RoutineEntity(id = id, name = name, note = note),
          exercises = exercises,
          gyms = gyms,
      )

  /**
   * Builds a relation row with [sets] empty planned sets — for the duration formula, only the count
   * matters.
   */
  private fun routineExercise(
      exercise: ExerciseEntity,
      position: Int,
      restSeconds: Int?,
      sets: Int,
  ): RoutineExerciseWithExercise =
      routineExerciseFull(exercise, position, restSeconds, List(sets) { PlannedSet() })

  private fun routineExerciseFull(
      exercise: ExerciseEntity,
      position: Int,
      restSeconds: Int?,
      plannedSets: List<PlannedSet>,
  ): RoutineExerciseWithExercise =
      RoutineExerciseWithExercise(
          routineExercise =
              RoutineExerciseEntity(
                  routineId = 0,
                  exerciseId = exercise.id,
                  position = position,
                  restSeconds = restSeconds,
                  plannedSets = plannedSets,
              ),
          exercise = exercise,
      )

  /**
   * In-memory [RoutineDao] backed by a [MutableStateFlow]. duplicate and delete go through
   * [getRoutineWithExercises], [upsertRoutine], [replaceRoutineExercises] and [deleteRoutine]; each
   * records its request so the tests can assert on what the view model persisted. [deleteRoutine]
   * also drops the routine from the observed list so the selection-reset path can be verified.
   */
  private class FakeRoutineDao(initial: List<RoutineWithExercises> = emptyList()) : RoutineDao {

    private val routines = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.routine.id } ?: 0L) + 1

    var lastUpsertedRoutine: RoutineEntity? = null
      private set

    var lastReplacedRoutineId: Long? = null
      private set

    var lastReplacedExercises: List<RoutineExerciseEntity> = emptyList()
      private set

    val deletedIds = mutableListOf<Long>()

    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> =
        MutableStateFlow(emptyList())

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = routines

    override suspend fun routinesFullOnce(): List<RoutineWithExercises> =
        observeRoutinesFull().first()

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? =
        routines.value.find { it.routine.id == id }

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? =
        routines.value.find { it.routine.syncId == syncId }?.routine

    override suspend fun getRoutineName(id: Long): String? =
        routines.value.find { it.routine.id == id }?.routine?.name

    override suspend fun upsertRoutine(routine: RoutineEntity): Long {
      lastUpsertedRoutine = routine
      val id = if (routine.id == 0L) nextId++ else routine.id
      routines.value =
          routines.value.filterNot { it.routine.id == id } +
              RoutineWithExercises(routine.copy(id = id), emptyList())
      return id
    }

    override suspend fun replaceRoutineExercises(
        routineId: Long,
        list: List<RoutineExerciseEntity>,
    ) {
      lastReplacedRoutineId = routineId
      lastReplacedExercises = list
    }

    override suspend fun deleteRoutine(id: Long) {
      deletedIds += id
      routines.value = routines.value.filterNot { it.routine.id == id }
    }

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }

  /** In-memory [ActiveWorkoutRepository]: counts [startFromRoutine] calls. */
  private class FakeActiveWorkoutRepository(activeWorkoutId: String? = null) :
      ActiveWorkoutRepository {
    var startFromRoutineCalls = 0
      private set

    private val active = MutableStateFlow(activeWorkoutId?.let(::activeWorkout))

    override suspend fun startFromRoutine(routineId: Long): String {
      startFromRoutineCalls++
      return "workout"
    }

    override suspend fun startEmpty(): String = "workout"

    override fun observeActive(): Flow<WorkoutFull?> = active

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = null

    override suspend fun updateSet(set: WorkoutSetEntity) = Unit

    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) = Unit

    override suspend fun addSet(workoutExerciseId: Long) = Unit

    override suspend fun deleteSet(setId: Long) = Unit

    override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = 0

    override suspend fun deleteExercise(workoutExerciseId: Long) = Unit

    override suspend fun reorderExercises(
        workoutId: String,
        orderedWorkoutExerciseIds: List<Long>,
    ) = Unit

    override suspend fun finish(workoutId: String) = Unit

    override suspend fun discard(workoutId: String) = Unit

    private fun activeWorkout(id: String) =
        WorkoutFull(
            workout = WorkoutEntity(id = id, name = "Тренировка", startedAt = 0L),
            exercises = emptyList(),
        )
  }

  /**
   * Minimal in-memory [DataStore] emitting a fixed set of [Preferences] so a real
   * [SettingsRepository] can read them.
   */
  private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {

    private val state = MutableStateFlow(prefs)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
      state.value = transform(state.value)
      return state.value
    }
  }
}
