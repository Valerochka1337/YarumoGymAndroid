package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.valerochka1337.valerochkagym.data.ai.WorkoutPreparationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@HiltWorker
class WorkoutPreparationWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: WorkoutPreparationRepository,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val id = inputData.getString("requestId") ?: return Result.failure()
    val owner = inputData.getString("owner")
    // Pre-v39 input had no owner. It can only proceed when the repository's current session
    // resolves that request; an explicit owner is always fenced before any transport work.
    if (!repository.step(id, owner)) return Result.success()
    if (runAttemptCount < 8) return Result.retry()
    repository.pausePending(id, owner)
    return Result.success()
  }
}

@Singleton
class WorkoutPreparationScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: WorkoutPreparationRepository,
) {
  suspend fun start() {
    var scheduled = emptySet<Pair<String, String>>()
    repository.all
        .map {
          it.filter { row -> row.state in WorkoutPreparationRepository.activeStates }
              .map { row -> row.owner to row.requestId }
              .toSet()
        }
        .distinctUntilChanged()
        .collect { active ->
          (active - scheduled).forEach { (owner, id) -> enqueue(owner, id) }
          scheduled = active
        }
  }

  fun enqueue(owner: String, id: String) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "workout_preparation_${owner}_$id",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WorkoutPreparationWorker>()
                .setInputData(workDataOf("owner" to owner, "requestId" to id))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
  }
}
