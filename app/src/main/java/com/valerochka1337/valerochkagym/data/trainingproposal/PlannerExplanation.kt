package com.valerochka1337.valerochkagym.data.trainingproposal

import com.valerochka1337.valerochkagym.domain.PlannerDuration
import kotlinx.serialization.Serializable

@Serializable
data class PlannerExplanation(
    val proposalId: String,
    val version: Int,
    val durationSpec: String,
    val desiredMinutes: Int,
    val estimatedSeconds: Long,
    val minimumSeconds: Long,
    val focusMuscles: List<String>,
    val repeatedExerciseIds: List<String>,
    val lastFinishedAtMillis: Long?,
    val eligibleExerciseCount: Int,
    val selectionReason: String,
    val repeatReason: String,
    val shortfallReason: String,
) {
  fun validFor(proposal: TrainingProposal): Boolean =
      proposalId == proposal.proposalId &&
          version == proposal.currentVersion &&
          proposal.source == ProposalSource.AI &&
          durationSpec == "planner-duration-v1" &&
          desiredMinutes in 10..240 &&
          estimatedSeconds == proposal.snapshot.draft.estimatedSeconds() &&
          estimatedSeconds in 1..desiredMinutes * 60L &&
          minimumSeconds == PlannerDuration.minimumSeconds(desiredMinutes) &&
          focusMuscles.size <= 3 &&
          focusMuscles.distinct() == focusMuscles &&
          focusMuscles.all { name ->
            com.valerochka1337.valerochkagym.data.db.entity.Muscle.entries.any { it.name == name }
          } &&
          repeatedExerciseIds.distinct() == repeatedExerciseIds &&
          repeatedExerciseIds.all { id ->
            proposal.snapshot.draft.exercises.any { it.exerciseId == id }
          } &&
          (lastFinishedAtMillis == null ||
              lastFinishedAtMillis in 0..proposal.snapshot.createdAt) &&
          (repeatedExerciseIds.isEmpty() || lastFinishedAtMillis != null) &&
          eligibleExerciseCount in proposal.snapshot.draft.exercises.size..1000 &&
          selectionReason in
              setOf("CONTINUITY", "PRIORITY", "GOAL_BALANCE", "CONSTRAINTS", "UNSPECIFIED") &&
          repeatReason in
              setOf("CONTINUITY", "PRIORITY", "LIMITED_OPTIONS", "NONE", "UNSPECIFIED") &&
          (repeatedExerciseIds.isEmpty() == (repeatReason == "NONE")) &&
          shortfallReason in setOf("VOLUME_LIMIT", "CONSTRAINTS", "NONE", "UNSPECIFIED") &&
          ((estimatedSeconds >= minimumSeconds) == (shortfallReason == "NONE"))
}

fun ApprovalDraft.estimatedSeconds(): Long =
    PlannerDuration.seconds(
        exercises.map {
          PlannerDuration.Exercise(it.plannedSets.map { set -> set.durationSec }, it.restSeconds)
        }
    )
