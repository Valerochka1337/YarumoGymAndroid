package com.valerochka1337.valerochkagym.data.trainingproposal

import com.valerochka1337.valerochkagym.domain.PlannerDuration
import kotlinx.serialization.Serializable

@Serializable
data class RuleBasedPlannerExplanation(
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
    val algorithm: String,
    val algorithmVersion: Int,
    val inputFingerprint: String,
    val effectiveGoal: String,
    val terminalCode: String?,
    val optimalityNotGuaranteed: Boolean,
    val nodeCount: Int,
    val evaluationCount: Int,
    val ruleIds: List<String>,
    val reasons: List<String>,
    val slotSelections: List<RuleBasedSlotSelection>,
) {
  fun legacyShape() =
      PlannerExplanation(
          proposalId,
          version,
          durationSpec,
          desiredMinutes,
          estimatedSeconds,
          minimumSeconds,
          focusMuscles,
          repeatedExerciseIds,
          lastFinishedAtMillis,
          eligibleExerciseCount,
          selectionReason,
          repeatReason,
          shortfallReason,
          ruleDetails =
              RuleBasedPlannerDetails(
                  algorithm,
                  algorithmVersion,
                  inputFingerprint,
                  effectiveGoal,
                  terminalCode,
                  optimalityNotGuaranteed,
                  nodeCount,
                  evaluationCount,
                  ruleIds,
                  reasons,
                  slotSelections,
              ),
      )

  fun validFor(proposal: TrainingProposal): Boolean =
      legacyShape().validFor(proposal) &&
          algorithm.isNotBlank() &&
          algorithm.length <= 255 &&
          algorithmVersion > 0 &&
          inputFingerprint.isNotBlank() &&
          inputFingerprint.length <= 255 &&
          effectiveGoal in
              setOf(
                  "STRENGTH",
                  "MUSCLE_GAIN",
                  "FAT_LOSS",
                  "GENERAL_FITNESS",
                  "ENDURANCE",
                  "GOAL_DEFAULTED_TO_GENERAL_FITNESS",
              ) &&
          (terminalCode == null || (terminalCode.isNotBlank() && terminalCode.length <= 255)) &&
          nodeCount >= 0 &&
          evaluationCount >= 0 &&
          ruleIds.distinct().size == ruleIds.size &&
          reasons.distinct().size == reasons.size &&
          slotSelections.map { it.selectionId }.distinct().size == slotSelections.size &&
          slotSelections.all {
            it.slotId.isNotBlank() &&
                it.slotId.length <= 255 &&
                it.selectionId.isNotBlank() &&
                it.selectionId.length <= 255 &&
                ProposalWire.uuid(it.exerciseId) &&
                proposal.snapshot.draft.exercises.any { exercise ->
                  exercise.exerciseId == it.exerciseId
                }
          }
}

@Serializable
data class RuleBasedSlotSelection(
    val slotId: String,
    val selectionId: String,
    val exerciseId: String,
)

data class RuleBasedPlannerDetails(
    val algorithm: String,
    val algorithmVersion: Int,
    val inputFingerprint: String,
    val effectiveGoal: String,
    val terminalCode: String?,
    val optimalityNotGuaranteed: Boolean,
    val nodeCount: Int,
    val evaluationCount: Int,
    val ruleIds: List<String>,
    val reasons: List<String>,
    val slotSelections: List<RuleBasedSlotSelection>,
)

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
    @kotlinx.serialization.Transient val ruleDetails: RuleBasedPlannerDetails? = null,
) {
  fun validFor(proposal: TrainingProposal): Boolean =
      proposalId == proposal.proposalId &&
          version == proposal.currentVersion &&
          proposal.source in setOf(ProposalSource.AI, ProposalSource.RULE_BASED) &&
          durationSpec ==
              (if (proposal.source == ProposalSource.RULE_BASED) "planner-duration-v2"
              else "planner-duration-v1") &&
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
              setOf(
                  "CONTINUITY",
                  "PRIORITY",
                  "GOAL_BALANCE",
                  "CONSTRAINTS",
                  "RULE_BASED",
                  "UNSPECIFIED",
              ) &&
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
