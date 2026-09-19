package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration1To35Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "planner-preferences-1-35.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `full migration retains historical routine and reaches planner preferences schema`() {
    helper.createDatabase(name, 1).use {
      it.execSQL("INSERT INTO routines(id,name,note) VALUES(1,'Ноги','')")
    }

    helper.runMigrationsAndValidate(name, 35, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT COUNT(*) FROM routines WHERE id=1").use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
      }
      db.query(
              "SELECT name FROM sqlite_master WHERE type='table' AND name='planner_exercise_preferences'"
          )
          .use { assertEquals(1, it.count) }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
