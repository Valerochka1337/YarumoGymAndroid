package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UploadMeasurementWorkerTest {

  @Test
  fun `successful measurement upload completes the work`() = runTest {
    val repository = FakeSheetsRepository(UploadResult.Success)

    val result = worker(repository, FakeBodyMeasurementDao()).doWork()

    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(listOf("m1"), repository.measurementIds)
  }

  @Test
  fun `transient measurement error retries before the final attempt`() = runTest {
    val dao = FakeBodyMeasurementDao()

    val result =
        worker(
                FakeSheetsRepository(UploadResult.TransientFailure("Нет сети")),
                dao,
                runAttemptCount = 4,
            )
            .doWork()

    assertEquals(ListenableWorker.Result.retry(), result)
    assertTrue(dao.statusUpdates.isEmpty())
  }

  @Test
  fun `last transient measurement error is visible as failed status`() = runTest {
    val dao = FakeBodyMeasurementDao()

    val result =
        worker(
                FakeSheetsRepository(UploadResult.TransientFailure("Нет сети")),
                dao,
                runAttemptCount = 5,
            )
            .doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    assertEquals(listOf(Triple("m1", UploadStatus.FAILED, "Нет сети")), dao.statusUpdates)
  }

  private fun worker(
      repository: SheetsRepository,
      dao: BodyMeasurementDao,
      runAttemptCount: Int = 0,
  ): UploadMeasurementWorker {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return TestListenableWorkerBuilder<UploadMeasurementWorker>(context)
        .setInputData(workDataOf("measurementId" to "m1"))
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(
            object : WorkerFactory() {
              override fun createWorker(
                  appContext: Context,
                  workerClassName: String,
                  workerParameters: WorkerParameters,
              ): ListenableWorker =
                  UploadMeasurementWorker(appContext, workerParameters, repository, dao)
            }
        )
        .build()
  }

  private class FakeSheetsRepository(private val result: UploadResult) : SheetsRepository {
    val measurementIds = mutableListOf<String>()

    override suspend fun uploadWorkout(workoutId: String): UploadResult = result

    override suspend fun uploadRoutine(routineSyncId: String): UploadResult = result

    override suspend fun uploadRoutineDeletion(
        routineSyncId: String,
        updatedAt: Long,
    ): UploadResult = result

    override suspend fun uploadMeasurement(measurementId: String): UploadResult {
      measurementIds += measurementId
      return result
    }
  }

  private class FakeBodyMeasurementDao : BodyMeasurementDao {
    val statusUpdates = mutableListOf<Triple<String, UploadStatus, String?>>()

    override suspend fun insert(measurement: BodyMeasurementEntity) = Unit

    override suspend fun update(measurement: BodyMeasurementEntity) = Unit

    override fun observeAll(): Flow<List<BodyMeasurementEntity>> = flowOf(emptyList())

    override suspend fun getById(id: String): BodyMeasurementEntity? = null

    override suspend fun setUploadStatus(
        measurementId: String,
        status: UploadStatus,
        error: String?,
    ) {
      statusUpdates += Triple(measurementId, status, error)
    }

    override suspend fun getNotUploaded(): List<String> = emptyList()

    override suspend fun delete(id: String) = Unit
  }
}
