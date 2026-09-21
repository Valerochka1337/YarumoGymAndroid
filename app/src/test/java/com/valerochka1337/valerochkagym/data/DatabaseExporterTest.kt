package com.valerochka1337.valerochkagym.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporterImpl
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccent
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentMarkerEntity
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExerciseAccentV2Entity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DatabaseExporterImpl] поверх настоящей файловой Room-базы: копия должна
 * открываться как SQLite и содержать данные, записанные до экспорта (WAL сброшен чекпоинтом).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DatabaseExporterTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var db: GymDatabase
  private lateinit var exporter: DatabaseExporterImpl

  @Before
  fun setUp() {
    // Файловая (не in-memory) база с настоящим именем: экспорт копирует файл gym.db.
    db =
        Room.databaseBuilder(context, GymDatabase::class.java, DatabaseExporter.DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
    exporter = DatabaseExporterImpl(context, db)
  }

  @After
  fun tearDown() {
    db.close()
    context.deleteDatabase(DatabaseExporter.DATABASE_NAME)
  }

  @Test
  fun `export writes an openable sqlite copy with the data written before it`() = runTest {
    db.workoutDao()
        .insertWorkout(
            WorkoutEntity(id = "w1", name = "Грудь", startedAt = 1_000, finishedAt = 2_000),
        )
    val target = File(context.cacheDir, "backup.db")

    val result = exporter.export(Uri.fromFile(target))

    assertEquals(ExportResult.Success, result)
    SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
      copy.rawQuery("SELECT COUNT(*) FROM workouts", null).use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
      }
    }
  }

  @Test
  fun `exported copy retains configured inventory and exercise requirements`() = runTest {
    val exerciseId =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Жим с гантелями",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    isCustom = true,
                    equipmentRequirementState = EquipmentRequirementState.KNOWN,
                ),
            )
    db.exerciseDao().replaceRequirements(exerciseId, setOf("dumbbells", "flat_bench"))
    val gymId = db.gymDao().insertGym(GymEntity(name = "Зал", inventoryConfigured = true))
    db.gymDao().replaceGymEquipment(gymId, setOf("dumbbells", "flat_bench"))
    val target = File(context.cacheDir, "backup-equipment.db")

    assertEquals(ExportResult.Success, exporter.export(Uri.fromFile(target)))
    SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
      copy.rawQuery("SELECT COUNT(*) FROM gym_equipment", null).use { cursor ->
        cursor.moveToFirst()
        assertEquals(2, cursor.getInt(0))
      }
      copy.rawQuery("SELECT COUNT(*) FROM exercise_equipment", null).use { cursor ->
        cursor.moveToFirst()
        assertEquals(2, cursor.getInt(0))
      }
      copy
          .rawQuery("SELECT inventoryConfigured FROM gyms WHERE id = ?", arrayOf(gymId.toString()))
          .use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
          }
      copy
          .rawQuery(
              "SELECT equipmentRequirementState FROM exercises WHERE id = ?",
              arrayOf(exerciseId.toString()),
          )
          .use { cursor ->
            cursor.moveToFirst()
            assertEquals("KNOWN", cursor.getString(0))
          }
    }
  }

  @Test
  fun `exported copy retains empty and populated v2 planner accent aggregates`() = runTest {
    db.plannerExerciseAccentV2Dao().upsertMarker(PlannerExerciseAccentMarkerEntity("empty"))
    db.plannerExerciseAccentV2Dao().upsertMarker(PlannerExerciseAccentMarkerEntity("owner"))
    db.plannerExerciseAccentV2Dao()
        .upsertRows(
            listOf(
                PlannerExerciseAccentV2Entity(
                    "owner",
                    "11111111-1111-1111-1111-111111111111",
                    PlannerExerciseAccent.NORMAL,
                )
            )
        )
    val target = File(context.cacheDir, "backup-accents.db")

    assertEquals(ExportResult.Success, exporter.export(Uri.fromFile(target)))
    SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
      copy.rawQuery("SELECT COUNT(*) FROM planner_exercise_accent_markers", null).use {
        it.moveToFirst()
        assertEquals(2, it.getInt(0))
      }
      copy
          .rawQuery("SELECT preference FROM planner_exercise_accents_v2 WHERE scope='owner'", null)
          .use {
            it.moveToFirst()
            assertEquals("NORMAL", it.getString(0))
          }
    }
  }

  @Test
  fun `an unwritable target reports a failure instead of throwing`() = runTest {
    val target = Uri.fromFile(File("/nonexistent-dir/backup.db"))

    val result = exporter.export(target)

    assertTrue(result is ExportResult.Failure)
  }

  @Test
  fun `suggested file name carries the date`() {
    assertEquals(
        "valerochka-gym-backup-20260802.db",
        DatabaseExporter.suggestedFileName("20260802"),
    )
  }
}
