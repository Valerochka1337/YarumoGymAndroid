package com.valerochka1337.valerochkagym.ui.routine

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineDetailViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `detail follows Room updates with ordered exercises gyms and planned sets`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeRoutineDao(listOf(routine()))
        val viewModel =
            RoutineDetailViewModel(SavedStateHandle(mapOf(GymRoutes.ROUTINE_ID_ARG to 7L)), dao)
        collectUiState(viewModel)

        val content = viewModel.uiState.value.routine!!
        assertEquals(listOf("Дом", "Зал"), content.gymNames)
        assertEquals(listOf("Приседания", "Планка"), content.exercises.map { it.name })
        assertEquals(90, content.exercises.first().restSeconds)
        assertEquals(
            listOf(PlannedSet(weightKg = 80.0, reps = 5)),
            content.exercises.first().plannedSets,
        )

        dao.set(emptyList())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.routine)
      }

  @Test
  fun `missing detail reports missing and retry replaces an error with content`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = RetryRoutineDao(listOf(routine()))
        val viewModel =
            RoutineDetailViewModel(SavedStateHandle(mapOf(GymRoutes.ROUTINE_ID_ARG to 7L)), dao)
        collectUiState(viewModel)

        assertTrue(viewModel.uiState.value.loadError)

        viewModel.retry()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loadError)
        assertEquals("Ноги", viewModel.uiState.value.routine?.name)
      }

  private fun TestScope.collectUiState(viewModel: RoutineDetailViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
  }

  private fun routine() =
      RoutineWithExercises(
          routine = RoutineEntity(id = 7, name = "Ноги", note = "Разминка"),
          exercises =
              listOf(
                  row(2, "Планка", ExerciseType.TIMED, 1, 30, listOf(PlannedSet(durationSec = 60))),
                  row(
                      1,
                      "Приседания",
                      ExerciseType.STRENGTH,
                      0,
                      90,
                      listOf(PlannedSet(weightKg = 80.0, reps = 5)),
                  ),
              ),
          gyms =
              listOf(
                  GymEntity(id = 1, syncId = "gym-1", name = "Зал"),
                  GymEntity(id = 2, syncId = "gym-2", name = "Дом"),
              ),
      )

  private fun row(
      id: Long,
      name: String,
      type: ExerciseType,
      position: Int,
      rest: Int?,
      sets: List<PlannedSet>,
  ) =
      RoutineExerciseWithExercise(
          RoutineExerciseEntity(
              routineId = 7,
              exerciseId = id,
              position = position,
              restSeconds = rest,
              plannedSets = sets,
          ),
          ExerciseEntity(id, name, MuscleGroup.LEGS, type),
      )

  private open class FakeRoutineDao(initial: List<RoutineWithExercises>) : RoutineDao {
    protected val routines = MutableStateFlow(initial)

    fun set(value: List<RoutineWithExercises>) {
      routines.value = value
    }

    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> =
        MutableStateFlow(emptyList())

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = routines

    override suspend fun routinesFullOnce(): List<RoutineWithExercises> =
        observeRoutinesFull().first()

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? =
        routines.value.firstOrNull { it.routine.id == id }

    override suspend fun getRoutineName(id: Long): String? =
        routines.value.firstOrNull { it.routine.id == id }?.routine?.name

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? =
        routines.value.firstOrNull { it.routine.syncId == syncId }?.routine

    override suspend fun upsertRoutine(routine: RoutineEntity): Long = routine.id

    override suspend fun deleteRoutine(id: Long) = Unit

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }

  private class RetryRoutineDao(initial: List<RoutineWithExercises>) : FakeRoutineDao(initial) {
    private var firstCollection = true

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flow {
      if (firstCollection) {
        firstCollection = false
        throw IOException("offline")
      }
      emit(routines.value)
    }
  }
}
