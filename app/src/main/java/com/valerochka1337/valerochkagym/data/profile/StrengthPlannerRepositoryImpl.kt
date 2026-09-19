package com.valerochka1337.valerochkagym.data.profile

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.StrengthPlannerProfileDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreferenceEntity
import com.valerochka1337.valerochkagym.data.db.entity.StrengthPlannerKeyExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.StrengthPlannerProfileEntity
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.PlannerExerciseChoice
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.StrengthExerciseCandidate
import com.valerochka1337.valerochkagym.domain.StrengthPlannerRepository
import com.valerochka1337.valerochkagym.domain.StrengthPlannerSaveResult
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.service.WallClock
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StrengthPlannerRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val profileDao: StrengthPlannerProfileDao,
    private val exerciseDao: ExerciseDao,
    private val sync: BackendSync,
    private val sessions: BackendSessionStore,
    private val clock: WallClock,
) : StrengthPlannerRepository {
  private val mutationMutex = Mutex()

  override fun observeLiveStrengthExercises(): Flow<List<StrengthExerciseCandidate>> =
      exerciseDao.getAll().map { exercises ->
        exercises
            .asSequence()
            .filter { it.type == ExerciseType.STRENGTH && !it.archived }
            .map { StrengthExerciseCandidate(it.id, it.syncId, it.name) }
            .sortedBy { it.name.lowercase() }
            .toList()
      }

  override fun observeLivePlannerExercises(): Flow<List<StrengthExerciseCandidate>> =
      exerciseDao.getAll().map { exercises ->
        exercises
            .asSequence()
            .filter { !it.archived }
            .map { StrengthExerciseCandidate(it.id, it.syncId, it.name) }
            .sortedBy { it.name.lowercase() }
            .toList()
      }

  override fun observe(target: ProfileEditTarget): Flow<List<KeyExerciseChoice>?> =
      combine(
          profileDao.observeKeyExercises(target.scope),
          exerciseDao.getAll(),
          sync.transfer,
          sessions.session,
      ) { choices, exercises, _, _ ->
        if (!targetStillCurrent(target)) null
        else {
          val ids = exercises.associateBy { it.syncId }
          choices.map { choice ->
            KeyExerciseChoice(
                exerciseId = ids[choice.exerciseSyncId]?.id,
                exerciseSyncId = choice.exerciseSyncId,
                priority = choice.priority,
            )
          }
        }
      }

  override fun observePlannerPreferences(
      target: ProfileEditTarget,
  ): Flow<List<PlannerExerciseChoice>?> =
      combine(
          database.plannerExercisePreferenceDao().observe(target.scope),
          exerciseDao.getAll(),
          sync.transfer,
          sessions.session,
      ) { choices, exercises, _, _ ->
        if (!targetStillCurrent(target)) null
        else {
          val ids = exercises.associateBy { it.syncId }
          choices.map {
            PlannerExerciseChoice(ids[it.exerciseSyncId]?.id, it.exerciseSyncId, it.preference)
          }
        }
      }

  override suspend fun savePlannerPreferences(
      target: ProfileEditTarget,
      choices: List<PlannerExerciseChoice>,
  ): StrengthPlannerSaveResult =
      mutationMutex.withLock {
        database.withTransaction {
          if (!targetStillCurrent(target))
              return@withTransaction StrengthPlannerSaveResult.StaleTarget
          val live = exerciseDao.getAllOnce().filterNot { it.archived }.associateBy { it.syncId }
          if (
              choices.map { it.exerciseSyncId }.distinct().size != choices.size ||
                  choices.any { it.exerciseSyncId !in live }
          )
              return@withTransaction StrengthPlannerSaveResult.Invalid
          database.plannerExercisePreferenceDao().delete(target.scope)
          database
              .plannerExercisePreferenceDao()
              .upsert(
                  choices
                      .sortedBy { it.exerciseSyncId }
                      .map {
                        PlannerExercisePreferenceEntity(
                            target.scope,
                            it.exerciseSyncId,
                            it.preference,
                        )
                      }
              )
          StrengthPlannerSaveResult.Saved
        }
      }

  override suspend fun save(
      target: ProfileEditTarget,
      profileGoal: TrainingGoal?,
      choices: List<KeyExerciseChoice>,
  ): StrengthPlannerSaveResult {
    if (profileGoal != TrainingGoal.STRENGTH || !validShape(choices))
        return StrengthPlannerSaveResult.Invalid
    return mutationMutex.withLock {
      database.withTransaction {
        if (!targetStillCurrent(target))
            return@withTransaction StrengthPlannerSaveResult.StaleTarget
        val exercises = exerciseDao.getAllOnce().associateBy { it.id }
        // Existing stale rows are removable, but a new save never retains a non-live selection.
        if (
            choices.any { choice ->
              choice.exerciseId
                  ?.let { exercises[it] }
                  ?.let { it.type != ExerciseType.STRENGTH || it.archived } != false
            }
        )
            return@withTransaction StrengthPlannerSaveResult.Invalid
        val existing = profileDao.get(target.scope)
        val owner = target.ownerId
        val syncId = owner?.let(::profileSyncId) ?: existing?.syncId ?: UUID.randomUUID().toString()
        profileDao.upsert(
            StrengthPlannerProfileEntity(
                scope = target.scope,
                syncId = syncId,
                updatedAt = clock.nowMillis().coerceAtLeast(existing?.updatedAt?.plus(1) ?: 0),
            ),
        )
        profileDao.deleteKeyExercises(target.scope)
        profileDao.upsertKeyExercises(
            choices.sortedWith(choiceComparator).map { choice ->
              StrengthPlannerKeyExerciseEntity(target.scope, choice.exerciseSyncId, choice.priority)
            },
        )
        StrengthPlannerSaveResult.Saved
      }
    }
  }

  private fun validShape(choices: List<KeyExerciseChoice>): Boolean =
      choices.size <= 5 &&
          choices.map(KeyExerciseChoice::exerciseSyncId).distinct().size == choices.size

  private fun targetStillCurrent(target: ProfileEditTarget): Boolean {
    val owner = sync.owner()
    val session = sessions.snapshot()
    return if (target.ownerId == null) owner == null && session == null && target.scope == "GUEST"
    else
        owner == target.ownerId &&
            session?.tokens?.userId == target.ownerId &&
            session.epoch == target.sessionEpoch
  }

  private fun profileSyncId(owner: String): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.strength-planner-profile.v1:$owner".toByteArray(UTF_8))
          .toString()

  private companion object {
    val choiceComparator =
        compareBy<KeyExerciseChoice>(
            { if (it.priority.name == "HIGH") 0 else 1 },
            { it.exerciseSyncId },
        )
  }
}
