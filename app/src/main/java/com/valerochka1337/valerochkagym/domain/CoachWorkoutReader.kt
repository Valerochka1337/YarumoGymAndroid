package com.valerochka1337.valerochkagym.domain

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.freshAt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class CoachWorkoutReader
@Inject
constructor(
    private val database: GymDatabase,
    private val restTimer: RestTimerEngine,
    private val sessions: BackendSessionStore,
    private val heartRateMonitor: HeartRateMonitor? = null,
) {
  private val json = Json { ignoreUnknownKeys = false }

  suspend fun snapshot(
      accountId: String,
      workoutId: String,
      expectedSessionEpoch: Long? = null,
  ): WorkoutSnapshot? =
      CoachDiagnostics.trace("context.snapshot") {
        database
            .withTransaction { readSnapshot(accountId, workoutId, expectedSessionEpoch) }
            .also {
              CoachDiagnostics.event(
                  "context.snapshot.result",
                  "available" to (it != null),
                  "revision" to it?.revision,
                  "exercises" to it?.exercises?.size,
                  "sets" to it?.exercises?.sumOf { exercise -> exercise.sets.size },
              )
            }
      }

  private suspend fun readSnapshot(
      accountId: String,
      workoutId: String,
      expectedSessionEpoch: Long?,
  ): WorkoutSnapshot? {
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return null
    val full = database.workoutDao().getWorkoutFull(workoutId) ?: return null
    if (full.workout.finishedAt != null) return null
    val context = database.coachDao().context(workoutId)
    if (context != null && context.accountId != accountId) return null
    val exercises =
        full.exercises
            .sortedBy { it.workoutExercise.position }
            .map { row ->
              val exercise =
                  database.exerciseDao().getById(row.workoutExercise.exerciseId) ?: return@map null
              SnapshotExercise(
                  sectionId = row.workoutExercise.sectionId,
                  exerciseId = exercise.id,
                  exerciseSyncId = exercise.syncId,
                  name = exercise.name,
                  type = exercise.type,
                  position = row.workoutExercise.position,
                  muscleIds =
                      database
                          .exerciseMuscleDao()
                          .getForExercise(exercise.id)
                          .map { it.muscle.name }
                          .toSet(),
                  equipmentIds = database.exerciseDao().getRequirementIds(exercise.id).toSet(),
                  sets =
                      row.sets
                          .sortedBy { it.setIndex }
                          .map { set ->
                            SnapshotSet(
                                syncId = set.syncId,
                                setIndex = set.setIndex,
                                completed = set.isCompleted,
                                weightKg = set.weightKg,
                                reps = set.reps,
                                durationSec = set.durationSec,
                                completedAt = set.completedAt,
                                speedKmh = set.speedKmh,
                                inclinePct = set.inclinePct,
                                setType = set.setType,
                                originalWeightKg = set.originalWeightKg,
                                originalReps = set.originalReps,
                                originalDurationSec = set.originalDurationSec,
                                originalSpeedKmh = set.originalSpeedKmh,
                                originalInclinePct = set.originalInclinePct,
                                targetWeightKg = set.targetWeightKg,
                                targetReps = set.targetReps,
                                targetDurationSec = set.targetDurationSec,
                                targetSpeedKmh = set.targetSpeedKmh,
                                targetInclinePct = set.targetInclinePct,
                                actualWeightKg = set.actualWeightKg,
                                actualReps = set.actualReps,
                                actualDurationSec = set.actualDurationSec,
                                actualSpeedKmh = set.actualSpeedKmh,
                                actualInclinePct = set.actualInclinePct,
                                reportedFeelings = set.reportedFeelingsJson.decodeStrings(),
                                actualRir = set.actualRir,
                                actualRirAtLeastFour = set.actualRirAtLeastFour,
                                note = set.note,
                            )
                          },
                  history =
                      database.coachDao().exerciseHistory(exercise.id, 3).map { historical ->
                        val set = historical.set
                        run {
                          SnapshotHistory(
                              set.completedAt ?: historical.historyWorkoutFinishedAt,
                              set.setIndex,
                              set.weightKg,
                              set.reps,
                              set.durationSec,
                              set.speedKmh,
                              set.inclinePct,
                              set.setType,
                              workoutId = historical.historyWorkoutId,
                              setSyncId = set.syncId,
                              actualRir = set.actualRir,
                              actualRirAtLeastFour = set.actualRirAtLeastFour,
                              note = set.note,
                              interrupted =
                                  "INTERRUPTED" in set.reportedFeelingsJson.decodeStrings(),
                          )
                        }
                      },
              )
            }
            .filterNotNull()
    val profile = database.profileDao().get(accountId)
    val profileEquipment = database.profileDao().equipmentIds(accountId).toSet()
    val excludedIds = context?.excludedExerciseIdsJson?.decodeLongs().orEmpty()
    val excludedSyncIds =
        excludedIds.mapNotNull { database.exerciseDao().getById(it)?.syncId }.toSet()
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return null
    val allSets = exercises.flatMap { it.sets }
    val current = allSets.firstOrNull { !it.completed }?.syncId
    val currentIndex = allSets.indexOfFirst { it.syncId == current }
    val previous =
        allSets
            .withIndex()
            .filter { it.value.completed }
            .maxWithOrNull(
                compareBy<IndexedValue<SnapshotSet>> { it.value.completedAt ?: Long.MIN_VALUE }
                    .thenBy { it.index }
            )
            ?.value
            ?.syncId
    val next =
        currentIndex
            .takeIf { it >= 0 }
            ?.let { index -> allSets.drop(index + 1).firstOrNull { !it.completed }?.syncId }
    val rest = restSnapshot()
    return WorkoutSnapshot(
        accountId = accountId,
        workoutId = workoutId,
        revision = full.workout.coachRevision,
        exercises = exercises,
        profile =
            CoachProfile(
                profile?.trainingGoal,
                profile?.experienceLevel,
                profile?.manualConstraints,
                profileEquipment,
                if (profile == null) BasicProfile.initial().preferredRepMin
                else profile.preferredRepMin,
                if (profile == null) BasicProfile.initial().preferredRepMax
                else profile.preferredRepMax,
            ),
        coachDecisions = CoachDecisionMemory.decode(context?.decisionMemoryJson ?: "[]"),
        phase =
            when {
              rest != null -> "RESTING"
              previous != null &&
                  exercises.firstOrNull { e -> e.sets.any { it.syncId == previous } }?.sectionId !=
                      exercises
                          .firstOrNull { e -> e.sets.any { it.syncId == current } }
                          ?.sectionId &&
                  current != null -> "BETWEEN_EXERCISES"
              else -> "READY"
            },
        paused = false,
        currentSetId = current,
        previousSetId = previous,
        nextSetId = next,
        rest = rest,
        elapsedSeconds =
            ((System.currentTimeMillis() - full.workout.startedAt) / 1_000).coerceAtLeast(0),
        availableTimeMinutes =
            context?.availableTimeEndsAtMillis?.let { endsAt ->
              ((endsAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999L)
                  .div(60_000L)
                  .toInt()
            } ?: context?.availableTimeMinutes,
        excludedExerciseIds = excludedIds,
        excludedExerciseSyncIds = excludedSyncIds,
        availableTimeEndsAtMillis = context?.availableTimeEndsAtMillis,
        feelings = allSets.flatMap { it.reportedFeelings }.toSet(),
        futureRestSeconds = context?.futureRestSeconds,
        autoregulationOptions =
            runCatching {
                  json.decodeFromString<
                      com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions
                  >(
                      context?.autoregulationOptionsJson ?: "{}"
                  )
                }
                .getOrDefault(
                    com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions()
                ),
        pulse =
            heartRateMonitor?.reading?.value?.freshAt(System.currentTimeMillis())?.let {
              SnapshotPulse(it.bpm, it.updatedAtMillis)
            },
    )
  }

  private fun restSnapshot(): SnapshotRest? =
      when (val rest = restTimer.state.value) {
        is RestTimerState.Timed ->
            SnapshotRest(
                restTimer.currentStartId() ?: return null,
                rest.totalSec,
                rest.remainingSec,
                rest.endsAtMillis - rest.totalSec * 1_000L,
                rest.endsAtMillis,
            )
        is RestTimerState.HeartRate ->
            SnapshotRest(
                restTimer.currentStartId() ?: return null,
                null,
                null,
                rest.startedAtMillis,
            )
        null -> null
      }

  private fun belongsToLiveAccount(accountId: String, expectedSessionEpoch: Long? = null): Boolean {
    val session = sessions.snapshot() ?: return false
    if (
        session.tokens.userId != accountId ||
            (expectedSessionEpoch != null && session.epoch != expectedSessionEpoch)
    )
        return false
    return database.openHelper.writableDatabase
        .query("SELECT owner FROM backend_state WHERE id=1")
        .use { row -> row.moveToFirst() && !row.isNull(0) && row.getString(0) == accountId }
  }

  private fun String.decodeStrings(): Set<String> =
      runCatching { json.decodeFromString<List<String>>(this).toSet() }.getOrDefault(emptySet())

  private fun String.decodeLongs(): Set<Long> =
      runCatching { json.decodeFromString<List<Long>>(this).toSet() }.getOrDefault(emptySet())
}
