package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementPeriod
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `selected period filters history and skips absent chart values`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val now = System.currentTimeMillis()
        val dao =
            FakeBodyMeasurementDao(
                listOf(
                    BodyMeasurementEntity(id = "recent", measuredAt = now - DAY, weightKg = 70.0),
                    BodyMeasurementEntity(
                        id = "old",
                        measuredAt = now - 250 * DAY,
                        weightKg = null,
                        waistCm = 72.0,
                    ),
                ),
            )
        val viewModel =
            MeasurementsViewModel(
                dao,
                FakeMeasurementUploadScheduler(),
                mainDispatcherRule.testDispatcher,
            )
        collectUiState(viewModel)

        assertEquals(listOf("recent"), viewModel.uiState.value.measurements!!.map { it.id })
        assertEquals(
            listOf("recent", "old"),
            viewModel.uiState.value.allMeasurements!!.map { it.id },
        )
        assertTrue(viewModel.uiState.value.hasMeasurements)
        assertEquals(
            70.0,
            viewModel.uiState.value.summary
                .single { it.metric == BodyMeasurementMetric.WEIGHT }
                .value,
            1e-6,
        )

        viewModel.onPeriodSelected(MeasurementPeriod.ALL)

        assertEquals(listOf("recent", "old"), viewModel.uiState.value.measurements!!.map { it.id })
      }

  @Test
  fun `tapping a point selects it and tapping again returns to the latest measurement`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val now = System.currentTimeMillis()
        val dao =
            FakeBodyMeasurementDao(
                listOf(
                    BodyMeasurementEntity(id = "latest", measuredAt = now, weightKg = 70.0),
                    BodyMeasurementEntity(id = "previous", measuredAt = now - DAY, weightKg = 71.0),
                ),
            )
        val viewModel =
            MeasurementsViewModel(
                dao,
                FakeMeasurementUploadScheduler(),
                mainDispatcherRule.testDispatcher,
            )
        collectUiState(viewModel)

        viewModel.onMeasurementSelected("previous")
        assertEquals("previous", viewModel.uiState.value.selectedMeasurement?.id)

        viewModel.onMeasurementSelected("previous")
        assertNull(viewModel.uiState.value.selectedMeasurementId)
        assertEquals("latest", viewModel.uiState.value.selectedMeasurement?.id)
      }

  @Test
  fun `retry upload forwards the selected measurement UUID to its scheduler`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val scheduler = FakeMeasurementUploadScheduler()
        val viewModel =
            MeasurementsViewModel(
                FakeBodyMeasurementDao(emptyList()),
                scheduler,
                mainDispatcherRule.testDispatcher,
            )

        viewModel.retryUpload("m1")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("m1"), scheduler.retried)
      }

  @Test
  fun `deleting from the complete measurement list removes the local record`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao =
            FakeBodyMeasurementDao(
                listOf(
                    BodyMeasurementEntity(
                        id = "visible",
                        measuredAt = System.currentTimeMillis(),
                        weightKg = 70.0,
                    ),
                    BodyMeasurementEntity(id = "archived", measuredAt = 1_000L, inBodyScore = 74),
                ),
            )
        val viewModel =
            MeasurementsViewModel(
                dao,
                FakeMeasurementUploadScheduler(),
                mainDispatcherRule.testDispatcher,
            )
        collectUiState(viewModel)

        viewModel.deleteMeasurement("archived")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("visible"), viewModel.uiState.value.allMeasurements!!.map { it.id })
        assertEquals(listOf("visible"), viewModel.uiState.value.measurements!!.map { it.id })
      }

  private suspend fun TestScope.collectUiState(viewModel: MeasurementsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    viewModel.uiState.first { !it.loading }
  }

  private class FakeBodyMeasurementDao(initial: List<BodyMeasurementEntity>) : BodyMeasurementDao {
    private val measurements = MutableStateFlow(initial)

    override suspend fun insert(measurement: BodyMeasurementEntity) {
      measurements.value = measurements.value + measurement
    }

    override suspend fun update(measurement: BodyMeasurementEntity) {
      measurements.value =
          measurements.value.map { if (it.id == measurement.id) measurement else it }
    }

    override fun observeAll(): Flow<List<BodyMeasurementEntity>> = measurements

    override suspend fun getById(id: String): BodyMeasurementEntity? =
        measurements.value.find { it.id == id }

    override suspend fun setUploadStatus(
        measurementId: String,
        status: UploadStatus,
        error: String?,
    ) = Unit

    override suspend fun getNotUploaded(): List<String> = emptyList()

    override suspend fun delete(id: String) {
      measurements.value = measurements.value.filterNot { it.id == id }
    }
  }

  private class FakeMeasurementUploadScheduler : MeasurementUploadScheduler {
    val retried = mutableListOf<String>()

    override fun schedule(measurementId: String) = Unit

    override suspend fun retry(measurementId: String) {
      retried += measurementId
    }

    override suspend fun scheduleAllPending(): Int = 0
  }

  private companion object {
    const val DAY = 24 * 60 * 60 * 1_000L
  }
}
