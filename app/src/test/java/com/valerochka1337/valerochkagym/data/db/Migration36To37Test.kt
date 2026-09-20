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
class Migration36To37Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves old receipt bytes and cursors`() {
    val name = "coach-behavior-36-37"
    helper.createDatabase(name, 36).use {
      it.execSQL("INSERT INTO coach_receipt_outbox VALUES('r','owner','run','{  }')")
      it.execSQL("INSERT INTO coach_event_cursors VALUES('owner','workout',42)")
    }
    helper.runMigrationsAndValidate(name, 37, true, GymDatabase.MIGRATION_36_37).use { db ->
      db.query("SELECT payload,workoutId FROM coach_receipt_outbox").use {
        assertTrue(it.moveToFirst())
        assertEquals("{  }", it.getString(0))
        assertTrue(it.isNull(1))
      }
      db.query("SELECT sequence FROM coach_event_cursors").use {
        assertTrue(it.moveToFirst())
        assertEquals(42L, it.getLong(0))
      }
      db.execSQL("INSERT INTO coach_phase VALUES('owner','workout','UNKNOWN',0,'[]')")
      db.execSQL(
          "INSERT INTO coach_behavior VALUES('q','owner','workout','question','{}','PENDING','{  }')"
      )
    }
  }
}
