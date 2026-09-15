package com.valerochka1337.valerochkagym.data.trainingproposal

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PlannerExplanationTest {
  private val id = "11111111-1111-4111-8111-111111111111"
  private val draft =
      ApprovalDraft(
          "Plan",
          emptyList(),
          listOf(
              ProposalPlannedExercise(
                  id,
                  90,
                  List(4) { ProposalPlannedSet(null, 10, null, null, null) },
              )
          ),
          100000,
          "UTC",
      )
  private val proposal =
      TrainingProposal(
          id,
          ProposalAuthor(ProposalSource.AI, null),
          id,
          ProposalSource.AI,
          ProposalStatus.PENDING,
          1,
          1000,
          1000,
          1000000,
          ProposalSnapshot(1, draft, 1, 1, 1000),
      )
  private val explanation =
      PlannerExplanation(
          id,
          1,
          "planner-duration-v1",
          60,
          450,
          2880,
          listOf("QUADS"),
          listOf(id),
          100,
          1,
          "GOAL_BALANCE",
          "CONTINUITY",
          "VOLUME_LIMIT",
      )

  @Test
  fun `optional explanation validates snapshot identity computed numbers and bounded references`() {
    assertTrue(explanation.validFor(proposal))
    val mutations =
        listOf(
            explanation.copy(proposalId = "other"),
            explanation.copy(version = 2),
            explanation.copy(durationSpec = "unknown"),
            explanation.copy(estimatedSeconds = 3600),
            explanation.copy(minimumSeconds = 0),
            explanation.copy(focusMuscles = listOf("invented")),
            explanation.copy(repeatedExerciseIds = listOf("unknown")),
            explanation.copy(lastFinishedAtMillis = 1001),
            explanation.copy(lastFinishedAtMillis = null),
            explanation.copy(eligibleExerciseCount = 0),
            explanation.copy(shortfallReason = "NONE"),
            explanation.copy(repeatReason = "NONE"),
            explanation.copy(selectionReason = "Medical claim"),
        )
    mutations.forEach { assertFalse(it.toString(), it.validFor(proposal)) }
    val bytes = ProposalWire.json.encodeToString(explanation).encodeToByteArray()
    assertEquals(explanation, ProposalWire.decode<PlannerExplanation>(bytes))
  }

  @Test
  fun `legacy proposal and draft wire stay exact and contain no explanation`() {
    val bytes = ProposalWire.json.encodeToString(proposal).encodeToByteArray()
    assertEquals(proposal, ProposalWire.decode<TrainingProposal>(bytes))
    assertFalse(bytes.decodeToString().contains("explanation"))
    assertEquals(
        draft,
        ProposalWire.decode<ApprovalDraft>(
            ProposalWire.json.encodeToString(draft).encodeToByteArray()
        ),
    )
  }
}
