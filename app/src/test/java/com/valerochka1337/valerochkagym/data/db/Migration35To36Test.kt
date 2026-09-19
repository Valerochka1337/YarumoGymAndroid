package com.valerochka1337.valerochkagym.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration35To36Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves durable coach rows and creates planner preferences table`() {
    val name = "planner-preferences-35-36.db"
    helper.createDatabase(name, 35).use { db ->
      db.execSQL("INSERT INTO coach_dirty_sessions(workoutId,generation) VALUES('workout',7)")
    }

    helper.runMigrationsAndValidate(name, 36, true, GymDatabase.MIGRATION_35_36).use { db ->
      db.query("SELECT generation FROM coach_dirty_sessions WHERE workoutId='workout'").use {
        assertTrue(it.moveToFirst())
        assertEquals(7, it.getInt(0))
      }
      db.query(
              "SELECT name FROM sqlite_master WHERE type='table' AND name='planner_exercise_preferences'"
          )
          .use { assertEquals(1, it.count) }
      db.execSQL(
          "INSERT INTO planner_exercise_preferences(scope,exerciseSyncId,preference) VALUES('owner','exercise','MORE')"
      )
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
