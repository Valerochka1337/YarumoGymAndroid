package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl
import com.valerochka1337.valerochkagym.data.WorkoutEffortRepositoryImpl
import com.valerochka1337.valerochkagym.data.ExerciseCatalogRepositoryImpl
import com.valerochka1337.valerochkagym.data.ExercisePersonalHintRepositoryImpl
import com.valerochka1337.valerochkagym.data.GymRepositoryImpl
import com.valerochka1337.valerochkagym.data.ai.AndroidInBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.BackendAiRepository
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.ai.InBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCaseImpl
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporterImpl
import com.valerochka1337.valerochkagym.data.health.HealthConsentStoreImpl
import com.valerochka1337.valerochkagym.data.profile.ProfileRepositoryImpl
import com.valerochka1337.valerochkagym.data.profile.StrengthPlannerRepositoryImpl
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHintRepository
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.HealthConsentStore
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.StrengthPlannerRepository
import com.valerochka1337.valerochkagym.domain.WorkoutEffortRepository
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerMeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class DomainModule {

  @Binds
  @Singleton
  abstract fun bindActiveWorkoutRepository(
      impl: ActiveWorkoutRepositoryImpl,
  ): ActiveWorkoutRepository

  @Binds
  @Singleton
  abstract fun bindExercisePersonalHintRepository(
      impl: ExercisePersonalHintRepositoryImpl,
  ): ExercisePersonalHintRepository

  @Binds
  @Singleton
  abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

  @Binds
  @Singleton
  abstract fun bindStrengthPlannerRepository(
      impl: StrengthPlannerRepositoryImpl,
  ): StrengthPlannerRepository

  @Binds
  @Singleton
  abstract fun bindWorkoutEffortRepository(
      impl: WorkoutEffortRepositoryImpl,
  ): WorkoutEffortRepository

  @Binds
  @Singleton
  abstract fun bindHealthConsentStore(impl: HealthConsentStoreImpl): HealthConsentStore

  @Binds @Singleton abstract fun bindGymRepository(impl: GymRepositoryImpl): GymRepository

  @Binds
  @Singleton
  abstract fun bindExerciseCatalogRepository(
      impl: ExerciseCatalogRepositoryImpl,
  ): ExerciseCatalogRepository

  @Binds
  @Singleton
  abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler

  @Binds
  @Singleton
  abstract fun bindMeasurementUploadScheduler(
      impl: WorkManagerMeasurementUploadScheduler,
  ): MeasurementUploadScheduler

  @Binds
  @Singleton
  abstract fun bindRoutineUploadScheduler(
      impl: WorkManagerRoutineUploadScheduler,
  ): RoutineUploadScheduler

  @Binds
  @Singleton
  abstract fun bindConfigurationUploadScheduler(
      impl: WorkManagerConfigurationUploadScheduler,
  ): ConfigurationUploadScheduler

  @Binds @Singleton abstract fun bindDatabaseExporter(impl: DatabaseExporterImpl): DatabaseExporter

  @Binds @Singleton abstract fun bindClearDataUseCase(impl: ClearDataUseCaseImpl): ClearDataUseCase

  @Binds
  @Singleton
  abstract fun bindExerciseAiGenerator(impl: BackendAiRepository): ExerciseAiGenerator

  @Binds
  @Singleton
  abstract fun bindInBodyPhotoEncoder(impl: AndroidInBodyPhotoEncoder): InBodyPhotoEncoder

  @Binds
  @Singleton
  abstract fun bindInBodyReportAiReader(impl: BackendAiRepository): InBodyReportAiReader
}
