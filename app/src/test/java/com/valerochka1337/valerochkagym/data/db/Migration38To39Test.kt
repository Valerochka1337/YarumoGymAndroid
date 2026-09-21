package com.valerochka1337.valerochkagym.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration38To39Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration preserves every legacy preparation value and null exactly`() {
    val name = "preparation-38-39.db"
    helper.createDatabase(name, 38).use { db ->
      db.execSQL(
          "INSERT INTO workout_preparations(owner,requestId,intentJson,replacesJson,requestJson,revision,catalogRevision,generation,state,errorCode,proposalJson) VALUES('owner','request',' { \"intent\" : 1 } ',' [ \"old\" ] ','  { \"raw\" : true }  ',7,8,9,'READY',NULL,NULL)"
      )
      db.execSQL(
          "INSERT INTO workout_preparations(owner,requestId,intentJson,replacesJson,requestJson,revision,catalogRevision,generation,state,errorCode,proposalJson) VALUES('other','second','{}','[]',NULL,NULL,NULL,NULL,'FAILED','ai_error',' { \"proposal\" : true } ')"
      )
    }
    helper.runMigrationsAndValidate(name, 39, true, GymDatabase.MIGRATION_38_39).use { db ->
      db.query("SELECT * FROM workout_preparations WHERE owner='owner' AND requestId='request'")
          .use { row ->
            assertEquals(true, row.moveToFirst())
            assertEquals(
                " { \"intent\" : 1 } ",
                row.getString(row.getColumnIndexOrThrow("intentJson")),
            )
            assertEquals(" [ \"old\" ] ", row.getString(row.getColumnIndexOrThrow("replacesJson")))
            assertEquals(
                "  { \"raw\" : true }  ",
                row.getString(row.getColumnIndexOrThrow("requestJson")),
            )
            assertEquals(7L, row.getLong(row.getColumnIndexOrThrow("revision")))
            assertEquals(8L, row.getLong(row.getColumnIndexOrThrow("catalogRevision")))
            assertEquals(9L, row.getLong(row.getColumnIndexOrThrow("generation")))
            assertEquals("READY", row.getString(row.getColumnIndexOrThrow("state")))
            assertEquals(0L, row.getLong(row.getColumnIndexOrThrow("createdAtMillis")))
            assertFalse(row.isNull(row.getColumnIndexOrThrow("requestJson")))
            assertEquals(true, row.isNull(row.getColumnIndexOrThrow("errorCode")))
            assertEquals(true, row.isNull(row.getColumnIndexOrThrow("proposalJson")))
          }
      db.query("SELECT * FROM workout_preparations WHERE owner='other'").use { row ->
        assertEquals(true, row.moveToFirst())
        assertEquals("other", row.getString(row.getColumnIndexOrThrow("owner")))
        assertEquals("second", row.getString(row.getColumnIndexOrThrow("requestId")))
        assertEquals("{}", row.getString(row.getColumnIndexOrThrow("intentJson")))
        assertEquals("[]", row.getString(row.getColumnIndexOrThrow("replacesJson")))
        listOf("requestJson", "revision", "catalogRevision", "generation").forEach { column ->
          assertEquals(true, row.isNull(row.getColumnIndexOrThrow(column)))
        }
        assertEquals("FAILED", row.getString(row.getColumnIndexOrThrow("state")))
        assertEquals("ai_error", row.getString(row.getColumnIndexOrThrow("errorCode")))
        assertEquals(
            " { \"proposal\" : true } ",
            row.getString(row.getColumnIndexOrThrow("proposalJson")),
        )
        assertEquals(0L, row.getLong(row.getColumnIndexOrThrow("createdAtMillis")))
      }
    }
  }
}
