package com.valerochka1337.valerochkagym.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.GymDatabaseCallback
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
import com.valerochka1337.valerochkagym.data.db.dao.ProfileDao
import com.valerochka1337.valerochkagym.data.db.dao.StrengthPlannerProfileDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutEffortDao
import com.valerochka1337.valerochkagym.data.settings.MuscleLoadUpgradeNotice
import com.valerochka1337.valerochkagym.data.settings.RoomMuscleLoadUpgradeNotice
import com.valerochka1337.valerochkagym.service.WallClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "settings")
private val Context.weeklyScheduleOperationsDataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "weekly_schedule_operations",
    )

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

  @Provides
  @Singleton
  @ApplicationScope
  fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Provides
  @ComputeDispatcher
  fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default

  /**
   * Стенные часы для [com.valerochka1337.valerochkagym.service.RestTimerEngine]: дедлайн отдыха
   * уезжает в `Notification.setWhen`, а хронометр уведомления сравнивает его именно с
   * [System.currentTimeMillis].
   */
  @Provides @Singleton fun provideWallClock(): WallClock = WallClock { System.currentTimeMillis() }

  @Provides
  @Singleton
  fun provideDatabase(
      @ApplicationContext context: Context,
      callback: GymDatabaseCallback,
  ): GymDatabase =
      Room.databaseBuilder(context, GymDatabase::class.java, DatabaseExporter.DATABASE_NAME)
          .addCallback(callback)
          .addMigrations(*GymDatabase.ALL_MIGRATIONS)
          .build()

  @Provides
  fun provideBodyMeasurementDao(database: GymDatabase): BodyMeasurementDao =
      database.bodyMeasurementDao()

  @Provides
  fun provideCalendarEventAccountLinkDao(database: GymDatabase): CalendarEventAccountLinkDao =
      database.calendarEventAccountLinkDao()

  @Provides
  fun provideCalendarPlanDao(database: GymDatabase): CalendarPlanDao = database.calendarPlanDao()

  @Provides
  fun provideConfigurationTombstoneDao(database: GymDatabase): ConfigurationTombstoneDao =
      database.configurationTombstoneDao()

  @Provides fun provideExerciseDao(database: GymDatabase): ExerciseDao = database.exerciseDao()

  @Provides
  fun provideExercisePersonalHintDao(database: GymDatabase): ExercisePersonalHintDao =
      database.exercisePersonalHintDao()

  @Provides
  fun provideExerciseMuscleDao(database: GymDatabase): ExerciseMuscleDao =
      database.exerciseMuscleDao()

  @Provides fun provideGymDao(database: GymDatabase): GymDao = database.gymDao()

  @Provides
  fun provideHealthAiConsentDao(database: GymDatabase): HealthAiConsentDao =
      database.healthAiConsentDao()

  @Provides fun provideHealthDao(database: GymDatabase): HealthDao = database.healthDao()

  @Provides
  fun provideHealthSyncDao(database: GymDatabase): HealthSyncDao = database.healthSyncDao()

  @Provides fun provideRoutineDao(database: GymDatabase): RoutineDao = database.routineDao()

  @Provides fun provideWorkoutDao(database: GymDatabase): WorkoutDao = database.workoutDao()

  @Provides
  fun provideWorkoutEffortDao(database: GymDatabase): WorkoutEffortDao = database.workoutEffortDao()

  @Provides fun provideCoachDao(database: GymDatabase): CoachDao = database.coachDao()

  @Provides
  fun provideMuscleLoadUpgradeNoticeDao(database: GymDatabase) =
      database.muscleLoadUpgradeNoticeDao()

  @Provides fun provideProfileDao(database: GymDatabase): ProfileDao = database.profileDao()

  @Provides
  fun provideStrengthPlannerProfileDao(database: GymDatabase): StrengthPlannerProfileDao =
      database.strengthPlannerProfileDao()

  @Provides
  fun provideScheduledWorkoutDao(database: GymDatabase): ScheduledWorkoutDao =
      database.scheduledWorkoutDao()

  @Provides
  @Singleton
  fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
      context.settingsDataStore

  @Provides
  @Singleton
  fun provideMuscleLoadUpgradeNotice(
      implementation: RoomMuscleLoadUpgradeNotice,
  ): MuscleLoadUpgradeNotice = implementation

  @Provides
  @Singleton
  @WeeklyScheduleOperations
  fun provideWeeklyScheduleOperationsDataStore(
      @ApplicationContext context: Context,
  ): DataStore<Preferences> = context.weeklyScheduleOperationsDataStore

  @Provides
  @Singleton
  fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
      WorkManager.getInstance(context)
}
