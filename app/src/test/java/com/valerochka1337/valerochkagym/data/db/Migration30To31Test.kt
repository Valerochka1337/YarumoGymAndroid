package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
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
class Migration30To31Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val name = "planner-preferences-30-31.db"

  @After
  fun cleanup() {
    context.deleteDatabase(name)
  }

  @Test
  fun `migration preserves version thirty rows and creates preferences aggregate table`() {
    helper.createDatabase(name, 30).use { db ->
      db.execSQL(
          "INSERT INTO strength_planner_profiles(scope,syncId,updatedAt) VALUES('owner','sync',1)"
      )
    }
    val room =
        Room.databaseBuilder(context, GymDatabase::class.java, name)
            .addMigrations(*GymDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
    try {
      room.openHelper.writableDatabase.query(
          "SELECT name FROM sqlite_master WHERE type='table' AND name='planner_exercise_preferences'"
      ).use { assertEquals(1, it.count) }
      room.openHelper.writableDatabase.query(
          "SELECT syncId FROM strength_planner_profiles WHERE scope='owner'"
      ).use {
        org.junit.Assert.assertTrue(it.moveToFirst())
        assertEquals("sync", it.getString(0))
      }
    } finally {
      room.close()
    }
  }
}
