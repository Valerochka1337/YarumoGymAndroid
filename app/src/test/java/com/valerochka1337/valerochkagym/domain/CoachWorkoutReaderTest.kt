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
    assertEquals(8, snapshot.profile.preferredRepMin)
    assertEquals(14, snapshot.profile.preferredRepMax)
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
    db.profileDao()
        .upsert(com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity("user", "profile"))
    assertNull(reader.snapshot("user", workout)!!.profile.preferredRepMin)
    assertNull(reader.snapshot("user", workout)!!.profile.preferredRepMax)
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
