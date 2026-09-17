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
class Migration30To31Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @Test
  fun `migration adds nullable RIR without deriving it from existing results`() {
    val name = "autoregulation-30-31.db"
    helper.createDatabase(name, 30).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Workout',1,'','PENDING',0)"
      )
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview) VALUES(1,'Press','CHEST','STRENGTH',1,'exercise',0,0)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
      )
      db.execSQL(
          "INSERT INTO strength_planner_profiles(scope,syncId,updatedAt) VALUES('owner','profile',42)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(workoutExerciseId,setIndex,weightKg,reps,isCompleted,syncId) VALUES(1,0,100,8,1,'set')"
      )
    }
    helper
        .runMigrationsAndValidate(
            name,
            32,
            true,
            GymDatabase.MIGRATION_30_31,
            GymDatabase.MIGRATION_31_32,
        )
        .use { db ->
          db.query("SELECT actualRirAtLeastFour FROM workout_sets WHERE syncId='set'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
          }
          db.query("SELECT updatedAt FROM strength_planner_profiles WHERE scope='owner'").use {
            assertTrue(it.moveToFirst())
            assertEquals(42L, it.getLong(0))
          }
          db.query("SELECT weightKg,reps,targetRir,actualRir FROM workout_sets WHERE syncId='set'")
              .use {
                assertTrue(it.moveToFirst())
                assertEquals(100.0, it.getDouble(0), 0.0)
                assertEquals(8, it.getInt(1))
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
              }
        }
  }

  @Test
  fun `migration accepts local v30 autoregulation schema and retains recorded RIR`() {
    val name = "autoregulation-local-v30.db"
    helper.createDatabase(name, 30).use { db ->
      db.execSQL("DROP TABLE strength_planner_key_exercises")
      db.execSQL("DROP TABLE strength_planner_profiles")
      db.execSQL("DROP TABLE workout_efforts")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN targetRir INTEGER")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN actualRir INTEGER")
      db.execSQL(
          "ALTER TABLE coach_session_context ADD COLUMN autoregulationOptionsJson TEXT NOT NULL DEFAULT '{}'"
      )
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Workout',1,'','PENDING',0)"
      )
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview) VALUES(1,'Press','CHEST','STRENGTH',1,'exercise',0,0)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(workoutExerciseId,setIndex,weightKg,reps,isCompleted,syncId,targetRir,actualRir) VALUES(1,0,100,8,1,'set',3,1)"
      )
    }
    helper
        .runMigrationsAndValidate(
            name,
            32,
            true,
            GymDatabase.MIGRATION_30_31,
            GymDatabase.MIGRATION_31_32,
        )
        .use { db ->
          db.query("SELECT actualRirAtLeastFour FROM workout_sets WHERE syncId='set'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
          }
          db.query("SELECT targetRir,actualRir FROM workout_sets WHERE syncId='set'").use {
            assertTrue(it.moveToFirst())
            assertEquals(3, it.getInt(0))
            assertEquals(1, it.getInt(1))
          }
          db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
  }
}
