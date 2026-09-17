package com.valerochka1337.valerochkagym.domain.autoregulation

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutoregulationEngineTest {
  @Test
  fun `four plus stays a range and never produces an exact RIR adjustment`() {
    val result = calculate(done.copy(actualRir = null, actualRirAtLeastFour = true))
    assertEquals(RecommendationKind.NO_CHANGE, result.kind)
    assertTrue(result.missingData.isEmpty())
    assertNull(result.packet())
  }

  @Test
  fun `missing effort never triggers automatic follow up questions`() {
    for (set in
        listOf(
            done.copy(actualRir = null, reportedFeelings = emptySet()),
            done.copy(setType = "UNKNOWN"),
        )) {
      assertNull(
          CoachInitiativePolicy.next(
              CoachInitiativeState(welcomed = true),
              1000,
              emptyList(),
              emptyList(),
              assessment = calculate(set),
          )
      )
    }
    assertNotNull(
        CoachInitiativePolicy.next(
            CoachInitiativeState(welcomed = true),
            1000,
            emptyList(),
            emptyList(),
            assessment = calculate(done.copy(reportedFeelings = setOf("PAIN"))),
        )
    )
  }

  @Test
  fun `reported planned effort prevents interpreting a deliberate hard set as fatigue`() {
    assertEquals(
        RecommendationKind.NO_CHANGE,
        calculate(done.copy(reportedFeelings = setOf("PLANNED_EFFORT"))).kind,
    )
  }

  @Test
  fun `reported pain asks for clarification even when RIR would increase the load`() {
    assertEquals(
        RecommendationKind.CLARIFY,
        calculate(done.copy(actualRir = 5, reportedFeelings = setOf("PAIN"))).kind,
    )
  }

  @Test
  fun `confirmed harder effort resolves ambiguity about reduced repetitions`() {
    assertEquals(
        RecommendationKind.NO_CHANGE,
        calculate(done.copy(reps = 6, reportedFeelings = emptySet())).kind,
    )
    val result = calculate(done.copy(reps = 6, reportedFeelings = setOf("HARDER_THAN_EXPECTED")))
    assertEquals(RecommendationKind.ADJUST, result.kind)
    assertEquals(WorkoutChangeSet.Operation.EditSet("next", reps = 5), result.operations.first())
  }

  @Test
  fun `planned descending repetitions do not trigger a sustained deviation question`() {
    val input =
        snapshot(done.copy(reps = 6, targetReps = 6, actualRir = 3, reportedFeelings = emptySet()))
    val section = input.exercises.single()
    val earlier =
        done.copy(syncId = "earlier", completedAt = 500, reps = 8, targetReps = 8, actualRir = 3)
    assertEquals(
        RecommendationKind.NO_CHANGE,
        AutoregulationEngine.calculate(
                input.copy(exercises = listOf(section.copy(sets = listOf(earlier) + section.sets)))
            )
            .kind,
    )
  }

  private val done =
      SnapshotSet(
          "done",
          0,
          true,
          100.0,
          8,
          null,
          1000,
          setType = "WORK",
          targetWeightKg = 100.0,
          targetReps = 8,
          actualRir = 1,
          reportedFeelings = setOf("HARDER_THAN_EXPECTED"),
      )
  private val next =
      done.copy(
          syncId = "next",
          setIndex = 1,
          completed = false,
          completedAt = null,
          actualRir = null,
          reportedFeelings = emptySet(),
      )

  private fun snapshot(completed: SnapshotSet = done, upcoming: SnapshotSet = next) =
      WorkoutSnapshot(
          "owner",
          "workout",
          4,
          listOf(
              SnapshotExercise(
                  "section",
                  1,
                  "exercise",
                  "Жим",
                  sets = listOf(completed, upcoming),
                  type = ExerciseType.STRENGTH,
              )
          ),
          futureRestSeconds = 120,
      )

  private fun calculate(completed: SnapshotSet = done, upcoming: SnapshotSet = next) =
      AutoregulationEngine.calculate(snapshot(completed, upcoming))

  @Test
  fun `harder work reduces next repetitions and increases future rest`() {
    val result = calculate()
    assertEquals(RecommendationKind.ADJUST, result.kind)
    assertEquals(WorkoutChangeSet.Operation.EditSet("next", reps = 7), result.operations.first())
    assertEquals(
        WorkoutChangeSet.Operation.Rest(RestAction.FUTURE_DURATION, null, 150),
        result.operations.last(),
    )
    assertNotNull(result.packet()?.autoregulation)
  }

  @Test
  fun `reported RIR alone never invents a target or increases load`() {
    for (rir in 0..10) {
      val result = calculate(done.copy(actualRir = rir, reportedFeelings = emptySet()))
      assertEquals(RecommendationKind.NO_CHANGE, result.kind)
      assertTrue(result.operations.isEmpty())
      assertTrue(result.missingData.isEmpty())
    }
  }

  @Test
  fun `planned hard work stays unchanged`() {
    assertEquals(
        RecommendationKind.NO_CHANGE,
        calculate(done.copy(actualRir = 0, reportedFeelings = emptySet())).kind,
    )
  }

  @Test
  fun `missing RIR stays unknown even with pulse and history`() {
    val input =
        snapshot(done.copy(actualRir = null, reportedFeelings = emptySet()))
            .copy(pulse = SnapshotPulse(180, 1000))
    val result = AutoregulationEngine.calculate(input)
    assertEquals(RecommendationKind.NO_CHANGE, result.kind)
    assertTrue(result.missingData.isEmpty())
    assertNull(result.packet())
  }

  @Test
  fun `warmup never changes working load`() {
    assertEquals(RecommendationKind.NO_CHANGE, calculate(done.copy(setType = "WARMUP")).kind)
  }

  @Test
  fun `interruption asks intent instead of reducing load`() {
    val result = calculate(done.copy(reportedFeelings = setOf("INTERRUPTED")))
    assertEquals(RecommendationKind.CLARIFY, result.kind)
    assertNull(result.packet())
  }

  @Test
  fun `different planned next set asks intent`() {
    assertEquals(RecommendationKind.CLARIFY, calculate(upcoming = next.copy(targetReps = 6)).kind)
  }

  @Test
  fun `available weights select only a real small step`() {
    val options =
        AutoregulationOptions(TrainingGoal.STRENGTH, mapOf("exercise" to listOf(90.0, 97.5, 100.0)))
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("next", weightKg = 97.5),
        AutoregulationEngine.calculate(snapshot(), options).operations.first(),
    )
    assertEquals(
        RecommendationKind.CLARIFY,
        AutoregulationEngine.calculate(
                snapshot(),
                options.copy(availableWeightsKg = mapOf("exercise" to listOf(90.0, 100.0))),
            )
            .kind,
    )
  }

  @Test
  fun `time budget removes only unfinished suffix`() {
    val initial = snapshot()
    val input =
        initial.copy(
            availableTimeMinutes = 3,
            exercises =
                initial.exercises.map {
                  it.copy(sets = it.sets + next.copy(syncId = "last", setIndex = 2))
                },
        )
    val result = AutoregulationEngine.calculate(input)
    assertEquals(listOf(WorkoutChangeSet.Operation.DeleteSet("last")), result.operations)
  }

  @Test
  fun `insufficient rest preserves prescribed load`() {
    val result =
        AutoregulationEngine.calculate(snapshot(), AutoregulationOptions(observedRestSeconds = 30))
    assertEquals(RecommendationKind.NO_CHANGE, result.kind)
  }

  @Test
  fun `repeated events and process restoration produce the same packet`() {
    val first = calculate()
    assertEquals(first, calculate())
    val packet = first.packet()!!
    assertEquals(
        packet,
        Json.decodeFromString<WorkoutChangeSet.Packet>(Json.encodeToString(packet)),
    )
    assertEquals(
        first.evidenceKey,
        AutoregulationEngine.calculate(
                snapshot()
                    .copy(revision = 90, elapsedSeconds = 900, pulse = SnapshotPulse(90, 2000))
            )
            .evidenceKey,
    )
  }

  @Test
  fun `changed facts invalidate evidence and calculation`() {
    assertNotEquals(calculate().evidenceKey, calculate(done.copy(actualRir = 5)).evidenceKey)
    assertNotEquals(calculate().packet(), calculate(done.copy(actualRir = 5)).packet())
  }

  @Test
  fun `invalid numbers and duplicate identities produce no mutation`() {
    assertNull(calculate(done.copy(weightKg = Double.NaN)).packet())
    assertNull(calculate(upcoming = next.copy(syncId = "done")).packet())
    assertNull(calculate(done.copy(actualRir = 11)).packet())
  }

  @Test
  fun `initiative ignores unchanged and rejected facts and respects pending without a count limit`() {
    val state = CoachInitiativeState(welcomed = true)
    val result = calculate()
    val decision =
        CoachInitiativePolicy.next(state, 1000, emptyList(), emptyList(), assessment = result)!!
    assertEquals(CoachInitiativeKind.AUTOREGULATION, decision.kind)
    assertNull(
        CoachInitiativePolicy.next(
            decision.nextState.copy(pendingInteraction = false),
            900000,
            emptyList(),
            emptyList(),
            assessment = result,
        )
    )
    assertNull(
        CoachInitiativePolicy.next(
            state.copy(pendingInteraction = true),
            1000,
            emptyList(),
            emptyList(),
            assessment = result,
        )
    )
    assertNotNull(
        CoachInitiativePolicy.next(
            state.copy(automaticCount = 3),
            1000,
            emptyList(),
            emptyList(),
            assessment = result,
        )
    )
    assertNull(
        CoachInitiativePolicy.next(
            state,
            1000,
            emptyList(),
            emptyList(),
            assessment = calculate(done.copy(actualRir = 3, reportedFeelings = emptySet())),
        )
    )
  }
}
