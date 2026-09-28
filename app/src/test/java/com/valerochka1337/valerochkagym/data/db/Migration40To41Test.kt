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
class Migration40To41Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves independent requests coach evidence and initializes protocol one`() {
    val name = "preparation-40-41.db"
    helper.createDatabase(name, 40).use { db ->
      db.execSQL(
          "INSERT INTO workout_preparations(owner,requestId,intentJson,replacesJson,requestJson,state,createdAtMillis) VALUES('owner','request',' { \"intent\" : 1 } ','[]',' { \"request\" : true } ','READY',7)"
      )
      db.execSQL(
          "INSERT INTO workout_preparations(owner,requestId,intentJson,replacesJson,requestJson,state,createdAtMillis) VALUES('owner','second','{}','[\"request\"]',NULL,'FAILED',8)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachOriginalPlanJson) VALUES('workout','Силовая',1,'','LOCAL_ONLY',' { \"plan\" : 1 } ')"
      )
      db.execSQL(
          "INSERT INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status,sourceSetsJson) VALUES('message','account','workout','ASSISTANT','Готово',2,'DELIVERED',' [ { \"set\" : 1 } ] ')"
      )
    }
    helper.runMigrationsAndValidate(name, 41, true, GymDatabase.MIGRATION_40_41).use { db ->
      db.query("SELECT intentJson,requestJson,protocolVersion,proposalId FROM workout_preparations")
          .use { row ->
            assertEquals(true, row.moveToFirst())
            assertEquals(" { \"intent\" : 1 } ", row.getString(0))
            assertEquals(" { \"request\" : true } ", row.getString(1))
            assertEquals(1L, row.getLong(2))
            assertEquals(true, row.isNull(3))
          }
      db.query(
              "SELECT requestId,replacesJson,requestJson FROM workout_preparations WHERE owner='owner' ORDER BY createdAtMillis"
          )
          .use { rows ->
            assertEquals(true, rows.moveToFirst())
            assertEquals("request", rows.getString(0))
            assertEquals(true, rows.moveToNext())
            assertEquals("second", rows.getString(0))
            assertEquals("[\"request\"]", rows.getString(1))
            assertEquals(true, rows.isNull(2))
          }
      db.query("SELECT coachOriginalPlanJson FROM workouts WHERE id='workout'").use { row ->
        assertEquals(true, row.moveToFirst())
        assertEquals(" { \"plan\" : 1 } ", row.getString(0))
      }
      db.query("SELECT sourceSetsJson FROM coach_messages WHERE id='message'").use { row ->
        assertEquals(true, row.moveToFirst())
        assertEquals(" [ { \"set\" : 1 } ] ", row.getString(0))
      }
    }
  }
}
