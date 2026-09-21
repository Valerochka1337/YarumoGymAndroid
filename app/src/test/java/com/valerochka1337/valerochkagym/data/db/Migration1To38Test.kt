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
class Migration1To38Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "planner-accents-1-38.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `full migration retains routines and reaches empty v2 accent schema`() {
    helper.createDatabase(name, 1).use {
      it.execSQL("INSERT INTO routines(id,name,note) VALUES(1,'Ноги','')")
    }
    helper.runMigrationsAndValidate(name, 38, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT COUNT(*) FROM routines WHERE id=1").use {
        it.moveToFirst()
        assertEquals(1, it.getInt(0))
      }
      db.query("SELECT COUNT(*) FROM planner_exercise_accent_markers").use {
        it.moveToFirst()
        assertEquals(0, it.getInt(0))
      }
    }
  }
}
