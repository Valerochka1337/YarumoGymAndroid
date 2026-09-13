package com.valerochka1337.valerochkagym.ui.trainingproposal

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedExercise
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedSet
import org.junit.Assert.*
import org.junit.Test

class ProposalExerciseEditingTest {
  @Test
  fun `replacement keeps set count and rest but clears incompatible strength values`() {
    val exercise =
        ProposalPlannedExercise(
            "bench",
            90,
            listOf(
                ProposalPlannedSet(60.0, 8, null, null, null),
                ProposalPlannedSet(65.0, 6, null, null, null),
            ),
        )
    val replacement = exercise.replacingExercise("plank", ExerciseType.TIMED)
    assertEquals("plank", replacement.exerciseId)
    assertEquals(90, replacement.restSeconds)
    assertEquals(2, replacement.plannedSets.size)
    assertTrue(
        replacement.plannedSets.all { it == ProposalPlannedSet(null, null, null, null, null) }
    )
    assertEquals(60.0, exercise.plannedSets.first().weightKg)
  }

  @Test
  fun `cardio replacement with timed keeps duration and removes speed and incline`() {
    val exercise =
        ProposalPlannedExercise("run", 30, listOf(ProposalPlannedSet(null, null, 180, 8.0, 2.0)))
    assertEquals(
        ProposalPlannedSet(null, null, 180, null, null),
        exercise.replacingExercise("plank", ExerciseType.TIMED).plannedSets.single(),
    )
    assertEquals(
        ProposalPlannedSet(null, null, null, null, null),
        exercise.replacingExercise("bench", ExerciseType.STRENGTH).plannedSets.single(),
    )
  }

  @Test
  fun `same identity selection preserves the exact draft`() {
    val exercise =
        ProposalPlannedExercise("bench", 60, listOf(ProposalPlannedSet(40.0, 8, null, null, null)))
    assertSame(exercise, exercise.replacingExercise("bench", ExerciseType.STRENGTH))
  }
}
