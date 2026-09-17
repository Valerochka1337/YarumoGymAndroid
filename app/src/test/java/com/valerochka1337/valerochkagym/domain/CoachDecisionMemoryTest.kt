package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.*
import org.junit.Test

class CoachDecisionMemoryTest {
  private val done =
      SnapshotSet("done", 0, true, 50.0, 8, null, 1000, setType = "WORK", actualRir = 2)
  private val next = done.copy(syncId = "next", setIndex = 1, completed = false, completedAt = null)
  private val snapshot =
      WorkoutSnapshot(
          "owner",
          "workout",
          0,
          listOf(SnapshotExercise("section", 1, sets = listOf(done, next))),
          previousSetId = "done",
      )
  private val packet =
      WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet("next", weightKg = 47.5)))

  private fun rejected(reason: CoachRejectionReason? = null) =
      CoachDecisionMemory.capture(snapshot, "proposal", "REJECTED", "47.5 кг", packet, reason)

  private fun later(reps: Int = 8, reason: CoachRejectionReason? = null) =
      snapshot.copy(
          exercises =
              snapshot.exercises.map {
                it.copy(
                    sets =
                        listOf(done, next.copy(completed = true, completedAt = 2000, reps = reps))
                )
              },
          coachDecisions = listOf(rejected(reason)),
      )

  @Test
  fun `plain refusal survives another comparable set and serialization`() {
    val decision = rejected()
    assertEquals(
        listOf(decision),
        CoachDecisionMemory.decode(CoachDecisionMemory.encode(listOf(decision))),
    )
    assertTrue(later().suppressesCoachInitiative())
  }

  @Test
  fun `material deterioration can reopen a plain refusal`() {
    assertFalse(later(5).suppressesCoachInitiative())
  }

  @Test
  fun `explicit keep applies to the exercise despite new repetitions`() {
    assertTrue(later(5, CoachRejectionReason.KEEP_EXERCISE).suppressesCoachInitiative())
  }

  @Test
  fun `safety report is never suppressed by a refusal`() {
    val input = later(reason = CoachRejectionReason.KEEP_EXERCISE)
    assertFalse(
        input
            .copy(
                exercises =
                    input.exercises.map { e ->
                      e.copy(sets = e.sets.map { it.copy(reportedFeelings = setOf("PAIN")) })
                    }
            )
            .suppressesCoachInitiative()
    )
  }

  @Test
  fun `accepted change and another exercise are not suppressed`() {
    assertFalse(
        snapshot
            .copy(coachDecisions = listOf(rejected().copy(status = "APPLIED")))
            .suppressesCoachInitiative()
    )
    assertFalse(
        snapshot
            .copy(coachDecisions = listOf(rejected().copy(sectionIds = setOf("other"))))
            .suppressesCoachInitiative()
    )
  }

  @Test
  fun `weight refusal is retained as structured data`() {
    assertEquals(
        CoachRejectionReason.UNAVAILABLE_WEIGHT,
        rejected(CoachRejectionReason.UNAVAILABLE_WEIGHT).reason,
    )
  }
}
