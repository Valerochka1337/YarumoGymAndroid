package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutUnavailableException
import com.valerochka1337.valerochkagym.domain.CompletedSetEditResult
import com.valerochka1337.valerochkagym.domain.NoteSaveResult
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Имя тренировки без программы. */
private const val EMPTY_WORKOUT_NAME = "Тренировка"
private const val MAX_NOTE_CODE_POINTS = 2_000

class ActiveWorkoutRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
    private val gymDao: GymDao = database.gymDao(),
    private val writes: WorkoutWriteQueue = WorkoutWriteQueue(),
) : ActiveWorkoutRepository {

  override suspend fun startFromRoutine(routineId: Long): String =
      writes.write {
        database.withTransaction {
          workoutDao.getActiveWorkoutId()?.let {
            return@withTransaction it
          }

          val routine =
              routineDao.getRoutineWithExercises(routineId)
                  ?: return@withTransaction startEmptyInTransaction()
          val gymIds = routine.gyms.map { it.id }
          if (gymIds.isNotEmpty()) {
            val unavailable =
                routine.exercises
                    .map { it.exercise }
                    .filterNot {
                      isEquipmentAvailable(it, routine.gyms, gymDao, database.exerciseDao())
                    }
                    .distinctBy { it.id }
            if (unavailable.isNotEmpty()) {
              throw RoutineGymConflictException(unavailable.map { it.name })
            }
          }
          val workoutId = newId()
          workoutDao.insertWorkout(
              WorkoutEntity(
                  id = workoutId,
                  routineId = routineId,
                  name = routine.routine.name,
                  startedAt = now(),
                  finishedAt = null,
              ),
          )
          gymDao.replaceWorkoutGyms(workoutId, gymIds)
          routine.exercises
              .sortedBy { it.routineExercise.position }
              .forEachIndexed { position, item ->
                val workoutExerciseId =
                    workoutDao.insertWorkoutExercise(
                        WorkoutExerciseEntity(
                            workoutId = workoutId,
                            exerciseId = item.exercise.id,
                            position = position,
                        ),
                    )
                val previous = workoutDao.lastCompletedSetsForExercise(item.exercise.id)
                val sets =
                    item.routineExercise.plannedSets.mapIndexed { index, planned ->
                      val source = previous.getOrNull(index)
                      source?.copy(
                          id = 0,
                          workoutExerciseId = workoutExerciseId,
                          setIndex = index,
                          isCompleted = false,
                          note = "",
                          syncId = UUID.randomUUID().toString(),
                          originalWeightKg = source.weightKg,
                          originalReps = source.reps,
                          originalDurationSec = source.durationSec,
                          originalSpeedKmh = source.speedKmh,
                          originalInclinePct = source.inclinePct,
                          targetWeightKg = source.weightKg,
                          targetReps = source.reps,
                          targetDurationSec = source.durationSec,
                          targetSpeedKmh = source.speedKmh,
                          targetInclinePct = source.inclinePct,
                      ) ?: planned.toSet(workoutExerciseId, index)
                    }
                if (sets.isNotEmpty()) workoutDao.insertSets(sets)
              }
          workoutId
        }
      }

  override suspend fun startEmpty(): String =
      writes.write { database.withTransaction { startEmptyInTransaction() } }

  override fun observeActive(): Flow<WorkoutFull?> =
      workoutDao.observeActiveWorkout().map { full -> full?.let(::sortedWorkoutFull) }

  override suspend fun getSet(setId: Long): WorkoutSetEntity? = workoutDao.getSet(setId)

  override suspend fun mutateSet(
      setId: Long,
      transform: (WorkoutSetEntity) -> WorkoutSetEntity,
  ): Boolean =
      writes.write {
        database.withTransaction {
          val current = workoutDao.getSet(setId) ?: return@withTransaction false
          val workoutId = activeWorkoutIdForSet(setId) ?: return@withTransaction false
          val changed = transform(current).copy(id = current.id, note = current.note)
          workoutDao.updateSet(
              if (changed.isCompleted)
                  changed.copy(
                      actualWeightKg = changed.weightKg,
                      actualReps = changed.reps,
                      actualDurationSec = changed.durationSec,
                      actualSpeedKmh = changed.speedKmh,
                      actualInclinePct = changed.inclinePct,
                  )
              else
                  changed.copy(
                      targetWeightKg = changed.weightKg,
                      targetReps = changed.reps,
                      targetDurationSec = changed.durationSec,
                      targetSpeedKmh = changed.speedKmh,
                      targetInclinePct = changed.inclinePct,
                      actualRir = changed.actualRir,
                  )
          )
          incrementCoachRevision(workoutId)
          true
        }
      }

  override suspend fun updateSet(set: WorkoutSetEntity) {
    mutateSet(set.id) { current -> set.copy(note = current.note) }
  }

  override suspend fun saveWorkoutNote(workoutId: String, text: String): NoteSaveResult {
    val note = text.trim()
    if (note.codePointCount(0, note.length) > MAX_NOTE_CODE_POINTS) return NoteSaveResult.TooLong
    return writes.write {
      database.withTransaction {
        if (workoutDao.updateActiveWorkoutNote(workoutId, note) == 1) {
          incrementCoachRevision(workoutId)
          NoteSaveResult.Saved
        } else NoteSaveResult.MissingOrInactive
      }
    }
  }

  override suspend fun saveSetNote(workoutId: String, setId: Long, text: String): NoteSaveResult {
    val note = text.trim()
    if (note.codePointCount(0, note.length) > MAX_NOTE_CODE_POINTS) return NoteSaveResult.TooLong
    return writes.write {
      database.withTransaction {
        if (workoutDao.updateActiveSetNote(workoutId, setId, note) == 1) {
          incrementCoachRevision(workoutId)
          NoteSaveResult.Saved
        } else NoteSaveResult.MissingOrInactive
      }
    }
  }

  override suspend fun updateCompletedSetNumbers(
      set: WorkoutSetEntity,
      type: ExerciseType,
  ): CompletedSetEditResult =
      writes.write {
        database.withTransaction {
          val workoutId =
              activeWorkoutIdForSet(set.id)
                  ?: return@withTransaction CompletedSetEditResult.MissingOrInactive
          val changed =
              when (type) {
                ExerciseType.STRENGTH ->
                    workoutDao.updateCompletedStrengthNumbers(set.id, set.weightKg, set.reps)
                ExerciseType.TIMED ->
                    workoutDao.updateCompletedTimedNumbers(set.id, set.durationSec)
                ExerciseType.CARDIO ->
                    workoutDao.updateCompletedCardioNumbers(
                        set.id,
                        set.durationSec,
                        set.speedKmh,
                        set.inclinePct,
                    )
              }
          if (changed == 1) {
            incrementCoachRevision(workoutId)
            CompletedSetEditResult.Saved
          } else {
            CompletedSetEditResult.MissingOrInactive
          }
        }
      }

  override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) =
      writes.write {
        database.withTransaction {
          val workoutId = workoutIdForSet(setId) ?: return@withTransaction
          workoutDao.setSetCompleted(setId, completed, completedAt = if (completed) now() else null)
          if (workoutDao.getActiveWorkoutId() == workoutId) incrementCoachRevision(workoutId)
        }
      }

  override suspend fun addSet(workoutExerciseId: Long) =
      writes.write {
        database.withTransaction {
          val existing = workoutDao.getSetsForWorkoutExercise(workoutExerciseId)
          val nextIndex = (existing.maxOfOrNull { it.setIndex } ?: -1) + 1
          val last = existing.lastOrNull()
          workoutDao.insertSet(
              WorkoutSetEntity(
                  workoutExerciseId = workoutExerciseId,
                  setIndex = nextIndex,
                  weightKg = last?.weightKg,
                  reps = last?.reps,
                  durationSec = last?.durationSec,
                  speedKmh = last?.speedKmh,
                  inclinePct = last?.inclinePct,
                  note = "",
                  isCompleted = false,
                  originalWeightKg = last?.weightKg,
                  originalReps = last?.reps,
                  originalDurationSec = last?.durationSec,
                  originalSpeedKmh = last?.speedKmh,
                  originalInclinePct = last?.inclinePct,
                  targetWeightKg = last?.weightKg,
                  targetReps = last?.reps,
                  targetDurationSec = last?.durationSec,
                  targetSpeedKmh = last?.speedKmh,
                  targetInclinePct = last?.inclinePct,
              ),
          )
          val workoutId =
              workoutDao
                  .getWorkoutExercises(workoutDao.getActiveWorkoutId().orEmpty())
                  .firstOrNull { it.id == workoutExerciseId }
                  ?.workoutId
          if (workoutId != null) incrementCoachRevision(workoutId)
          Unit
        }
      }

  override suspend fun deleteSet(setId: Long) =
      writes.write {
        database.withTransaction {
          val workoutId = activeWorkoutIdForSet(setId) ?: return@withTransaction
          workoutDao.deleteSet(setId)
          incrementCoachRevision(workoutId)
        }
      }

  override suspend fun addExercise(workoutId: String, exerciseId: Long): Long =
      writes.write {
        database.withTransaction {
          workoutDao.getWorkoutFull(workoutId)?.workout?.takeIf { it.finishedAt == null }
              ?: throw ActiveWorkoutUnavailableException()
          val gyms = gymDao.getGymsForWorkout(workoutId)
          if (gyms.isNotEmpty()) {
            val selected = database.exerciseDao().getById(exerciseId)
            val available =
                selected != null &&
                    isEquipmentAvailable(selected, gyms, gymDao, database.exerciseDao())
            if (!available) {
              val name = database.exerciseDao().getById(exerciseId)?.name ?: "Упражнение"
              throw RoutineGymConflictException(listOf(name))
            }
          }
          val existing = workoutDao.getWorkoutExercises(workoutId)
          val position = (existing.maxOfOrNull { it.position } ?: -1) + 1
          val workoutExerciseId =
              workoutDao.insertWorkoutExercise(
                  WorkoutExerciseEntity(
                      workoutId = workoutId,
                      exerciseId = exerciseId,
                      position = position,
                  ),
              )
          val previous = workoutDao.lastCompletedSetsForExercise(exerciseId).firstOrNull()
          workoutDao.insertSet(
              WorkoutSetEntity(
                  workoutExerciseId = workoutExerciseId,
                  setIndex = 0,
                  weightKg = previous?.weightKg,
                  reps = previous?.reps,
                  durationSec = previous?.durationSec,
                  speedKmh = previous?.speedKmh,
                  inclinePct = previous?.inclinePct,
                  isCompleted = false,
                  originalWeightKg = previous?.weightKg,
                  originalReps = previous?.reps,
                  originalDurationSec = previous?.durationSec,
                  originalSpeedKmh = previous?.speedKmh,
                  originalInclinePct = previous?.inclinePct,
                  targetWeightKg = previous?.weightKg,
                  targetReps = previous?.reps,
                  targetDurationSec = previous?.durationSec,
                  targetSpeedKmh = previous?.speedKmh,
                  targetInclinePct = previous?.inclinePct,
              ),
          )
          incrementCoachRevision(workoutId)
          workoutExerciseId
        }
      }

  override suspend fun deleteExercise(workoutExerciseId: Long) =
      writes.write {
        database.withTransaction {
          val workoutId =
              workoutDao.getActiveWorkoutId()?.let { id ->
                workoutDao
                    .getWorkoutExercises(id)
                    .firstOrNull { it.id == workoutExerciseId }
                    ?.workoutId
              } ?: return@withTransaction
          workoutDao.deleteWorkoutExercise(workoutExerciseId)
          incrementCoachRevision(workoutId)
        }
      }

  override suspend fun reorderExercises(
      workoutId: String,
      orderedWorkoutExerciseIds: List<Long>,
  ) =
      writes.write {
        database.withTransaction {
          val existing = workoutDao.getWorkoutExercises(workoutId)
          val existingIds = existing.map { it.id }
          require(
              orderedWorkoutExerciseIds.size == existingIds.size &&
                  orderedWorkoutExerciseIds.toSet().size == orderedWorkoutExerciseIds.size &&
                  orderedWorkoutExerciseIds.toSet() == existingIds.toSet(),
          ) {
            "The supplied exercise ids must be the complete unique set for workout $workoutId"
          }

          val byId = existing.associateBy { it.id }
          workoutDao.updateWorkoutExercises(
              orderedWorkoutExerciseIds.mapIndexed { position, id ->
                requireNotNull(byId[id]).copy(position = position)
              },
          )
          incrementCoachRevision(workoutId)
        }
      }

  override suspend fun finish(workoutId: String) =
      writes.write {
        database.withTransaction {
          val full = workoutDao.getWorkoutFull(workoutId) ?: return@withTransaction
          // Идемпотентность: повторный тап «Завершить» не должен перезаписывать метку времени.
          if (full.workout.finishedAt != null) return@withTransaction
          for (exercise in full.exercises) {
            val remaining =
                exercise.sets.filter { set ->
                  val empty = !set.isCompleted && set.isBlank()
                  if (empty) workoutDao.deleteSet(set.id)
                  !empty
                }
            if (remaining.isEmpty()) workoutDao.deleteWorkoutExercise(exercise.workoutExercise.id)
          }
          workoutDao.setFinishedAt(workoutId, now())
          database.coachRunDao().markDirty(workoutId)
        }
      }

  override suspend fun discard(workoutId: String) =
      writes.write {
        database.withTransaction {
          database.coachRunDao().markDirty(workoutId)
          workoutDao.deleteWorkout(workoutId)
        }
      }

  private suspend fun startEmptyInTransaction(): String {
    workoutDao.getActiveWorkoutId()?.let {
      return it
    }
    val workoutId = newId()
    workoutDao.insertWorkout(
        WorkoutEntity(
            id = workoutId,
            routineId = null,
            name = EMPTY_WORKOUT_NAME,
            startedAt = now(),
            finishedAt = null,
        ),
    )
    return workoutId
  }

  private fun now(): Long = System.currentTimeMillis()

  private suspend fun activeWorkoutIdForSet(setId: Long): String? {
    val workoutId = workoutDao.getActiveWorkoutId() ?: return null
    return workoutDao
        .getWorkoutExercises(workoutId)
        .firstOrNull { section ->
          workoutDao.getSetsForWorkoutExercise(section.id).any { it.id == setId }
        }
        ?.workoutId
  }

  private fun workoutIdForSet(setId: Long): String? =
      database.openHelper.writableDatabase
          .query(
              "SELECT we.workoutId FROM workout_sets ws JOIN workout_exercises we ON we.id=ws.workoutExerciseId WHERE ws.id=?",
              arrayOf(setId),
          )
          .use { row -> if (row.moveToFirst()) row.getString(0) else null }

  private suspend fun incrementCoachRevision(workoutId: String) {
    database.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workoutId),
    )
    database.coachRunDao().markDirty(workoutId)
  }

  private fun newId(): String = UUID.randomUUID().toString()
}

private fun WorkoutSetEntity.isBlank(): Boolean =
    weightKg == null &&
        reps == null &&
        durationSec == null &&
        speedKmh == null &&
        inclinePct == null &&
        note.isBlank()

private fun PlannedSet.toSet(workoutExerciseId: Long, setIndex: Int): WorkoutSetEntity =
    WorkoutSetEntity(
        workoutExerciseId = workoutExerciseId,
        setIndex = setIndex,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
        isCompleted = false,
        originalWeightKg = weightKg,
        originalReps = reps,
        originalDurationSec = durationSec,
        originalSpeedKmh = speedKmh,
        originalInclinePct = inclinePct,
        targetWeightKg = weightKg,
        targetReps = reps,
        targetDurationSec = durationSec,
        targetSpeedKmh = speedKmh,
        targetInclinePct = inclinePct,
    )

/** Доменная сортировка дерева тренировки: упражнения по position, подходы по setIndex. */
internal fun sortedWorkoutFull(full: WorkoutFull): WorkoutFull =
    full.copy(
        exercises =
            full.exercises
                .sortedBy { it.workoutExercise.position }
                .map { exercise: WorkoutExerciseWithSets ->
                  exercise.copy(sets = exercise.sets.sortedBy { it.setIndex })
                },
    )
