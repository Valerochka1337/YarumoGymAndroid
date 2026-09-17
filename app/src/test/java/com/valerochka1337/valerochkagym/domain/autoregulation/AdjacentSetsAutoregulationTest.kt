package com.valerochka1337.valerochkagym.domain.autoregulation

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.*
import org.junit.Assert.*
import org.junit.Test

class AdjacentSetsAutoregulationTest {
  private val previous =
      SnapshotSet(
          "previous",
          0,
          true,
          50.0,
          8,
          null,
          completedAt = 1000,
          setType = "WORK",
          actualRir = 0,
      )
  private val current =
      previous.copy(syncId = "current", setIndex = 1, reps = 5, completedAt = 2000)
  private val next =
      current.copy(
          syncId = "next",
          setIndex = 2,
          completed = false,
          completedAt = null,
          actualRir = null,
          setType = "UNKNOWN",
      )

  private fun snapshot(
      before: SnapshotSet = previous,
      done: SnapshotSet = current,
      upcoming: SnapshotSet = next,
      rest: Int? = 120,
  ) =
      WorkoutSnapshot(
          "owner",
          "workout",
          0,
          listOf(
              SnapshotExercise(
                  "section",
                  1,
                  "exercise",
                  "Жим",
                  sets = listOf(before, done, upcoming),
                  type = ExerciseType.STRENGTH,
              )
          ),
          futureRestSeconds = rest,
      )

  private fun calculate(
      before: SnapshotSet = previous,
      done: SnapshotSet = current,
      rest: Int? = 120,
      weights: List<Double> = emptyList(),
  ) =
      AutoregulationEngine.calculate(
          snapshot(before, done, rest = rest),
          AutoregulationOptions(availableWeightsKg = mapOf("exercise" to weights)),
      )

  @Test
  fun `first working set outside the rep range triggers advice without a previous set`() {
    for (reps in listOf(2, 16)) {
      val input = snapshot(done = current.copy(reps = reps))
      val only = input.exercises.single()
      val result =
          AutoregulationEngine.calculate(
              input.copy(
                  exercises = listOf(only.copy(sets = only.sets.filter { it.syncId != "previous" }))
              )
          )
      assertEquals(RecommendationKind.ADVISE, result.kind)
    }
  }

  @Test
  fun `three fewer repetitions at equal weight proposes more rest without a chat report`() {
    val result = calculate()
    assertEquals(RecommendationKind.ADJUST, result.kind)
    assertEquals(
        listOf(WorkoutChangeSet.Operation.Rest(RestAction.FUTURE_DURATION, null, 150)),
        result.operations,
    )
  }

  @Test
  fun `two fewer repetitions at equal weight preserves the plan`() {
    assertEquals(RecommendationKind.NO_CHANGE, calculate(done = current.copy(reps = 6)).kind)
  }

  @Test
  fun `five to two at fifty kilograms recommends a lower weight and more rest`() {
    val result =
        calculate(
            previous.copy(reps = 5),
            current.copy(reps = 2),
            weights = listOf(47.5, 50.0, 52.5),
        )
    assertEquals(
        listOf(
            WorkoutChangeSet.Operation.EditSet("next", weightKg = 47.5),
            WorkoutChangeSet.Operation.Rest(RestAction.FUTURE_DURATION, null, 150),
        ),
        result.operations,
    )
    assertFalse(result.operations.any { it is WorkoutChangeSet.Operation.DeleteSet })
  }

  @Test
  fun `increased weight below five recommends reduction without attributing the drop to rest`() {
    val result =
        calculate(
            previous.copy(weightKg = 47.5),
            current.copy(reps = 4),
            weights = listOf(47.5, 50.0),
        )
    assertEquals(
        listOf(WorkoutChangeSet.Operation.EditSet("next", weightKg = 47.5)),
        result.operations,
    )
    assertEquals(
        RecommendationKind.NO_CHANGE,
        calculate(previous.copy(weightKg = 47.5), current.copy(reps = 5)).kind,
    )
  }

