package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [currentFocus] / [lastCompletedFocus]. Both are pure functions over an already
 * domain-sorted [WorkoutFull], so the trees here are hand-built — no database and no Robolectric.
 */
class SessionFocusTest {

  @Test
  fun `extra set stays in sequence until the next exercise has a completed set`() {
    val first = exercise("Жим", set(1, completed = true))
    val second =
        exercise("Тяга", set(2), set(3)).let {
          it.copy(workoutExercise = it.workoutExercise.copy(id = 2))
        }
    val workout = workout(first, second)
    assertEquals(true, workout.canAddSet(1))
    assertEquals(true, workout.canAddSet(2))
    val started = second.copy(sets = listOf(set(2, completed = true), set(3)))
    assertEquals(false, workout(first, started).canAddSet(1))
    val extended = first.copy(sets = first.sets + set(4))
    assertEquals(4L, workout(extended, second).currentFocus()?.set?.id)
    assertEquals(false, workout(extended, second).canAddSet(2))
  }

  @Test
  fun `extra set is available after the final set until the workout finishes`() {
    val workout = workout(exercise("Жим", set(1, completed = true)))
    assertEquals(true, workout.canAddSet(1))
    assertEquals(
        false,
        workout.copy(workout = workout.workout.copy(finishedAt = 2000)).canAddSet(1),
    )
    assertEquals(false, workout.canAddSet(99))
    assertEquals(false, workout().canAddSet(1))
  }

  // region currentFocus

  @Test
  fun `currentFocus returns the first incomplete set of the first unfinished exercise`() {
    val workout =
        workout(
            exercise(
                "Жим лёжа",
                set(1, completed = true),
                set(2, completed = true),
                set(3),
                set(4),
            ),
            exercise("Тяга", set(5)),
        )

    val focus = workout.currentFocus()

    assertEquals("Жим лёжа", focus?.exerciseName)
    assertEquals(3L, focus?.set?.id)
    assertEquals(3, focus?.setNumber)
    assertEquals(4, focus?.setsInExercise)
  }

  @Test
  fun `currentFocus skips a fully finished exercise and numbers sets within their own exercise`() {
    val workout =
        workout(
            exercise("Жим лёжа", set(1, completed = true), set(2, completed = true)),
            exercise("Тяга", set(3, completed = true), set(4), set(5)),
        )

    val focus = workout.currentFocus()

    assertEquals("Тяга", focus?.exerciseName)
    assertEquals(4L, focus?.set?.id)
    // Нумерация внутри упражнения, а не сквозная по тренировке.
    assertEquals(2, focus?.setNumber)
    assertEquals(3, focus?.setsInExercise)
  }

  @Test
  fun `currentFocus is null when everything is completed`() {
    val workout = workout(exercise("Жим лёжа", set(1, completed = true)))

    assertNull(workout.currentFocus())
  }

  @Test
  fun `currentFocus is null for an empty workout and for an exercise without sets`() {
    assertNull(workout().currentFocus())
    assertNull(workout(exercise("Жим лёжа")).currentFocus())
  }

  // endregion

  // region lastCompletedFocus

  @Test
  fun `lastCompletedFocus returns the set closed most recently, not the last in order`() {
    val workout =
        workout(
            exercise("Жим лёжа", set(1, completed = true, completedAt = 300)),
            exercise("Тяга", set(2, completed = true, completedAt = 100), set(3)),
        )

    val focus = workout.lastCompletedFocus()

    assertEquals("Жим лёжа", focus?.exerciseName)
    assertEquals(1L, focus?.set?.id)
  }

  @Test
  fun `lastCompletedFocus falls back to domain order when completedAt ties`() {
    val workout =
        workout(
            exercise("Жим лёжа", set(1, completed = true, completedAt = 100)),
            exercise("Тяга", set(2, completed = true, completedAt = 100)),
        )

    // Равные отметки времени — берём более поздний подход в доменном порядке.
    assertEquals(2L, workout.lastCompletedFocus()?.set?.id)
  }

  @Test
  fun `lastCompletedFocus handles sets closed before completedAt existed`() {
    val workout =
        workout(
            exercise("Жим лёжа", set(1, completed = true), set(2, completed = true), set(3)),
        )

    val focus = workout.lastCompletedFocus()

    assertEquals(2L, focus?.set?.id)
    assertEquals(2, focus?.setNumber)
  }

  @Test
  fun `lastCompletedFocus is null when nothing is completed`() {
    assertNull(workout(exercise("Жим лёжа", set(1), set(2))).lastCompletedFocus())
  }

  // endregion

  // region counts

  @Test
  fun `set counts span the whole workout`() {
    val workout =
        workout(
            exercise("Жим лёжа", set(1, completed = true), set(2)),
            exercise("Тяга", set(3, completed = true), set(4, completed = true), set(5)),
        )

    assertEquals(3, workout.completedSetCount())
    assertEquals(5, workout.totalSetCount())
  }

  // endregion

  private fun workout(vararg exercises: WorkoutExerciseWithSets) =
      WorkoutFull(
          workout = WorkoutEntity(id = "w", name = "Тренировка", startedAt = 1_000),
          exercises = exercises.toList(),
      )

  private fun exercise(
      name: String,
      vararg sets: WorkoutSetEntity,
      type: ExerciseType = ExerciseType.STRENGTH,
  ) =
      WorkoutExerciseWithSets(
          workoutExercise =
              WorkoutExerciseEntity(id = 1, workoutId = "w", exerciseId = 1, position = 0),
          exercise =
              ExerciseEntity(id = 1, name = name, muscleGroup = MuscleGroup.CHEST, type = type),
          sets = sets.toList(),
      )

  private fun set(
      id: Long,
      completed: Boolean = false,
      completedAt: Long? = null,
  ) =
      WorkoutSetEntity(
          id = id,
          workoutExerciseId = 1,
          setIndex = id.toInt(),
          isCompleted = completed,
          completedAt = completedAt,
      )
}
