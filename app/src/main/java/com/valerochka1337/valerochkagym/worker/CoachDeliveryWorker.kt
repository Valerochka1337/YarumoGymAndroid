package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.service.DurableCoachCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Delivery survives foreground-service teardown; cancelling this worker never cancels a run. */
@HiltWorker
class CoachDeliveryWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: DurableCoachCoordinator,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result =
      try {
        if (coordinator.deliverPending()) Result.retry() else Result.success()
      } catch (failure: CancellationException) {
        throw failure
      } catch (failure: BackendException) {
        // A new login queues fresh work. Never retry a previous login's credentials in the
        // background.
        if (failure.status == 401) Result.success() else Result.retry()
      } catch (_: Exception) {
        Result.retry()
      }
}

@Singleton
class CoachDeliveryScheduler @Inject constructor(private val workManager: WorkManager) {
  fun enqueue() {
    val request =
        OneTimeWorkRequestBuilder<CoachDeliveryWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    // Appending covers writes racing the current worker's final empty-outbox check.
    workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  companion object {
    const val UNIQUE_WORK_NAME = "coach_delivery"
  }
}