  @Test
  fun `more than fifteen recommends a small available increase even with four plus RIR`() {
    val result =
        calculate(
            done = current.copy(reps = 16, actualRir = null, actualRirAtLeastFour = true),
            weights = listOf(50.0, 52.5, 55.0),
        )
    assertEquals(
        listOf(WorkoutChangeSet.Operation.EditSet("next", weightKg = 52.5)),
        result.operations,
    )
    assertEquals(RecommendationKind.NO_CHANGE, calculate(done = current.copy(reps = 15)).kind)
  }

  @Test
  fun `unknown equipment produces advice without inventing a weight or waiting for a reply`() {
    val result = calculate(previous.copy(reps = 5), current.copy(reps = 2), rest = null)
    assertEquals(RecommendationKind.ADVISE, result.kind)
    assertTrue(result.operations.isEmpty())
    assertTrue(result.explanation().contains("снизить вес"))
    assertTrue(result.explanation().contains("увеличить отдых"))
    val decision =
        CoachInitiativePolicy.next(
            CoachInitiativeState(welcomed = true),
            0,
            emptyList(),
            emptyList(),
            assessment = result,
        )!!
    assertFalse(decision.nextState.pendingInteraction)
    assertNull(
        CoachInitiativePolicy.next(
            decision.nextState,
            1,
            emptyList(),
            emptyList(),
            assessment = result,
        )
    )
  }

  @Test
  fun `warmup and interrupted previous sets do not trigger comparison`() {
    for (before in
        listOf(
            previous.copy(setType = "WARMUP"),
            previous.copy(reportedFeelings = setOf("INTERRUPTED")),
        )) {
      assertEquals(RecommendationKind.NO_CHANGE, calculate(before).kind)
    }
  }

  @Test
  fun `planned effort and pain take precedence over rep thresholds`() {
    assertEquals(
        RecommendationKind.NO_CHANGE,
        calculate(done = current.copy(reps = 2, reportedFeelings = setOf("PLANNED_EFFORT"))).kind,
    )
    val pain = calculate(done = current.copy(reps = 2, reportedFeelings = setOf("PAIN")))
    assertEquals(RecommendationKind.CLARIFY, pain.kind)
    assertTrue(pain.operations.isEmpty())
  }

  @Test
  fun `different upcoming load is preserved and a weight step beyond five percent is not invented`() {
    val input = snapshot(done = current.copy(reps = 16), upcoming = next.copy(weightKg = 40.0))
    val result =
        AutoregulationEngine.calculate(
            input,
            AutoregulationOptions(availableWeightsKg = mapOf("exercise" to listOf(52.5))),
        )
    assertEquals(RecommendationKind.ADVISE, result.kind)
    assertTrue(result.operations.isEmpty())
    assertEquals(
        RecommendationKind.ADVISE,
        calculate(done = current.copy(reps = 16), weights = listOf(60.0)).kind,
    )
  }

  @Test
  fun `rest increase extends the active timed rest and never shortens a long rest`() {
    val result =
        AutoregulationEngine.calculate(snapshot().copy(rest = SnapshotRest("timer", 120, 90, 1000)))
    assertTrue(WorkoutChangeSet.Operation.Rest(RestAction.EXTEND, "timer", 30) in result.operations)
    val long = calculate(rest = 360)
    assertTrue(long.operations.isEmpty())
    val capped = calculate(rest = 290)
    assertEquals(
        listOf(WorkoutChangeSet.Operation.Rest(RestAction.FUTURE_DURATION, null, 300)),
        capped.operations,
    )
  }

  @Test
  fun `actual repetitions override copied plan values and unrelated exercises are not compared`() {
    val result = calculate(done = current.copy(reps = 8, actualReps = 5))
    assertEquals(RecommendationKind.ADJUST, result.kind)
    val input = snapshot()
    val section = input.exercises.single()
    val separated =
        input.copy(
            exercises =
                listOf(
                    section.copy(sets = listOf(current, next)),
                    section.copy(sectionId = "other", exerciseId = 2, sets = listOf(previous)),
                )
        )
    assertEquals(RecommendationKind.NO_CHANGE, AutoregulationEngine.calculate(separated).kind)
  }
}
