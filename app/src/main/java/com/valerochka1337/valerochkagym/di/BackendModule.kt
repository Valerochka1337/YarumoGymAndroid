package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.ai.BackendCoachModelGateway
import com.valerochka1337.valerochkagym.data.ai.CoachModelCatalogSource
import com.valerochka1337.valerochkagym.data.ai.CoachModelGateway
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.routineshare.RoutineShareDataSource
import com.valerochka1337.valerochkagym.data.routineshare.RoutineShareRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendModule {
  @Binds abstract fun transport(impl: BackendApi): BackendTransport

  @Binds abstract fun sessions(impl: BackendTokenStore): BackendSessionStore

  @Binds abstract fun coachModelGateway(impl: BackendCoachModelGateway): CoachModelGateway

  @Binds abstract fun coachModelCatalog(impl: BackendCoachModelGateway): CoachModelCatalogSource

  @Binds abstract fun calendarCloudStatus(impl: BackendSync): CalendarCloudStatus

  @Binds abstract fun syncReadySource(impl: SyncReadyAdapter): SyncReadySource

  @Binds abstract fun routineShareDataSource(impl: RoutineShareRepository): RoutineShareDataSource
}
