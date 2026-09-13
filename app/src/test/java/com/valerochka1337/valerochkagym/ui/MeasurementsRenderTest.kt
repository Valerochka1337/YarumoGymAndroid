package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementsScreen
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementsViewModel
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Рендер-смоук пустого и заполненного экрана замеров; снимки лежат рядом с analysis-render. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h8000dp-xhdpi")
class MeasurementsRenderTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `empty measurements screen renders`() {
    render(emptyList(), "measurements-empty.png")
  }

  @Test
  fun `filled measurements screen renders charts and history`() {
    val now = System.currentTimeMillis()
    render(
        listOf(
            BodyMeasurementEntity(
                id = "latest",
                measuredAt = now,
                weightKg = 70.0,
                skeletalMuscleMassKg = 28.0,
                bodyFatPercentage = 25.0,
                bodyFatMassKg = 17.5,
                visceralFatLevel = 8,
                inBodyScore = 74,
                totalBodyWaterLiters = 38.1,
                proteinKg = 9.2,
                mineralsKg = 3.6,
                bodyMassIndex = 22.4,
                fatFreeMassKg = 52.5,
                basalMetabolicRateKcal = 1450,
                recommendedCalorieIntakeKcal = 2300,
                leftArmLeanMassKg = 2.2,
                leftArmLeanPercentage = 98.0,
                rightArmLeanMassKg = 2.1,
                rightArmLeanPercentage = 96.0,
                trunkLeanMassKg = 23.0,
                trunkLeanPercentage = 102.0,
                leftLegLeanMassKg = 8.1,
                leftLegLeanPercentage = 101.0,
                rightLegLeanMassKg = 8.0,
                rightLegLeanPercentage = 100.0,
                leftArmFatMassKg = 1.1,
                leftArmFatPercentage = 88.0,
                rightArmFatMassKg = 1.0,
                rightArmFatPercentage = 86.0,
                trunkFatMassKg = 8.0,
                trunkFatPercentage = 94.0,
                leftLegFatMassKg = 2.8,
                leftLegFatPercentage = 90.0,
                rightLegFatMassKg = 2.7,
                rightLegFatPercentage = 88.0,
                waistCm = 72.0,
                chestCm = 95.0,
                hipsCm = 96.0,
                rightRelaxedArmCm = 31.0,
                rightThighCm = 56.0,
                uploadStatus = UploadStatus.UPLOADED,
            ),
            BodyMeasurementEntity(
                id = "previous",
                measuredAt = now - 20 * DAY,
                weightKg = 72.0,
                skeletalMuscleMassKg = 27.5,
                bodyFatPercentage = 27.0,
                visceralFatLevel = 9,
                waistCm = 74.0,
                chestCm = 96.0,
                hipsCm = 97.0,
                rightRelaxedArmCm = 30.5,
                rightThighCm = 57.0,
            ),
        ),
        "measurements-filled.png",
    )
  }

  @Test
  fun `complete measurements menu renders local rows and delete action`() {
    val viewModel =
        MeasurementsViewModel(
            FakeBodyMeasurementDao(
                listOf(
                    BodyMeasurementEntity(
                        id = "one",
                        measuredAt = System.currentTimeMillis(),
                        weightKg = 70.0,
                    ),
                ),
            ),
            FakeMeasurementUploadScheduler(),
            Dispatchers.Unconfined,
        )
    composeRule.setContent {
      GymTheme {
        MeasurementsScreen(
            onBack = {},
            onCreateMeasurement = {},
            onEditMeasurement = {},
            viewModel = viewModel,
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("Все замеры").performClick()
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
  }

  private fun render(measurements: List<BodyMeasurementEntity>, fileName: String) {
    val viewModel =
        MeasurementsViewModel(
            FakeBodyMeasurementDao(measurements),
            FakeMeasurementUploadScheduler(),
            Dispatchers.Unconfined,
        )
    composeRule.setContent {
      GymTheme {
        MeasurementsScreen(
            onBack = {},
            onCreateMeasurement = {},
            onEditMeasurement = {},
            viewModel = viewModel,
        )
      }
    }
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    val dir = File("build/reports/analysis-render").apply { mkdirs() }
    FileOutputStream(File(dir, fileName)).use { stream ->
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
    }
  }

  private class FakeBodyMeasurementDao(initial: List<BodyMeasurementEntity>) : BodyMeasurementDao {
    private val measurements = MutableStateFlow(initial)

    override suspend fun insert(measurement: BodyMeasurementEntity) = Unit

    override suspend fun update(measurement: BodyMeasurementEntity) = Unit

    override fun observeAll(): Flow<List<BodyMeasurementEntity>> = measurements

    override suspend fun getById(id: String): BodyMeasurementEntity? =
        measurements.value.find { it.id == id }

    override suspend fun setUploadStatus(
        measurementId: String,
        status: UploadStatus,
        error: String?,
    ) = Unit

    override suspend fun getNotUploaded(): List<String> = emptyList()

    override suspend fun delete(id: String) = Unit
  }

  private class FakeMeasurementUploadScheduler : MeasurementUploadScheduler {
    override fun schedule(measurementId: String) = Unit

    override suspend fun retry(measurementId: String) = Unit

    override suspend fun scheduleAllPending(): Int = 0
  }

  private companion object {
    const val DAY = 24 * 60 * 60 * 1_000L
  }
}
