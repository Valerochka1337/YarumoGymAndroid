package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.SyncSchema
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.domain.CompletedWorkoutRoutineCommand
import com.valerochka1337.valerochkagym.domain.CompletedWorkoutRoutineResult
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.SaveExerciseConfigurationResult
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.domain.completedWorkoutFingerprint
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpConfigurationUploadScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GymRepositoryImplTest : RoomDaoTest() {

  private lateinit var repository: GymRepositoryImpl

  @Before
  fun createRepository() {
    repository =
        GymRepositoryImpl(
            database = db,
            gymDao = db.gymDao(),
            exerciseDao = db.exerciseDao(),
            exerciseMuscleDao = db.exerciseMuscleDao(),
            routineDao = db.routineDao(),
            workoutDao = db.workoutDao(),
        )
  }

  @Test
  fun `standard gym rejects inventory edits and deletion without changing its data`() = runTest {
    val id =
        (repository.saveGymInventory(null, "Шаблон зала", setOf("dumbbells"))
                as SaveGymResult.Saved)
            .gymId
    db.openHelper.writableDatabase.execSQL(
        "UPDATE gyms SET origin='STANDARD' WHERE syncId=?",
        arrayOf(id),
    )
    val original = repository.getGym(id)

    assertEquals(
        SaveGymResult.Failure,
        repository.saveGymInventory(id, "Изменённый зал", emptySet()),
    )
    assertEquals(DeleteGymResult.Failure, repository.deleteGym(id))
    assertEquals(original, repository.getGym(id))
    assertEquals(0, tableCount("configuration_tombstones"))
  }

  @Test
  fun `creating an exercise leaves selected gym inventories unchanged`() = runTest {
    val alpha = savedGym("Альфа")
    val beta = savedGym("Бета")

    val exercise =
        repository.createExerciseAndAssign(
            NewExerciseConfiguration(
                exercise = exercise("Жим"),
                muscles = listOf(ExerciseMuscleEntity(0, Muscle.UPPER_CHEST, 100)),
            ),
            setOf(alpha, beta),
        )!!

    assertTrue(exercise.id > 0)
    assertTrue(repository.getGym(alpha)!!.exercises.isEmpty())
    assertTrue(repository.getGym(beta)!!.exercises.isEmpty())
    assertTrue(repository.observeAvailableExercises(setOf(alpha, beta)).first().isEmpty())
    assertEquals(1, db.exerciseMuscleDao().getForExercise(exercise.id).size)
  }

  @Test
  fun `creating from the active picker also adds the exercise to its workout`() = runTest {
    val gym = savedGym("Альфа")
    db.workoutDao()
        .insertWorkout(
            WorkoutEntity(id = "active", name = "Тренировка", startedAt = 1_000),
        )
    val localGymId = db.gymDao().getGymBySyncId(gym)!!.id
    db.gymDao().replaceWorkoutGyms("active", listOf(localGymId))

    val exercise =
        repository.createExerciseAssignAndAddToWorkout(
            NewExerciseConfiguration(
                exercise = exercise("Жим"),
                muscles = listOf(ExerciseMuscleEntity(0, Muscle.UPPER_CHEST, 100)),
            ),
            setOf(gym),
            "active",
        )!!

    assertEquals(
        listOf(exercise.id),
        db.workoutDao().getWorkoutExercises("active").map { it.exerciseId },
    )
    assertTrue(repository.getGym(gym)!!.exercises.isEmpty())
    assertEquals(1, tableCount("workout_sets"))
    assertEquals(1L, db.workoutDao().getWorkoutFull("active")!!.workout.coachRevision)
  }

  @Test
  fun `routine save and gym narrowing both reject unavailable exercises`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Присед"))
    val alpha = savedGym("Альфа", setOf(exerciseId))
    val beta = savedGym("Бета", setOf(exerciseId))
    val saved =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Ноги"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                    ),
                gymIds = setOf(alpha, beta),
            ),
        )

    assertTrue(saved is SaveRoutineConfigurationResult.Saved)
    val narrowing = repository.saveGym(beta, "Бета", emptySet())
    assertTrue(narrowing is SaveGymResult.Conflict)
    narrowing as SaveGymResult.Conflict
    assertEquals(listOf("Ноги"), narrowing.details.routines.map { it.name })
    assertEquals(listOf("Присед"), narrowing.details.exercises.map { it.name })
    assertEquals(
        setOf(exerciseId),
        repository.getGym(beta)!!.exercises.mapTo(hashSetOf()) { it.id },
    )
  }

  @Test
  fun `replaying a new routine sync id returns the committed row without rewriting it`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Жим"))
    val first =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine =
                    RoutineEntity(
                        syncId = "save-operation",
                        name = "Первое имя",
                        note = "Первый текст",
                    ),
                exercises =
                    listOf(
                        RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0)
                    ),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    val replay =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine =
                    RoutineEntity(
                        syncId = "save-operation",
                        name = "Не должно замениться",
                        note = "Другой текст",
                    ),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved
    val distinct =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(syncId = "next-operation", name = "Следующая программа"),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    assertEquals(first.routine, replay.routine)
    assertEquals(first.routineId, replay.routineId)
    assertEquals(2, tableCount("routines"))
    assertEquals(1, tableCount("routine_exercises"))
    assertEquals(
        "Первое имя",
        db.routineDao().getRoutineWithExercises(first.routineId)?.routine?.name,
    )
    assertTrue(distinct.routineId != first.routineId)
  }

  @Test
  fun `completed workout replacement atomically keeps identity rest gyms and workout history`() =
      runTest {
        val exerciseId = db.exerciseDao().insert(exercise("Жим"))
        val gym = savedGym("Альфа", setOf(exerciseId))
        val sourceId =
            db.routineDao().upsertRoutine(RoutineEntity(name = "Грудь", note = "Заметка"))
        db.routineDao()
            .replaceRoutineExercises(
                sourceId,
                listOf(
                    RoutineExerciseEntity(
                        routineId = sourceId,
                        exerciseId = exerciseId,
                        position = 0,
                        restSeconds = 90,
                        plannedSets = (1..4).map { PlannedSet(weightKg = 80.0, reps = it) },
                    )
                ),
            )
        db.gymDao().replaceRoutineGyms(sourceId, listOf(db.gymDao().getGymBySyncId(gym)!!.id))
        db.workoutDao()
            .insertWorkout(
                WorkoutEntity(
                    id = "finished",
                    routineId = sourceId,
                    name = "История",
                    startedAt = 1,
                    finishedAt = 2,
                )
            )
        val source = db.routineDao().getRoutineWithExercises(sourceId)!!
        val replacement =
            listOf(
                RoutineExerciseEntity(
                    routineId = sourceId,
                    exerciseId = exerciseId,
                    position = 0,
                    restSeconds = 90,
                    plannedSets = (1..3).map { PlannedSet(weightKg = 80.0, reps = it) },
                )
            )

        val result =
            repository.saveCompletedWorkoutRoutine(
                CompletedWorkoutRoutineCommand.Replace(
                    sourceRoutineId = sourceId,
                    sourceRoutineSyncId = source.routine.syncId,
                    expectedUpdatedAt = source.routine.updatedAt,
                    expectedSourceFingerprint = source.completedWorkoutFingerprint(),
                    predictedTargetFingerprint = source.completedWorkoutFingerprint(replacement),
                    replacementExercises = replacement,
                    operationUuid = "replace-1",
                )
            )

        assertTrue(result is CompletedWorkoutRoutineResult.Saved)
        val saved = db.routineDao().getRoutineWithExercises(sourceId)!!
        assertEquals(source.routine.syncId, saved.routine.syncId)
        assertEquals(listOf(gym), saved.gyms.map { it.syncId })
        assertEquals(90, saved.exercises.single().routineExercise.restSeconds)
        assertEquals(3, saved.exercises.single().routineExercise.plannedSets.size)
        assertEquals(sourceId, db.workoutDao().getWorkoutFull("finished")!!.workout.routineId)
      }

  @Test
  fun `replacement rejects a child-only source change without writing`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val sourceId = db.routineDao().upsertRoutine(RoutineEntity(name = "Спина"))
    db.routineDao()
        .replaceRoutineExercises(
            sourceId,
            listOf(
                RoutineExerciseEntity(routineId = sourceId, exerciseId = exerciseId, position = 0)
            ),
        )
    val source = db.routineDao().getRoutineWithExercises(sourceId)!!
    val replacement =
        listOf(
            RoutineExerciseEntity(
                routineId = sourceId,
                exerciseId = exerciseId,
                position = 0,
                plannedSets = listOf(PlannedSet(weightKg = 60.0, reps = 10)),
            )
        )
    db.routineDao()
        .replaceRoutineExercises(
            sourceId,
            listOf(
                RoutineExerciseEntity(
                    routineId = sourceId,
                    exerciseId = exerciseId,
                    position = 0,
                    restSeconds = 120,
                )
            ),
        )
    val before = db.routineDao().getRoutineWithExercises(sourceId)!!

    val result =
        repository.saveCompletedWorkoutRoutine(
            CompletedWorkoutRoutineCommand.Replace(
                sourceId,
                source.routine.syncId,
                source.routine.updatedAt,
                source.completedWorkoutFingerprint(),
                source.completedWorkoutFingerprint(replacement),
                replacement,
                "replace-conflict",
            )
        )

    assertEquals(CompletedWorkoutRoutineResult.Conflict, result)
    assertEquals(before, db.routineDao().getRoutineWithExercises(sourceId))
  }

  @Test
  fun `editing a nonzero routine id still replaces its fields exercises and gyms`() = runTest {
    val firstExercise = db.exerciseDao().insert(exercise("Жим"))
    val replacementExercise = db.exerciseDao().insert(exercise("Тяга"))
    val alpha = savedGym("Альфа", setOf(firstExercise))
    val beta = savedGym("Бета", setOf(replacementExercise))
    val created =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine =
                    RoutineEntity(syncId = "editor-routine", name = "Старая", note = "До правки"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(
                            routineId = 0,
                            exerciseId = firstExercise,
                            position = 0,
                        )
                    ),
                gymIds = setOf(alpha),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    val updated =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = created.routine.copy(name = "Новая", note = "После правки"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(
                            routineId = 0,
                            exerciseId = replacementExercise,
                            position = 5,
                        )
                    ),
                gymIds = setOf(beta),
            ),
        ) as SaveRoutineConfigurationResult.Saved
    val full = db.routineDao().getRoutineWithExercises(created.routineId)!!

    assertEquals(created.routineId, updated.routineId)
    assertEquals("Новая", full.routine.name)
    assertEquals("После правки", full.routine.note)
    assertEquals(listOf(replacementExercise), full.exercises.map { it.exercise.id })
    assertEquals(listOf(5), full.exercises.map { it.routineExercise.position })
    assertEquals(listOf(beta), full.gyms.map { it.syncId })
  }

  @Test
  fun `deleting a gym linked to a routine is blocked`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val gym = savedGym("Альфа", setOf(exerciseId))
    repository.saveRoutineConfiguration(
        RoutineConfigurationDraft(
            routine = RoutineEntity(name = "Спина"),
            exercises =
                listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
            gymIds = setOf(gym),
        ),
    )

    val result = repository.deleteGym(gym)

    assertTrue(result is DeleteGymResult.InUse)
    assertTrue(repository.getGym(gym) != null)
  }

  @Test
  fun `renaming a linked gym without narrowing its catalogue succeeds`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val gym = savedGym("Альфа", setOf(exerciseId))
    repository.saveRoutineConfiguration(
        RoutineConfigurationDraft(
            routine = RoutineEntity(name = "Спина"),
            exercises =
                listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
            gymIds = setOf(gym),
        ),
    )

    val result = repository.saveGym(gym, "Основной зал", setOf(exerciseId))

    assertTrue(result is SaveGymResult.Saved)
    assertEquals("Основной зал", repository.getGym(gym)?.name)
  }

  @Test
  fun `updating an exercise keeps its cloud version strictly monotonic`() = runTest {
    val futureVersion = System.currentTimeMillis() + 60_000
    val exerciseId = db.exerciseDao().insert(exercise("Тяга").copy(updatedAt = futureVersion))
    val staleDraft =
        db.exerciseDao()
            .getById(exerciseId)!!
            .copy(
                name = "Тяга блока",
                updatedAt = 1,
            )

    val saved =
        repository.updateExerciseAndAssign(
            NewExerciseConfiguration(
                exercise = staleDraft,
                muscles = listOf(ExerciseMuscleEntity(exerciseId, Muscle.LATS, 100)),
            ),
            emptySet(),
        )!!

    assertEquals(futureVersion + 1, saved.updatedAt)
    assertEquals(futureVersion + 1, db.exerciseDao().getById(exerciseId)?.updatedAt)
  }

  @Test
  fun `configured inventory exposes a builtin only when its dumbbells and bench are present`() =
      runTest {
        val builtin =
            com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry.entries
                .first { it.key == "chest-2-1" }
                .exercise
        val exerciseId = db.exerciseDao().insert(builtin.copy(id = 0))
        val saved =
            repository.saveGymInventory(
                id = null,
                name = "Основной зал",
                equipmentIds = setOf("dumbbells", "adjustable_bench"),
            ) as SaveGymResult.Saved

        assertEquals(
            listOf(exerciseId),
            repository.observeAvailableExercises(setOf(saved.gymId)).first().map { it.id },
        )
        assertTrue(repository.getGym(saved.gymId)!!.inventoryConfigured)
        assertEquals(
            setOf("dumbbells", "adjustable_bench"),
            repository.getGym(saved.gymId)!!.equipmentIds,
        )
      }

  @Test
  fun `unknown custom exercise remains available in legacy gym and is rejected by configured or mixed gyms`() =
      runTest {
        val unknown = db.exerciseDao().insert(exercise("Старое упражнение"))
        val legacy = savedGym("Старый зал", setOf(unknown))
        val configured =
            (repository.saveGymInventory(null, "Новый зал", setOf("dumbbells"))
                    as SaveGymResult.Saved)
                .gymId

        assertEquals(
            listOf(unknown),
            repository.observeAvailableExercises(setOf(legacy)).first().map { it.id },
        )
        assertTrue(repository.observeAvailableExercises(setOf(configured)).first().isEmpty())
        assertTrue(
            repository.observeAvailableExercises(setOf(legacy, configured)).first().isEmpty()
        )
      }

  @Test
  fun `requirement conflict rolls back linked routine active workout and inventory`() = runTest {
    val exerciseId =
        db.exerciseDao()
            .insert(
                exercise("Жим с требованиями")
                    .copy(equipmentRequirementState = EquipmentRequirementState.KNOWN),
            )
    db.exerciseDao().replaceRequirements(exerciseId, setOf("dumbbells"))
    val gym =
        (repository.saveGymInventory(null, "Зал", setOf("dumbbells")) as SaveGymResult.Saved).gymId
    val localGym = db.gymDao().getGymBySyncId(gym)!!
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Грудь"))
    db.routineDao()
        .replaceRoutineExercises(
            routineId,
            listOf(
                RoutineExerciseEntity(routineId = routineId, exerciseId = exerciseId, position = 0)
            ),
        )
    db.gymDao().replaceRoutineGyms(routineId, listOf(localGym.id))
    db.workoutDao().insertWorkout(WorkoutEntity(id = "active", name = "Активная", startedAt = 1))
    db.gymDao().replaceWorkoutGyms("active", listOf(localGym.id))
    val workoutExercise =
        db.workoutDao()
            .insertWorkoutExercise(
                com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity(
                    workoutId = "active",
                    exerciseId = exerciseId,
                    position = 0,
                ),
            )
    db.workoutDao()
        .insertSet(
            com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity(
                workoutExerciseId = workoutExercise,
                setIndex = 0,
            ),
        )
    val existing = db.exerciseDao().getById(exerciseId)!!

    val result =
        repository.saveExerciseConfiguration(
            NewExerciseConfiguration(
                exercise = existing.copy(name = "Нельзя сохранить"),
                muscles = emptyList(),
                requirements =
                    ExerciseEquipmentRequirements.Required(setOf("dumbbells", "flat_bench")),
            ),
            gymIds = emptySet(),
        )

    assertTrue(result is SaveExerciseConfigurationResult.Conflict)
    result as SaveExerciseConfigurationResult.Conflict
    assertEquals(setOf("flat_bench"), result.details.missingEquipmentIds)
    assertEquals(setOf("dumbbells"), db.exerciseDao().getRequirementIds(exerciseId).toSet())
    assertEquals("Жим с требованиями", db.exerciseDao().getById(exerciseId)!!.name)
    assertEquals(setOf("dumbbells"), db.gymDao().getGymEquipmentIds(localGym.id).toSet())
    assertEquals(1, db.workoutDao().getWorkoutExercises("active").size)
    assertEquals(1, tableCount("workout_sets"))
  }

  @Test
  fun `inventory conflict names only routines with equipment that becomes unavailable`() = runTest {
    val bodyweight =
        db.exerciseDao()
            .insert(
                exercise("Планка")
                    .copy(equipmentRequirementState = EquipmentRequirementState.KNOWN),
            )
    val pullup =
        db.exerciseDao()
            .insert(
                exercise("Подтягивание")
                    .copy(equipmentRequirementState = EquipmentRequirementState.KNOWN),
            )
    db.exerciseDao().replaceRequirements(pullup, setOf("pullup_bar"))
    val gym =
        (repository.saveGymInventory(null, "Зал с турником", setOf("pullup_bar"))
                as SaveGymResult.Saved)
            .gymId
    val localGym = db.gymDao().getGymBySyncId(gym)!!
    val floorRoutine = db.routineDao().upsertRoutine(RoutineEntity(name = "Пол"))
    val pullupRoutine = db.routineDao().upsertRoutine(RoutineEntity(name = "Турник"))
    db.routineDao()
        .replaceRoutineExercises(
            floorRoutine,
            listOf(
                RoutineExerciseEntity(
                    routineId = floorRoutine,
                    exerciseId = bodyweight,
                    position = 0,
                )
            ),
        )
    db.routineDao()
        .replaceRoutineExercises(
            pullupRoutine,
            listOf(
                RoutineExerciseEntity(routineId = pullupRoutine, exerciseId = pullup, position = 0)
            ),
        )
    db.gymDao().replaceRoutineGyms(floorRoutine, listOf(localGym.id))
    db.gymDao().replaceRoutineGyms(pullupRoutine, listOf(localGym.id))

    val result = repository.saveGymInventory(gym, "Зал с турником", emptySet())

    assertTrue(result is SaveGymResult.Conflict)
    result as SaveGymResult.Conflict
    assertEquals(listOf("Турник"), result.details.routines.map { it.name })
    assertEquals(listOf("Подтягивание"), result.details.exercises.map { it.name })
    assertEquals(setOf("pullup_bar"), result.details.missingEquipmentIds)
  }

  @Test
  fun `duplicating a routine copies exercises and gyms atomically`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
    val gym = savedGym("Альфа", setOf(exerciseId))
    val source =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Спина", note = "Тяжёлый день"),
                exercises =
                    listOf(
                        RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                    ),
                gymIds = setOf(gym),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    val copy = repository.duplicateRoutine(source.routineId)!!
    val full = db.routineDao().getRoutineWithExercises(copy.id)!!

    assertEquals("Спина (копия)", full.routine.name)
    assertEquals("Тяжёлый день", full.routine.note)
    assertEquals(listOf(exerciseId), full.exercises.map { it.exercise.id })
    assertEquals(listOf(gym), full.gyms.map { it.syncId })
  }

  @Test
  fun `named routine clone preserves the complete standard source as a new personal program`() =
      runTest {
        val first = db.exerciseDao().insert(exercise("Жим"))
        val second = db.exerciseDao().insert(exercise("Тяга"))
        val gymId = db.gymDao().insertGym(GymEntity(name = "Зал"))
        val sourceId =
            db.routineDao()
                .upsertRoutine(
                    RoutineEntity(name = "Встроенная", note = "Сохранённая заметка"),
                )
        db.routineDao()
            .replaceRoutineExercises(
                sourceId,
                listOf(
                    RoutineExerciseEntity(
                        routineId = sourceId,
                        exerciseId = second,
                        position = 1,
                        restSeconds = 90,
                        plannedSets = listOf(PlannedSet(weightKg = 40.0, reps = 12)),
                    ),
                    RoutineExerciseEntity(
                        routineId = sourceId,
                        exerciseId = first,
                        position = 0,
                        restSeconds = 120,
                        plannedSets =
                            listOf(
                                PlannedSet(weightKg = 50.0, reps = 8),
                                PlannedSet(weightKg = 55.0, reps = 6),
                            ),
                    ),
                ),
            )
        db.gymDao().replaceRoutineGyms(sourceId, listOf(gymId))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE routines SET origin='STANDARD' WHERE id=?",
            arrayOf(sourceId),
        )
        val original = db.routineDao().getRoutineWithExercises(sourceId)!!

        val copy = repository.duplicateRoutine(sourceId, "  Моя программа  ")!!
        val cloned = db.routineDao().getRoutineWithExercises(copy.id)!!

        assertEquals("Моя программа", copy.name)
        assertEquals("PERSONAL", copy.origin)
        assertTrue(copy.id != sourceId)
        assertTrue(copy.syncId != original.routine.syncId)
        assertEquals(original.routine.note, copy.note)
        assertEquals(original.gyms, cloned.gyms)
        assertEquals(
            original.exercises
                .sortedBy { it.routineExercise.position }
                .map { it.routineExercise.copy(id = 0, routineId = 0) },
            cloned.exercises
                .sortedBy { it.routineExercise.position }
                .map { it.routineExercise.copy(id = 0, routineId = 0) },
        )
        assertEquals(original, db.routineDao().getRoutineWithExercises(sourceId))
      }

  @Test
  fun `named routine clone rejects blank names and missing sources without writing`() = runTest {
    val sourceId = db.routineDao().upsertRoutine(RoutineEntity(name = "Программа"))
    assertEquals(null, repository.duplicateRoutine(sourceId, "  "))
    assertEquals(null, repository.duplicateRoutine(Long.MAX_VALUE, "Новая"))
    assertEquals(1, tableCount("routines"))
  }

  @Test
  fun `named gym clone preserves configured and legacy sources without sharing identity`() =
      runTest {
        val exerciseId = db.exerciseDao().insert(exercise("Жим"))
        val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Программа"))
        for (configured in listOf(false, true)) {
          val source = GymEntity(name = "Встроенный $configured", inventoryConfigured = configured)
          val sourceId = db.gymDao().insertGym(source)
          db.gymDao().replaceGymEquipment(sourceId, setOf("dumbbells"))
          db.gymDao().replaceGymExercises(sourceId, listOf(exerciseId))
          db.gymDao().replaceRoutineGyms(routineId, listOf(sourceId))
          db.openHelper.writableDatabase.execSQL(
              "UPDATE gyms SET origin='STANDARD' WHERE id=?",
              arrayOf(sourceId),
          )
          val original = db.gymDao().getGymWithExercises(sourceId)

          val result =
              repository.cloneGym(source.syncId, "  Мой зал $configured  ") as SaveGymResult.Saved
          val copy = db.gymDao().getGymBySyncId(result.gymId)!!

          assertEquals("Мой зал $configured", copy.name)
          assertEquals("PERSONAL", copy.origin)
          assertEquals(configured, copy.inventoryConfigured)
          assertTrue(copy.id != sourceId)
          assertTrue(copy.syncId != source.syncId)
          assertEquals(listOf("dumbbells"), db.gymDao().getGymEquipmentIds(copy.id))
          assertEquals(listOf(exerciseId), db.gymDao().getGymExerciseIds(copy.id))
          assertEquals(listOf(sourceId), db.gymDao().getGymsForRoutine(routineId).map { it.id })
          assertEquals(original, db.gymDao().getGymWithExercises(sourceId))
        }
      }

  @Test
  fun `gym clone rejects duplicate names blanks and missing sources without writing`() = runTest {
    val source = GymEntity(name = "Зал")
    db.gymDao().insertGym(source)
    assertEquals(SaveGymResult.NameAlreadyExists, repository.cloneGym(source.syncId, "  зал  "))
    assertEquals(SaveGymResult.Failure, repository.cloneGym(source.syncId, "  "))
    assertEquals(SaveGymResult.NotFound, repository.cloneGym("missing", "Другой"))
    assertEquals(1, tableCount("gyms"))
  }

  @Test
  fun `gym clone schedules only the committed copy and rolls back failed links`() = runTest {
    val scheduled = mutableListOf<String>()
    var failScheduling = false
    val cloningRepository =
        GymRepositoryImpl(
            database = db,
            gymDao = db.gymDao(),
            exerciseDao = db.exerciseDao(),
            exerciseMuscleDao = db.exerciseMuscleDao(),
            routineDao = db.routineDao(),
            workoutDao = db.workoutDao(),
            configurationUploadScheduler =
                object : ConfigurationUploadScheduler by NoOpConfigurationUploadScheduler {
                  override fun scheduleGym(syncId: String) {
                    if (failScheduling) error("Queue unavailable")
                    scheduled += syncId
                  }
                },
        )
    val source = GymEntity(name = "Встроенный", inventoryConfigured = true)
    val sourceId = db.gymDao().insertGym(source)
    db.gymDao().replaceGymEquipment(sourceId, setOf("dumbbells"))
    db.openHelper.writableDatabase.execSQL(
        "UPDATE gyms SET origin='STANDARD' WHERE id=?",
        arrayOf(sourceId),
    )
    val successful = cloningRepository.cloneGym(source.syncId, "Мой зал") as SaveGymResult.Saved
    assertEquals(listOf(successful.gymId), scheduled)

    db.openHelper.writableDatabase.execSQL(
        "CREATE TEMP TRIGGER reject_clone_equipment BEFORE INSERT ON gym_equipment BEGIN SELECT RAISE(ABORT, 'test failure'); END",
    )
    assertEquals(SaveGymResult.Failure, cloningRepository.cloneGym(source.syncId, "Не сохранён"))
    assertEquals(2, tableCount("gyms"))
    assertEquals(2, tableCount("gym_equipment"))
    assertEquals(listOf(successful.gymId), scheduled)
    db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_clone_equipment")
    failScheduling = true
    assertTrue(cloningRepository.cloneGym(source.syncId, "Локальная копия") is SaveGymResult.Saved)
    assertEquals(3, tableCount("gyms"))
  }

  @Test
  fun `failed named routine clone rolls back its row and preserves the source`() = runTest {
    val exerciseId = db.exerciseDao().insert(exercise("Жим"))
    val sourceId = db.routineDao().upsertRoutine(RoutineEntity(name = "Программа"))
    db.routineDao()
        .replaceRoutineExercises(
            sourceId,
            listOf(
                RoutineExerciseEntity(routineId = sourceId, exerciseId = exerciseId, position = 0),
            ),
        )
    val original = db.routineDao().getRoutineWithExercises(sourceId)
    db.openHelper.writableDatabase.execSQL(
        "CREATE TEMP TRIGGER reject_clone_exercise BEFORE INSERT ON routine_exercises BEGIN SELECT RAISE(ABORT, 'test failure'); END",
    )

    assertEquals(null, repository.duplicateRoutine(sourceId, "Моя копия"))
    assertEquals(1, tableCount("routines"))
    assertEquals(1, tableCount("routine_exercises"))
    assertEquals(original, db.routineDao().getRoutineWithExercises(sourceId))
  }

  @Test
  fun `deleting configuration keeps durable tombstones until upload succeeds`() = runTest {
    val gym = savedGym("Временный")
    val routine =
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Пустая"),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

    assertEquals(DeleteGymResult.Deleted, repository.deleteGym(gym))
    val routineDeletion = repository.deleteRoutine(routine.routineId)!!

    assertEquals(
        listOf(gym),
        db.configurationTombstoneDao().getByKind(ConfigurationTombstoneKind.GYM).map { it.syncId },
    )
    assertEquals(
        listOf(routineDeletion.syncId),
        db.configurationTombstoneDao().getByKind(ConfigurationTombstoneKind.ROUTINE).map {
          it.syncId
        },
    )
  }

  @Test
  fun `deleting a scheduled routine removes calendar entries and preserves completed history`() =
      runTest {
        SyncSchema.install(db.openHelper.writableDatabase)
        val exerciseId = db.exerciseDao().insert(exercise("Жим"))
        val routine =
            repository.saveRoutineConfiguration(
                RoutineConfigurationDraft(
                    routine = RoutineEntity(name = "Запланированная"),
                    exercises =
                        listOf(
                            RoutineExerciseEntity(
                                routineId = 0,
                                exerciseId = exerciseId,
                                position = 0,
                            ),
                        ),
                    gymIds = emptySet(),
                ),
            ) as SaveRoutineConfigurationResult.Saved
        db.calendarPlanDao()
            .upsertPlan(
                CalendarPlanEntity("plan", routine.routineId, 1_800_000_000_000, "UTC"),
            )
        db.calendarPlanDao()
            .upsertRule(
                CalendarRuleEntity("rule", routine.routineId, 1, "08:30", "UTC", "2026-09-20"),
            )
        db.calendarPlanDao()
            .upsertException(
                CalendarExceptionEntity(
                    id = "exception",
                    ruleId = "rule",
                    instanceKey = "2026-09-21",
                    kind = CalendarExceptionKind.CANCELLED,
                ),
            )
        db.workoutDao()
            .insertWorkout(
                WorkoutEntity(
                    id = "completed",
                    routineId = routine.routineId,
                    name = "Завершённая",
                    startedAt = 1_700_000_000_000,
                    finishedAt = 1_700_000_360_000,
                ),
            )
        val workoutExerciseId = insertWorkoutExercise("completed", exerciseId)
        insertSet(workoutExerciseId, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        val generationBeforeDeletion = backendGeneration()

        assertTrue(repository.deleteRoutine(routine.routineId) != null)

        assertEquals(null, db.routineDao().getRoutineWithExercises(routine.routineId))
        assertEquals(0, tableCount("calendar_plans"))
        assertEquals(0, tableCount("calendar_rules"))
        assertEquals(0, tableCount("calendar_exceptions"))
        val preserved = db.workoutDao().getWorkoutFull("completed")!!
        assertEquals(null, preserved.workout.routineId)
        assertEquals(1, preserved.exercises.single().sets.size)
        assertTrue(backendGeneration() >= generationBeforeDeletion + 6)
      }

  private suspend fun savedGym(name: String, exerciseIds: Set<Long> = emptySet()): String =
      (repository.saveGym(null, name, exerciseIds) as SaveGymResult.Saved).gymId

  private fun exercise(name: String) =
      ExerciseEntity(
          name = name,
          muscleGroup = MuscleGroup.FULL_BODY,
          type = ExerciseType.STRENGTH,
          isCustom = true,
      )

  private fun backendGeneration(): Long =
      db.openHelper.writableDatabase.query("SELECT generation FROM backend_state WHERE id=1").use {
        check(it.moveToFirst())
        it.getLong(0)
      }
}
