package com.valerochka1337.valerochkagym.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valerochka1337.valerochkagym.data.backend.GuestMergeDao
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.CalendarEventAccountLinkDao
import com.valerochka1337.valerochkagym.data.db.dao.CalendarPlanDao
import com.valerochka1337.valerochkagym.data.db.dao.CoachDao
import com.valerochka1337.valerochkagym.data.db.dao.ConfigurationTombstoneDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.ExercisePersonalHintDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthAiConsentDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncDao
import com.valerochka1337.valerochkagym.data.db.dao.MuscleLoadUpgradeNoticeDao
import com.valerochka1337.valerochkagym.data.db.dao.ProfileDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarGoogleLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationMetadataEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachCommandReceiptEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSyncStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEquipmentEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExercisePersonalHintEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEquipmentEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentIntentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthHeadHistoryEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthLogicalRecordEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthMetricIdentityEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRecordVersionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncBaselineEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStagingEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoadUpgradeNoticeEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEquipmentPreferenceEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineGymEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutGymEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.entity.builtInExerciseSyncId
import com.valerochka1337.valerochkagym.data.db.entity.migratedCustomExerciseSyncId
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Database(
    entities =
        [
            com.valerochka1337.valerochkagym.data.backend.BackendStateEntity::class,
            com.valerochka1337.valerochkagym.data.backend.BackendBaselineEntity::class,
            com.valerochka1337.valerochkagym.data.backend.BackendOutboxEntity::class,
            com.valerochka1337.valerochkagym.data.backend.BackendRejectedOperationEntity::class,
            com.valerochka1337.valerochkagym.data.backend.BackendConflictCopyEntity::class,
            com.valerochka1337.valerochkagym.data.backend.CatalogStateEntity::class,
            com.valerochka1337.valerochkagym.data.backend.CatalogRecordEntity::class,
            com.valerochka1337.valerochkagym.data.backend.CatalogEquipmentEntity::class,
            com.valerochka1337.valerochkagym.data.ai.PreparationEntity::class,
            BodyMeasurementEntity::class,
            CalendarEventAccountLinkEntity::class,
            CalendarPlanEntity::class,
            CalendarRuleEntity::class,
            CalendarExceptionEntity::class,
            CalendarGoogleLinkEntity::class,
            CalendarMigrationStateEntity::class,
            CalendarMigrationMetadataEntity::class,
            ConfigurationTombstoneEntity::class,
            ExerciseEntity::class,
            ExercisePersonalHintEntity::class,
            ExerciseEquipmentEntity::class,
            ExerciseMuscleEntity::class,
            MuscleLoadUpgradeNoticeEntity::class,
            ProfileEntity::class,
            ProfileEquipmentPreferenceEntity::class,
            GymEntity::class,
            GymEquipmentEntity::class,
            HealthAiConsentStateEntity::class,
            HealthAiConsentOutboxEntity::class,
            HealthAiConsentIntentEntity::class,
            TrainingProposalDraftEntity::class,
            TrainingProposalOperationEntity::class,
            TrainingProposalProjectionEntity::class,
            HealthLogicalRecordEntity::class,
            HealthRecordVersionEntity::class,
            HealthHeadHistoryEntity::class,
            HealthMetricIdentityEntity::class,
            HealthSyncBaselineEntity::class,
            HealthSyncStateEntity::class,
            HealthSyncOutboxEntity::class,
            HealthSyncStagingEntity::class,
            GymExerciseEntity::class,
            RoutineEntity::class,
            RoutineExerciseEntity::class,
            RoutineGymEntity::class,
            ScheduledWorkoutEntity::class,
            WorkoutEntity::class,
            WorkoutExerciseEntity::class,
            WorkoutGymEntity::class,
            WorkoutSetEntity::class,
            CoachMessageEntity::class,
            CoachProposalEntity::class,
            CoachCommandReceiptEntity::class,
            CoachJournalEntity::class,
            CoachSessionContextEntity::class,
            CoachSyncStateEntity::class,
        ],
    version = 29,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GymDatabase : RoomDatabase() {
  abstract fun preparationDao(): com.valerochka1337.valerochkagym.data.ai.PreparationDao

  abstract fun bodyMeasurementDao(): BodyMeasurementDao

  abstract fun calendarEventAccountLinkDao(): CalendarEventAccountLinkDao

  abstract fun calendarPlanDao(): CalendarPlanDao

  abstract fun configurationTombstoneDao(): ConfigurationTombstoneDao

  abstract fun exerciseDao(): ExerciseDao

  abstract fun exercisePersonalHintDao(): ExercisePersonalHintDao

  abstract fun exerciseMuscleDao(): ExerciseMuscleDao

  abstract fun muscleLoadUpgradeNoticeDao(): MuscleLoadUpgradeNoticeDao

  abstract fun profileDao(): ProfileDao

  abstract fun gymDao(): GymDao

  abstract fun healthAiConsentDao(): HealthAiConsentDao

  abstract fun trainingProposalDao(): TrainingProposalDao

  abstract fun healthDao(): HealthDao

  abstract fun healthSyncDao(): HealthSyncDao

  abstract fun guestMergeDao(): GuestMergeDao

  abstract fun routineDao(): RoutineDao

  abstract fun workoutDao(): WorkoutDao

  abstract fun coachDao(): CoachDao

  abstract fun scheduledWorkoutDao(): ScheduledWorkoutDao

  companion object {
    /** v1 → v2: у подходов появляется момент отметки (nullable, старые строки → NULL). */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN completedAt INTEGER")
          }
        }

    /**
     * v2 → v3: появляется карта вовлечения мышц `exercise_muscles` ([ExerciseMuscleEntity]).
     * Таблица создаётся пустой — заполняет её ExerciseMuscleSeeder при открытии базы (по каталогу
     * для встроенных упражнений, по группе мышц для своих и импортированных), так что миграция не
     * тащит на себе каталог и остаётся чистым DDL.
     *
     * DDL повторяет то, что генерирует Room для v3 (см. `schemas/3.json`) — при расхождении Room
     * упадёт на проверке схемы при открытии; это ловит `Migration2To3Test`.
     */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_muscles` (" +
                    "`exerciseId` INTEGER NOT NULL, `muscle` TEXT NOT NULL, " +
                    "`contribution` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`exerciseId`, `muscle`), " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_exercise_muscles_exerciseId` " +
                    "ON `exercise_muscles` (`exerciseId`)",
            )
          }
        }

    /**
     * v3 → v4: отдельная таблица замеров тела. Пустые показатели хранятся как NULL, а не как нули:
     * это сохраняет честные разрывы в трендах и при экспорте в Sheets.
     *
     * DDL повторяет `schemas/.../4.json`; миграционный тест открывает получившуюся базу через Room
     * и тем самым проверяет типы, первичный ключ и оба индекса.
     */
    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `body_measurements` (" +
                    "`id` TEXT NOT NULL, `measuredAt` INTEGER NOT NULL, " +
                    "`weightKg` REAL, `skeletalMuscleMassKg` REAL, " +
                    "`bodyFatPercentage` REAL, `visceralFatLevel` INTEGER, " +
                    "`waistHipRatio` REAL, `waistCm` REAL, `chestCm` REAL, " +
                    "`hipsCm` REAL, `rightRelaxedArmCm` REAL, `rightThighCm` REAL, " +
                    "`uploadStatus` TEXT NOT NULL, `uploadError` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_body_measurements_measuredAt` " +
                    "ON `body_measurements` (`measuredAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_body_measurements_uploadStatus` " +
                    "ON `body_measurements` (`uploadStatus`)",
            )
          }
        }

    /**
     * v4 → v5: полный отчёт InBody. Все дополнительные значения nullable: старые ручные замеры
     * остаются валидными, а отсутствующая строка отчёта не маскируется нулём.
     */
    val MIGRATION_4_5: Migration =
        object : Migration(4, 5) {
          override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                    "ALTER TABLE body_measurements ADD COLUMN bodyFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN inBodyScore INTEGER",
                    "ALTER TABLE body_measurements ADD COLUMN totalBodyWaterLiters REAL",
                    "ALTER TABLE body_measurements ADD COLUMN proteinKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN mineralsKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN bodyMassIndex REAL",
                    "ALTER TABLE body_measurements ADD COLUMN fatFreeMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN basalMetabolicRateKcal INTEGER",
                    "ALTER TABLE body_measurements ADD COLUMN recommendedCalorieIntakeKcal INTEGER",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegFatPercentage REAL",
                )
                .forEach(db::execSQL)
          }
        }

    /**
     * v5 → v6: встроенные карты мышц переходят с локальной на общую шкалу нагрузки. Удаляем только
     * разметку стандартных упражнений: [GymDatabaseCallback] заполнит её заново из
     * [seedExerciseMuscles] при первом открытии. Свои упражнения и их ручная разметка сохраняются.
     */
    val MIGRATION_5_6: Migration =
        object : Migration(5, 6) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM exercise_muscles WHERE exerciseId IN " +
                    "(SELECT id FROM exercises WHERE isCustom = 0)",
            )
          }
        }

    /**
     * v6 → v7: у программ появляется независимый от локального ID ключ синхронизации и монотонная
     * версия снимка. UUID генерируются один раз именно в миграции, поэтому уже созданные программы
     * не меняют свою cloud-идентичность при следующем открытии базы.
     */
    val MIGRATION_6_7: Migration =
        object : Migration(6, 7) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE routines ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE routines ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            val migratedAt = System.currentTimeMillis()
            db.query("SELECT id FROM routines").use { cursor ->
              val idColumn = cursor.getColumnIndexOrThrow("id")
              while (cursor.moveToNext()) {
                db.execSQL(
                    "UPDATE routines SET syncId = ?, updatedAt = ? WHERE id = ?",
                    arrayOf<Any?>(
                        UUID.randomUUID().toString(),
                        migratedAt,
                        cursor.getLong(idColumn),
                    ),
                )
              }
            }
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_routines_syncId ON routines (syncId)",
            )
          }
        }

    /**
     * v7 → v8: упражнения получают переносимую cloud-идентичность, а конфигурации залов —
     * нормализованные таблицы многие-ко-многим. У встроенного каталога UUID зависит только от
     * канонического имени и совпадает со fresh seed; у старых custom-записей дополнительно
     * участвует local ID, чтобы одноимённые пользовательские упражнения не схлопнулись.
     */
    val MIGRATION_7_8: Migration =
        object : Migration(7, 8) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE exercises ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE exercises ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            val migratedAt = System.currentTimeMillis()
            db.query("SELECT id, name, isCustom FROM exercises").use { cursor ->
              val idColumn = cursor.getColumnIndexOrThrow("id")
              val nameColumn = cursor.getColumnIndexOrThrow("name")
              val customColumn = cursor.getColumnIndexOrThrow("isCustom")
              while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val isCustom = cursor.getInt(customColumn) != 0
                val syncId =
                    if (!isCustom) {
                      builtInExerciseSyncId(name)
                    } else {
                      migratedCustomExerciseSyncId(id, name)
                    }
                val updatedAt = if (isCustom) migratedAt else 1L
                db.execSQL(
                    "UPDATE exercises SET syncId = ?, updatedAt = ? WHERE id = ?",
                    arrayOf<Any?>(syncId, updatedAt, id),
                )
              }
            }
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_exercises_syncId ON exercises (syncId)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `gyms` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `name` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_gyms_syncId` ON `gyms` (`syncId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `gym_exercises` (" +
                    "`gymId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`gymId`, `exerciseId`), " +
                    "FOREIGN KEY(`gymId`) REFERENCES `gyms`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_gym_exercises_exerciseId` " +
                    "ON `gym_exercises` (`exerciseId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `routine_gyms` (" +
                    "`routineId` INTEGER NOT NULL, `gymId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`routineId`, `gymId`), " +
                    "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`gymId`) REFERENCES `gyms`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE NO ACTION)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_routine_gyms_gymId` ON `routine_gyms` (`gymId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `workout_gyms` (" +
                    "`workoutId` TEXT NOT NULL, `gymId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`workoutId`, `gymId`), " +
                    "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`gymId`) REFERENCES `gyms`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workout_gyms_gymId` ON `workout_gyms` (`gymId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `configuration_tombstones` (" +
                    "`kind` TEXT NOT NULL, `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`kind`, `syncId`))",
            )
          }
        }

    /** v8 → v9: retained shipping migration for devices upgrading through v10. */
    val MIGRATION_8_9: Migration =
        object : Migration(8, 9) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_variants` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `syncId` TEXT NOT NULL, " +
                    "`exerciseId` INTEGER NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, " +
                    "`isArchived` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_variants_syncId` ON `exercise_variants` (`syncId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_variants_exerciseId_syncId` ON `exercise_variants` (`exerciseId`, `syncId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_variants_exerciseId_normalizedName` ON `exercise_variants` (`exerciseId`, `normalizedName`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_exercise_variants_exerciseId_isArchived` ON `exercise_variants` (`exerciseId`, `isArchived`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `routine_exercises_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                    "`exerciseId` INTEGER NOT NULL, `variantSyncId` TEXT, `position` INTEGER NOT NULL, " +
                    "`restSeconds` INTEGER, `plannedSetsJson` TEXT NOT NULL, " +
                    "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                    "FOREIGN KEY(`exerciseId`, `variantSyncId`) REFERENCES `exercise_variants`(`exerciseId`, `syncId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            )
            db.execSQL(
                "INSERT INTO `routine_exercises_new` (`id`,`routineId`,`exerciseId`,`variantSyncId`,`position`,`restSeconds`,`plannedSetsJson`) " +
                    "SELECT `id`,`routineId`,`exerciseId`,NULL,`position`,`restSeconds`,`plannedSetsJson` FROM `routine_exercises`",
            )
            db.execSQL("DROP TABLE `routine_exercises`")
            db.execSQL("ALTER TABLE `routine_exercises_new` RENAME TO `routine_exercises`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_routine_exercises_exerciseId_variantSyncId` ON `routine_exercises` (`exerciseId`, `variantSyncId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `workout_exercises_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                    "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `variantSyncId` TEXT, " +
                    "`variantNameSnapshot` TEXT, `position` INTEGER NOT NULL, CHECK(length(trim(`sectionId`)) > 0), " +
                    "CHECK((`variantSyncId` IS NULL AND `variantNameSnapshot` IS NULL) OR " +
                    "(`variantSyncId` IS NOT NULL AND length(trim(`variantNameSnapshot`)) > 0)), " +
                    "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            )
            db.execSQL("CREATE TABLE `workout_sets_v8_backup` AS SELECT * FROM `workout_sets`")
            db.query("SELECT `id`, `workoutId`, `exerciseId`, `position` FROM `workout_exercises`")
                .use { cursor ->
                  while (cursor.moveToNext()) {
                    db.execSQL(
                        "INSERT INTO `workout_exercises_new` (`id`,`workoutId`,`exerciseId`,`sectionId`,`variantSyncId`,`variantNameSnapshot`,`position`) VALUES (?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getLong(2),
                            UUID.randomUUID().toString(),
                            null,
                            null,
                            cursor.getInt(3),
                        ),
                    )
                  }
                }
            db.execSQL("DROP TABLE `workout_exercises`")
            db.execSQL("ALTER TABLE `workout_exercises_new` RENAME TO `workout_exercises`")
            db.execSQL("DELETE FROM `workout_sets`")
            db.execSQL("INSERT INTO `workout_sets` SELECT * FROM `workout_sets_v8_backup`")
            db.execSQL("DROP TABLE `workout_sets_v8_backup`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)"
            )
          }
        }

    /** v9 → v10: discard variant metadata while preserving every base row and workout set. */
    val MIGRATION_9_10: Migration =
        object : Migration(9, 10) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
              db.execSQL(
                  "CREATE TABLE `routine_exercises_new` (" +
                      "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                      "`exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `restSeconds` INTEGER, " +
                      "`plannedSetsJson` TEXT NOT NULL, " +
                      "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                      "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
              )
              db.execSQL(
                  "INSERT INTO `routine_exercises_new` (`id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson`) " +
                      "SELECT `id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson` FROM `routine_exercises`",
              )
              db.execSQL("DROP TABLE `routine_exercises`")
              db.execSQL("ALTER TABLE `routine_exercises_new` RENAME TO `routine_exercises`")
              db.execSQL(
                  "CREATE INDEX `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)"
              )
              db.execSQL(
                  "CREATE INDEX `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)"
              )

              db.execSQL("CREATE TABLE `workout_sets_v9_backup` AS SELECT * FROM `workout_sets`")
              db.execSQL(
                  "CREATE TABLE `workout_exercises_new` (" +
                      "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                      "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                      "CHECK(length(trim(`sectionId`)) > 0), " +
                      "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                      "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
              )
              db.execSQL(
                  "INSERT INTO `workout_exercises_new` (`id`,`workoutId`,`exerciseId`,`sectionId`,`position`) " +
                      "SELECT `id`,`workoutId`,`exerciseId`,`sectionId`,`position` FROM `workout_exercises`",
              )
              db.execSQL("DROP TABLE `workout_exercises`")
              db.execSQL("ALTER TABLE `workout_exercises_new` RENAME TO `workout_exercises`")
              db.execSQL("DELETE FROM `workout_sets`")
              db.execSQL("INSERT INTO `workout_sets` SELECT * FROM `workout_sets_v9_backup`")
              db.execSQL("DROP TABLE `workout_sets_v9_backup`")
              db.execSQL(
                  "CREATE INDEX `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)"
              )
              db.execSQL(
                  "CREATE INDEX `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)"
              )
              db.execSQL(
                  "CREATE UNIQUE INDEX `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)"
              )
              db.execSQL("DROP TABLE `exercise_variants`")
              db.setTransactionSuccessful()
            } finally {
              db.endTransaction()
            }
          }
        }

    /** v10 → v12: v10 уже имеет целевую base-only схему. */
    val MIGRATION_10_12: Migration =
        object : Migration(10, 12) {
          override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

    /**
     * v11 → v12: recovery для выпущенной до v10 вариации, которая успела попасть на устройства.
     * Сначала удаляем дочерние мышцы, затем перестраиваем таблицы, сохраняем подходы через backup и
     * только после этого удаляем варианты.
     */
    val MIGRATION_11_12: Migration =
        object : Migration(11, 12) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
              db.execSQL("DROP TABLE `exercise_variant_muscles`")

              db.execSQL(
                  "CREATE TABLE `routine_exercises_new` (" +
                      "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                      "`exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `restSeconds` INTEGER, " +
                      "`plannedSetsJson` TEXT NOT NULL, " +
                      "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                      "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
              )
              db.execSQL(
                  "INSERT INTO `routine_exercises_new` (`id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson`) " +
                      "SELECT `id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson` FROM `routine_exercises`",
              )
              db.execSQL("DROP TABLE `routine_exercises`")
              db.execSQL("ALTER TABLE `routine_exercises_new` RENAME TO `routine_exercises`")
              db.execSQL(
                  "CREATE INDEX `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)"
              )
              db.execSQL(
                  "CREATE INDEX `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)"
              )

              db.execSQL("CREATE TABLE `workout_sets_v11_backup` AS SELECT * FROM `workout_sets`")
              db.execSQL(
                  "CREATE TABLE `workout_exercises_new` (" +
                      "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                      "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                      "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                      "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
              )
              db.execSQL(
                  "INSERT INTO `workout_exercises_new` (`id`,`workoutId`,`exerciseId`,`sectionId`,`position`) " +
                      "SELECT `id`,`workoutId`,`exerciseId`,`sectionId`,`position` FROM `workout_exercises`",
              )
              db.execSQL("DROP TABLE `workout_exercises`")
              db.execSQL("ALTER TABLE `workout_exercises_new` RENAME TO `workout_exercises`")
              db.execSQL("DELETE FROM `workout_sets`")
              db.execSQL(
                  "INSERT INTO `workout_sets` (`id`,`workoutExerciseId`,`setIndex`,`weightKg`,`reps`," +
                      "`durationSec`,`speedKmh`,`inclinePct`,`isCompleted`,`completedAt`) " +
                      "SELECT `id`,`workoutExerciseId`,`setIndex`,`weightKg`,`reps`,`durationSec`," +
                      "`speedKmh`,`inclinePct`,`isCompleted`,`completedAt` FROM `workout_sets_v11_backup`",
              )
              db.execSQL("DROP TABLE `workout_sets_v11_backup`")
              db.execSQL(
                  "CREATE INDEX `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)"
              )
              db.execSQL(
                  "CREATE INDEX `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)"
              )
              db.execSQL(
                  "CREATE UNIQUE INDEX `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)"
              )

              db.execSQL("DROP TABLE `exercise_variants`")
              db.setTransactionSuccessful()
            } finally {
              db.endTransaction()
            }
          }
        }

    /**
     * v12 → v13 changes percentage-like load values into the durable role encoding. A legacy zero
     * meant "not involved", so only this migration removes zero rows. From v13 onward an explicit
     * zero is a stabilizer and must remain distinct from absence.
     */
    val MIGRATION_12_13: Migration =
        object : Migration(12, 13) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
              // Recovery fixtures (and interrupted vendor restores) can carry the current
              // column while their user_version is still older. The normal v12 schema does
              // not, but guarding this DDL keeps the handwritten migration reopen-safe.
              val hasReviewColumn =
                  db.query("PRAGMA table_info(exercises)").use { columns ->
                    var found = false
                    while (columns.moveToNext()) if (columns.getString(1) == "needsMuscleMapReview")
                        found = true
                    found
                  }
              if (!hasReviewColumn) {
                db.execSQL(
                    "ALTER TABLE exercises ADD COLUMN needsMuscleMapReview INTEGER NOT NULL DEFAULT 0"
                )
              }
              db.execSQL(
                  "CREATE TABLE IF NOT EXISTS `muscle_load_upgrade_notice` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))"
              )
              db.execSQL("INSERT OR IGNORE INTO muscle_load_upgrade_notice(id) VALUES(1)")
              db.execSQL("DELETE FROM exercise_muscles WHERE contribution = 0")
              db.execSQL(
                  "UPDATE exercise_muscles SET contribution = CASE " +
                      "WHEN contribution >= 60 THEN 100 " +
                      "WHEN contribution >= 25 THEN 50 ELSE 0 END",
              )
              // Keep the existing local row identity by moving old CHEST to upper chest.
              db.execSQL(
                  "UPDATE exercise_muscles SET muscle = 'UPPER_CHEST' WHERE muscle = 'CHEST'"
              )
              // CHEST was approximate. Only custom maps are duplicated and flagged for review.
              db.execSQL(
                  "INSERT INTO exercise_muscles(exerciseId, muscle, contribution) " +
                      "SELECT m.exerciseId, 'LOWER_CHEST', m.contribution " +
                      "FROM exercise_muscles m JOIN exercises e ON e.id = m.exerciseId " +
                      "WHERE m.muscle = 'UPPER_CHEST' AND e.isCustom = 1",
              )
              db.execSQL(
                  "UPDATE exercises SET needsMuscleMapReview = 1 WHERE isCustom = 1 AND id IN " +
                      "(SELECT exerciseId FROM exercise_muscles WHERE muscle IN ('UPPER_CHEST','LOWER_CHEST') " +
                      "GROUP BY exerciseId HAVING COUNT(*) = 2)",
              )
              db.setTransactionSuccessful()
            } finally {
              db.endTransaction()
            }
          }
        }

    /** v13 → v14 adds explicit inventory and the three-state exercise requirement model. */
    val MIGRATION_13_14: Migration =
        object : Migration(13, 14) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
              db.execSQL(
                  "ALTER TABLE gyms ADD COLUMN inventoryConfigured INTEGER NOT NULL DEFAULT 0"
              )
              db.execSQL(
                  "ALTER TABLE exercises ADD COLUMN equipmentRequirementState TEXT NOT NULL DEFAULT 'UNKNOWN'",
              )
              db.execSQL(
                  "CREATE TABLE IF NOT EXISTS gym_equipment (gymId INTEGER NOT NULL, equipmentId TEXT NOT NULL, PRIMARY KEY(gymId, equipmentId), FOREIGN KEY(gymId) REFERENCES gyms(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
              )
              db.execSQL(
                  "CREATE INDEX IF NOT EXISTS index_gym_equipment_gymId ON gym_equipment(gymId)"
              )
              db.execSQL(
                  "CREATE TABLE IF NOT EXISTS exercise_equipment (exerciseId INTEGER NOT NULL, equipmentId TEXT NOT NULL, PRIMARY KEY(exerciseId, equipmentId), FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
              )
              db.execSQL(
                  "CREATE INDEX IF NOT EXISTS index_exercise_equipment_exerciseId ON exercise_equipment(exerciseId)"
              )
              db.setTransactionSuccessful()
            } finally {
              db.endTransaction()
            }
          }
        }

    val MIGRATION_15_16: Migration =
        object : Migration(15, 16) {
          override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf("exercises", "gyms", "routines")) {
              db.execSQL("ALTER TABLE $table ADD COLUMN origin TEXT NOT NULL DEFAULT 'PERSONAL'")
              db.execSQL("ALTER TABLE $table ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
            com.valerochka1337.valerochkagym.data.backend.CatalogSchema.create(db)
          }
        }

    val MIGRATION_16_17: Migration =
        object : Migration(16, 17) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_event_account_links` (" +
                    "`scheduledWorkoutId` INTEGER NOT NULL, `ownerEmail` TEXT, " +
                    "`state` TEXT NOT NULL, PRIMARY KEY(`scheduledWorkoutId`), " +
                    "FOREIGN KEY(`scheduledWorkoutId`) REFERENCES `scheduled_workouts`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "INSERT INTO calendar_event_account_links(scheduledWorkoutId,ownerEmail,state) " +
                    "SELECT id,NULL,'LEGACY_OWNER_UNKNOWN' FROM scheduled_workouts"
            )
            CalendarEventAccountLinkSchema.install(db)
          }
        }

    val MIGRATION_17_18: Migration =
        object : Migration(17, 18) {
          override fun migrate(db: SupportSQLiteDatabase) {
            if (hasColumn(db, "workouts", "coachRevision"))
                restoreCalendarEventAccountLinksIfMissing(db)
            db.execSQL("ALTER TABLE backend_state ADD COLUMN phase TEXT NOT NULL DEFAULT 'GUEST'")
            db.execSQL("ALTER TABLE backend_state ADD COLUMN mergeId TEXT")
            db.execSQL(
                "ALTER TABLE backend_state ADD COLUMN initialMergeAcknowledged INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE backend_state SET phase=CASE WHEN owner IS NULL THEN 'GUEST' ELSE 'OWNED' END, initialMergeAcknowledged=CASE WHEN owner IS NULL THEN 0 ELSE 1 END"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS backend_conflict_copies (mergeId TEXT NOT NULL,kind TEXT NOT NULL,originalSyncId TEXT NOT NULL,remoteRevision INTEGER NOT NULL,localPayloadFingerprint TEXT NOT NULL,localCopySyncId TEXT NOT NULL,PRIMARY KEY(mergeId,kind,originalSyncId,remoteRevision,localPayloadFingerprint))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_backend_conflict_copies_localCopySyncId ON backend_conflict_copies(localCopySyncId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS backend_rejected_operations (operationId TEXT NOT NULL,owner TEXT NOT NULL,PRIMARY KEY(operationId))"
            )
          }
        }

    /** CAL-01 keeps legacy sources and introduces Room-owned portable calendar aggregates. */
    val MIGRATION_18_19: Migration =
        object : Migration(18, 19) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_plans` (`id` TEXT NOT NULL, `routineId` INTEGER NOT NULL, `startsAtMillis` INTEGER NOT NULL, `timeZoneId` TEXT NOT NULL, `legacyScheduleId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_calendar_plans_routineId` ON `calendar_plans` (`routineId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_plans_legacyScheduleId` ON `calendar_plans` (`legacyScheduleId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_rules` (`id` TEXT NOT NULL, `routineId` INTEGER NOT NULL, `isoDay` INTEGER NOT NULL, `localTime` TEXT NOT NULL, `timeZoneId` TEXT NOT NULL, `startLocalDate` TEXT NOT NULL, `legacyRuleKey` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_calendar_rules_routineId` ON `calendar_rules` (`routineId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_rules_legacyRuleKey` ON `calendar_rules` (`legacyRuleKey`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_exceptions` (`id` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `instanceKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `movedAtMillis` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`ruleId`) REFERENCES `calendar_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_calendar_exceptions_ruleId` ON `calendar_exceptions` (`ruleId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_exceptions_ruleId_instanceKey` ON `calendar_exceptions` (`ruleId`, `instanceKey`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_google_links` (`objectKind` TEXT NOT NULL, `objectId` TEXT NOT NULL, `ownerEmail` TEXT, `calendarId` TEXT, `eventId` TEXT, `status` TEXT NOT NULL, `error` TEXT, `remoteRevision` INTEGER, PRIMARY KEY(`objectKind`, `objectId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_migration_state` (`id` INTEGER NOT NULL, `phase` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_migration_metadata` (`id` INTEGER NOT NULL, `sourceFingerprint` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `weeklyStartLocalDate` TEXT NOT NULL, `observedOwners` TEXT NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL("ALTER TABLE backend_state ADD COLUMN capabilityOwner TEXT")
            db.execSQL(
                "ALTER TABLE backend_state ADD COLUMN acceptedCapabilities TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO calendar_migration_state(id,phase) VALUES (1,'PENDING')"
            )
          }
        }

    /** AI-01: owner-scoped durable disclosure receipt and exact mutation outbox. */
    val MIGRATION_19_20: Migration =
        object : Migration(19, 20) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_ai_consent_state` (`owner` TEXT NOT NULL, `revision` INTEGER NOT NULL, `noticeVersion` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `recordedAtEpochMs` INTEGER NOT NULL, `receiptBytes` BLOB NOT NULL, PRIMARY KEY(`owner`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_ai_consent_outbox` (`owner` TEXT NOT NULL, `operationId` TEXT NOT NULL, `requestBytes` BLOB NOT NULL, `requestSha256` TEXT NOT NULL, `dispatched` INTEGER NOT NULL, PRIMARY KEY(`owner`))"
            )
          }
        }

    /** Keeps a later explicit choice while the previous exact request is recovered. */
    val MIGRATION_20_21: Migration =
        object : Migration(20, 21) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_ai_consent_intent` (`owner` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`owner`))"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO health_ai_consent_intent(owner,enabled) SELECT owner, CASE WHEN instr(CAST(requestBytes AS TEXT),'\"enabled\":true') > 0 THEN 1 ELSE 0 END FROM health_ai_consent_outbox"
            )
          }
        }

    /** v21 → v22: factual set notes and portable private exercise hints. */
    val MIGRATION_21_22: Migration =
        object : Migration(21, 22) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE workout_sets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_personal_hints` (`exerciseSyncId` TEXT NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`exerciseSyncId`))"
            )
            // Existing owned databases do not re-run claim(), so install the new generation
            // triggers during the schema upgrade as well.
            com.valerochka1337.valerochkagym.data.backend.SyncSchema.install(db)
          }
        }

    /** v22 → v23: owner-scoped optional profile and its canonical equipment child set. */
    val MIGRATION_22_23: Migration =
        object : Migration(22, 23) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `profiles` (`scope` TEXT NOT NULL, `syncId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `trainingGoal` TEXT, `sex` TEXT, `birthDate` TEXT, `experienceLevel` TEXT, `plannedSessionsPerWeek` INTEGER, `preferredSessionDurationMinutes` INTEGER, `manualConstraints` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`scope`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `profile_equipment` (`scope` TEXT NOT NULL, `equipmentId` TEXT NOT NULL, PRIMARY KEY(`scope`, `equipmentId`), FOREIGN KEY(`scope`) REFERENCES `profiles`(`scope`) ON UPDATE CASCADE ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_profile_equipment_scope` ON `profile_equipment` (`scope`)"
            )
            com.valerochka1337.valerochkagym.data.backend.SyncSchema.install(db)
          }
        }

    /** v23 → v24: isolated immutable manual-health ledger and exact health-only sync journal. */
    val MIGRATION_23_24: Migration =
        object : Migration(23, 24) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_logical_records` (`logicalId` TEXT NOT NULL, `scope` TEXT NOT NULL, `kind` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `currentVersionId` TEXT, `headRevision` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `healthRevision` INTEGER, PRIMARY KEY(`logicalId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_logical_records_scope` ON `health_logical_records` (`scope`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_logical_records_scope_deleted` ON `health_logical_records` (`scope`, `deleted`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_record_versions` (`versionId` TEXT NOT NULL, `logicalId` TEXT NOT NULL, `parentVersionId` TEXT, `kind` TEXT NOT NULL, `state` TEXT NOT NULL, `enteredAtEpochMs` INTEGER NOT NULL, `payloadJson` TEXT, `serverSequence` INTEGER, `healthRevision` INTEGER, PRIMARY KEY(`versionId`), FOREIGN KEY(`logicalId`) REFERENCES `health_logical_records`(`logicalId`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_record_versions_logicalId` ON `health_record_versions` (`logicalId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_record_versions_logicalId_enteredAtEpochMs` ON `health_record_versions` (`logicalId`, `enteredAtEpochMs`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_head_history` (`logicalId` TEXT NOT NULL, `headRevision` INTEGER NOT NULL, `currentVersionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `deleted` INTEGER NOT NULL, `healthRevision` INTEGER NOT NULL, PRIMARY KEY(`logicalId`, `headRevision`), FOREIGN KEY(`logicalId`) REFERENCES `health_logical_records`(`logicalId`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_head_history_healthRevision` ON `health_head_history` (`healthRevision`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_metric_identities` (`id` TEXT NOT NULL, `scope` TEXT NOT NULL, `nameOriginal` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_metric_identities_scope` ON `health_metric_identities` (`scope`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_sync_baseline` (`scope` TEXT NOT NULL, `versionId` TEXT NOT NULL, `versionJson` TEXT NOT NULL, PRIMARY KEY(`scope`, `versionId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_sync_state` (`scope` TEXT NOT NULL, `cursor` TEXT, `needsFullRefresh` INTEGER NOT NULL, `pendingCursor` TEXT, `pendingWatermark` INTEGER, PRIMARY KEY(`scope`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_sync_outbox` (`operationId` TEXT NOT NULL, `scope` TEXT NOT NULL, `requestBytes` BLOB NOT NULL, `requestSha256` TEXT NOT NULL, `dispatched` INTEGER NOT NULL, PRIMARY KEY(`operationId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_sync_outbox_scope` ON `health_sync_outbox` (`scope`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_sync_staging` (`scope` TEXT NOT NULL, `healthRevision` INTEGER NOT NULL, `eventKind` TEXT NOT NULL, `eventId` TEXT NOT NULL, `eventJson` TEXT NOT NULL, PRIMARY KEY(`scope`, `healthRevision`, `eventKind`, `eventId`))"
            )
            com.valerochka1337.valerochkagym.data.backend.SyncSchema.install(db)
          }
        }

    val MIGRATION_26_27: Migration =
        object : Migration(26, 27) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS workout_preparations (owner TEXT NOT NULL, requestId TEXT NOT NULL, intentJson TEXT NOT NULL, replacesJson TEXT NOT NULL, requestJson TEXT, revision INTEGER, catalogRevision INTEGER, generation INTEGER, state TEXT NOT NULL, errorCode TEXT, proposalJson TEXT, PRIMARY KEY(owner))"
            )
          }
        }

    val MIGRATION_25_26: Migration =
        object : Migration(25, 26) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS coach_relation_operations (owner TEXT NOT NULL, operationId TEXT NOT NULL, action TEXT NOT NULL, route TEXT NOT NULL, resource TEXT NOT NULL, rawSha256 TEXT NOT NULL, firstSendBytes BLOB, state TEXT NOT NULL, resultJson TEXT, PRIMARY KEY(owner,operationId))"
            )
          }
        }

    /** v27 → v28: the complete Live Coach schema on top of the released preparation journal. */
    val MIGRATION_27_28: Migration =
        object : Migration(27, 28) {
          override fun migrate(db: SupportSQLiteDatabase) {
            // A pre-rebase draft was labeled v17 and already carried these fields without Room's
            // defaults. Repair only those columns; rebuilding workouts/workout_sets would cascade
            // their children and lose the durable transcript.
            val archivedCoachShape = hasColumn(db, "workouts", "coachRevision")
            if (archivedCoachShape) restoreCalendarEventAccountLinksIfMissing(db)
            repairRequiredDefaultColumn(
                db,
                "workouts",
                "coachRevision",
                "INTEGER NOT NULL DEFAULT 0",
                "0",
            )
            if (hasColumn(db, "workout_sets", "syncId")) {
              db.execSQL("DROP INDEX IF EXISTS index_workout_sets_syncId")
            }
            repairRequiredDefaultColumn(
                db,
                "workout_sets",
                "syncId",
                "TEXT NOT NULL DEFAULT ''",
                "''",
            )
            repairRequiredDefaultColumn(
                db,
                "workout_sets",
                "setType",
                "TEXT NOT NULL DEFAULT 'UNKNOWN'",
                "'UNKNOWN'",
            )
            repairRequiredDefaultColumn(
                db,
                "workout_sets",
                "reportedFeelingsJson",
                "TEXT NOT NULL DEFAULT '[]'",
                "'[]'",
            )
            repairRequiredDefaultColumn(
                db,
                "workout_sets",
                "coachMutationRevision",
                "INTEGER NOT NULL DEFAULT 0",
                "0",
            )
            listOf(
                    "originalWeightKg REAL",
                    "originalReps INTEGER",
                    "originalDurationSec INTEGER",
                    "originalSpeedKmh REAL",
                    "originalInclinePct REAL",
                    "targetWeightKg REAL",
                    "targetReps INTEGER",
                    "targetDurationSec INTEGER",
                    "targetSpeedKmh REAL",
                    "targetInclinePct REAL",
                    "actualWeightKg REAL",
                    "actualReps INTEGER",
                    "actualDurationSec INTEGER",
                    "actualSpeedKmh REAL",
                    "actualInclinePct REAL",
                    "restSnapshotJson TEXT",
                )
                .forEach { definition -> addColumnIfMissing(db, "workout_sets", definition) }
            db.query("SELECT id FROM workout_sets WHERE syncId IS NULL OR trim(syncId) = ''").use {
                rows ->
              val id = rows.getColumnIndexOrThrow("id")
              while (rows.moveToNext()) {
                db.execSQL(
                    "UPDATE workout_sets SET syncId=? WHERE id=? AND (syncId IS NULL OR trim(syncId) = '')",
                    arrayOf<Any>(java.util.UUID.randomUUID().toString(), rows.getLong(id)),
                )
              }
            }
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_workout_sets_syncId ON workout_sets(syncId)"
            )
            LegacyCoachArchiveRegistry.archiveConfirmedDeviceV17(db)
            val markExistingRepliesRead =
                tableExists(db, "coach_messages") && !hasColumn(db, "coach_messages", "readAt")
            createCoachTablesIfMissing(db)
            addColumnIfMissing(db, "coach_messages", "quickRepliesJson TEXT")
            addColumnIfMissing(db, "coach_messages", "readAt INTEGER")
            addColumnIfMissing(db, "coach_proposals", "previewJson TEXT")
            if (markExistingRepliesRead) {
              db.execSQL("UPDATE coach_messages SET readAt=createdAt WHERE role='assistant'")
            }
            repairCoachSessionContext(db)
            sanitizeLegacyUndoPackets(db)
            markRetiredOccupiedEquipmentProposalsStale(db)
          }
        }

    /** v28 → v29: remove the retired inter-account relation queue and its cached proposals. */
    val MIGRATION_28_29: Migration =
        object : Migration(28, 29) {
          override fun migrate(db: SupportSQLiteDatabase) {
            removeSocialProposalJournal(db)
            db.execSQL("DROP TABLE IF EXISTS coach_relation_operations")
          }
        }

    private fun removeSocialProposalJournal(db: SupportSQLiteDatabase) {
      val socialRows = mutableListOf<Triple<String, String, Int>>()
      db.query("SELECT owner,proposalId,version,proposalJson FROM training_proposal_drafts").use {
          rows ->
        val owner = rows.getColumnIndexOrThrow("owner")
        val proposalId = rows.getColumnIndexOrThrow("proposalId")
        val version = rows.getColumnIndexOrThrow("version")
        val proposalJson = rows.getColumnIndexOrThrow("proposalJson")
        while (rows.moveToNext()) {
          val source =
              runCatching {
                    (legacyCoachJson.parseToJsonElement(rows.getString(proposalJson))
                            as? JsonObject)
                        ?.get("source")
                        ?.let { it as? JsonPrimitive }
                        ?.takeIf(JsonPrimitive::isString)
                        ?.content
                  }
                  .getOrNull()
          if (source == "COACH")
              socialRows +=
                  Triple(rows.getString(owner), rows.getString(proposalId), rows.getInt(version))
        }
      }
      socialRows.forEach { (owner, proposalId, version) ->
        val args = arrayOf<Any>(owner, proposalId, version)
        db.execSQL(
            "DELETE FROM training_proposal_operations WHERE owner=? AND proposalId=? AND version=?",
            args,
        )
        db.execSQL(
            "DELETE FROM training_proposal_projections WHERE owner=? AND proposalId=? AND version=?",
            args,
        )
        db.execSQL(
            "DELETE FROM training_proposal_drafts WHERE owner=? AND proposalId=? AND version=?",
            args,
        )
      }
    }

    private fun sanitizeLegacyUndoPackets(db: SupportSQLiteDatabase) {
      db.query(
              "SELECT workoutId,lastUndoPacketJson FROM coach_session_context WHERE lastUndoPacketJson IS NOT NULL"
          )
          .use { rows ->
            val workoutId = rows.getColumnIndexOrThrow("workoutId")
            val packet = rows.getColumnIndexOrThrow("lastUndoPacketJson")
            while (rows.moveToNext()) {
              val sanitized = sanitizeLegacyUndoPacket(rows.getString(packet))
              if (sanitized == null) {
                db.execSQL(
                    "UPDATE coach_session_context SET lastUndoPacketJson=NULL,lastUndoRevision=NULL WHERE workoutId=?",
                    arrayOf(rows.getString(workoutId)),
                )
              } else {
                db.execSQL(
                    "UPDATE coach_session_context SET lastUndoPacketJson=? WHERE workoutId=?",
                    arrayOf(sanitized, rows.getString(workoutId)),
                )
              }
            }
          }
    }

    private fun sanitizeLegacyUndoPacket(raw: String): String? {
      return try {
        val entry = legacyCoachJson.parseToJsonElement(raw) as? JsonObject ?: return null
        if (!entry.keys.all { it == "packet" || it == "context" }) return null
        val packet = entry["packet"] as? JsonObject ?: return null
        val operations = packet["operations"] as? JsonArray ?: return null
        if (operations.isEmpty() || operations.any { it !is JsonObject }) return null
        when (val context = entry["context"]) {
          null,
          JsonNull -> raw
          is JsonObject ->
              if ("occupiedEquipmentJson" !in context) raw
              else
                  legacyCoachJson.encodeToString(
                      JsonObject.serializer(),
                      JsonObject(
                          entry.toMutableMap().apply {
                            put(
                                "context",
                                JsonObject(context.filterKeys { it != "occupiedEquipmentJson" }),
                            )
                          }
                      ),
                  )
          else -> null
        }
      } catch (_: Exception) {
        null
      }
    }

    private fun markRetiredOccupiedEquipmentProposalsStale(db: SupportSQLiteDatabase) {
      val staleIds = buildList {
        db.query("SELECT id,packetJson FROM coach_proposals WHERE state='PENDING'").use { rows ->
          val id = rows.getColumnIndexOrThrow("id")
          val packet = rows.getColumnIndexOrThrow("packetJson")
          while (rows.moveToNext()) {
            if (containsRetiredOccupiedEquipmentOperation(rows.getString(packet))) {
              add(rows.getString(id))
            }
          }
        }
      }
      staleIds.forEach { id ->
        db.execSQL(
            "UPDATE coach_proposals SET state='STALE' WHERE id=? AND state='PENDING'",
            arrayOf(id),
        )
      }
    }

    private fun containsRetiredOccupiedEquipmentOperation(packetJson: String): Boolean {
      return try {
        val packet = legacyCoachJson.parseToJsonElement(packetJson) as? JsonObject ?: return false
        val operations = packet["operations"] as? JsonArray ?: return false
        operations.any { operation ->
          val type = (operation as? JsonObject)?.get("type") as? JsonPrimitive
          type?.isString == true && type.content == RETIRED_OCCUPIED_EQUIPMENT_OPERATION
        }
      } catch (_: Exception) {
        false
      }
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
          it.moveToFirst()
        }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { rows ->
          val name = rows.getColumnIndexOrThrow("name")
          while (rows.moveToNext()) if (rows.getString(name) == column) return true
          false
        }

    private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, definition: String) {
      val name = definition.substringBefore(' ')
      if (!hasColumn(db, table, name)) db.execSQL("ALTER TABLE `$table` ADD COLUMN $definition")
    }

    private fun repairRequiredDefaultColumn(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        definition: String,
        fallback: String,
    ) {
      if (!hasColumn(db, table, column)) {
        db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
        return
      }
      val legacy = "__legacy_$column"
      db.execSQL("ALTER TABLE `$table` RENAME COLUMN `$column` TO `$legacy`")
      db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
      db.execSQL("UPDATE `$table` SET `$column`=COALESCE(`$legacy`, $fallback)")
      db.execSQL("ALTER TABLE `$table` DROP COLUMN `$legacy`")
    }

    private fun restoreCalendarEventAccountLinksIfMissing(db: SupportSQLiteDatabase) {
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS `calendar_event_account_links` (`scheduledWorkoutId` INTEGER NOT NULL, `ownerEmail` TEXT, `state` TEXT NOT NULL, PRIMARY KEY(`scheduledWorkoutId`), FOREIGN KEY(`scheduledWorkoutId`) REFERENCES `scheduled_workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
      )
      db.execSQL(
          "INSERT INTO calendar_event_account_links(scheduledWorkoutId,ownerEmail,state) SELECT s.id,NULL,'LEGACY_OWNER_UNKNOWN' FROM scheduled_workouts s WHERE NOT EXISTS (SELECT 1 FROM calendar_event_account_links l WHERE l.scheduledWorkoutId=s.id)",
      )
      CalendarEventAccountLinkSchema.install(db)
    }

    private fun createCoachTablesIfMissing(db: SupportSQLiteDatabase) {
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS coach_messages (id TEXT NOT NULL, accountId TEXT NOT NULL, workoutId TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL, quickRepliesJson TEXT, readAt INTEGER, PRIMARY KEY(id), FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_messages_workoutId ON coach_messages(workoutId)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_messages_accountId ON coach_messages(accountId)"
      )
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS coach_proposals (id TEXT NOT NULL, accountId TEXT NOT NULL, workoutId TEXT NOT NULL, baseRevision INTEGER NOT NULL, beforeSummary TEXT NOT NULL, afterSummary TEXT NOT NULL, packetJson TEXT NOT NULL, expiresAt INTEGER NOT NULL, state TEXT NOT NULL, previewJson TEXT, PRIMARY KEY(id), FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_proposals_workoutId ON coach_proposals(workoutId)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_proposals_accountId ON coach_proposals(accountId)"
      )
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS coach_command_receipts (operationId TEXT NOT NULL, accountId TEXT NOT NULL, workoutId TEXT NOT NULL, revision INTEGER NOT NULL, result TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(operationId), FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_command_receipts_workoutId ON coach_command_receipts(workoutId)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_command_receipts_accountId ON coach_command_receipts(accountId)"
      )
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS coach_journal (id TEXT NOT NULL, accountId TEXT NOT NULL, workoutId TEXT NOT NULL, createdAt INTEGER NOT NULL, payload TEXT NOT NULL, uploaded INTEGER NOT NULL, deviceId TEXT, PRIMARY KEY(id), FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_journal_workoutId ON coach_journal(workoutId)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_journal_accountId ON coach_journal(accountId)"
      )
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_journal_uploaded ON coach_journal(uploaded)"
      )
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS coach_sync_state (accountId TEXT NOT NULL, deviceId TEXT NOT NULL, watermark INTEGER NOT NULL, PRIMARY KEY(accountId))"
      )
    }

    private fun repairCoachSessionContext(db: SupportSQLiteDatabase) {
      val canonical =
          "CREATE TABLE coach_session_context (workoutId TEXT NOT NULL, accountId TEXT NOT NULL, availableTimeMinutes INTEGER, availableTimeEndsAtMillis INTEGER, futureRestSeconds INTEGER, excludedExerciseIdsJson TEXT NOT NULL, lastUndoPacketJson TEXT, lastUndoRevision INTEGER, initiativeEnabled INTEGER NOT NULL, initiativeWelcomed INTEGER NOT NULL, initiativeAutomaticCount INTEGER NOT NULL, initiativeLastAutomaticAtMillis INTEGER, initiativeAskedExerciseIdsJson TEXT NOT NULL, initiativeEndReminderSent INTEGER NOT NULL, initiativePendingInteraction INTEGER NOT NULL, PRIMARY KEY(workoutId), FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
      if (!tableExists(db, "coach_session_context")) {
        db.execSQL(canonical)
      } else {
        val existing = columnNames(db, "coach_session_context")
        val newFields =
            setOf(
                "availableTimeEndsAtMillis",
                "initiativeEnabled",
                "initiativeWelcomed",
                "initiativeAutomaticCount",
                "initiativeLastAutomaticAtMillis",
                "initiativeAskedExerciseIdsJson",
                "initiativeEndReminderSent",
                "initiativePendingInteraction",
            )
        if (!existing.containsAll(newFields) || "occupiedEquipmentJson" in existing) {
          db.execSQL("ALTER TABLE coach_session_context RENAME TO __legacy_coach_session_context")
          db.execSQL(canonical)
          fun value(column: String, fallback: String, coalesceExisting: Boolean = false): String =
              when {
                column !in existing -> fallback
                coalesceExisting -> "COALESCE(`$column`, $fallback)"
                else -> "`$column`"
              }
          db.execSQL(
              "INSERT INTO coach_session_context(workoutId,accountId,availableTimeMinutes,availableTimeEndsAtMillis,futureRestSeconds,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeLastAutomaticAtMillis,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction) SELECT " +
                  listOf(
                          value("workoutId", "NULL"),
                          value("accountId", "NULL"),
                          value("availableTimeMinutes", "NULL"),
                          value("availableTimeEndsAtMillis", "NULL"),
                          value("futureRestSeconds", "NULL"),
                          value("excludedExerciseIdsJson", "'[]'"),
                          value("lastUndoPacketJson", "NULL"),
                          value("lastUndoRevision", "NULL"),
                          value("initiativeEnabled", "1", coalesceExisting = true),
                          value("initiativeWelcomed", "0", coalesceExisting = true),
                          value("initiativeAutomaticCount", "0", coalesceExisting = true),
                          value("initiativeLastAutomaticAtMillis", "NULL"),
                          value("initiativeAskedExerciseIdsJson", "'[]'", coalesceExisting = true),
                          value("initiativeEndReminderSent", "0", coalesceExisting = true),
                          value("initiativePendingInteraction", "0", coalesceExisting = true),
                      )
                      .joinToString(",") +
                  " FROM __legacy_coach_session_context",
          )
          db.execSQL("DROP TABLE __legacy_coach_session_context")
        }
      }
      db.execSQL(
          "CREATE INDEX IF NOT EXISTS index_coach_session_context_accountId ON coach_session_context(accountId)"
      )
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { rows ->
          val name = rows.getColumnIndexOrThrow("name")
          buildSet { while (rows.moveToNext()) add(rows.getString(name)) }
        }

    val MIGRATION_24_25: Migration =
        object : Migration(24, 25) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS training_proposal_drafts (owner TEXT NOT NULL, proposalId TEXT NOT NULL, version INTEGER NOT NULL, proposalJson TEXT NOT NULL, draftJson TEXT NOT NULL, PRIMARY KEY(owner,proposalId,version))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS training_proposal_operations (owner TEXT NOT NULL, proposalId TEXT NOT NULL, version INTEGER NOT NULL, operationId TEXT NOT NULL, requestBytes BLOB NOT NULL, requestSha256 TEXT NOT NULL, acceptedResultJson TEXT, rejected INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(owner,proposalId,version,operationId))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_training_proposal_operations_operationId ON training_proposal_operations(operationId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS training_proposal_projections (owner TEXT NOT NULL, proposalId TEXT NOT NULL, version INTEGER NOT NULL, routineId TEXT NOT NULL, calendarPlanId TEXT NOT NULL, syncRevision INTEGER NOT NULL, PRIMARY KEY(owner,proposalId,version))"
            )
          }
        }

    val MIGRATION_14_15: Migration =
        object : Migration(14, 15) {
          override fun migrate(db: SupportSQLiteDatabase) {
            com.valerochka1337.valerochkagym.data.backend.SyncSchema.create(db)
          }
        }

    /** Единственный production/test реестр всех поддерживаемых путей до текущей схемы. */
    val ALL_MIGRATIONS: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_12,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
        )

    private val legacyCoachJson = Json {
      isLenient = false
      ignoreUnknownKeys = false
    }

    private const val RETIRED_OCCUPIED_EQUIPMENT_OPERATION =
        "com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.SetOccupiedEquipment"
  }
}
