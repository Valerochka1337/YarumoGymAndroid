package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MeasurementUploadSchedulerTest {

  private lateinit var workManager: WorkManager

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    workManager = WorkManager.getInstance(context)
  }

  @Test
  fun `schedule uses a distinct unique work name for a measurement`() = runTest {
    val dao = FakeBodyMeasurementDao()
    val scheduler = WorkManagerMeasurementUploadScheduler(workManager, dao)

    scheduler.schedule("m1")

    assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_measurement_m1").get().size)
    assertTrue(dao.statusUpdates.isEmpty())
  }

  @Test
  fun `retry and export all reset statuses and enqueue every pending measurement`() = runTest {
    val dao = FakeBodyMeasurementDao(notUploaded = listOf("m2", "m3"))
    val scheduler = WorkManagerMeasurementUploadScheduler(workManager, dao)

    scheduler.retry("m1")
    val count = scheduler.scheduleAllPending()

    assertEquals(2, count)
    assertEquals(
        listOf(
            "m1" to UploadStatus.PENDING,
            "m2" to UploadStatus.PENDING,
            "m3" to UploadStatus.PENDING,
        ),
        dao.statusUpdates,
    )
    listOf("m1", "m2", "m3").forEach { id ->
      assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_measurement_$id").get().size)
    }
  }

  private class FakeBodyMeasurementDao(
      private val notUploaded: List<String> = emptyList(),
  ) : BodyMeasurementDao {
    val statusUpdates = mutableListOf<Pair<String, UploadStatus>>()

    override suspend fun insert(measurement: BodyMeasurementEntity) = Unit

    override suspend fun update(measurement: BodyMeasurementEntity) = Unit

    override fun observeAll(): Flow<List<BodyMeasurementEntity>> = flowOf(emptyList())

    override suspend fun getById(id: String): BodyMeasurementEntity? = null

    override suspend fun setUploadStatus(
        measurementId: String,
        status: UploadStatus,
        error: String?,
    ) {
      statusUpdates += measurementId to status
    }

    override suspend fun getNotUploaded(): List<String> = notUploaded

    override suspend fun delete(id: String) = Unit
  }
}
