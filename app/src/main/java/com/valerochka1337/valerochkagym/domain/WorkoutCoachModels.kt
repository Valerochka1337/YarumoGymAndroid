@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.valerochka1337.valerochkagym.domain

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Immutable fact snapshot passed to Coach tools. No authority, secret or reasoning is persisted.
 */
data class WorkoutSnapshot(
    val accountId: String,
    val workoutId: String,
    val revision: Long,
    val exercises: List<SnapshotExercise>,
    val currentSetId: String? = null,
    val previousSetId: String? = null,
    val nextSetId: String? = null,
    val rest: SnapshotRest? = null,
    val elapsedSeconds: Long = 0,
    val availableTimeMinutes: Int? = null,
    val excludedExerciseIds: Set<Long> = emptySet(),
    /** Portable exclusions include exercises absent from the current workout. */
    val excludedExerciseSyncIds: Set<String> = emptySet(),
    val feelings: Set<String> = emptySet(),
    val pulse: SnapshotPulse? = null,
    val futureRestSeconds: Int? = null,
    val profile: CoachProfile = CoachProfile(),
    val coachDecisions: List<CoachDecisionMemory> = emptyList(),
    val observedAtMillis: Long = System.currentTimeMillis(),
    val availableTimeEndsAtMillis: Long? = null,
    val autoregulationOptions:
        com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions =
        com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions(),
    val phase: String = "UNKNOWN",
    val paused: Boolean = false,
)

data class CoachProfile(
    val trainingGoal: String? = null,
    val experienceLevel: String? = null,
    val constraints: String? = null,
    val equipmentIds: Set<String> = emptySet(),
    val preferredRepMin: Int? = null,
    val preferredRepMax: Int? = null,
)

data class SnapshotExercise(
    val sectionId: String,
    val exerciseId: Long,
    val exerciseSyncId: String = "",
    val name: String = "",
    val position: Int = 0,
    val muscleIds: Set<String> = emptySet(),
    val equipmentIds: Set<String> = emptySet(),
    val sets: List<SnapshotSet> = emptyList(),
    val history: List<SnapshotHistory> = emptyList(),
    val type: com.valerochka1337.valerochkagym.data.db.entity.ExerciseType? = null,
)

data class SnapshotSet(
    val syncId: String,
    val setIndex: Int,
    val completed: Boolean,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val completedAt: Long? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val setType: String = "UNKNOWN",
    val originalWeightKg: Double? = null,
    val originalReps: Int? = null,
    val originalDurationSec: Int? = null,
    val originalSpeedKmh: Double? = null,
    val originalInclinePct: Double? = null,
    val targetWeightKg: Double? = null,
    val targetReps: Int? = null,
    val targetDurationSec: Int? = null,
    val targetSpeedKmh: Double? = null,
    val targetInclinePct: Double? = null,
    val actualWeightKg: Double? = null,
    val actualReps: Int? = null,
    val actualDurationSec: Int? = null,
    val actualSpeedKmh: Double? = null,
    val actualInclinePct: Double? = null,
    val reportedFeelings: Set<String> = emptySet(),
    val actualRir: Int? = null,
    val actualRirAtLeastFour: Boolean = false,
    val note: String = "",
)

data class SnapshotHistory(
    val completedAt: Long,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val setType: String = "UNKNOWN",
    val workoutId: String = "",
    val setSyncId: String = "",
    val actualRir: Int? = null,
    val actualRirAtLeastFour: Boolean = false,
    val interrupted: Boolean = false,
    val note: String = "",
)

data class SnapshotPulse(val bpm: Int, val measuredAtMillis: Long)

data class SnapshotRest(
    val startId: String,
    val plannedSeconds: Int?,
    val remainingSeconds: Int?,
    val startedAtMillis: Long,
    val endsAtMillis: Long? = null,
)

@Serializable
sealed interface WorkoutChangeSet {
  /** The only mutating unit. A packet is all-or-nothing and earns exactly one revision. */
  @Serializable
  data class Packet(
      val operations: List<Operation>,
      @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
      val autoregulation:
          com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationProof? =
          null,
  ) : WorkoutChangeSet {
    init {
      require(operations.isNotEmpty())
    }
  }

