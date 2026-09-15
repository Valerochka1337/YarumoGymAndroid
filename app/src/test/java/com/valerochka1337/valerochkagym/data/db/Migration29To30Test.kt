package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration29To30Test {
  @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val name = "strength-planner-29-30.db"

  @After fun cleanup() { context.deleteDatabase(name) }

  @Test
  fun `migration retains existing workouts and creates optional planner tables`() {
    helper.createDatabase(name, 29).use { db ->
      db.execSQL("INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Тренировка',1,'', 'PENDING',0)")
    }
    val room =
        Room.databaseBuilder(context, GymDatabase::class.java, name)
            .addMigrations(*GymDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
    try {
          room.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('strength_planner_profiles','strength_planner_key_exercises','workout_efforts')").use { cursor ->
            assertEquals(3, cursor.count)
          }
          room.openHelper.writableDatabase.query("SELECT name FROM workouts WHERE id='w'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Тренировка", cursor.getString(0))
          }
          room.openHelper.writableDatabase
              .query(
                  "SELECT 1 FROM sqlite_master WHERE type='table' AND name='coach_relation_operations'"
              )
              .use { cursor -> assertEquals(0, cursor.count) }
          room.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
          }
    } finally {
      room.close()
    }
  }
}
