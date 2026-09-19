package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WorkoutSetMutator]. Репозиторий подменён [RecordingRepository], поэтому весь
 * read-modify-write остаётся на тестовом диспетчере и виден целиком: тесты проверяют не только
 * итоговое значение подхода, но и последовательность записей — именно она ломается при потерянных
 * обновлениях.
 *
 * [RecordingRepository.getSet] делает `yield()`, отдавая управление планировщику между чтением и
 * записью. Будь у мутатора не один потребитель, а несколько, правки успели бы переплестись на этой
 * точке и последняя записала бы поверх исходного значения.
 *
 * Очередь докручивается через `runCurrent()`, а не `advanceUntilIdle()`: последний пропускает
 * работу [kotlinx.coroutines.test.TestScope.backgroundScope], на котором и живёт потребитель.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSetMutatorTest {

  @Test
  fun `rapid steps stack on each other instead of overwriting the original value`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 60.0, reps = 10))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    repeat(4) { mutator.stepWeight(SET_ID, 2.5) }
    runCurrent()

    assertEquals(listOf(62.5, 65.0, 67.5, 70.0), repository.writes.map { it.weightKg })
    assertEquals(70.0, repository.current.weightKg!!, EPS)
  }

  @Test
  fun `steps and keyboard input queue in call order`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 60.0, reps = 10))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    mutator.stepReps(SET_ID, 1)
    mutator.setWeight(SET_ID, "80")
    mutator.stepWeight(SET_ID, 2.5)
    runCurrent()

    assertEquals(11, repository.current.reps)
    assertEquals(82.5, repository.current.weightKg!!, EPS)
  }

  @Test
  fun `half kilo steps do not accumulate floating point drift`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 0.0, reps = 0))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    repeat(3) { mutator.stepWeight(SET_ID, 0.5) }
    runCurrent()

    assertEquals(1.5, repository.current.weightKg!!, 0.0)
  }

  @Test
  fun `steps never push a value below zero`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 2.0, reps = 1))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    mutator.stepWeight(SET_ID, -2.5)
    mutator.stepReps(SET_ID, -5)
    runCurrent()

    assertEquals(0.0, repository.current.weightKg!!, EPS)
    assertEquals(0, repository.current.reps)
  }

  @Test
  fun `edit runs an arbitrary transform in the same queue`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 60.0, reps = 10))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    mutator.stepWeight(SET_ID, 2.5)
    mutator.edit(SET_ID) { it.copy(reps = 8) }
    runCurrent()

    assertEquals(62.5, repository.current.weightKg!!, EPS)
    assertEquals(8, repository.current.reps)
  }

  @Test
  fun `an edit for a missing set is dropped without blocking the queue`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 60.0, reps = 10))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    mutator.stepWeight(MISSING_SET_ID, 2.5)
    mutator.stepWeight(SET_ID, 2.5)
    runCurrent()

    assertEquals(1, repository.writes.size)
    assertEquals(62.5, repository.current.weightKg!!, EPS)
  }

  @Test
  fun `completed number edits reply after projecting only their exercise type`() = runTest {
    val repository =
        RecordingRepository(
            strengthSet(weightKg = 60.0, reps = 10).copy(durationSec = 120, isCompleted = true),
        )
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    val result = async {
      mutator.editCompletedNumbers(
          SET_ID,
          ExerciseType.STRENGTH,
          CompletedSetNumbers(weightKg = 72.5, reps = 8),
          effort = SetEffort.FOUR_PLUS,
      )
    }
    runCurrent()

    assertEquals(CompletedSetEditResult.Saved, result.await())
    assertEquals(72.5, repository.current.weightKg!!, EPS)
    assertEquals(8, repository.current.reps)
    assertEquals(120, repository.current.durationSec)
    assertEquals("WORK", repository.current.setType)
    assertEquals(null, repository.current.actualRir)
    assertTrue(repository.current.actualRirAtLeastFour)
    assertTrue(repository.current.isCompleted)
  }

  @Test
  fun `a missing completed edit replies and later queued work still runs`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 60.0, reps = 10))
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    val missing = async {
      mutator.editCompletedNumbers(
          MISSING_SET_ID,
          ExerciseType.STRENGTH,
          CompletedSetNumbers(weightKg = 72.5),
      )
    }
    mutator.stepWeight(SET_ID, 2.5)
    runCurrent()

    assertEquals(CompletedSetEditResult.MissingOrInactive, missing.await())
    assertEquals(62.5, repository.current.weightKg!!, EPS)
  }

  @Test
  fun `a cancelled completed edit caller does not stall the next queued mutation`() = runTest {
    val repository = RecordingRepository(strengthSet(weightKg = 60.0, reps = 10))
    val release = CompletableDeferred<Unit>()
    repository.completedEditGate = release
    val mutator = WorkoutSetMutator(repository, backgroundScope)

    val cancelledCaller = async {
      mutator.editCompletedNumbers(
          SET_ID,
          ExerciseType.STRENGTH,
          CompletedSetNumbers(weightKg = 72.5, reps = 8),
      )
    }
    runCurrent()
    cancelledCaller.cancel()
    release.complete(Unit)
    mutator.stepWeight(SET_ID, 2.5)
    runCurrent()

    assertEquals(75.0, repository.current.weightKg!!, EPS)
  }

  private fun strengthSet(weightKg: Double, reps: Int) =
      WorkoutSetEntity(
          id = SET_ID,
          workoutExerciseId = 1,
          setIndex = 0,
          weightKg = weightKg,
          reps = reps,
      )

  /**
   * Holds a single set in memory and records every write in order. Unused members are unreachable.
   */
  private class RecordingRepository(var current: WorkoutSetEntity) : ActiveWorkoutRepository {

    val writes = mutableListOf<WorkoutSetEntity>()
    var completedEditGate: CompletableDeferred<Unit>? = null

    override suspend fun getSet(setId: Long): WorkoutSetEntity? {
      yield()
      return current.takeIf { it.id == setId }
    }

    override suspend fun updateSet(set: WorkoutSetEntity) {
      current = set
      writes += set
    }

    override suspend fun mutateSet(
        setId: Long,
        transform: (WorkoutSetEntity) -> WorkoutSetEntity,
    ): Boolean {
      val set = current.takeIf { it.id == setId } ?: return false
      current = transform(set)
      writes += current
      return true
    }

    override suspend fun updateCompletedSetNumbers(
        set: WorkoutSetEntity,
        type: ExerciseType,
    ): CompletedSetEditResult {
      completedEditGate?.await()
      current = set
      return CompletedSetEditResult.Saved
    }

    override suspend fun startFromRoutine(routineId: Long): String = unused()

    override suspend fun startEmpty(): String = unused()

    override fun observeActive(): Flow<WorkoutFull?> = unused()

    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) = unused()

    override suspend fun addSet(workoutExerciseId: Long) = unused()

    override suspend fun deleteSet(setId: Long) = unused()

    override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = unused()

    override suspend fun deleteExercise(workoutExerciseId: Long) = unused()

    override suspend fun reorderExercises(
        workoutId: String,
        orderedWorkoutExerciseIds: List<Long>,
    ) = unused()

    override suspend fun finish(workoutId: String) = unused()

    override suspend fun discard(workoutId: String) = unused()

    private fun unused(): Nothing = error("not used by WorkoutSetMutator")
  }
}

private const val SET_ID = 1L
private const val MISSING_SET_ID = 999L
private const val EPS = 1e-9
