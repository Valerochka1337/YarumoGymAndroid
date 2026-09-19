package com.valerochka1337.valerochkagym.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class CoachRejectionReason(val label: String) {
  KEEP_EXERCISE("Оставить до конца упражнения"),
  UNAVAILABLE_WEIGHT("Нет такого веса"),
}

/** Local, owner-scoped decisions, never reconstructed from imported conversation text. */
@Serializable
data class CoachDecisionMemory(
    val proposalId: String,
    val status: String,
    val summary: String,
    val sectionIds: Set<String>,
    val reason: CoachRejectionReason? = null,
    val evidence: List<CoachDecisionEvidence> = emptyList(),
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(value: String): List<CoachDecisionMemory> =
        runCatching { json.decodeFromString<List<CoachDecisionMemory>>(value) }
            .getOrDefault(emptyList())

    fun encode(values: List<CoachDecisionMemory>): String = json.encodeToString(values.takeLast(30))

    fun capture(
        snapshot: WorkoutSnapshot,
        proposalId: String,
        status: String,
        summary: String,
        packet: WorkoutChangeSet.Packet,
        reason: CoachRejectionReason? = null,
    ): CoachDecisionMemory {
      val latestSection =
          snapshot.exercises.firstOrNull { e -> e.sets.any { it.syncId == snapshot.previousSetId } }
      val sections =
          packet.operations
              .flatMap { op ->
                when (op) {
                  is WorkoutChangeSet.Operation.EditSet ->
                      snapshot.exercises
                          .filter { e -> e.sets.any { it.syncId == op.setSyncId } }
                          .map { it.sectionId }
                  is WorkoutChangeSet.Operation.DeleteSet ->
                      snapshot.exercises
                          .filter { e -> e.sets.any { it.syncId == op.setSyncId } }
                          .map { it.sectionId }
                  is WorkoutChangeSet.Operation.AddSet -> listOf(op.sectionId)
                  is WorkoutChangeSet.Operation.RemoveRemaining -> listOf(op.sectionId)
                  is WorkoutChangeSet.Operation.ReplaceRemaining -> listOf(op.sourceSectionId)
                  is WorkoutChangeSet.Operation.DeleteExercise -> listOf(op.sectionId)
                  is WorkoutChangeSet.Operation.MoveExercise -> listOf(op.sectionId)
                  is WorkoutChangeSet.Operation.ReorderExercises -> op.sectionIds
                  is WorkoutChangeSet.Operation.Rest -> listOfNotNull(latestSection?.sectionId)
                  else -> emptyList()
                }
              }
              .toSet()
      return CoachDecisionMemory(
          proposalId,
          status,
          summary,
          sections,
          reason,
          snapshot.exercises
              .filter { it.sectionId in sections }
              .mapNotNull { exercise ->
                exercise.sets
                    .filter { it.completed }
                    .maxByOrNull { it.completedAt ?: Long.MIN_VALUE }
                    ?.let {
                      CoachDecisionEvidence(
                          exercise.sectionId,
                          it.syncId,
                          it.actualWeightKg ?: it.weightKg,
                          it.actualReps ?: it.reps,
                          it.actualRir,
                          it.reportedFeelings,
                      )
                    }
              },
      )
    }
  }
}

@Serializable
data class CoachDecisionEvidence(
    val sectionId: String,
    val setId: String,
    val weightKg: Double?,
    val reps: Int?,
    val rir: Int?,
    val feelings: Set<String>,
)