  @Serializable
  sealed interface Operation {
    @Serializable
    data class EditSet(
        val setSyncId: String,
        val weightKg: Double? = null,
        val reps: Int? = null,
        val durationSec: Int? = null,
        val speedKmh: Double? = null,
        val inclinePct: Double? = null,
        val completed: Boolean? = null,
        val clearFields: Set<String> = emptySet(),
        val recordResult: Boolean = false,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val actualRir: Int? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val setType: String? = null,
    ) : Operation

    @Serializable data class AddSet(val sectionId: String) : Operation

    @Serializable data class DeleteSet(val setSyncId: String) : Operation

    @Serializable
    data class MoveExercise(val sectionId: String, val targetPosition: Int) : Operation

    @Serializable data class ReorderExercises(val sectionIds: List<String>) : Operation

    @Serializable
    data class AddExercise(
        val exerciseId: Long,
        val prefilledSets: List<RestoreSet> = emptyList(),
        val position: Int? = null,
    ) : Operation

    @Serializable data class DeleteExercise(val sectionId: String) : Operation

    @Serializable data class RemoveRemaining(val sectionId: String) : Operation

    /** Only these exact unfinished rows move; completed source rows remain in their section. */
    @Serializable
    data class ReplaceRemaining(
        val sourceSectionId: String,
        val destinationSectionId: String,
        val replacementExerciseId: Long,
        val unfinishedSetSyncIds: List<String>,
        val replacementWeightKg: Double?,
    ) : Operation

    @Serializable
    data class Rest(
        val action: RestAction,
        val expectedRestStartId: String?,
        val seconds: Int? = null,
    ) : Operation

    @Serializable data class SetAvailableTime(val minutes: Int?) : Operation

    @Serializable data class SetExcludedExercises(val exerciseIds: Set<Long>) : Operation

    @Serializable
    data class ReportFeelings(val setSyncId: String, val feelings: Set<String>) : Operation

    @Serializable data object UndoLast : Operation

    /** App-authored inverse only; the tool codec deliberately has no wire action for this. */
    @Serializable data class RestoreWorkout(val sections: List<RestoreSection>) : Operation
  }
}

@Serializable
data class RestoreSection(
    val sectionId: String,
    val exerciseId: Long,
    val position: Int,
    val sets: List<RestoreSet>,
)

@Serializable
data class RestoreSet(
    val syncId: String,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double?,
    val inclinePct: Double?,
    val isCompleted: Boolean,
    val completedAt: Long?,
    val originalWeightKg: Double?,
    val originalReps: Int?,
    val originalDurationSec: Int?,
    val originalSpeedKmh: Double?,
    val originalInclinePct: Double?,
    val targetWeightKg: Double?,
    val targetReps: Int?,
    val targetDurationSec: Int?,
    val targetSpeedKmh: Double?,
    val targetInclinePct: Double?,
    val actualWeightKg: Double?,
    val actualReps: Int?,
    val actualDurationSec: Int?,
    val actualSpeedKmh: Double?,
    val actualInclinePct: Double?,
    val setType: String,
    val reportedFeelingsJson: String,
    val restSnapshotJson: String?,
    val coachMutationRevision: Long,
    val note: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val actualRir: Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val actualRirAtLeastFour: Boolean = false,
    /** Read-only compatibility with saved undo from builds with planned RIR. Never applied. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val targetRir: Int? = null,
)

@Serializable
enum class RestAction {
  START,
  EXTEND,
  SKIP,
  FUTURE_DURATION,
}

enum class CommandResult {
  APPLIED,
  REPLAYED,
  STALE,
  CONFIRMATION,
  INVALID,
}

data class CommandReceipt(
    val operationId: String,
    val workoutId: String,
    val revision: Long,
    val result: CommandResult,
)

data class WorkoutProposal(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val workoutId: String,
    val baseRevision: Long,
    val beforeSummary: String,
    val afterSummary: String,
    val packet: WorkoutChangeSet.Packet,
    val expiresAt: Long,
)
