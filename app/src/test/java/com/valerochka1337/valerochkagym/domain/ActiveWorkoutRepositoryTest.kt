package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.sortedWorkoutFull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ActiveWorkoutRepositoryTest : RoomDaoTest() {

  private lateinit var workoutDao: WorkoutDao
  private lateinit var routineDao: RoutineDao
  private lateinit var exerciseDao: ExerciseDao
  private lateinit var repository: ActiveWorkoutRepositoryImpl

  @Before
  fun grabDaos() {
    workoutDao = db.workoutDao()
    routineDao = db.routineDao()
    exerciseDao = db.exerciseDao()
    repository = ActiveWorkoutRepositoryImpl(db, workoutDao, routineDao)
  }

  @Test
  fun `effort survives numeric edits and completion and can switch to warmup`() = runTest {
    val exercise = addExercise("Жим RIR")
    val workout = repository.startEmpty()
    val section = repository.addExercise(workout, exercise)
    val set = workoutDao.getSetsForWorkoutExercise(section).single()
    repository.mutateSet(set.id) { SetEffort.FOUR_PLUS.applyTo(it) }
    repository.mutateSet(set.id) { it.copy(reps = 8) }
    repository.toggleSetCompleted(set.id, true)
    val completed = repository.getSet(set.id)!!
    assertTrue(completed.actualRirAtLeastFour)
    assertNull(completed.actualRir)
    assertEquals("WORK", completed.setType)
    repository.addSet(section)
    assertFalse(workoutDao.getSetsForWorkoutExercise(section).last().actualRirAtLeastFour)
    repository.mutateSet(set.id) { SetEffort.FAILURE.applyTo(it) }
    assertEquals(0, repository.getSet(set.id)!!.actualRir)
    assertFalse(repository.getSet(set.id)!!.actualRirAtLeastFour)
    repository.mutateSet(set.id) { SetEffort.WARMUP.applyTo(it) }
    assertEquals("WARMUP", repository.getSet(set.id)!!.setType)
    assertNull(repository.getSet(set.id)!!.actualRir)
    repository.mutateSet(set.id) { SetEffort.FOUR_PLUS.applyTo(it) }
    repository.toggleSetCompleted(set.id, false)
    assertFalse(repository.getSet(set.id)!!.actualRirAtLeastFour)
  }

  @Test
  fun `set note is guarded by active workout and survives a stale numeric entity save`() = runTest {
    val exercise = addExercise("Заметка")
    val workoutId = repository.startEmpty()
    val section = repository.addExercise(workoutId, exercise)
    val stale = workoutDao.getSetsForWorkoutExercise(section).single()

    assertEquals(NoteSaveResult.Saved, repository.saveSetNote(workoutId, stale.id, "  cue  "))
    repository.updateSet(stale.copy(reps = 8))

    assertEquals("cue", workoutDao.getSet(stale.id)?.note)
    assertEquals(
        NoteSaveResult.TooLong,
        repository.saveSetNote(workoutId, stale.id, "x".repeat(2001)),
    )
    repository.finish(workoutId)
    assertEquals(
        NoteSaveResult.MissingOrInactive,
        repository.saveSetNote(workoutId, stale.id, "later"),
    )
  }

  @Test
  fun `notes only incomplete set survives finish while copied history remains incomplete`() =
      runTest {
        val exercise = addExercise("Подсказка")
        val workoutId = repository.startEmpty()
        val section = repository.addExercise(workoutId, exercise)
        val set = workoutDao.getSetsForWorkoutExercise(section).single()

        assertEquals(NoteSaveResult.Saved, repository.saveSetNote(workoutId, set.id, "наблюдение"))
        repository.finish(workoutId)

        val saved = workoutFull(workoutId).exercises.single().sets.single()
        assertFalse(saved.isCompleted)
        assertEquals("наблюдение", saved.note)
      }

  @Test
  fun `set note stays on its row through reorder while duplicate is empty and cascade removes it`() =
      runTest {
        val firstExercise = addExercise("Первое")
        val secondExercise = addExercise("Второе")
        val workoutId = repository.startEmpty()
        val firstSection = repository.addExercise(workoutId, firstExercise)
        val secondSection = repository.addExercise(workoutId, secondExercise)
        val noted = workoutDao.getSetsForWorkoutExercise(firstSection).single()
        assertEquals(
            NoteSaveResult.Saved,
            repository.saveSetNote(workoutId, noted.id, "только этот"),
        )

        repository.addSet(firstSection)
        repository.reorderExercises(workoutId, listOf(secondSection, firstSection))

        val rows = workoutDao.getSetsForWorkoutExercise(firstSection)
        assertEquals("только этот", rows.single { it.id == noted.id }.note)
        assertEquals("", rows.single { it.id != noted.id }.note)
        repository.deleteExercise(firstSection)
        assertEquals(null, workoutDao.getSet(noted.id))
      }

  // region startFromRoutine

  @Test
  fun `startFromRoutine builds the tree from the routine`() = runTest {
    val squat = addExercise("Присед")
    val bench = addExercise("Жим")
    val routineId = addRoutine("День A")
    addRoutineExercise(
        routineId,
        squat,
        position = 0,
        plannedSets = listOf(planned(100.0, 5), planned(100.0, 5)),
    )
    addRoutineExercise(routineId, bench, position = 1, plannedSets = listOf(planned(60.0, 8)))

    val workoutId = repository.startFromRoutine(routineId)

    val full = sortedWorkoutFull(workoutFull(workoutId))
    assertEquals("День A", full.workout.name)
    assertEquals(routineId, full.workout.routineId)
    assertNull(full.workout.finishedAt)
    assertEquals(listOf("Присед", "Жим"), full.exercises.map { it.exercise.name })
    assertEquals(listOf(0, 1), full.exercises.map { it.workoutExercise.position })
    assertEquals(listOf(2, 1), full.exercises.map { it.sets.size })
  }

  @Test
  fun `startFromRoutine prefills sets from the last completed workout`() = runTest {
    val squat = addExercise("Присед")
    val routineId = addRoutine("День A")
    addRoutineExercise(
        routineId,
        squat,
        position = 0,
        plannedSets = listOf(planned(100.0, 5), planned(100.0, 5)),
    )
    seedHistory(
        squat,
        listOf(
            completedSet(weightKg = 110.0, reps = 6),
            completedSet(weightKg = 112.5, reps = 4),
        ),
    )

    val workoutId = repository.startFromRoutine(routineId)

    val sets = sortedWorkoutFull(workoutFull(workoutId)).exercises.single().sets
    assertEquals(listOf(110.0, 112.5), sets.map { it.weightKg })
    assertEquals(listOf(6, 4), sets.map { it.reps })
    assertTrue(sets.none { it.isCompleted })
  }

  @Test
  fun `startFromRoutine prefills from plannedSets when there is no history`() = runTest {
    val squat = addExercise("Присед")
    val routineId = addRoutine("День A")
    addRoutineExercise(routineId, squat, position = 0, plannedSets = listOf(planned(80.0, 12)))

    val workoutId = repository.startFromRoutine(routineId)

    val set = sortedWorkoutFull(workoutFull(workoutId)).exercises.single().sets.single()
    assertEquals(80.0, set.weightKg!!, 0.0)
    assertEquals(12, set.reps)
    assertFalse(set.isCompleted)
  }

  @Test
  fun `startFromRoutine takes the tail from plannedSets when history is shorter`() = runTest {
    val squat = addExercise("Присед")
    val routineId = addRoutine("День A")
    addRoutineExercise(
        routineId,
        squat,
        position = 0,
        plannedSets = listOf(planned(100.0, 5), planned(100.0, 5), planned(100.0, 5)),
    )
    seedHistory(squat, listOf(completedSet(weightKg = 110.0, reps = 6)))

    val workoutId = repository.startFromRoutine(routineId)

    val sets = sortedWorkoutFull(workoutFull(workoutId)).exercises.single().sets
    assertEquals(listOf(110.0, 100.0, 100.0), sets.map { it.weightKg })
    assertEquals(listOf(6, 5, 5), sets.map { it.reps })
  }

  @Test
  fun `startFromRoutine with a missing routine creates an empty workout`() = runTest {
    val workoutId = repository.startFromRoutine(routineId = 999)

    val full = workoutFull(workoutId)
    assertEquals("Тренировка", full.workout.name)
    assertNull(full.workout.routineId)
    assertTrue(full.exercises.isEmpty())
  }

  @Test
  fun `startFromRoutine copies configured gyms into the active workout snapshot`() = runTest {
    val squat = addExercise("Присед")
    val routineId = addRoutine("Ноги")
    addRoutineExercise(routineId, squat, position = 0, plannedSets = listOf(planned(80.0, 8)))
    val gymId = db.gymDao().insertGym(GymEntity(name = "Альфа"))
    db.gymDao().replaceGymExercises(gymId, listOf(squat))
    db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))

    val workoutId = repository.startFromRoutine(routineId)

    assertEquals(listOf("Альфа"), workoutFull(workoutId).gyms.map { it.name })
  }

  @Test
  fun `startFromRoutine rejects a program incompatible with its gyms`() = runTest {
    val squat = addExercise("Присед")
    val routineId = addRoutine("Ноги")
    addRoutineExercise(routineId, squat, position = 0, plannedSets = listOf(planned(80.0, 8)))
    val gymId = db.gymDao().insertGym(GymEntity(name = "Альфа"))
    db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))

    try {
      repository.startFromRoutine(routineId)
      fail("Incompatible routine must not start")
    } catch (conflict: RoutineGymConflictException) {
      assertEquals(listOf("Присед"), conflict.exerciseNames)
    }

    assertEquals(0, tableCount("workouts"))
  }

  @Test
  fun `startFromRoutine accepts a configured gym covering every requirement`() = runTest {
    val press =
        exerciseDao.insert(
            ExerciseEntity(
                name = "Жим скамья",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
                isCustom = true,
                equipmentRequirementState = EquipmentRequirementState.KNOWN,
            ),
        )
    exerciseDao.replaceRequirements(press, setOf("dumbbells", "flat_bench"))
    val routineId = addRoutine("Грудь")
    addRoutineExercise(routineId, press, position = 0, plannedSets = listOf(planned(20.0, 10)))
    val gymId = db.gymDao().insertGym(GymEntity(name = "Альфа", inventoryConfigured = true))
    db.gymDao().replaceGymEquipment(gymId, setOf("dumbbells", "flat_bench"))
    db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))

    val workoutId = repository.startFromRoutine(routineId)

    assertEquals(listOf(press), workoutDao.getWorkoutExercises(workoutId).map { it.exerciseId })
  }

  @Test
  fun `missing bench rejects configured routine start and workout addition atomically`() = runTest {
    val press =
        exerciseDao.insert(
            ExerciseEntity(
                name = "Жим скамья",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
                isCustom = true,
                equipmentRequirementState = EquipmentRequirementState.KNOWN,
            ),
        )
    exerciseDao.replaceRequirements(press, setOf("dumbbells", "flat_bench"))
    val routineId = addRoutine("Грудь")
    addRoutineExercise(routineId, press, position = 0, plannedSets = listOf(planned(20.0, 10)))
    val gymId = db.gymDao().insertGym(GymEntity(name = "Альфа", inventoryConfigured = true))
    db.gymDao().replaceGymEquipment(gymId, setOf("dumbbells"))
    db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))

    try {
      repository.startFromRoutine(routineId)
      fail("A missing bench must reject the routine")
    } catch (_: RoutineGymConflictException) {
      // The transaction must not create a partial workout.
    }
    assertEquals(0, tableCount("workouts"))

    val emptyWorkout = repository.startEmpty()
    db.gymDao().replaceWorkoutGyms(emptyWorkout, listOf(gymId))
    try {
      repository.addExercise(emptyWorkout, press)
      fail("A missing bench must reject adding an exercise")
    } catch (_: RoutineGymConflictException) {
      // The transaction must not insert an exercise or its first set.
    }
    assertTrue(workoutDao.getWorkoutExercises(emptyWorkout).isEmpty())
    assertEquals(0, tableCount("workout_sets"))
  }

  // endregion

  // region single active workout guard

  @Test
  fun `startFromRoutine returns the existing id when a workout is active`() = runTest {
    val squat = addExercise("Присед")
    val routineId = addRoutine("День A")
    addRoutineExercise(routineId, squat, position = 0, plannedSets = listOf(planned(100.0, 5)))

    val first = repository.startFromRoutine(routineId)
    val second = repository.startFromRoutine(routineId)

    assertEquals(first, second)
    assertEquals(1, tableCount("workouts"))
  }

  @Test
  fun `startEmpty returns the existing id when a workout is active`() = runTest {
    val first = repository.startEmpty()
    val second = repository.startEmpty()

    assertEquals(first, second)
    assertEquals(1, tableCount("workouts"))
  }

  @Test
  fun `startFromRoutine returns the active empty workout without building a routine tree`() =
      runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat, position = 0, plannedSets = listOf(planned(100.0, 5)))

        val empty = repository.startEmpty()
        val fromRoutine = repository.startFromRoutine(routineId)

        assertEquals(empty, fromRoutine)
        assertEquals(1, tableCount("workouts"))
        val full = workoutFull(fromRoutine)
        assertEquals("Тренировка", full.workout.name)
        assertTrue(full.exercises.isEmpty())
      }

  // endregion

  // region set and exercise editing

  @Test
  fun `extra set returns focus to the previous exercise and rejects it after the next starts`() =
      runTest {
        val workout = repository.startEmpty()
        val first = repository.addExercise(workout, addExercise("Первое"))
        val second = repository.addExercise(workout, addExercise("Второе"))
        val firstSet = workoutDao.getSetsForWorkoutExercise(first).single()
        repository.toggleSetCompleted(firstSet.id, true)
        repository.addSet(first)
        val extra = workoutDao.getSetsForWorkoutExercise(first).last()
        assertFalse(extra.isCompleted)
        assertEquals(2, workoutDao.getSetsForWorkoutExercise(first).size)
        repository.toggleSetCompleted(extra.id, true)
        repository.toggleSetCompleted(
            workoutDao.getSetsForWorkoutExercise(second).single().id,
            true,
        )
        repository.addSet(first)
        assertEquals(2, workoutDao.getSetsForWorkoutExercise(first).size)
        repository.addSet(second)
        assertEquals(2, workoutDao.getSetsForWorkoutExercise(second).size)
      }

  @Test
  fun `addSet copies the last set with the next index`() = runTest {
    val squat = addExercise("Присед")
    val workoutId = repository.startEmpty()
    val workoutExerciseId = repository.addExercise(workoutId, squat)
    val first = workoutDao.getSetsForWorkoutExercise(workoutExerciseId).single()
    repository.updateSet(first.copy(weightKg = 80.0, reps = 10, isCompleted = true))

    repository.addSet(workoutExerciseId)

    val sets = workoutDao.getSetsForWorkoutExercise(workoutExerciseId)
    assertEquals(listOf(0, 1), sets.map { it.setIndex })
    val added = sets.last()
    assertEquals(80.0, added.weightKg!!, 0.0)
    assertEquals(10, added.reps)
    assertFalse(added.isCompleted)
  }

  @Test
  fun `addExercise appends at position max plus one`() = runTest {
    val squat = addExercise("Присед")
    val bench = addExercise("Жим")
    val workoutId = repository.startEmpty()

    repository.addExercise(workoutId, squat)
    repository.addExercise(workoutId, bench)

    val exercises = workoutDao.getWorkoutExercises(workoutId)
    assertEquals(listOf(0, 1), exercises.map { it.position })
  }

  @Test
  fun `addExercise prefills the first set from the last completed workout`() = runTest {
    val squat = addExercise("Присед")
    seedHistory(squat, listOf(completedSet(weightKg = 90.0, reps = 5)))
    val workoutId = repository.startEmpty()

    val workoutExerciseId = repository.addExercise(workoutId, squat)

    val set = workoutDao.getSetsForWorkoutExercise(workoutExerciseId).single()
    assertEquals(0, set.setIndex)
    assertEquals(90.0, set.weightKg!!, 0.0)
    assertEquals(5, set.reps)
    assertFalse(set.isCompleted)
  }

  @Test
  fun `addExercise rejects an already finished workout`() = runTest {
    val squat = addExercise("Присед")
    insertWorkout("done", finishedAt = 777)

    try {
      repository.addExercise("done", squat)
      fail("A finished workout must remain immutable")
    } catch (_: ActiveWorkoutUnavailableException) {
      // Expected: a stale picker result cannot mutate workout history.
    }

    assertTrue(workoutDao.getWorkoutExercises("done").isEmpty())
  }

  @Test
  fun `deleteSet removes only that set`() = runTest {
    val squat = addExercise("Присед")
    val workoutId = repository.startEmpty()
    val workoutExerciseId = repository.addExercise(workoutId, squat)
    repository.addSet(workoutExerciseId)
    val sets = workoutDao.getSetsForWorkoutExercise(workoutExerciseId)

    repository.deleteSet(sets.first().id)

    val remaining = workoutDao.getSetsForWorkoutExercise(workoutExerciseId)
    assertEquals(listOf(sets.last().id), remaining.map { it.id })
  }

  @Test
  fun `deleteExercise removes the exercise and cascades to its sets`() = runTest {
    val squat = addExercise("Присед")
    val workoutId = repository.startEmpty()
    val workoutExerciseId = repository.addExercise(workoutId, squat)

    repository.deleteExercise(workoutExerciseId)

    assertTrue(workoutDao.getWorkoutExercises(workoutId).isEmpty())
    assertEquals(0, tableCount("workout_sets"))
  }

  @Test
  fun `reorderExercises atomically renumbers the complete exercise order`() = runTest {
    val squat = addExercise("Присед")
    val bench = addExercise("Жим")
    val row = addExercise("Тяга")
    val workoutId = repository.startEmpty()
    val squatId = repository.addExercise(workoutId, squat)
    val benchId = repository.addExercise(workoutId, bench)
    val rowId = repository.addExercise(workoutId, row)

    repository.reorderExercises(workoutId, listOf(rowId, squatId, benchId))

    val reordered = workoutDao.getWorkoutExercises(workoutId)
    assertEquals(listOf(rowId, squatId, benchId), reordered.map { it.id })
    assertEquals(listOf(0, 1, 2), reordered.map { it.position })
  }

  @Test
  fun `reorderExercises is reflected by the reactively sorted active workout`() = runTest {
    val squat = addExercise("Присед")
    val bench = addExercise("Жим")
    val workoutId = repository.startEmpty()
    val squatId = repository.addExercise(workoutId, squat)
    val benchId = repository.addExercise(workoutId, bench)

    repository.reorderExercises(workoutId, listOf(benchId, squatId))

    val active = repository.observeActive().first()!!
    assertEquals(listOf(benchId, squatId), active.exercises.map { it.workoutExercise.id })
    assertEquals(listOf(0, 1), active.exercises.map { it.workoutExercise.position })
  }

  @Test
  fun `reorderExercises rejects a non-unique or incomplete id set without changing positions`() =
      runTest {
        val squat = addExercise("Присед")
        val bench = addExercise("Жим")
        val row = addExercise("Тяга")
        val workoutId = repository.startEmpty()
        val squatId = repository.addExercise(workoutId, squat)
        val benchId = repository.addExercise(workoutId, bench)
        val rowId = repository.addExercise(workoutId, row)

        try {
          repository.reorderExercises(workoutId, listOf(squatId, benchId))
          fail("Incomplete ids must be rejected")
        } catch (_: IllegalArgumentException) {
          // Expected: the supplied ids do not cover all workout exercises.
        }
        try {
          repository.reorderExercises(workoutId, listOf(squatId, squatId, rowId))
          fail("Duplicate ids must be rejected")
        } catch (_: IllegalArgumentException) {
          // Expected: every workout exercise id must occur exactly once.
        }

        val unchanged = workoutDao.getWorkoutExercises(workoutId)
        assertEquals(listOf(squatId, benchId, rowId), unchanged.map { it.id })
        assertEquals(listOf(0, 1, 2), unchanged.map { it.position })
      }

  @Test
  fun `toggleSetCompleted flips the completed flag`() = runTest {
    val squat = addExercise("Присед")
    val workoutId = repository.startEmpty()
    val workoutExerciseId = repository.addExercise(workoutId, squat)
    val setId = workoutDao.getSetsForWorkoutExercise(workoutExerciseId).single().id

    repository.toggleSetCompleted(setId, completed = true)
    assertTrue(setById(workoutExerciseId, setId).isCompleted)

    repository.toggleSetCompleted(setId, completed = false)
    assertFalse(setById(workoutExerciseId, setId).isCompleted)
  }

  @Test
  fun `completed strength edit changes only strength numbers and preserves completion metadata`() =
      runTest {
        val exercise = addExercise("Жим", ExerciseType.STRENGTH)
        val workoutId = repository.startEmpty()
        val workoutExerciseId = repository.addExercise(workoutId, exercise)
        val original =
            workoutDao
                .getSetsForWorkoutExercise(workoutExerciseId)
                .single()
                .copy(
                    weightKg = 60.0,
                    reps = 10,
                    durationSec = 120,
                    speedKmh = 9.0,
                    inclinePct = 4.0,
                    isCompleted = true,
                    completedAt = 456L,
                )
        repository.updateSet(original)

        val result =
            repository.updateCompletedSetNumbers(
                original.copy(weightKg = 72.5, reps = 8, durationSec = 999),
                ExerciseType.STRENGTH,
            )

        val stored = setById(workoutExerciseId, original.id)
        assertEquals(CompletedSetEditResult.Saved, result)
        assertEquals(72.5, stored.weightKg!!, 0.0)
        assertEquals(8, stored.reps)
        assertEquals(120, stored.durationSec)
        assertEquals(9.0, stored.speedKmh!!, 0.0)
        assertEquals(4.0, stored.inclinePct!!, 0.0)
        assertTrue(stored.isCompleted)
        assertEquals(456L, stored.completedAt)
      }

  @Test
  fun `completed edit rejects unfinished or wrong type rows without changing them`() = runTest {
    val timed = addExercise("Планка", ExerciseType.TIMED)
    val workoutId = repository.startEmpty()
    val workoutExerciseId = repository.addExercise(workoutId, timed)
    val original =
        workoutDao
            .getSetsForWorkoutExercise(workoutExerciseId)
            .single()
            .copy(
                durationSec = 60,
                isCompleted = true,
                completedAt = 456L,
            )
    repository.updateSet(original)

    val wrongType =
        repository.updateCompletedSetNumbers(
            original.copy(weightKg = 80.0, reps = 5),
            ExerciseType.STRENGTH,
        )
    repository.finish(workoutId)
    val finished =
        repository.updateCompletedSetNumbers(
            original.copy(durationSec = 75),
            ExerciseType.TIMED,
        )

    val stored = setById(workoutExerciseId, original.id)
    assertEquals(CompletedSetEditResult.MissingOrInactive, wrongType)
    assertEquals(CompletedSetEditResult.MissingOrInactive, finished)
    assertEquals(60, stored.durationSec)
    assertEquals(null, stored.weightKg)
    assertTrue(stored.isCompleted)
    assertEquals(456L, stored.completedAt)
  }

  // endregion

  // region finish and discard

  @Test
  fun `finish prunes empty uncompleted sets and empty exercises then stamps finishedAt`() =
      runTest {
        val a = addExercise("A")
        val b = addExercise("B")
        val c = addExercise("C")
        insertWorkout("active", finishedAt = null)
        val weA = insertWorkoutExercise("active", a, position = 0)
        insertSet(
            weA,
            setIndex = 0,
            weightKg = 50.0,
            reps = 10,
            isCompleted = true,
        ) // real completed -> kept
        insertSet(weA, setIndex = 1, isCompleted = false) // empty uncompleted -> pruned
        val weB = insertWorkoutExercise("active", b, position = 1)
        insertSet(
            weB,
            setIndex = 0,
            isCompleted = false,
        ) // empty uncompleted -> pruned, exercise emptied -> dropped
        val weC = insertWorkoutExercise("active", c, position = 2)
        insertSet(weC, setIndex = 0, isCompleted = true) // completed but empty -> kept

        repository.finish("active")

        val full = workoutFull("active")
        assertNotNull(full.workout.finishedAt)
        assertEquals(setOf(a, c), full.exercises.map { it.exercise.id }.toSet())
        assertEquals(1, full.exercises.first { it.workoutExercise.id == weA }.sets.size)
        assertEquals(1, full.exercises.first { it.workoutExercise.id == weC }.sets.size)
        assertEquals(2, tableCount("workout_exercises"))
        assertEquals(2, tableCount("workout_sets"))
      }

  @Test
  fun `finish keeps an already stamped finishedAt`() = runTest {
    val a = addExercise("A")
    insertWorkout("done", finishedAt = 777)
    val we = insertWorkoutExercise("done", a, position = 0)
    insertSet(we, setIndex = 0, weightKg = 50.0, reps = 10, isCompleted = true)

    repository.finish("done")

    assertEquals(777L, workoutFull("done").workout.finishedAt)
  }

  @Test
  fun `discard deletes the workout and cascades to exercises and sets`() = runTest {
    val a = addExercise("A")
    insertWorkout("active", finishedAt = null)
    val we = insertWorkoutExercise("active", a, position = 0)
    insertSet(we, setIndex = 0, weightKg = 50.0, reps = 10, isCompleted = true)

    repository.discard("active")

    assertNull(workoutDao.getWorkoutFull("active"))
    assertEquals(0, tableCount("workout_exercises"))
    assertEquals(0, tableCount("workout_sets"))
  }

  // endregion

  // region observeActive

  @Test
  fun `observeActive sorts exercises by position and sets by index`() = runTest {
    val a = addExercise("A")
    val b = addExercise("B")
    insertWorkout("active", finishedAt = null)
    // Inserted out of order to prove the flow applies the domain sort.
    val weB = insertWorkoutExercise("active", b, position = 1)
    val weA = insertWorkoutExercise("active", a, position = 0)
    insertSet(weA, setIndex = 2, weightKg = 60.0, reps = 5, isCompleted = false)
    insertSet(weA, setIndex = 0, weightKg = 40.0, reps = 5, isCompleted = false)
    insertSet(weA, setIndex = 1, weightKg = 50.0, reps = 5, isCompleted = false)

    val full = repository.observeActive().first()!!

    assertEquals(listOf(weA, weB), full.exercises.map { it.workoutExercise.id })
    assertEquals(listOf(0, 1), full.exercises.map { it.workoutExercise.position })
    assertEquals(listOf(0, 1, 2), full.exercises.first().sets.map { it.setIndex })
  }

  // endregion

  // region helpers

  private suspend fun addExercise(name: String, type: ExerciseType = ExerciseType.STRENGTH): Long =
      exerciseDao.insert(ExerciseEntity(name = name, muscleGroup = MuscleGroup.LEGS, type = type))

  private suspend fun addRoutine(name: String): Long =
      routineDao.upsertRoutine(RoutineEntity(name = name))

  private suspend fun addRoutineExercise(
      routineId: Long,
      exerciseId: Long,
      position: Int,
      plannedSets: List<PlannedSet>,
      restSeconds: Int? = null,
  ) {
    routineDao.insertRoutineExercises(
        listOf(
            RoutineExerciseEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = position,
                restSeconds = restSeconds,
                plannedSets = plannedSets,
            ),
        )
    )
  }

  /** Seeds a finished workout whose completed sets become the "last time" prefill source. */
  private suspend fun seedHistory(exerciseId: Long, sets: List<WorkoutSetEntity>) {
    insertWorkout("history", startedAt = 100, finishedAt = 500)
    val workoutExerciseId = insertWorkoutExercise("history", exerciseId)
    workoutDao.insertSets(
        sets.mapIndexed { index, set ->
          set.copy(workoutExerciseId = workoutExerciseId, setIndex = index)
        },
    )
  }

  private fun planned(weightKg: Double, reps: Int): PlannedSet =
      PlannedSet(weightKg = weightKg, reps = reps)

  private fun completedSet(weightKg: Double, reps: Int): WorkoutSetEntity =
      WorkoutSetEntity(
          workoutExerciseId = 0,
          setIndex = 0,
          weightKg = weightKg,
          reps = reps,
          isCompleted = true,
      )

  private suspend fun setById(workoutExerciseId: Long, setId: Long): WorkoutSetEntity =
      workoutDao.getSetsForWorkoutExercise(workoutExerciseId).first { it.id == setId }

  // endregion
}
