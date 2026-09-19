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
class Migration34To35Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves exact queued bytes and classifies prior automatic runs`() {
    val name = "coach-session-stream-34-35.db"
    helper.createDatabase(name, 34).use { db ->
      db.execSQL("INSERT INTO coach_runs VALUES('user','owner','w','{  }','v',1,0,0,4,NULL)")
      db.execSQL("INSERT INTO coach_runs VALUES('auto','owner','w','','v',2,1,0,2,NULL)")
      db.execSQL("INSERT INTO coach_session_outbox VALUES('w','owner',7,'v','{  }',0,0)")
      db.execSQL("INSERT INTO coach_dirty_sessions VALUES('w',3)")
    }
    helper.runMigrationsAndValidate(name, 35, true, GymDatabase.MIGRATION_34_35).use { db ->
      db.query("SELECT requestJson,origin,cursor FROM coach_runs WHERE requestId='user'").use {
        assertTrue(it.moveToFirst())
        assertEquals("{  }", it.getString(0))
        assertEquals("USER", it.getString(1))
        assertEquals(4L, it.getLong(2))
      }
      db.query("SELECT origin FROM coach_runs WHERE requestId='auto'").use {
        assertTrue(it.moveToFirst())
        assertEquals("COACH", it.getString(0))
      }
      db.query("SELECT sequence,payload FROM coach_session_outbox").use {
        assertTrue(it.moveToFirst())
        assertEquals(7L, it.getLong(0))
        assertEquals("{  }", it.getString(1))
      }
      db.execSQL("INSERT INTO coach_event_cursors VALUES('owner','w',9)")
    }
  }
}
