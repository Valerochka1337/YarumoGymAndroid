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

  @Test
  fun `captured decision survives serialization`() {
    val decision = rejected()
    assertEquals(
        listOf(decision),
        CoachDecisionMemory.decode(CoachDecisionMemory.encode(listOf(decision))),
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
