package com.valerochka1337.valerochkagym.data.profile

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ProfileDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEquipmentPreferenceEntity
import com.valerochka1337.valerochkagym.data.db.entity.StrengthPlannerKeyExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.StrengthPlannerProfileEntity
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.service.WallClock
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val GUEST_SCOPE = "GUEST"

class ProfileRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val profileDao: ProfileDao,
    private val sync: BackendSync,
    private val sessions: BackendSessionStore,
    private val clock: WallClock,
) : ProfileRepository {
  private val mutationMutex = Mutex()

  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> = flow {
    val target = currentTarget()
    if (target == null) emit(null)
    else
        emitAll(
            observe(target).map { profile -> profile?.let { ProfileEditorSnapshot(target, it) } }
        )
  }

  override suspend fun openEditor(): ProfileEditorSnapshot? {
    val target = currentTarget() ?: return null
    val profile = database.withTransaction { profileFor(target.scope) }
    return if (targetStillCurrent(target)) ProfileEditorSnapshot(target, profile) else null
  }

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> =
      combine(
          profileDao.observe(target.scope),
          profileDao.observeEquipmentIds(target.scope),
          sync.transfer,
          sessions.session,
      ) { entity, equipmentIds, _, _ ->
        if (!targetStillCurrent(target)) null else entity?.toProfile(equipmentIds) ?: BasicProfile()
      }

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult =
      saveInternal(target, profile, null)

  override suspend fun saveWithStrength(
      target: ProfileEditTarget,
      profile: BasicProfile,
      keyExercises: List<KeyExerciseChoice>,
  ): ProfileSaveResult = saveInternal(target, profile, keyExercises)

  private suspend fun saveInternal(
      target: ProfileEditTarget,
      profile: BasicProfile,
      keyExercises: List<KeyExerciseChoice>?,
  ): ProfileSaveResult {
    val normalized =
        ProfileValidator.normalize(profile, clock.nowMillis()) ?: return ProfileSaveResult.Invalid
    if (
        keyExercises != null &&
            (normalized.trainingGoal != TrainingGoal.STRENGTH ||
                keyExercises.size > 5 ||
                keyExercises.map(KeyExerciseChoice::exerciseSyncId).distinct().size !=
                    keyExercises.size)
    )
        return ProfileSaveResult.Invalid
    return mutationMutex.withLock {
      try {
        database.withTransaction {
          if (!targetStillCurrent(target)) return@withTransaction ProfileSaveResult.StaleTarget
          val selectedExercises =
              keyExercises?.let { choices ->
                val catalog = database.exerciseDao().getAllOnce().associateBy { it.syncId }
                val existingKeys =
                    database
                        .strengthPlannerProfileDao()
                        .keyExercises(target.scope)
                        .map { it.exerciseSyncId }
                        .toSet()
                if (
                    choices.any { choice ->
                      val canonical =
                          runCatching { UUID.fromString(choice.exerciseSyncId).toString() }
                              .getOrNull()
                      canonical != choice.exerciseSyncId ||
                          (choice.exerciseSyncId !in existingKeys &&
                              catalog[choice.exerciseSyncId]?.let {
                                it.type == ExerciseType.STRENGTH && !it.archived
                              } != true)
                    }
                )
                    return@withTransaction ProfileSaveResult.Invalid
                choices.associate { choice -> choice.exerciseSyncId to choice.exerciseSyncId }
              }
          // Exercise validation is a suspend read; verify the owner epoch again before any writes.
          if (!targetStillCurrent(target)) return@withTransaction ProfileSaveResult.StaleTarget
          val existing = profileDao.get(target.scope)
          val syncId =
              target.ownerId?.let(::profileSyncId)
                  ?: existing?.syncId
                  ?: UUID.randomUUID().toString()
          profileDao.upsert(
              ProfileEntity(
                  scope = target.scope,
                  syncId = syncId,
                  trainingGoal = normalized.trainingGoal?.name,
                  sex = normalized.sex?.name,
                  birthDate = normalized.birthDate,
                  experienceLevel = normalized.experienceLevel?.name,
                  plannedSessionsPerWeek = normalized.plannedSessionsPerWeek,
                  preferredSessionDurationMinutes = normalized.preferredSessionDurationMinutes,
                  manualConstraints = normalized.manualConstraints,
                  updatedAt = clock.nowMillis().coerceAtLeast(0),
              )
          )
          profileDao.deleteEquipment(target.scope)
          profileDao.upsertEquipment(
              normalized.equipmentIds.sorted().map {
                ProfileEquipmentPreferenceEntity(target.scope, it)
              }
          )
          if (keyExercises != null && selectedExercises != null) {
            val strengthPlannerProfileDao = database.strengthPlannerProfileDao()
            val existingStrength = strengthPlannerProfileDao.get(target.scope)
            if (!targetStillCurrent(target)) throw StaleProfileTargetException()
            val strengthSyncId =
                target.ownerId?.let(::strengthProfileSyncId)
                    ?: existingStrength?.syncId
                    ?: UUID.randomUUID().toString()
            strengthPlannerProfileDao.upsert(
                StrengthPlannerProfileEntity(
                    scope = target.scope,
                    syncId = strengthSyncId,
                    updatedAt =
                        clock.nowMillis().coerceAtLeast(existingStrength?.updatedAt?.plus(1) ?: 0),
                ),
            )
            strengthPlannerProfileDao.deleteKeyExercises(target.scope)
            strengthPlannerProfileDao.upsertKeyExercises(
                keyExercises.sortedWith(keyExerciseComparator).map { choice ->
                  StrengthPlannerKeyExerciseEntity(
                      scope = target.scope,
                      exerciseSyncId = selectedExercises.getValue(choice.exerciseSyncId),
                      priority = choice.priority,
                  )
                },
            )
          }
          if (!targetStillCurrent(target)) throw StaleProfileTargetException()
          ProfileSaveResult.Saved
        }
      } catch (_: StaleProfileTargetException) {
        ProfileSaveResult.StaleTarget
      }
    }
  }

  private suspend fun profileFor(scope: String): BasicProfile =
      profileDao.get(scope)?.toProfile(profileDao.equipmentIds(scope)) ?: BasicProfile()

  private fun currentTarget(): ProfileEditTarget? {
    val initialOwner = sync.owner()
    val session = sessions.snapshot()
    if (sync.owner() != initialOwner) return null
    return if (initialOwner == null) {
      if (session != null) null else ProfileEditTarget(GUEST_SCOPE, null, 0)
    } else if (session?.tokens?.userId == initialOwner) {
      ProfileEditTarget(initialOwner, initialOwner, session.epoch)
    } else null
  }

  private fun targetStillCurrent(target: ProfileEditTarget): Boolean = currentTarget() == target

  private fun ProfileEntity.toProfile(equipmentIds: List<String>) =
      BasicProfile(
          trainingGoal = trainingGoal?.let(TrainingGoal::valueOf),
          sex = sex?.let(ProfileSex::valueOf),
          birthDate = birthDate,
          experienceLevel = experienceLevel?.let(ExperienceLevel::valueOf),
          plannedSessionsPerWeek = plannedSessionsPerWeek,
          preferredSessionDurationMinutes = preferredSessionDurationMinutes,
          equipmentIds = equipmentIds.toSet(),
          manualConstraints = manualConstraints,
      )

  private fun profileSyncId(owner: String): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.profile.v1:$owner".toByteArray(UTF_8)).toString()

  private fun strengthProfileSyncId(owner: String): String =
      UUID.nameUUIDFromBytes(
              "ValerochkaGym.strength-planner-profile.v1:$owner".toByteArray(UTF_8),
          )
          .toString()

  private companion object {
    val keyExerciseComparator =
        compareBy<KeyExerciseChoice>(
            { if (it.priority.name == "HIGH") 0 else 1 },
            { it.exerciseSyncId },
        )
  }

  private class StaleProfileTargetException : RuntimeException()
}
