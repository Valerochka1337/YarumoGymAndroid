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
class Migration33To34Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves conversation and creates durable delivery tables`() {
    val name = "coach-durable-33-34.db"
    helper.createDatabase(name, 33).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Workout',1,'','PENDING',3)"
      )
      db.execSQL(
          "INSERT INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status) VALUES('m','owner','w','user','Question',1,'DELIVERED')"
      )
    }
    helper.runMigrationsAndValidate(name, 34, true, GymDatabase.MIGRATION_33_34).use { db ->
      db.query("SELECT text FROM coach_messages WHERE id='m'").use {
        assertTrue(it.moveToFirst())
        assertEquals("Question", it.getString(0))
      }
      db.execSQL("INSERT INTO coach_runs VALUES('r','owner','w','{}','v',2,0,0,0,NULL)")
      db.execSQL("INSERT INTO coach_session_outbox VALUES('w','owner',1,'v','{}',0,0)")
      db.execSQL("INSERT INTO coach_receipt_outbox VALUES('receipt','owner','r','{}')")
      db.execSQL("INSERT INTO coach_dirty_sessions VALUES('w',1)")
      // Deleting a workout must not erase a pending inactive-state/receipt delivery.
      db.execSQL("DELETE FROM workouts WHERE id='w'")
      listOf("coach_runs", "coach_session_outbox", "coach_receipt_outbox", "coach_dirty_sessions")
          .forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use {
              assertTrue(it.moveToFirst())
              assertEquals(1, it.getInt(0))
            }
          }
    }
  }
}
