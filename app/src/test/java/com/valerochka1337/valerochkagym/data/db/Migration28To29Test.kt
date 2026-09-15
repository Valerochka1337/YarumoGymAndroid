package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration28To29Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val name = "remove-coach-relations-28-29.db"

  @After
  fun cleanup() {
    context.deleteDatabase(name)
  }

  @Test
  fun `removing social relations preserves personal AI proposals and drops their queue`() {
    helper.createDatabase(name, 28).use { db ->
      db.execSQL(
          "INSERT INTO training_proposal_drafts(owner,proposalId,version,proposalJson,draftJson) VALUES('owner','personal',1,'{\"source\":\"AI\",\"note\":\"COACH\"}','{}')"
      )
      db.execSQL(
          "INSERT INTO training_proposal_drafts(owner,proposalId,version,proposalJson,draftJson) VALUES('owner','social',1,'{ \"source\" : \"COACH\" }','{}')"
      )
      db.execSQL(
          "INSERT INTO training_proposal_operations(owner,proposalId,version,operationId,requestBytes,requestSha256,rejected) VALUES('owner','personal',1,'personal-op',X'01','hash',0)"
      )
      db.execSQL(
          "INSERT INTO training_proposal_operations(owner,proposalId,version,operationId,requestBytes,requestSha256,rejected) VALUES('owner','social',1,'social-op',X'01','hash',0)"
      )
      db.execSQL(
          "INSERT INTO training_proposal_projections(owner,proposalId,version,routineId,calendarPlanId,syncRevision) VALUES('owner','personal',1,'routine','plan',1)"
      )
      db.execSQL(
          "INSERT INTO training_proposal_projections(owner,proposalId,version,routineId,calendarPlanId,syncRevision) VALUES('owner','social',1,'routine-social','plan-social',1)"
      )
      db.execSQL(
          "INSERT INTO coach_relation_operations(owner,operationId,action,route,resource,rawSha256,state) VALUES('owner','op','CREATE_INVITE','POST /v1/coach-relations/invitations','owner','hash','PENDING')"
      )
    }

    val room = openCurrent()
    try {
      val db = room.openHelper.writableDatabase
      db.query("SELECT proposalId FROM training_proposal_drafts ORDER BY proposalId").use {
        assertTrue(it.moveToFirst())
        assertEquals("personal", it.getString(0))
        assertFalse(it.moveToNext())
      }
      db.query(
              "SELECT 1 FROM sqlite_master WHERE type='table' AND name='coach_relation_operations'"
          )
          .use { assertFalse(it.moveToFirst()) }
      db.query("SELECT proposalId FROM training_proposal_operations").use {
        assertTrue(it.moveToFirst())
        assertEquals("personal", it.getString(0))
        assertFalse(it.moveToNext())
      }
      db.query("SELECT proposalId FROM training_proposal_projections").use {
        assertTrue(it.moveToFirst())
        assertEquals("personal", it.getString(0))
        assertFalse(it.moveToNext())
      }
    } finally {
      room.close()
    }
  }

  private fun openCurrent(): GymDatabase =
      Room.databaseBuilder(context, GymDatabase::class.java, name)
          .addMigrations(*GymDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()
}
