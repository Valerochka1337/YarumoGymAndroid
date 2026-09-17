package com.valerochka1337.valerochkagym.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration32To33Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves profiles and pending decisions without inventing preferences`() {
    val name = "coach-context-32-33.db"
    helper.createDatabase(name, 32).use { db ->
      db.execSQL(
          "INSERT INTO profiles(scope,syncId,schemaVersion,trainingGoal,updatedAt) VALUES('owner','profile',1,'STRENGTH',42)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Workout',1,'','PENDING',3)"
      )
      db.execSQL(
          "INSERT INTO coach_session_context(workoutId,accountId,excludedExerciseIdsJson,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction,autoregulationOptionsJson) VALUES('w','owner','[]',1,0,2,'[\"seen\"]',0,1,'{}')"
      )
    }
    helper.runMigrationsAndValidate(name, 33, true, GymDatabase.MIGRATION_32_33).use { db ->
      db.query("SELECT trainingGoal,updatedAt,preferredRepMin,preferredRepMax FROM profiles").use {
        assertTrue(it.moveToFirst())
        assertEquals("STRENGTH", it.getString(0))
        assertEquals(42L, it.getLong(1))
        assertTrue(it.isNull(2))
        assertTrue(it.isNull(3))
      }
      db.query(
              "SELECT initiativeAskedExerciseIdsJson,decisionMemoryJson FROM coach_session_context"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertEquals("[\"seen\"]", it.getString(0))
            assertEquals("[]", it.getString(1))
          }
    }
  }
}
