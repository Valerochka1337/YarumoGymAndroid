package com.valerochka1337.valerochkagym.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration37To38Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration creates v2 tables without adopting legacy preferences`() {
    val name = "planner-accents-37-38.db"
    helper.createDatabase(name, 37).use { db ->
      db.execSQL(
          "INSERT INTO planner_exercise_preferences(scope,exerciseSyncId,preference) VALUES('owner','legacy','MORE')"
      )
    }
    helper.runMigrationsAndValidate(name, 38, true, GymDatabase.MIGRATION_37_38).use { db ->
      db.query("SELECT preference FROM planner_exercise_preferences WHERE scope='owner'").use {
        assertEquals(true, it.moveToFirst())
        assertEquals("MORE", it.getString(0))
      }
      db.query("SELECT * FROM planner_exercise_accent_markers").use { assertEquals(0, it.count) }
      db.query(
              "SELECT name FROM sqlite_master WHERE type='table' AND name='planner_exercise_accents_v2'"
          )
          .use { assertEquals(1, it.count) }
    }
  }
}
