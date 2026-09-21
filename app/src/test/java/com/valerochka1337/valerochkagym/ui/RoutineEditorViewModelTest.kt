package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEquipmentEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.routine.RoutineEditorViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [RoutineEditorViewModel]. The editor keeps every edit in memory and only touches
 * the DAOs on [RoutineEditorViewModel.save] and on load, so a [FakeRoutineDao] and a
 * [FakeExerciseDao] backed by plain state are enough — no Room, no Robolectric. Loading and
 * `addExerciseById` run inside `viewModelScope`, so the [MainDispatcherRule]'s unconfined
 * dispatcher lets the test read `uiState.value` right after the triggering call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  // region new routine

  @Test
  fun `a new routine starts empty and invalid`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

        val state = viewModel.uiState.value
        assertTrue(state.isNew)
        assertEquals("", state.name)
        assertEquals(emptyList<Any>(), state.exercises)
        assertFalse(state.isValid)
      }

  @Test
  fun `save does nothing while the state is invalid`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao()
        val viewModel = RoutineEditorViewModel(SavedStateHandle(), routineDao, FakeExerciseDao())

        viewModel.save()

        assertEquals(0, routineDao.upsertCount)
        assertNull(routineDao.lastReplacedRoutineId)
      }

  // endregion

  // region loading an existing routine

  @Test
  fun `loading an existing routine maps its fields and sorts exercises by position`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(id = 1, name = "Приседания", type = ExerciseType.STRENGTH)
        val plank = exercise(id = 2, name = "Планка", type = ExerciseType.TIMED)
        val routine =
            RoutineWithExercises(
                routine = RoutineEntity(id = 5, name = "День ног", note = "разминка"),
                exercises =
                    listOf(
                        routineExercise(
                            plank,
                            position = 1,
                            restSeconds = 30,
                            plannedSets = listOf(PlannedSet(durationSec = 60)),
                        ),
                        routineExercise(
                            squat,
                            position = 0,
                            restSeconds = 90,
                            plannedSets = listOf(PlannedSet(reps = 5)),
                        ),
                    ),
            )
        val viewModel =
            RoutineEditorViewModel(
                savedStateHandleFor(5),
                FakeRoutineDao(listOf(routine)),
                FakeExerciseDao(),
            )

        val state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertEquals("День ног", state.name)
        assertEquals("разминка", state.note)
        assertEquals(listOf("Приседания", "Планка"), state.exercises.map { it.exerciseName })
        val first = state.exercises.first()
        assertEquals(1L, first.exerciseId)
        assertEquals(ExerciseType.STRENGTH, first.exerciseType)
        assertEquals(90, first.restSeconds)
        assertEquals(listOf(PlannedSet(reps = 5)), first.plannedSets)
      }

  @Test
  fun `loading and saving an existing routine keeps its gym selection in the repository draft`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(id = 1, name = "Приседания")
        val firstGym = gym("gym-first", "Первый", listOf(squat))
        val secondGym = gym("gym-second", "Второй", listOf(squat))
        val routine =
            RoutineWithExercises(
                routine = RoutineEntity(id = 5, name = "День ног"),
                exercises =
                    listOf(
                        routineExercise(
                            squat,
                            position = 0,
                            restSeconds = 90,
                            plannedSets = listOf(PlannedSet(reps = 5)),
                        ),
                    ),
                gyms = listOf(GymEntity(id = 10, syncId = firstGym.id, name = firstGym.name)),
            )
        val repository = FakeGymRepository(listOf(firstGym, secondGym))
        val viewModel =
            RoutineEditorViewModel(
                savedStateHandle = savedStateHandleFor(5),
                routineDao = FakeRoutineDao(listOf(routine)),
                exerciseDao = FakeExerciseDao(),
                gymRepository = repository,
            )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("gym-first"), viewModel.uiState.value.selectedGymIds)

        viewModel.toggleGym("gym-second")
        viewModel.save()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("gym-first", "gym-second"), repository.lastRoutineDraft?.gymIds)
        assertEquals(5L, repository.lastRoutineDraft?.routine?.id)
        assertEquals(listOf(1L), repository.lastRoutineDraft?.exercises?.map { it.exerciseId })
      }

  @Test
  fun `standard editor ignores every draft mutation and save`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(id = 1, name = "Приседания")
        val replacement = exercise(id = 2, name = "Выпады")
        val routine =
            RoutineWithExercises(
                routine = RoutineEntity(id = 5, name = "Стандарт", origin = "STANDARD"),
                exercises =
                    listOf(
                        routineExercise(
                            squat,
                            position = 0,
                            restSeconds = 90,
                            plannedSets = listOf(PlannedSet(reps = 5)),
                        )
                    ),
            )
        val routineDao = FakeRoutineDao(listOf(routine))
        val viewModel =
            RoutineEditorViewModel(
                savedStateHandleFor(5),
                routineDao,
                FakeExerciseDao(listOf(replacement)),
            )
        val before = viewModel.uiState.value

        viewModel.setName("Изменено")
        viewModel.toggleGym("gym")
        viewModel.addExercise(replacement)
        viewModel.addExerciseById(replacement.id)
        viewModel.removeExercise(0)
        viewModel.moveExercise(0, 0)
        viewModel.setRest(0, 30)
        viewModel.addPlannedSet(0)
        viewModel.removePlannedSet(0, 0)
        viewModel.updatePlannedSet(0, 0, PlannedSet(reps = 12))
        viewModel.save()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value)
        assertEquals(0, routineDao.upsertCount)
        assertNull(routineDao.lastReplacedRoutineId)
      }

  // endregion

  // region editing exercises

  @Test
  fun `addExercise seeds three empty sets for a strength exercise`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

        viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))

        val added = viewModel.uiState.value.exercises.single()
        assertEquals(List(3) { PlannedSet() }, added.plannedSets)
      }

  @Test
  fun `unbound routine keeps exercises free of availability conflicts`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

        viewModel.addExercise(exercise(id = 1, name = "Приседания"))
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCheckingAvailability)
        assertEquals(emptyList<Any>(), viewModel.uiState.value.conflictingExercises)
      }

  @Test
  fun `addExercise seeds a single set for timed and cardio exercises`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

        viewModel.addExercise(exercise(id = 1, name = "Планка", type = ExerciseType.TIMED))
        viewModel.addExercise(exercise(id = 2, name = "Бег", type = ExerciseType.CARDIO))

        val sizes = viewModel.uiState.value.exercises.map { it.plannedSets.size }
        assertEquals(listOf(1, 1), sizes)
      }

  @Test
  fun `adding the same exercise twice gives each editor row a unique key`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        val squat = exercise(id = 44, name = "Приседания", type = ExerciseType.STRENGTH)

        viewModel.addExercise(squat)
        viewModel.addExercise(squat)

        val rows = viewModel.uiState.value.exercises
        assertEquals(listOf(44L, 44L), rows.map { it.exerciseId })
        assertEquals(rows.size, rows.map { it.editorId }.toSet().size)
      }

  @Test
  fun `removeExercise drops the exercise at the index`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Первое"))
        viewModel.addExercise(exercise(id = 2, name = "Второе"))

        viewModel.removeExercise(0)

        assertEquals(listOf("Второе"), viewModel.uiState.value.exercises.map { it.exerciseName })
      }

  @Test
  fun `moveExercise moves an arbitrary exercise and keeps its card data`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Первое"))
        viewModel.addExercise(exercise(id = 2, name = "Второе"))
        viewModel.addExercise(exercise(id = 3, name = "Третье"))
        viewModel.setRest(2, 75)
        viewModel.updatePlannedSet(2, 0, PlannedSet(weightKg = 42.5, reps = 12))

        viewModel.moveExercise(2, 0)

        val exercises = viewModel.uiState.value.exercises
        assertEquals(listOf("Третье", "Первое", "Второе"), exercises.map { it.exerciseName })
        assertEquals(75, exercises.first().restSeconds)
        assertEquals(PlannedSet(weightKg = 42.5, reps = 12), exercises.first().plannedSets.first())
      }

  @Test
  fun `moveExercise ignores indexes outside the exercise list`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Первое"))
        viewModel.addExercise(exercise(id = 2, name = "Второе"))

        viewModel.moveExercise(0, -1)
        viewModel.moveExercise(0, 2)
        viewModel.moveExercise(-1, 0)
        viewModel.moveExercise(2, 0)

        assertEquals(
            listOf("Первое", "Второе"),
            viewModel.uiState.value.exercises.map { it.exerciseName },
        )
      }

  @Test
  fun `addPlannedSet copies the last set`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))
        val lastSet = PlannedSet(weightKg = 60.0, reps = 8)
        viewModel.updatePlannedSet(0, 2, lastSet)

        viewModel.addPlannedSet(0)

        val sets = viewModel.uiState.value.exercises.single().plannedSets
        assertEquals(4, sets.size)
        assertEquals(lastSet, sets.last())
      }

  @Test
  fun `removePlannedSet drops the set at the index`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))
        viewModel.updatePlannedSet(0, 0, PlannedSet(reps = 5))

        viewModel.removePlannedSet(0, 0)

        val sets = viewModel.uiState.value.exercises.single().plannedSets
        assertEquals(listOf(PlannedSet(), PlannedSet()), sets)
      }

  @Test
  fun `updatePlannedSet replaces the set at the index`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))

        val updated = PlannedSet(weightKg = 80.0, reps = 3)
        viewModel.updatePlannedSet(0, 1, updated)

        assertEquals(updated, viewModel.uiState.value.exercises.single().plannedSets[1])
      }

  @Test
  fun `setRest updates the rest seconds of the exercise`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))

        viewModel.setRest(0, 75)

        assertEquals(75, viewModel.uiState.value.exercises.single().restSeconds)
      }

  @Test
  fun `addExerciseById adds the exercise fetched from the library`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val curl = exercise(id = 7, name = "Сгибания", type = ExerciseType.STRENGTH)
        val viewModel =
            RoutineEditorViewModel(
                SavedStateHandle(),
                FakeRoutineDao(),
                FakeExerciseDao(listOf(curl)),
            )

        viewModel.addExerciseById(7)

        val added = viewModel.uiState.value.exercises.single()
        assertEquals(7L, added.exerciseId)
        assertEquals("Сгибания", added.exerciseName)
        assertEquals(3, added.plannedSets.size)
      }

  // endregion

  // region saving

  @Test
  fun `selecting gyms with different catalogues exposes conflicts and blocks save`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(id = 1, name = "Приседания")
        val legPress = exercise(id = 2, name = "Жим ногами")
        val repository =
            FakeGymRepository(
                listOf(
                    gym("gym-full", "Полный", listOf(squat, legPress)),
                    gym("gym-small", "Малый", listOf(squat)),
                ),
            )
        val viewModel =
            RoutineEditorViewModel(
                savedStateHandle = SavedStateHandle(),
                routineDao = FakeRoutineDao(),
                exerciseDao = FakeExerciseDao(),
                gymRepository = repository,
            )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setName("Ноги")
        viewModel.addExercise(squat)
        viewModel.addExercise(legPress)

        viewModel.toggleGym("gym-full")
        viewModel.toggleGym("gym-small")

        val state = viewModel.uiState.value
        assertEquals(listOf("Жим ногами"), state.conflictingExercises.map { it.exerciseName })
        assertFalse(state.isValid)

        viewModel.save()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.lastRoutineDraft)
      }

  @Test
  fun `configured gym availability follows inventory requirements instead of empty legacy links`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dumbbellBenchPress = exercise(id = 17, name = "Жим гантелей лёжа")
        val unrelated = exercise(id = 18, name = "Приседания")
        val configuredGym =
            GymConfiguration(
                id = "home",
                name = "Дом",
                exercises = emptyList(),
                equipmentIds = setOf("dumbbells", "flat_bench"),
                inventoryConfigured = true,
            )
        val repository = FakeGymRepository(listOf(configuredGym))
        repository.setAvailable(setOf("home"), listOf(dumbbellBenchPress, unrelated))
        val viewModel =
            RoutineEditorViewModel(
                SavedStateHandle(),
                FakeRoutineDao(),
                FakeExerciseDao(),
                gymRepository = repository,
            )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setName("Домашняя")
        viewModel.addExercise(dumbbellBenchPress)
        viewModel.toggleGym("home")
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(emptyList<Any>(), viewModel.uiState.value.conflictingExercises)

        repository.setAvailable(setOf("home"), listOf(unrelated))
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("Жим гантелей лёжа"),
            viewModel.uiState.value.conflictingExercises.map { it.exerciseName },
        )
      }

  @Test
  fun `gym metadata update preserves the latest availability conflict`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val unavailable = exercise(id = 19, name = "Жим ногами")
        val original = gym("home", "Дом", emptyList())
        val repository = FakeGymRepository(listOf(original))
        val viewModel =
            RoutineEditorViewModel(
                SavedStateHandle(),
                FakeRoutineDao(),
                FakeExerciseDao(),
                gymRepository = repository,
            )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setName("Домашняя")
        viewModel.addExercise(unavailable)
        viewModel.toggleGym("home")
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("Жим ногами"),
            viewModel.uiState.value.conflictingExercises.map { it.exerciseName },
        )

        repository.setGyms(listOf(original.copy(name = "Дом у парка")))
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCheckingAvailability)
        assertEquals(
            listOf("Жим ногами"),
            viewModel.uiState.value.conflictingExercises.map { it.exerciseName },
        )
      }

  @Test
  fun `a repository conflict keeps the editor open and shows the unavailable exercises`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val legPress = exercise(id = 2, name = "Жим ногами")
        val repository =
            FakeGymRepository(
                gyms = listOf(gym("gym", "Основной", listOf(legPress))),
                routineSaveResult = SaveRoutineConfigurationResult.Conflict(listOf(legPress)),
            )
        val viewModel =
            RoutineEditorViewModel(
                savedStateHandle = SavedStateHandle(),
                routineDao = FakeRoutineDao(),
                exerciseDao = FakeExerciseDao(),
                gymRepository = repository,
            )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setName("Ноги")
        viewModel.addExercise(legPress)
        viewModel.toggleGym("gym")

        viewModel.save()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("Жим ногами"),
            viewModel.uiState.value.conflictingExercises.map { it.exerciseName },
        )
        assertEquals(
            "Некоторые упражнения недоступны во всех выбранных залах",
            viewModel.uiState.value.saveError,
        )
      }

  @Test
  fun `save on a new routine upserts a zero id and reindexes positions`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao()
        val viewModel = RoutineEditorViewModel(SavedStateHandle(), routineDao, FakeExerciseDao())
        viewModel.setName("  План  ")
        viewModel.addExercise(exercise(id = 1, name = "Первое"))
        viewModel.addExercise(exercise(id = 2, name = "Второе"))
        viewModel.moveExercise(1, 0)

        viewModel.save()

        assertEquals(0L, routineDao.lastUpsertedRoutine?.id)
        assertEquals("План", routineDao.lastUpsertedRoutine?.name)
        assertEquals("", routineDao.lastUpsertedRoutine?.note)
        // The DAO hands back a fresh id (1) for the zero-id insert; the exercises must be written
        // against that returned id, not against `routineId ?: 0`, which would slip through as 0.
        assertEquals(1L, routineDao.lastReplacedRoutineId)
        assertEquals(listOf(1L), routineDao.lastReplacedExercises.map { it.routineId }.distinct())
        assertEquals(listOf(2L, 1L), routineDao.lastReplacedExercises.map { it.exerciseId })
        assertEquals(listOf(0, 1), routineDao.lastReplacedExercises.map { it.position })
      }

  @Test
  fun `save on an existing routine trims the name and keeps the note and id`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(id = 1, name = "Приседания", type = ExerciseType.STRENGTH)
        val routine =
            RoutineWithExercises(
                routine = RoutineEntity(id = 5, name = "День ног", note = "разминка"),
                exercises =
                    listOf(
                        routineExercise(
                            squat,
                            position = 0,
                            restSeconds = 90,
                            plannedSets = listOf(PlannedSet(reps = 5)),
                        )
                    ),
            )
        val routineDao = FakeRoutineDao(listOf(routine))
        val viewModel =
            RoutineEditorViewModel(savedStateHandleFor(5), routineDao, FakeExerciseDao())
        viewModel.setName("  Новое имя  ")

        viewModel.save()

        assertEquals(5L, routineDao.lastUpsertedRoutine?.id)
        assertEquals("Новое имя", routineDao.lastUpsertedRoutine?.name)
        assertEquals("разминка", routineDao.lastUpsertedRoutine?.note)
        assertEquals(5L, routineDao.lastReplacedRoutineId)
        assertEquals(listOf(0), routineDao.lastReplacedExercises.map { it.position })
      }

  @Test
  fun `save emits the saved event`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.setName("План")
        viewModel.addExercise(exercise(id = 1, name = "Первое"))
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.saved.collect { events += it }
        }

        viewModel.save()

        assertEquals(1, events.size)
      }

  // endregion

  private fun savedStateHandleFor(routineId: Long): SavedStateHandle =
      SavedStateHandle(mapOf(GymRoutes.ROUTINE_ID_ARG to routineId.toString()))

  private fun exercise(
      id: Long,
      name: String,
      type: ExerciseType = ExerciseType.STRENGTH,
  ): ExerciseEntity =
      ExerciseEntity(id = id, name = name, muscleGroup = MuscleGroup.LEGS, type = type)

  private fun gym(id: String, name: String, exercises: List<ExerciseEntity>): GymConfiguration =
      GymConfiguration(id = id, name = name, exercises = exercises)

  private fun routineExercise(
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
   * In-memory [RoutineDao] backed by a [MutableStateFlow]. [upsertRoutine] assigns a fresh id to a
   * zero-id routine and records the request; [replaceRoutineExercises] and [deleteRoutine] record
   * their arguments so the tests can assert on what the view model asked the DAO to persist. The
   * exercise-row helpers are not exercised by the editor and are left as no-ops.
   */
  private class FakeRoutineDao(initial: List<RoutineWithExercises> = emptyList()) : RoutineDao {

    private val routines = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.routine.id } ?: 0L) + 1

    var upsertCount = 0
      private set

    var lastUpsertedRoutine: RoutineEntity? = null
      private set

    var lastReplacedRoutineId: Long? = null
      private set

    var lastReplacedExercises: List<RoutineExerciseEntity> = emptyList()
      private set

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
      upsertCount++
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
      routines.value = routines.value.filterNot { it.routine.id == id }
    }

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }

  /**
   * In-memory [ExerciseDao] over a fixed catalogue; only [getById] is used by the editor, the rest
   * are the simplest correct implementations over the backing list.
   */
  private class FakeExerciseDao(private val items: List<ExerciseEntity> = emptyList()) :
      ExerciseDao {

    override fun getAll(): Flow<List<ExerciseEntity>> = MutableStateFlow(items)

    override suspend fun insert(exercise: ExerciseEntity): Long = 0

    override suspend fun update(exercise: ExerciseEntity) = Unit

    override suspend fun insertAll(exercises: List<ExerciseEntity>) = Unit

    override suspend fun count(): Int = items.size

    override suspend fun getById(id: Long): ExerciseEntity? = items.find { it.id == id }

    override suspend fun getAllOnce(): List<ExerciseEntity> = items

    private val requirements = MutableStateFlow<List<ExerciseEquipmentEntity>>(emptyList())

    override suspend fun getRequirementIds(exerciseId: Long): List<String> =
        requirements.value.filter { it.exerciseId == exerciseId }.map { it.equipmentId }

    override suspend fun getRequirements(exerciseIds: List<Long>): List<ExerciseEquipmentEntity> =
        requirements.value.filter { it.exerciseId in exerciseIds }

    override fun observeAllRequirements(): Flow<List<ExerciseEquipmentEntity>> = requirements

    override suspend fun insertRequirements(requirements: List<ExerciseEquipmentEntity>) {
      this.requirements.value += requirements
    }

    override suspend fun deleteRequirements(exerciseId: Long) {
      requirements.value = requirements.value.filterNot { it.exerciseId == exerciseId }
    }
  }

  private class FakeGymRepository(
      gyms: List<GymConfiguration>,
      private val routineSaveResult: SaveRoutineConfigurationResult? = null,
  ) : GymRepository {
    private val gymsFlow = MutableStateFlow(gyms)
    private val availableOverrides =
        MutableStateFlow<Map<Set<String>, List<ExerciseEntity>>>(emptyMap())

    var lastRoutineDraft: RoutineConfigurationDraft? = null
      private set

    override fun observeGyms(): Flow<List<GymConfiguration>> = gymsFlow

    override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> =
        MutableStateFlow(emptyList())

    fun setAvailable(gymIds: Set<String>, exercises: List<ExerciseEntity>) {
      availableOverrides.value = availableOverrides.value + (gymIds to exercises)
    }

    fun setGyms(gyms: List<GymConfiguration>) {
      gymsFlow.value = gyms
    }

    override fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> =
        combine(gymsFlow, availableOverrides) { gyms, overrides ->
          overrides[gymIds]
              ?: gyms
                  .filter { it.id in gymIds }
                  .takeIf { it.size == gymIds.size }
                  ?.let { selected ->
                    selected.firstOrNull()?.exercises.orEmpty().filter { exercise ->
                      selected.all { gym -> gym.exercises.any { it.id == exercise.id } }
                    }
                  }
                  .orEmpty()
        }

    override suspend fun getGym(id: String): GymConfiguration? = gymsFlow.value.find { it.id == id }

    override suspend fun saveGym(
        id: String?,
        name: String,
        exerciseIds: Set<Long>,
    ): SaveGymResult = SaveGymResult.Failure

    override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.NotFound

    override suspend fun saveRoutineConfiguration(
        draft: RoutineConfigurationDraft,
    ): SaveRoutineConfigurationResult {
      lastRoutineDraft = draft
      return routineSaveResult
          ?: SaveRoutineConfigurationResult.Saved(
              routineId = draft.routine.id.takeUnless { it == 0L } ?: 1L,
              routine = draft.routine,
          )
    }
  }
}
