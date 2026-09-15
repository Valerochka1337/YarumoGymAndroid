package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutEffortDao
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffort
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffortEntity
import com.valerochka1337.valerochkagym.domain.WorkoutEffortEditTarget
import com.valerochka1337.valerochkagym.domain.WorkoutEffortRepository
import com.valerochka1337.valerochkagym.domain.WorkoutEffortSaveResult
import com.valerochka1337.valerochkagym.service.WallClock
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkoutEffortRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val effortDao: WorkoutEffortDao,
    private val workoutDao: WorkoutDao,
    private val sync: BackendSync,
    private val sessions: BackendSessionStore,
    private val clock: WallClock,
) : WorkoutEffortRepository {
  private val mutationMutex = Mutex()

  override fun captureTarget(workoutId: String): WorkoutEffortEditTarget? =
      currentOwner()?.let { WorkoutEffortEditTarget(workoutId, it.scope, it.epoch) }

  override fun observe(target: WorkoutEffortEditTarget): Flow<WorkoutEffort?> =
      combine(effortDao.observe(target.workoutId, target.scope), sync.transfer, sessions.sessionEpochs) {
        row, _, _ -> if (captureTarget(target.workoutId) == target) row?.effort else null
      }

  override suspend fun save(target: WorkoutEffortEditTarget, effort: WorkoutEffort?): WorkoutEffortSaveResult =
      mutationMutex.withLock {
        try {
          database.withTransaction {
            fun checkTarget() {
              if (captureTarget(target.workoutId) != target) throw StaleEffortTarget()
            }
            checkTarget()
            val workout = workoutDao.getWorkoutFull(target.workoutId)?.workout
                ?: return@withTransaction WorkoutEffortSaveResult.Invalid
            if (workout.finishedAt == null) return@withTransaction WorkoutEffortSaveResult.Invalid
            val existing = effortDao.get(target.workoutId, target.scope)
            checkTarget()
            effortDao.upsert(
                WorkoutEffortEntity(
                    workoutId = target.workoutId,
                    scope = target.scope,
                    syncId = existing?.syncId ?: effortSyncId(target.scope, target.workoutId),
                    updatedAt = clock.nowMillis().coerceAtLeast(existing?.updatedAt?.plus(1) ?: 0),
                    effort = effort,
                ),
            )
            checkTarget() // Throwing rolls back if the session changed during the suspending write.
            WorkoutEffortSaveResult.Saved
          }
        } catch (_: StaleEffortTarget) {
          WorkoutEffortSaveResult.StaleOwner
        }
      }

  private fun currentOwner(): EffortOwner? {
    val owner = sync.owner()
    val session = sessions.snapshot()
    return if (owner == null) {
      if (session == null) EffortOwner("GUEST", null, 0) else null
    } else if (session?.tokens?.userId == owner) EffortOwner(owner, owner, session.epoch) else null
  }

  private fun effortSyncId(scope: String, workoutId: String): String =
      UUID.nameUUIDFromBytes(
              "ValerochkaGym.workout-effort.v1:$scope:$workoutId".toByteArray(UTF_8),
          )
          .toString()
}

private data class EffortOwner(val scope: String, val ownerId: String?, val epoch: Long)

private class StaleEffortTarget : RuntimeException()
