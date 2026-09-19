package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CoachDirtySessionTest : RoomDaoTest() {
  @Test
  fun `context and delivery marker roll back together`() = runTest {
    val workout = insertWorkout("rollback")
    try {
      db.withTransaction {
        db.coachDao().saveContext(CoachSessionContextEntity(workout, "owner"))
        throw IllegalStateException("Abort edit")
      }
    } catch (_: IllegalStateException) {}
    assertNull(db.coachDao().context(workout))
    assertTrue(db.coachRunDao().dirtySessions().isEmpty())
  }

  @Test
  fun `acknowledging an older capture retains newer context changes`() = runTest {
    val workout = insertWorkout("newer-context")
    val context = CoachSessionContextEntity(workout, "owner")
    db.coachDao().saveContext(context)
    val captured = db.coachRunDao().dirtySessions().single()
    db.coachDao().saveContext(context.copy(availableTimeMinutes = 15))
    db.coachRunDao().clean(workout, captured.generation)
    assertEquals(captured.generation + 1, db.coachRunDao().dirtySessions().single().generation)
  }

  @Test
  fun `set edits and workout removal retain durable delivery work`() = runTest {
    val repository = ActiveWorkoutRepositoryImpl(db, db.workoutDao(), db.routineDao())
    val workout = repository.startEmpty()
    val exercise =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Press",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                )
            )
    val section = repository.addExercise(workout, exercise)
    val initial = db.coachRunDao().dirtySessions().single()
    db.coachRunDao().clean(workout, initial.generation)
    val set = db.workoutDao().getSetsForWorkoutExercise(section).single()
    repository.mutateSet(set.id) { it.copy(reps = 8) }
    assertEquals(workout, db.coachRunDao().dirtySessions().single().workoutId)
    repository.discard(workout)
    assertNull(db.workoutDao().getWorkoutFull(workout))
    assertEquals(workout, db.coachRunDao().dirtySessions().single().workoutId)
  }
}
