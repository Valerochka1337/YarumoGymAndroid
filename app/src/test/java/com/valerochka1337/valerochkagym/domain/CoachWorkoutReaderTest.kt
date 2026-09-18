package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CoachWorkoutReaderTest : RoomDaoTest() {
  @Test
  fun `snapshot keeps portable exclusions for exercises outside the workout`() = runTest {
    val workout = insertWorkout("excluded-context")
    val excluded = exercise("Excluded press")
    val session = FakeSession()
    session.save(BackendTokens("user", "user@example.com", "access", "refresh"))
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)"
    )
    val deadline = System.currentTimeMillis() + 600_000L
    db.coachDao()
        .saveContext(
            com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity(
                workout,
                "user",
                excludedExerciseIdsJson = "[$excluded]",
                availableTimeEndsAtMillis = deadline,
            )
        )
    val snapshot =
        CoachWorkoutReader(db, RestTimerEngine(backgroundScope) { 0L }, session)
            .snapshot("user", workout)!!
    assertTrue(snapshot.exercises.isEmpty())
    assertEquals(
        setOf(db.exerciseDao().getById(excluded)!!.syncId),
        snapshot.excludedExerciseSyncIds,
    )
    assertEquals(deadline, snapshot.availableTimeEndsAtMillis)
  }

  @Test
  fun `snapshot reads only the active owners profile and durable decisions`() = runTest {
    val workout = insertWorkout("active-profile")
    val session = FakeSession()
    session.save(BackendTokens("user", "user@example.com", "access", "refresh"))
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)"
    )
    db.profileDao()
        .upsert(
            com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity(
                "other",
                "other-profile",
                preferredRepMin = 3,
                preferredRepMax = 5,
            )
        )
    db.profileDao()
        .upsert(
            com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity(
                "user",
                "profile",
                trainingGoal = "MUSCLE_GAIN",
                manualConstraints = "Без спешки",
                preferredRepMin = 6,
                preferredRepMax = 12,
            )
        )
    val decision = CoachDecisionMemory("proposal", "REJECTED", "Изменение веса", setOf("section"))
    db.coachDao()
        .saveContext(
            com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity(
                workout,
                "user",
                decisionMemoryJson = CoachDecisionMemory.encode(listOf(decision)),
            )
        )
    val reader = CoachWorkoutReader(db, RestTimerEngine(backgroundScope) { 0L }, session)
    val snapshot = reader.snapshot("user", workout)!!
    assertEquals(6, snapshot.profile.preferredRepMin)
    assertEquals("MUSCLE_GAIN", snapshot.profile.trainingGoal)
    assertEquals(listOf(decision), snapshot.coachDecisions)
    assertNull(reader.snapshot("other", workout))
  }

  @Test
  fun `search ranks before limiting filters muscle groups and returns the entire latest workout`() =
      runTest {
        repeat(51) { exercise("Unused $it") }
        val older = exercise("Older")
        val recent = exercise("Recent")
        val back = exercise("Back", MuscleGroup.BACK)
        val past = insertWorkout("past", finishedAt = 2000)
        insertSet(insertWorkoutExercise(past, older), 0, reps = 10, isCompleted = true)
        val latest = insertWorkout("latest", finishedAt = 3000)
        val firstSection = insertWorkoutExercise(latest, recent)
        repeat(31) {
          insertSet(firstSection, it, weightKg = 30.0 + it, reps = 8, isCompleted = true)
        }
        insertSet(firstSection, 31, weightKg = 999.0, reps = 1)
        insertSet(insertWorkoutExercise(latest, recent, 1), 0, reps = 7, isCompleted = true)
        insertSet(insertWorkoutExercise(latest, back), 0, reps = 10, isCompleted = true)
        val active = insertWorkout("active", startedAt = 4000)
        insertSet(insertWorkoutExercise(active, older), 0, reps = 99, isCompleted = true)
        val reader = CoachWorkoutReader(db, RestTimerEngine(backgroundScope) { 0L }, FakeSession())
        val snapshot =
            WorkoutSnapshot("user", active, 0, listOf(SnapshotExercise("scheduled", recent)))
        val results = reader.find(snapshot, null, null, null, setOf("CHEST"), 2)
        assertEquals(listOf("Recent", "Older"), results.map { it.name })
        assertEquals(3000L, results.first().lastUsedAt)
        assertEquals(1, results.first().workoutCount)
        assertEquals(32, results.first().lastWorkoutSets.size)
        assertEquals(7, results.first().lastWorkoutSets.last().set.reps)
        assertEquals(listOf("scheduled"), results.first().currentSectionIds)
        assertEquals(10, results.last().lastWorkoutSets.single().set.reps)
        assertTrue(
            reader
                .find(snapshot.copy(excludedExerciseIds = setOf(recent)), "Recent", null, null)
                .isEmpty()
        )
      }

  @Test
  fun `informational history includes three finished workouts and skips unfinished only sessions`() =
      runTest {
        val exercise = exercise("Press")
        repeat(5) { index ->
          val section =
              insertWorkoutExercise(
                  insertWorkout("past$index", finishedAt = 2000L + index),
                  exercise,
              )
          insertSet(section, 0, reps = index + 1, isCompleted = index != 4)
        }
        val reader = CoachWorkoutReader(db, RestTimerEngine(backgroundScope) { 0L }, FakeSession())
        val history = reader.history(db.exerciseDao().getById(exercise)!!.syncId)
        assertEquals(listOf(4, 3, 2), history.map { it.set.reps })
        assertEquals(listOf("past3", "past2", "past1"), history.map { it.historyWorkoutId })
        assertTrue(history.all { it.set.completedAt == null })
      }

  private suspend fun exercise(name: String, group: MuscleGroup = MuscleGroup.CHEST): Long =
      db.exerciseDao()
          .insert(ExerciseEntity(name = name, muscleGroup = group, type = ExerciseType.STRENGTH))

  private class FakeSession : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }
}
