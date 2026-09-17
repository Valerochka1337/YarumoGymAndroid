package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import org.junit.Assert.*
import org.junit.Test

class CoachInitiativeTriggerTest {
  @Test
  fun `one minute of countdown schedules no repeated business calculations`() {
    val trigger = CoachInitiativeTrigger()
    val workout = workout()
    val rest = RestTimerState.Timed(90, 90, 90_000)
    assertTrue(trigger.changed(workout, rest))
    for (remaining in 89 downTo 30) {
      assertFalse(trigger.changed(workout.copy(), rest.copy(remainingSec = remaining)))
    }
  }

  @Test
  fun `rest starts extensions replacements and completion trigger reassessment`() {
    val trigger = CoachInitiativeTrigger()
    val workout = workout()
    val rest = RestTimerState.Timed(90, 90, 90_000)
    assertTrue(trigger.changed(workout, null))
    assertTrue(trigger.changed(workout, rest))
    assertTrue(trigger.changed(workout, rest.copy(totalSec = 120, endsAtMillis = 120_000)))
    assertTrue(trigger.changed(workout, rest.copy(totalSec = 120, endsAtMillis = 130_000)))
    assertTrue(trigger.changed(workout, null))
    assertFalse(trigger.changed(workout, null))
  }

  @Test
  fun `heart rate hold progress stays quiet while rest plan changes trigger reassessment`() {
    val trigger = CoachInitiativeTrigger()
    val workout = workout()
    val rest = RestTimerState.HeartRate(110, 10, 1_000)
    assertTrue(trigger.changed(workout, rest))
    assertFalse(trigger.changed(workout, rest.copy(belowSinceMillis = 2_000)))
    assertFalse(trigger.changed(workout, rest))
    assertTrue(trigger.changed(workout, rest.copy(thresholdBpm = 115)))
    assertTrue(trigger.changed(workout, rest.copy(holdSeconds = 15)))
    assertTrue(trigger.changed(workout, rest.copy(startedAtMillis = 5_000)))
    assertTrue(trigger.changed(workout, null))
  }

  @Test
  fun `RIR changes trigger reassessment even without a revision increment`() {
    val trigger = CoachInitiativeTrigger()
    val workout = workout()
    assertTrue(trigger.changed(workout, null))
    val section = workout.exercises.single()
    val edited =
        workout.copy(
            exercises =
                listOf(section.copy(sets = listOf(section.sets.single().copy(actualRir = 1))))
        )
    assertTrue(trigger.changed(edited, null))
    assertFalse(trigger.changed(edited.copy(), null))
    assertTrue(
        trigger.changed(edited.copy(workout = edited.workout.copy(coachRevision = 12)), null)
    )
  }

  @Test
  fun `finished or absent workouts stay quiet and a new active workout triggers reassessment`() {
    val trigger = CoachInitiativeTrigger()
    val workout = workout()
    assertFalse(trigger.changed(null, null))
    assertTrue(trigger.changed(workout, null))
    assertFalse(
        trigger.changed(workout.copy(workout = workout.workout.copy(finishedAt = 1000)), null)
    )
    assertFalse(trigger.changed(null, null))
    assertTrue(trigger.changed(workout, null))
    assertTrue(trigger.changed(workout.copy(workout = workout.workout.copy(id = "next")), null))
  }

  private fun workout() =
      WorkoutFull(
          WorkoutEntity(id = "workout", name = "Workout", startedAt = 0, coachRevision = 11),
          listOf(
              WorkoutExerciseWithSets(
                  WorkoutExerciseEntity(
                      id = 1,
                      workoutId = "workout",
                      exerciseId = 1,
                      position = 0,
                  ),
                  ExerciseEntity(
                      id = 1,
                      name = "Press",
                      muscleGroup = MuscleGroup.CHEST,
                      type = ExerciseType.STRENGTH,
                  ),
                  listOf(
                      WorkoutSetEntity(
                          id = 1,
                          workoutExerciseId = 1,
                          setIndex = 0,
                          weightKg = 50.0,
                          reps = 8,
                          isCompleted = true,
                          setType = "WORK",
                      )
                  ),
              )
          ),
      )
}
