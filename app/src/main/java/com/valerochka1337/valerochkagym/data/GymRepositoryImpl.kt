package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.domain.CompletedWorkoutRoutineCommand
import com.valerochka1337.valerochkagym.domain.CompletedWorkoutRoutineResult
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.RoutineDeletion
import com.valerochka1337.valerochkagym.domain.SaveExerciseConfigurationResult
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import com.valerochka1337.valerochkagym.domain.completedWorkoutFingerprint
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpConfigurationUploadScheduler
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class GymRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val gymDao: GymDao,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val routineDao: RoutineDao,
    private val workoutDao: WorkoutDao,
    private val configurationUploadScheduler: ConfigurationUploadScheduler =
        NoOpConfigurationUploadScheduler,
    private val writes: WorkoutWriteQueue = WorkoutWriteQueue(),
) : GymRepository {

  override fun observeGyms(): Flow<List<GymConfiguration>> =
      gymDao.observeGyms().flatMapLatest { gyms ->
        if (gyms.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(
            exerciseDao.getAll(),
            gymDao.observeGymExerciseIds(gyms.map(GymEntity::id)),
            gymDao.observeGymEquipment(gyms.map(GymEntity::id)),
        ) { exercises, links, equipment ->
          val exercisesById = exercises.associateBy(ExerciseEntity::id)
          val exerciseIdsByGym = links.groupBy({ it.gymId }, { it.exerciseId })
          val equipmentIdsByGym =
              equipment.groupBy({ it.gymId }, { it.equipmentId }).mapValues { it.value.toSet() }
          gyms.map { gym ->
            GymConfiguration(
                id = gym.syncId,
                name = gym.name,
                exercises = exerciseIdsByGym[gym.id].orEmpty().mapNotNull(exercisesById::get),
                equipmentIds = equipmentIdsByGym[gym.id].orEmpty(),
                inventoryConfigured = gym.inventoryConfigured,
                origin = gym.origin,
                archived = gym.archived,
            )
          }
        }
      }

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = exerciseDao.getAll()

  override fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> {
    if (gymIds.isEmpty()) return exerciseDao.getAll()
    return gymDao.observeGyms().flatMapLatest { gyms ->
      val selected = gyms.filter { it.syncId in gymIds }
      if (selected.size != gymIds.size) return@flatMapLatest flowOf(emptyList())
      combine(
          exerciseDao.getAll(),
          gymDao.observeGymExerciseIds(selected.map(GymEntity::id)),
          gymDao.observeGymEquipment(selected.map(GymEntity::id)),
          exerciseDao.observeAllRequirements(),
          LocalEquipmentCatalog.state,
      ) { exercises, links, equipment, customRequirements, _ ->
        val legacyByGym = links.groupBy({ it.gymId }, { it.exerciseId })
        val equipmentByGym =
            equipment.groupBy({ it.gymId }, { it.equipmentId }).mapValues { it.value.toSet() }
        val customByExercise =
            customRequirements.groupBy({ it.exerciseId }, { it.equipmentId }).mapValues {
              it.value.toSet()
            }
        exercises.filter { exercise ->
          selected.all { gym ->
            if (!gym.inventoryConfigured) exercise.id in legacyByGym[gym.id].orEmpty()
            else
                covers(
                    gym.id,
                    exercise,
                    equipmentByGym[gym.id].orEmpty(),
                    customByExercise[exercise.id],
                )
          }
        }
      }
    }
  }

  override suspend fun getGym(id: String): GymConfiguration? {
    val gym = gymDao.getGymBySyncId(id) ?: return null
    val full = gymDao.getGymWithExercises(gym.id) ?: return null
    return GymConfiguration(
        gym.syncId,
        gym.name,
        full.exercises.sortedBy { it.name.lowercase() },
        gymDao.getGymEquipmentIds(gym.id).toSet(),
        gym.inventoryConfigured,
        gym.origin,
        gym.archived,
    )
  }

  override suspend fun requirementsFor(exercise: ExerciseEntity): ExerciseEquipmentRequirements {
    if (exercise.equipmentRequirementState == EquipmentRequirementState.UNKNOWN)
        return ExerciseEquipmentRequirements.UnknownLegacy
    return exerciseDao.getRequirementIds(exercise.id).toSet().toRequirements()
  }

  override suspend fun saveGymInventory(
      id: String?,
      name: String,
      equipmentIds: Set<String>,
  ): SaveGymResult = runMutation {
    if (equipmentIds.any { !LocalEquipmentCatalog.isKnown(it) })
        return@runMutation SaveGymResult.Failure
    val normalizedName = name.trim()
    if (normalizedName.isEmpty()) return@runMutation SaveGymResult.Failure
    val result =
        database.withTransaction {
          val gyms = gymDao.getGyms()
          if (gyms.any { it.syncId != id && it.name.equals(normalizedName, true) })
              return@withTransaction SaveGymResult.NameAlreadyExists
          val existing = id?.let { syncId -> gymDao.getGymBySyncId(syncId) }
          if (id != null && existing == null) return@withTransaction SaveGymResult.NotFound
          if (existing != null) {
            val conflicts = gymInventoryConflicts(existing.id, equipmentIds)
            if (conflicts.routines.isNotEmpty() || conflicts.exercises.isNotEmpty())
                return@withTransaction SaveGymResult.Conflict(conflicts)
          }
          val saved =
              (existing
                      ?: GymEntity(
                          syncId = id ?: UUID.randomUUID().toString(),
                          name = normalizedName,
                      ))
                  .copy(name = normalizedName, inventoryConfigured = true)
                  .withNextUpdatedAt()
          val localId =
              if (existing == null) gymDao.insertGym(saved)
              else existing.id.also { gymDao.updateGym(saved) }
          gymDao.replaceGymEquipment(localId, equipmentIds)
          // The old link table is an explicit legacy snapshot and must never coexist as
          // authoritative data.
          gymDao.deleteGymExercises(localId)
          SaveGymResult.Saved(saved.syncId)
        }
    if (result is SaveGymResult.Saved) configurationUploadScheduler.scheduleGym(result.gymId)
    result
  }

  override suspend fun saveGym(
      id: String?,
      name: String,
      exerciseIds: Set<Long>,
  ): SaveGymResult = runMutation {
    val normalizedName = name.trim()
    if (normalizedName.isEmpty()) return@runMutation SaveGymResult.Failure
    val result =
        database.withTransaction {
          val gyms = gymDao.getGyms()
          if (gyms.any { it.syncId != id && it.name.equals(normalizedName, ignoreCase = true) }) {
            return@withTransaction SaveGymResult.NameAlreadyExists
          }
          val existing = id?.let { gymDao.getGymBySyncId(it) }
          if (id != null && existing == null) return@withTransaction SaveGymResult.NotFound

          if (existing != null) {
            val conflicts = gymEditConflicts(existing.id, exerciseIds)
            if (conflicts.routines.isNotEmpty() || conflicts.exercises.isNotEmpty()) {
              return@withTransaction SaveGymResult.Conflict(conflicts)
            }
          }
          val saved =
              if (existing == null) {
                GymEntity(syncId = id ?: UUID.randomUUID().toString(), name = normalizedName)
              } else {
                existing.copy(name = normalizedName).withNextUpdatedAt()
              }
          val localId =
              if (existing == null) gymDao.insertGym(saved)
              else existing.id.also { gymDao.updateGym(saved) }
          gymDao.replaceGymExercises(localId, exerciseIds.toList())
          SaveGymResult.Saved(saved.syncId)
        }
    if (result is SaveGymResult.Saved) {
      configurationUploadScheduler.scheduleGym(result.gymId)
    }
    result
  }

  override suspend fun deleteGym(id: String): DeleteGymResult = runDelete {
    val outcome =
        database.withTransaction {
          val gym =
              gymDao.getGymBySyncId(id)
                  ?: return@withTransaction GymDeletionOutcome(DeleteGymResult.NotFound)
          val blockers = linkedReferences(gym.id)
          if (blockers.isNotEmpty()) {
            return@withTransaction GymDeletionOutcome(DeleteGymResult.InUse(blockers))
          }
          if (gymDao.deleteGym(gym.id) == 0) {
            GymDeletionOutcome(DeleteGymResult.NotFound)
          } else {
            val deletedAt = gym.withNextUpdatedAt().updatedAt
            database
                .configurationTombstoneDao()
                .upsert(
                    ConfigurationTombstoneEntity(
                        kind = ConfigurationTombstoneKind.GYM,
                        syncId = gym.syncId,
                        updatedAt = deletedAt,
                    ),
                )
            GymDeletionOutcome(
                result = DeleteGymResult.Deleted,
                syncId = gym.syncId,
                updatedAt = deletedAt,
            )
          }
        }
    if (outcome.result == DeleteGymResult.Deleted) {
      configurationUploadScheduler.scheduleGymDeletion(
          requireNotNull(outcome.syncId),
          requireNotNull(outcome.updatedAt),
      )
    }
    outcome.result
  }

  override suspend fun unavailableExercises(
      gymIds: Set<String>,
      exerciseIds: Set<Long>,
  ): List<ExerciseEntity> {
    if (gymIds.isEmpty() || exerciseIds.isEmpty()) return emptyList()
    val selected =
        resolveGyms(gymIds) ?: return exerciseDao.getAllOnce().filter { it.id in exerciseIds }
    return exerciseDao.getAllOnce().filter {
      it.id in exerciseIds && !isEquipmentAvailable(it, selected, gymDao, exerciseDao)
    }
  }

  override suspend fun saveExerciseConfiguration(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
      workoutId: String?,
  ): SaveExerciseConfigurationResult {
    val result =
        try {
          writes.write {
            database.withTransaction {
              val existing =
                  configuration.exercise.id.takeIf { it != 0L }?.let { exerciseDao.getById(it) }
              if (configuration.exercise.id != 0L && existing == null)
                  return@withTransaction SaveExerciseConfigurationResult.Failure
              val requested = configuration.requirements
              val requirementIds =
                  when (requested) {
                    is ExerciseEquipmentRequirements.Required -> requested.equipmentIds
                    ExerciseEquipmentRequirements.ExplicitNone -> emptySet()
                    ExerciseEquipmentRequirements.UnknownLegacy ->
                        return@withTransaction SaveExerciseConfigurationResult.Failure
                    null ->
                        existing
                            ?.let {
                              requirementIds(it, exerciseDao.getRequirementIds(it.id).toSet())
                            }
                            .orEmpty()
                  }
              if (requirementIds.any { !LocalEquipmentCatalog.isKnown(it) })
                  return@withTransaction SaveExerciseConfigurationResult.Failure
              if (existing?.origin == "STANDARD")
                  return@withTransaction SaveExerciseConfigurationResult.Failure
              val gyms =
                  resolveGyms(gymIds)
                      ?: return@withTransaction SaveExerciseConfigurationResult.Failure
              val candidate =
                  configuration.exercise.copy(
                      id = existing?.id ?: 0,
                      syncId = existing?.syncId ?: configuration.exercise.syncId,
                      updatedAt = existing?.updatedAt ?: configuration.exercise.updatedAt,
                      equipmentRequirementState =
                          if (requested == null)
                              existing?.equipmentRequirementState
                                  ?: configuration.exercise.equipmentRequirementState
                          else EquipmentRequirementState.KNOWN,
                  )
              val unavailableGyms =
                  gyms.filter { gym ->
                    if (candidate.equipmentRequirementState == EquipmentRequirementState.UNKNOWN)
                        true
                    else if (!gym.inventoryConfigured)
                        candidate.id == 0L || candidate.id !in gymDao.getGymExerciseIds(gym.id)
                    else
                        !requirementIds.all {
                          LocalEquipmentCatalog.covers(
                              gymDao.getGymEquipmentIds(gym.id).toSet(),
                              it,
                          )
                        }
                  }
              if (unavailableGyms.isNotEmpty()) {
                return@withTransaction SaveExerciseConfigurationResult.Conflict(
                    GymConfigurationConflict(
                        routines =
                            unavailableGyms.map { GymRoutineReference(-1, "Зал «${it.name}»") },
                        exercises = listOf(candidate),
                        missingEquipmentIds =
                            requirementIds
                                .filterNot { requirement ->
                                  unavailableGyms.all { gym ->
                                    LocalEquipmentCatalog.covers(
                                        gymDao.getGymEquipmentIds(gym.id).toSet(),
                                        requirement,
                                    )
                                  }
                                }
                                .toSet(),
                    ),
                )
              }
              if (existing != null && requested != null) {
                requirementEditConflict(candidate, requirementIds)?.let {
                  return@withTransaction SaveExerciseConfigurationResult.Conflict(it)
                }
              }
              val saved =
                  if (existing == null) {
                    val id =
                        exerciseDao.insert(
                            candidate.copy(
                                id = 0,
                                equipmentRequirementState =
                                    if (requested == null) EquipmentRequirementState.UNKNOWN
                                    else EquipmentRequirementState.KNOWN,
                            )
                        )
                    candidate.copy(
                        id = id,
                        equipmentRequirementState =
                            if (requested == null) EquipmentRequirementState.UNKNOWN
                            else EquipmentRequirementState.KNOWN,
                    )
                  } else {
                    candidate.withNextUpdatedAt().also { updated -> exerciseDao.update(updated) }
                  }
              exerciseMuscleDao.replaceForExercise(
                  saved.id,
                  configuration.muscles.map { it.copy(exerciseId = saved.id) },
              )
              if (requested != null) exerciseDao.replaceRequirements(saved.id, requirementIds)
              if (workoutId != null) addExerciseToActiveWorkout(saved.id, workoutId)
              SaveExerciseConfigurationResult.Saved(saved)
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          SaveExerciseConfigurationResult.Failure
        }
    if (result is SaveExerciseConfigurationResult.Saved) {
      configurationUploadScheduler.scheduleExercise(result.exercise.syncId)
    }
    return result
  }

  override suspend fun createExerciseAndAssign(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
  ): ExerciseEntity? =
      try {
        val saved =
            database.withTransaction {
              val exerciseId = exerciseDao.insert(configuration.exercise.copy(id = 0))
              val exercise = configuration.exercise.copy(id = exerciseId)
              exerciseMuscleDao.replaceForExercise(
                  exerciseId,
                  configuration.muscles.map { it.copy(exerciseId = exerciseId) },
              )
              // Creating an exercise never changes any saved gym inventory.
              exercise
            }
        saved.let { exercise ->
          configurationUploadScheduler.scheduleExercise(exercise.syncId)
          gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        saved
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  override suspend fun assignExerciseToGyms(exerciseId: Long, gymIds: Set<String>): Boolean =
      try {
        val assigned =
            database.withTransaction {
              if (exerciseDao.getById(exerciseId) == null) return@withTransaction false
              val gyms = resolveGyms(gymIds) ?: return@withTransaction false
              gyms.forEach { gym ->
                val updatedIds = (gymDao.getGymExerciseIds(gym.id) + exerciseId).distinct()
                gymDao.replaceGymExercises(gym.id, updatedIds)
                gymDao.updateGym(gym.withNextUpdatedAt())
              }
              true
            }
        if (assigned) gymIds.forEach(configurationUploadScheduler::scheduleGym)
        assigned
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        false
      }

  override suspend fun createExerciseAssignAndAddToWorkout(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
      workoutId: String,
  ): ExerciseEntity? =
      try {
        val saved =
            writes.write {
              database.withTransaction {
                val workout =
                    workoutDao.getWorkoutFull(workoutId)?.workout?.takeIf { it.finishedAt == null }
                        ?: return@withTransaction null
                val snapshotGymIds =
                    gymDao.getGymsForWorkout(workout.id).mapTo(hashSetOf()) { it.syncId }
                if (snapshotGymIds != gymIds) return@withTransaction null

                val exerciseId = exerciseDao.insert(configuration.exercise.copy(id = 0))
                val exercise = configuration.exercise.copy(id = exerciseId)
                exerciseMuscleDao.replaceForExercise(
                    exerciseId,
                    configuration.muscles.map { it.copy(exerciseId = exerciseId) },
                )
                // Creating an exercise never changes any saved gym inventory.
                val position =
                    (workoutDao.getWorkoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) +
                        1
                val workoutExerciseId =
                    workoutDao.insertWorkoutExercise(
                        WorkoutExerciseEntity(
                            workoutId = workoutId,
                            exerciseId = exerciseId,
                            position = position,
                        ),
                    )
                workoutDao.insertSet(
                    WorkoutSetEntity(workoutExerciseId = workoutExerciseId, setIndex = 0),
                )
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
                    arrayOf<Any>(workoutId),
                )
                exercise
              }
            }
        saved?.let { exercise ->
          configurationUploadScheduler.scheduleExercise(exercise.syncId)
          gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        saved
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  override suspend fun updateExerciseAndAssign(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
  ): ExerciseEntity? =
      try {
        val updated =
            database.withTransaction {
              val existing =
                  exerciseDao.getById(configuration.exercise.id) ?: return@withTransaction null
              val saved =
                  configuration.exercise
                      .copy(
                          id = existing.id,
                          syncId = existing.syncId,
                          updatedAt = existing.updatedAt,
                      )
                      .withNextUpdatedAt()
              exerciseDao.update(saved)
              exerciseMuscleDao.replaceForExercise(
                  saved.id,
                  configuration.muscles.map { it.copy(exerciseId = saved.id) },
              )
              // Exercise edits are independent from gym inventory.
              saved
            }
        updated?.let {
          configurationUploadScheduler.scheduleExercise(it.syncId)
          gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        updated
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  override suspend fun updateExerciseAssignAndAddToWorkout(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
      workoutId: String,
  ): ExerciseEntity? =
      try {
        val updated =
            writes.write {
              database.withTransaction {
                val existing =
                    exerciseDao.getById(configuration.exercise.id) ?: return@withTransaction null
                val workout =
                    workoutDao.getWorkoutFull(workoutId)?.workout?.takeIf { it.finishedAt == null }
                        ?: return@withTransaction null
                val snapshotGymIds =
                    gymDao.getGymsForWorkout(workout.id).mapTo(hashSetOf()) { it.syncId }
                if (snapshotGymIds != gymIds) return@withTransaction null

                val saved =
                    configuration.exercise
                        .copy(
                            id = existing.id,
                            syncId = existing.syncId,
                            updatedAt = existing.updatedAt,
                        )
                        .withNextUpdatedAt()
                exerciseDao.update(saved)
                exerciseMuscleDao.replaceForExercise(
                    saved.id,
                    configuration.muscles.map { it.copy(exerciseId = saved.id) },
                )
                // Exercise edits are independent from gym inventory.
                val position =
                    (workoutDao.getWorkoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) +
                        1
                val workoutExerciseId =
                    workoutDao.insertWorkoutExercise(
                        WorkoutExerciseEntity(
                            workoutId = workoutId,
                            exerciseId = saved.id,
                            position = position,
                        ),
                    )
                val previous = workoutDao.lastCompletedSetsForExercise(saved.id).firstOrNull()
                workoutDao.insertSet(
                    WorkoutSetEntity(
                        workoutExerciseId = workoutExerciseId,
                        setIndex = 0,
                        weightKg = previous?.weightKg,
                        reps = previous?.reps,
                        durationSec = previous?.durationSec,
                        speedKmh = previous?.speedKmh,
                        inclinePct = previous?.inclinePct,
                    ),
                )
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
                    arrayOf<Any>(workoutId),
                )
                saved
              }
            }
        updated?.let {
          configurationUploadScheduler.scheduleExercise(it.syncId)
          gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        updated
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  override suspend fun saveRoutineConfiguration(
      draft: RoutineConfigurationDraft,
  ): SaveRoutineConfigurationResult =
      try {
        database.withTransaction {
          if (draft.routine.id == 0L) {
            routineDao.getRoutineBySyncId(draft.routine.syncId)?.let { existing ->
              return@withTransaction SaveRoutineConfigurationResult.Saved(
                  routineId = existing.id,
                  routine = existing,
              )
            }
          }
          val gyms =
              resolveGyms(draft.gymIds)
                  ?: return@withTransaction SaveRoutineConfigurationResult.GymNotFound
          if (gyms.isNotEmpty()) {
            val requestedIds = draft.exercises.mapTo(linkedSetOf()) { it.exerciseId }
            val all = exerciseDao.getAllOnce().filter { it.id in requestedIds }
            val custom =
                exerciseDao
                    .getRequirements(all.map(ExerciseEntity::id))
                    .groupBy({ it.exerciseId }, { it.equipmentId })
                    .mapValues { it.value.toSet() }
            val inventories = gyms.associate { it.id to gymDao.getGymEquipmentIds(it.id).toSet() }
            val legacy = gyms.associate { it.id to gymDao.getGymExerciseIds(it.id).toSet() }
            val conflicts =
                all.filter { exercise ->
                  gyms.any { gym ->
                    if (!gym.inventoryConfigured) exercise.id !in legacy.getValue(gym.id)
                    else
                        !covers(gym.id, exercise, inventories.getValue(gym.id), custom[exercise.id])
                  }
                }
            if (conflicts.isNotEmpty()) {
              return@withTransaction SaveRoutineConfigurationResult.Conflict(conflicts)
            }
          }
          val insertedId = routineDao.upsertRoutine(draft.routine)
          val routineId = draft.routine.id.takeIf { it != 0L } ?: insertedId
          routineDao.replaceRoutineExercises(
              routineId,
              draft.exercises.map { it.copy(routineId = routineId) },
          )
          gymDao.replaceRoutineGyms(routineId, gyms.map(GymEntity::id))
          SaveRoutineConfigurationResult.Saved(
              routineId = routineId,
              routine = draft.routine.copy(id = routineId),
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        SaveRoutineConfigurationResult.Failure
      }

  override suspend fun saveCompletedWorkoutRoutine(
      command: CompletedWorkoutRoutineCommand,
  ): CompletedWorkoutRoutineResult =
      try {
        database.withTransaction {
          when (command) {
            is CompletedWorkoutRoutineCommand.Create -> saveCompletedWorkoutRoutineCreate(command)
            is CompletedWorkoutRoutineCommand.Replace -> saveCompletedWorkoutRoutineReplace(command)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        CompletedWorkoutRoutineResult.Failure
      }

  private suspend fun saveCompletedWorkoutRoutineCreate(
      command: CompletedWorkoutRoutineCommand.Create,
  ): CompletedWorkoutRoutineResult {
    val draft = command.draft
    if (draft.exercises.isEmpty() || draft.routine.id != 0L)
        return CompletedWorkoutRoutineResult.Failure
    routineDao.getRoutineBySyncId(draft.routine.syncId)?.let { existing ->
      return CompletedWorkoutRoutineResult.Saved(existing, replayedWithoutWrite = true)
    }
    return when (val saved = saveRoutineConfiguration(draft)) {
      is SaveRoutineConfigurationResult.Saved ->
          CompletedWorkoutRoutineResult.Saved(saved.routine, replayedWithoutWrite = false)
      is SaveRoutineConfigurationResult.Conflict ->
          CompletedWorkoutRoutineResult.AvailabilityConflict(saved.exercises)
      SaveRoutineConfigurationResult.GymNotFound -> CompletedWorkoutRoutineResult.NotFound
      SaveRoutineConfigurationResult.Failure -> CompletedWorkoutRoutineResult.Failure
    }
  }

  private suspend fun saveCompletedWorkoutRoutineReplace(
      command: CompletedWorkoutRoutineCommand.Replace,
  ): CompletedWorkoutRoutineResult {
    if (command.replacementExercises.isEmpty()) return CompletedWorkoutRoutineResult.Failure
    val current =
        routineDao.getRoutineWithExercises(command.sourceRoutineId)
            ?: return CompletedWorkoutRoutineResult.NotFound
    val routine = current.routine
    if (routine.origin != "PERSONAL" || routine.archived)
        return CompletedWorkoutRoutineResult.ReadOnly
    if (routine.syncId != command.sourceRoutineSyncId) return CompletedWorkoutRoutineResult.Conflict

    val currentFingerprint = current.completedWorkoutFingerprint()
    if (currentFingerprint == command.predictedTargetFingerprint) {
      return CompletedWorkoutRoutineResult.Saved(routine, replayedWithoutWrite = true)
    }
    if (
        currentFingerprint != command.expectedSourceFingerprint ||
            routine.updatedAt != command.expectedUpdatedAt
    ) {
      return CompletedWorkoutRoutineResult.Conflict
    }
    val availability = unavailableRoutineExercises(current, command.replacementExercises)
    if (availability.isNotEmpty())
        return CompletedWorkoutRoutineResult.AvailabilityConflict(availability)

    val updatedRoutine = routine.withNextUpdatedAt()
    routineDao.upsertRoutine(updatedRoutine)
    routineDao.replaceRoutineExercises(
        routine.id,
        command.replacementExercises.map { it.copy(id = 0, routineId = routine.id) },
    )
    return CompletedWorkoutRoutineResult.Saved(updatedRoutine, replayedWithoutWrite = false)
  }

  private suspend fun unavailableRoutineExercises(
      source: com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises,
      replacementExercises:
          List<com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity>,
  ): List<ExerciseEntity> {
    if (source.gyms.isEmpty()) return emptyList()
    val requestedIds = replacementExercises.mapTo(linkedSetOf()) { it.exerciseId }
    val exercises = exerciseDao.getAllOnce().filter { it.id in requestedIds }
    if (exercises.size != requestedIds.size) return exercises
    val custom =
        exerciseDao
            .getRequirements(exercises.map(ExerciseEntity::id))
            .groupBy({ it.exerciseId }, { it.equipmentId })
            .mapValues { it.value.toSet() }
    val inventories = source.gyms.associate { it.id to gymDao.getGymEquipmentIds(it.id).toSet() }
    val legacy = source.gyms.associate { it.id to gymDao.getGymExerciseIds(it.id).toSet() }
    return exercises.filter { exercise ->
      source.gyms.any { gym ->
        if (!gym.inventoryConfigured) exercise.id !in legacy.getValue(gym.id)
        else !covers(gym.id, exercise, inventories.getValue(gym.id), custom[exercise.id])
      }
    }
  }

  override suspend fun duplicateRoutine(sourceRoutineId: Long, name: String?): RoutineEntity? =
      try {
        database.withTransaction {
          val normalizedName = name?.trim()
          if (normalizedName != null && normalizedName.isEmpty()) return@withTransaction null
          val source =
              routineDao.getRoutineWithExercises(sourceRoutineId) ?: return@withTransaction null
          val copy =
              RoutineEntity(
                  name = normalizedName ?: "${source.routine.name} (копия)",
                  note = source.routine.note,
              )
          val newId = routineDao.upsertRoutine(copy)
          routineDao.replaceRoutineExercises(
              newId,
              source.exercises
                  .sortedBy { it.routineExercise.position }
                  .mapIndexed { index, item ->
                    item.routineExercise.copy(
                        id = 0,
                        routineId = newId,
                        position = index,
                    )
                  },
          )
          gymDao.replaceRoutineGyms(newId, source.gyms.map { it.id })
          copy.copy(id = newId)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  override suspend fun cloneGym(sourceGymId: String, name: String): SaveGymResult = runMutation {
    val normalizedName = name.trim()
    if (normalizedName.isEmpty()) return@runMutation SaveGymResult.Failure
    val result =
        database.withTransaction {
          val source =
              gymDao.getGymBySyncId(sourceGymId) ?: return@withTransaction SaveGymResult.NotFound
          if (gymDao.getGyms().any { it.name.equals(normalizedName, ignoreCase = true) }) {
            return@withTransaction SaveGymResult.NameAlreadyExists
          }
          val copy =
              GymEntity(name = normalizedName, inventoryConfigured = source.inventoryConfigured)
          val localId = gymDao.insertGym(copy)
          gymDao.replaceGymEquipment(localId, gymDao.getGymEquipmentIds(source.id).toSet())
          gymDao.replaceGymExercises(localId, gymDao.getGymExerciseIds(source.id))
          SaveGymResult.Saved(copy.syncId)
        }
    if (result is SaveGymResult.Saved) {
      try {
        configurationUploadScheduler.scheduleGym(result.gymId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Копия уже сохранена. Повтор клонирования из-за ошибки очереди создаст дубликат;
        // следующая общая синхронизация подберёт локальную запись.
      }
    }
    result
  }

  override suspend fun deleteRoutine(routineId: Long): RoutineDeletion? =
      try {
        database.withTransaction {
          val routine =
              routineDao.getRoutineWithExercises(routineId)?.routine ?: return@withTransaction null
          val deletedAt = routine.withNextUpdatedAt().updatedAt
          database
              .configurationTombstoneDao()
              .upsert(
                  ConfigurationTombstoneEntity(
                      kind = ConfigurationTombstoneKind.ROUTINE,
                      syncId = routine.syncId,
                      updatedAt = deletedAt,
                  ),
              )
          val calendarDao = database.calendarPlanDao()
          calendarDao.rulesForRoutine(routineId).forEach { rule ->
            calendarDao.deleteExceptions(rule.id)
            calendarDao.deleteRule(rule.id)
          }
          calendarDao.plansForRoutine(routineId).forEach { plan -> calendarDao.deletePlan(plan.id) }
          routineDao.deleteRoutine(routineId)
          RoutineDeletion(routine.syncId, deletedAt)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  private suspend fun resolveGyms(ids: Set<String>): List<GymEntity>? {
    if (ids.isEmpty()) return emptyList()
    val gyms = gymDao.getGyms().filter { it.syncId in ids }
    return gyms.takeIf { it.size == ids.size }
  }

  private suspend fun addExerciseToActiveWorkout(exerciseId: Long, workoutId: String) {
    val workout =
        workoutDao.getWorkoutFull(workoutId)?.workout?.takeIf { it.finishedAt == null }
            ?: throw IllegalArgumentException("Workout is not active")
    val exercise = requireNotNull(exerciseDao.getById(exerciseId))
    val gyms = gymDao.getGymsForWorkout(workout.id)
    if (!isEquipmentAvailable(exercise, gyms, gymDao, exerciseDao)) {
      throw IllegalArgumentException("Exercise is unavailable in workout gyms")
    }
    val position =
        (workoutDao.getWorkoutExercises(workout.id).maxOfOrNull { it.position } ?: -1) + 1
    val workoutExerciseId =
        workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(
                workoutId = workout.id,
                exerciseId = exerciseId,
                position = position,
            ),
        )
    workoutDao.insertSet(WorkoutSetEntity(workoutExerciseId = workoutExerciseId, setIndex = 0))
    database.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workoutId),
    )
  }

  private suspend fun requirementEditConflict(
      candidate: ExerciseEntity,
      requirements: Set<String>,
  ): GymConfigurationConflict? {
    val blockedExercises = mutableListOf<ExerciseEntity>()
    val references = linkedSetOf<GymRoutineReference>()
    val inventories = mutableListOf<Set<String>>()
    routineDao.observeRoutinesFull().first().forEach { routine ->
      if (routine.exercises.none { it.exercise.id == candidate.id }) return@forEach
      routine.gyms
          .filter { it.inventoryConfigured }
          .forEach { gym ->
            val equipment = gymDao.getGymEquipmentIds(gym.id).toSet()
            if (!requirements.all { LocalEquipmentCatalog.covers(equipment, it) }) {
              blockedExercises += candidate
              references += GymRoutineReference(routine.routine.id, routine.routine.name)
              inventories += equipment
            }
          }
    }
    workoutDao.getActiveWorkoutId()?.let { workoutId ->
      val workout = workoutDao.getWorkoutFull(workoutId) ?: return@let
      if (workout.exercises.any { it.exercise.id == candidate.id }) {
        gymDao
            .getGymsForWorkout(workoutId)
            .filter { it.inventoryConfigured }
            .forEach { gym ->
              val equipment = gymDao.getGymEquipmentIds(gym.id).toSet()
              if (!requirements.all { LocalEquipmentCatalog.covers(equipment, it) }) {
                blockedExercises += candidate
                references +=
                    GymRoutineReference(-1, "Активная тренировка «${workout.workout.name}»")
                inventories += equipment
              }
            }
      }
    }
    if (blockedExercises.isEmpty()) return null
    return GymConfigurationConflict(
        routines = references.toList(),
        exercises = blockedExercises.distinctBy(ExerciseEntity::id),
        missingEquipmentIds =
            requirements
                .filterNot { required ->
                  inventories.all { LocalEquipmentCatalog.covers(it, required) }
                }
                .toSet(),
    )
  }

  private suspend fun gymEditConflicts(
      gymId: Long,
      proposedExerciseIds: Set<Long>,
  ): GymConfigurationConflict {
    val routines = gymDao.getLinkedRoutines(gymId)
    val conflictingExerciseIds = linkedSetOf<Long>()
    val references = linkedSetOf<GymRoutineReference>()
    routines.forEach { routine ->
      val unavailableIds =
          routineDao
              .getRoutineWithExercises(routine.id)
              ?.exercises
              .orEmpty()
              .map { it.exercise.id }
              .filter { it !in proposedExerciseIds }
      if (unavailableIds.isNotEmpty()) {
        conflictingExerciseIds += unavailableIds
        references += GymRoutineReference(routine.id, routine.name)
      }
    }
    val activeWorkouts = gymDao.getLinkedActiveWorkouts(gymId)
    activeWorkouts.forEach { workout ->
      val unavailableIds =
          workoutDao
              .getWorkoutFull(workout.id)
              ?.exercises
              .orEmpty()
              .map { it.exercise.id }
              .filter { it !in proposedExerciseIds }
      if (unavailableIds.isNotEmpty()) {
        conflictingExerciseIds += unavailableIds
        references += GymRoutineReference(-1, "Активная тренировка «${workout.name}»")
      }
    }
    val exercises = exerciseDao.getAllOnce().filter { it.id in conflictingExerciseIds }
    return GymConfigurationConflict(references.toList(), exercises)
  }

  private suspend fun gymInventoryConflicts(
      gymId: Long,
      proposedEquipmentIds: Set<String>,
  ): GymConfigurationConflict {
    val linkedReferences = mutableListOf<Pair<GymRoutineReference, List<ExerciseEntity>>>()
    gymDao.getLinkedRoutines(gymId).forEach { routine ->
      val exercises =
          routineDao.getRoutineWithExercises(routine.id)?.exercises.orEmpty().map { it.exercise }
      linkedReferences += GymRoutineReference(routine.id, routine.name) to exercises
    }
    gymDao.getLinkedActiveWorkouts(gymId).forEach { workout ->
      val exercises = workoutDao.getWorkoutFull(workout.id)?.exercises.orEmpty().map { it.exercise }
      linkedReferences +=
          GymRoutineReference(-1, "Активная тренировка «${workout.name}»") to exercises
    }
    val linkedExercises = linkedReferences.flatMap { it.second }.distinctBy(ExerciseEntity::id)
    val custom =
        exerciseDao
            .getRequirements(linkedExercises.map(ExerciseEntity::id))
            .groupBy({ it.exerciseId }, { it.equipmentId })
            .mapValues { it.value.toSet() }
    val unavailable = linkedSetOf<ExerciseEntity>()
    val references = linkedSetOf<GymRoutineReference>()
    linkedReferences.forEach { (reference, exercises) ->
      val blocked =
          exercises.filterNot { exercise ->
            covers(gymId, exercise, proposedEquipmentIds, custom[exercise.id])
          }
      if (blocked.isNotEmpty()) {
        unavailable.addAll(blocked)
        references += reference
      }
    }
    val missing =
        unavailable
            .flatMap { requirementIds(it, custom[it.id]) }
            .filterNot { LocalEquipmentCatalog.covers(proposedEquipmentIds, it) }
            .toSet()
    return GymConfigurationConflict(
        routines = references.toList(),
        exercises = unavailable.toList(),
        missingEquipmentIds = missing,
    )
  }

  private fun covers(
      gymId: Long,
      exercise: ExerciseEntity,
      equipmentIds: Set<String>,
      customRequirements: Set<String>?,
  ): Boolean {
    if (exercise.equipmentRequirementState == EquipmentRequirementState.UNKNOWN) return false
    return requirementIds(exercise, customRequirements).all {
      LocalEquipmentCatalog.covers(equipmentIds, it)
    }
  }

  private fun requirementIds(
      exercise: ExerciseEntity,
      customRequirements: Set<String>?,
  ): Set<String> = customRequirements.orEmpty()

  private fun Set<String>.toRequirements(): ExerciseEquipmentRequirements =
      if (isEmpty()) ExerciseEquipmentRequirements.ExplicitNone
      else ExerciseEquipmentRequirements.Required(this)

  private suspend fun linkedReferences(gymId: Long): List<GymRoutineReference> =
      gymDao.getLinkedRoutines(gymId).map { GymRoutineReference(it.id, it.name) } +
          gymDao.getLinkedActiveWorkouts(gymId).map {
            GymRoutineReference(-1, "Активная тренировка «${it.name}»")
          }

  private suspend inline fun runMutation(block: suspend () -> SaveGymResult): SaveGymResult =
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        SaveGymResult.Failure
      }

  private suspend inline fun runDelete(block: suspend () -> DeleteGymResult): DeleteGymResult =
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        DeleteGymResult.Failure
      }
}

private data class GymDeletionOutcome(
    val result: DeleteGymResult,
    val syncId: String? = null,
    val updatedAt: Long? = null,
)
