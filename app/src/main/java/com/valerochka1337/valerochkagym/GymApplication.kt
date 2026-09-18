package com.valerochka1337.valerochkagym

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.valerochka1337.valerochkagym.data.appicon.AppIconManager
import com.valerochka1337.valerochkagym.data.calendar.CalendarLegacyMigration
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureRepository
import com.valerochka1337.valerochkagym.data.settings.LegacyAiSecretCleanup
import com.valerochka1337.valerochkagym.data.update.PostUpdateRelaunchCoordinator
import com.valerochka1337.valerochkagym.di.ApplicationScope
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Точка входа приложения. Реализует [Configuration.Provider], чтобы WorkManager использовал
 * [HiltWorkerFactory] и мог создавать воркеры с внедрёнными зависимостями (см.
 * [com.valerochka1337.valerochkagym.worker.UploadWorkoutWorker]). Дефолтный инициализатор
 * WorkManager отключён в манифесте — конфигурация берётся отсюда, лениво при первом обращении.
 */
@HiltAndroidApp
class GymApplication : Application(), Configuration.Provider {

  @Inject
  lateinit var backendSyncScheduler:
      javax.inject.Provider<com.valerochka1337.valerochkagym.data.backend.BackendSyncScheduler>

  @Inject
  lateinit var preparationScheduler:
      Provider<com.valerochka1337.valerochkagym.worker.WorkoutPreparationScheduler>

  @Inject
  lateinit var coachDeliveryScheduler:
      Provider<com.valerochka1337.valerochkagym.worker.CoachDeliveryScheduler>

  @Inject
  lateinit var backendSessionStore:
      com.valerochka1337.valerochkagym.data.backend.BackendSessionStore

  @Inject lateinit var workerFactory: HiltWorkerFactory

  @Inject lateinit var appIconManager: AppIconManager

  @Inject lateinit var weeklyScheduleRecoveryScheduler: Provider<WeeklyScheduleRecoveryScheduler>

  @Inject lateinit var calendarLegacyMigration: CalendarLegacyMigration

  @Inject lateinit var postUpdateRelaunchCoordinator: PostUpdateRelaunchCoordinator

  @Inject lateinit var legacyAiSecretCleanup: LegacyAiSecretCleanup

  @Inject lateinit var healthAiDisclosureRepository: HealthAiDisclosureRepository

  @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

  override fun onCreate() {
    super.onCreate()
    // Иконка лаунчера — часть настройки акцента, а не разовое действие экрана: подписываемся
    // на неё на весь процесс, чтобы состояние alias'ов совпадало с сохранённым выбором.
    appIconManager.startSync()
    applicationScope.launch {
      // Do not let sync or the historical recovery worker observe/replay legacy calendar state
      // before it has been copied and quarantined durably.
      if (calendarLegacyMigration.ensureReady()) {
        backendSyncScheduler.get().start()
        weeklyScheduleRecoveryScheduler.get().enqueue()
        launch { preparationScheduler.get().start() }
      }
    }
    applicationScope.launch {
      backendSessionStore.sessionEpochs.collect { coachDeliveryScheduler.get().enqueue() }
    }
    applicationScope.launch { postUpdateRelaunchCoordinator.reconcilePending() }
    applicationScope.launch { legacyAiSecretCleanup.clear() }
    applicationScope.launch { healthAiDisclosureRepository.recoverPending() }
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
