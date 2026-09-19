package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.calendar.CalendarLegacyMigration
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanRepository
import com.valerochka1337.valerochkagym.data.calendar.RoomCalendarPlanRepository
import com.valerochka1337.valerochkagym.data.google.ConfigurationSheetsRepository
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepositoryImpl
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerWeeklyScheduleRecoveryScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class GoogleModule {

  @Binds
  @Singleton
  abstract fun bindCalendarPlanRepository(impl: RoomCalendarPlanRepository): CalendarPlanRepository

  @Binds
  @Singleton
  abstract fun bindCalendarMigrationGate(impl: CalendarLegacyMigration): CalendarMigrationGate

  @Binds
  @Singleton
  abstract fun bindSheetsRepository(
      impl: com.valerochka1337.valerochkagym.data.backend.BackendUploadAdapter
  ): SheetsRepository

  @Binds
  @Singleton
  abstract fun bindConfigurationSheetsRepository(
      impl: com.valerochka1337.valerochkagym.data.backend.BackendUploadAdapter,
  ): ConfigurationSheetsRepository

  @Binds
  @Singleton
  abstract fun bindWorkoutImportRepository(
      impl: com.valerochka1337.valerochkagym.data.backend.BackendUploadAdapter
  ): WorkoutImportRepository

  @Binds
  @Singleton
  abstract fun bindWeeklyScheduleRepository(
      impl: WeeklyScheduleRepositoryImpl
  ): WeeklyScheduleRepository

  @Binds
  @Singleton
  abstract fun bindWeeklyScheduleRecoveryScheduler(
      impl: WorkManagerWeeklyScheduleRecoveryScheduler,
  ): WeeklyScheduleRecoveryScheduler
}
