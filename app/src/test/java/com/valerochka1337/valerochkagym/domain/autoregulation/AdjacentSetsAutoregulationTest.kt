package com.valerochka1337.valerochkagym.domain.autoregulation

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.*
import org.junit.Assert.*
import org.junit.Test

class AdjacentSetsAutoregulationTest {
  private val done = SnapshotSet("done", 1, true, 50.0, 8, null, 3000, setType = "WORK")
  private val next = done.copy(syncId = "next", setIndex = 2, completed = false, completedAt = null)

  private fun input(
      reps: Int = 8,
      history: List<SnapshotHistory> = emptyList(),
      previous: Int? = null,
      profile: CoachProfile = CoachProfile(),
  ): WorkoutSnapshot =
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
                  type = ExerciseType.STRENGTH,
                  sets =
                      listOfNotNull(
                          previous?.let {
                            done.copy(
                                syncId = "before",
                                setIndex = 0,
                                completedAt = 2500,
                                reps = it,
                            )
                          },
                          done.copy(reps = reps),
                          next,
                      ),
                  history = history,
              )
          ),
          profile = profile,
          observedAtMillis = 4000,
      )

  private fun history(reps: Int, weight: Double = 50.0, index: Int = 1) =
      (1..3).map {
        SnapshotHistory(
            it * 1000L,
            index,
            weight,
            reps,
            null,
            setType = "WORK",
            workoutId = "past$it",
            setSyncId = "set$it",
        )
      }

  @Test
  fun `three and twenty repetitions alone do not invent an obligatory range`() {
    for (reps in listOf(3, 20)) assertEquals(
        RecommendationKind.NO_CHANGE,
        AutoregulationEngine.calculate(input(reps)).kind,
    )
  }

  @Test
  fun `stable personal history takes precedence over a general preferred range`() {
    for (reps in listOf(3, 20)) assertEquals(
        RecommendationKind.NO_CHANGE,
        AutoregulationEngine.calculate(
                input(
                    reps,
                    history(reps),
                    profile = CoachProfile(preferredRepMin = 6, preferredRepMax = 12),
                )
            )
            .kind,
    )
  }

  @Test
  fun `first unfamiliar result outside the preference asks intent without a weight change`() {
    val result =
        AutoregulationEngine.calculate(
            input(3, profile = CoachProfile(preferredRepMin = 6, preferredRepMax = 12))
        )
    assertEquals(RecommendationKind.CLARIFY, result.kind)
    assertTrue(result.performanceSignal)
    assertTrue(result.operations.isEmpty())
  }

  @Test
  fun `persistent outside range invites an assessment without prescribing a guessed load`() {
    val result =
        AutoregulationEngine.calculate(
            input(
                4,
                previous = 4,
                profile = CoachProfile(preferredRepMin = 6, preferredRepMax = 12),
            )
        )
    assertEquals(RecommendationKind.ADVISE, result.kind)
    assertTrue(result.operations.isEmpty())
  }

  @Test
  fun `stable eight repetitions against twelve historically creates a meaningful signal`() {
    val result = AutoregulationEngine.calculate(input(8, history(12), previous = 8))
    assertEquals(RecommendationKind.ADVISE, result.kind)
    assertTrue(result.reason.contains("12.0"))
    assertTrue(result.operations.isEmpty())
  }

  @Test
  fun `improvement over comparable history can prompt assessment`() {
    assertEquals(
        RecommendationKind.ADVISE,
        AutoregulationEngine.calculate(input(12, history(8))).kind,
    )
  }

  @Test
  fun `known decline at the same set index does not trigger extra rest`() {
    assertEquals(
        RecommendationKind.NO_CHANGE,
        AutoregulationEngine.calculate(input(8, history(8), previous = 12)).kind,
    )
  }

  @Test
  fun `unexplained adjacent decline asks cause instead of extending a timer`() {
    val result =
        AutoregulationEngine.calculate(input(8, previous = 12).copy(futureRestSeconds = 120))
    assertEquals(RecommendationKind.CLARIFY, result.kind)
    assertTrue(result.operations.isEmpty())
  }

  @Test
  fun `different weight index old interrupted and warmup history are not a baseline`() {
    val candidates =
        listOf(
            history(12, 60.0),
            history(12, index = 0),
            history(12).map { it.copy(completedAt = -100L * 24 * 60 * 60 * 1000) },
            history(12).map { it.copy(interrupted = true) },
            history(12).map { it.copy(setType = "WARMUP") },
            history(12).take(1),
        )
    candidates.forEach {
      assertEquals(RecommendationKind.NO_CHANGE, AutoregulationEngine.calculate(input(8, it)).kind)
    }
  }

  @Test
  fun `duplicate sections at the same index do not inflate historical confidence`() {
    val history =
        history(12).flatMap { listOf(it, it.copy(setSyncId = it.setSyncId + "duplicate")) }
    assertEquals(
        RecommendationKind.NO_CHANGE,
        AutoregulationEngine.calculate(input(8, history)).kind,
    )
  }

  @Test
  fun `stale prefilled targets do not become an expected result`() {
    val initial = input(8)
    val changed =
        initial.copy(
            exercises =
                initial.exercises.map { e ->
                  e.copy(sets = e.sets.map { it.copy(targetReps = 20, originalReps = 20) })
                }
        )
    assertEquals(RecommendationKind.NO_CHANGE, AutoregulationEngine.calculate(changed).kind)
  }

  @Test
  fun `explicit planned effort and warmup prevent performance intervention`() {
    for (set in
        listOf(
            done.copy(setType = "WARMUP"),
            done.copy(reportedFeelings = setOf("PLANNED_EFFORT")),
        )) {
      val initial = input(8, history(12))
      assertEquals(
          RecommendationKind.NO_CHANGE,
          AutoregulationEngine.calculate(
                  initial.copy(
                      exercises = initial.exercises.map { it.copy(sets = listOf(set, next)) }
                  )
              )
              .kind,
      )
    }
  }

  @Test
  fun `profile change invalidates previously calculated evidence`() {
    val input = input()
    assertNotEquals(
        AutoregulationEngine.calculate(input).evidenceKey,
        AutoregulationEngine.calculate(
                input.copy(profile = CoachProfile(preferredRepMin = 10, preferredRepMax = 15))
            )
            .evidenceKey,
    )
  }
}
