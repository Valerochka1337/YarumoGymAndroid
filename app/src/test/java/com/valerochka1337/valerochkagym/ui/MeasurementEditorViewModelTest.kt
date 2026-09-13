package com.valerochka1337.valerochkagym.ui

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiResult
import com.valerochka1337.valerochkagym.data.ai.InBodyReportDraft
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.domain.measurements.effectiveWaistHipRatio
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementEditorViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MeasurementEditorViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `saving a new measurement calculates WHR and schedules its upload`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeBodyMeasurementDao()
        val scheduler = FakeMeasurementUploadScheduler()
        val viewModel = MeasurementEditorViewModel(SavedStateHandle(), dao, scheduler)

        viewModel.setWaistCm("72")
        viewModel.setHipsCm("96")
        assertTrue(viewModel.uiState.value.canSave)
        assertEquals(0.75, viewModel.uiState.value.effectiveWaistHipRatio!!, 1e-6)

        viewModel.save()
        testScheduler.advanceUntilIdle()

        val stored = dao.inserted.single()
        assertNull(stored.waistHipRatio)
        assertEquals(0.75, stored.effectiveWaistHipRatio()!!, 1e-6)
        assertEquals(UploadStatus.PENDING, stored.uploadStatus)
        assertEquals(listOf(stored.id), scheduler.scheduled)
      }

  @Test
  fun `InBody fill never opens sources and continue opens them once`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val profile = PromptProfileRepository()
        val opened = mutableListOf<Unit>()
        val openedProfiles = mutableListOf<Unit>()
        val viewModel =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(),
                FakeMeasurementUploadScheduler(),
                aiProfilePromptGate = promptGate(profile),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.openImportSources.collect { opened += it }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.openProfile.collect { openedProfiles += it }
        }
        viewModel.requestInBodyImport()
        advanceUntilIdle()
        val token = requireNotNull(viewModel.profilePrompt.value).token
        assertTrue(opened.isEmpty())
        viewModel.fillProfileFromPrompt(token)
        viewModel.fillProfileFromPrompt(token)
        advanceUntilIdle()
        assertTrue(opened.isEmpty())
        assertEquals(1, openedProfiles.size)
        val next =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(),
                FakeMeasurementUploadScheduler(),
                aiProfilePromptGate = promptGate(PromptProfileRepository()),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          next.openImportSources.collect { opened += it }
        }
        next.requestInBodyImport()
        advanceUntilIdle()
        val nextToken = requireNotNull(next.profilePrompt.value).token
        next.continueAfterProfilePrompt(nextToken)
        next.continueAfterProfilePrompt(nextToken)
        advanceUntilIdle()
        assertEquals(1, opened.size)
      }

  @Test
  fun `successful InBody scan fills only its draft and preserves manual circumferences`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeBodyMeasurementDao()
        val reader =
            FakeInBodyReportAiReader(
                InBodyReportAiResult.Success(
                    InBodyReportDraft(
                        measuredDate = LocalDate.of(2026, 7, 24),
                        measuredTime = LocalTime.of(18, 6),
                        weightKg = 59.5,
                        bodyFatMassKg = 14.8,
                        inBodyScore = 74,
                        segments =
                            mapOf(
                                InBodySegment.LEFT_ARM to
                                    InBodySegmentValues(leanMassKg = 1.99, fatPercentage = 88.8),
                            ),
                    ),
                ),
            )
        val viewModel =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                dao,
                FakeMeasurementUploadScheduler(),
                reader,
            )
        testScheduler.advanceUntilIdle()
        viewModel.setWaistCm("72")
        viewModel.setChestCm("95")
        viewModel.setHipsCm("96")
        viewModel.setProteinKg("8.7")

        viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isScanningInBody)
        assertNull(state.inBodyScanError)
        assertEquals("59.5", state.weightKg)
        assertEquals("14.8", state.bodyFatMassKg)
        assertEquals("74", state.inBodyScore)
        assertEquals("1.99", state.segments.getValue(InBodySegment.LEFT_ARM).leanMassKg)
        assertEquals("72", state.waistCm)
        assertEquals("95", state.chestCm)
        assertEquals("96", state.hipsCm)
        assertEquals("8.7", state.proteinKg)
        assertEquals(
            LocalDate.of(2026, 7, 24),
            Instant.ofEpochMilli(state.measuredAt).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
      }

  @Test
  fun `failed InBody scan preserves draft and allows another attempt`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val reader =
            FakeInBodyReportAiReader(
                InBodyReportAiResult.Failure("Не удалось прочитать лист"),
                InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 60.0)),
            )
        val viewModel =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(),
                FakeMeasurementUploadScheduler(),
                reader,
            )
        testScheduler.advanceUntilIdle()
        viewModel.setWeightKg("70")
        viewModel.setWaistCm("72")

        viewModel.scanInBody(Uri.parse("content://picker/first.jpg"))
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanningInBody)
        assertEquals("Не удалось прочитать лист", viewModel.uiState.value.inBodyScanError)
        assertEquals("70", viewModel.uiState.value.weightKg)
        assertEquals("72", viewModel.uiState.value.waistCm)

        viewModel.scanInBody(Uri.parse("content://picker/second.jpg"))
        testScheduler.advanceUntilIdle()

        assertEquals("60.0", viewModel.uiState.value.weightKg)
        assertNull(viewModel.uiState.value.inBodyScanError)
        assertEquals(2, reader.uris.size)
      }

  @Test
  fun `saving a scanned report schedules one measurement upload`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeBodyMeasurementDao()
        val scheduler = FakeMeasurementUploadScheduler()
        val viewModel =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                dao,
                scheduler,
                FakeInBodyReportAiReader(
                    InBodyReportAiResult.Success(
                        InBodyReportDraft(
                            weightKg = 59.5,
                            bodyFatMassKg = 14.8,
                            segments =
                                mapOf(
                                    InBodySegment.RIGHT_LEG to
                                        InBodySegmentValues(
                                            leanMassKg = 7.39,
                                            leanPercentage = 107.5,
                                            fatMassKg = 2.4,
                                            fatPercentage = 85.6,
                                        ),
                                ),
                        ),
                    ),
                ),
            )
        testScheduler.advanceUntilIdle()

        viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
        testScheduler.advanceUntilIdle()
        viewModel.save()
        testScheduler.advanceUntilIdle()

        val stored = dao.inserted.single()
        assertEquals(14.8, stored.bodyFatMassKg!!, 1e-6)
        assertEquals(7.39, stored.rightLegLeanMassKg!!, 1e-6)
        assertEquals(85.6, stored.rightLegFatPercentage!!, 1e-6)
        assertEquals(listOf(stored.id), scheduler.scheduled)
      }

  @Test
  fun `saving an AI report with a full-report value stores it locally`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeBodyMeasurementDao()
        val viewModel =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                dao,
                FakeMeasurementUploadScheduler(),
                FakeInBodyReportAiReader(
                    InBodyReportAiResult.Success(InBodyReportDraft(inBodyScore = 74))
                ),
            )
        testScheduler.advanceUntilIdle()

        viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.save()
        testScheduler.advanceUntilIdle()

        assertEquals(74, dao.inserted.single().inBodyScore)
      }

  @Test
  fun `failed measurement persistence keeps the draft and exposes a retryable error`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(failOnInsert = true),
                FakeMeasurementUploadScheduler(),
            )
        viewModel.setInBodyScore("74")

        viewModel.save()
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(
            "Не удалось сохранить замер — попробуйте ещё раз",
            viewModel.uiState.value.saveError,
        )
        assertEquals("74", viewModel.uiState.value.inBodyScore)
      }

  @Test
  fun `editing an uploaded measurement does not schedule a second append`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val existing =
            BodyMeasurementEntity(
                id = "uploaded",
                measuredAt = 1_000,
                weightKg = 70.0,
                uploadStatus = UploadStatus.UPLOADED,
            )
        val dao = FakeBodyMeasurementDao(existing)
        val scheduler = FakeMeasurementUploadScheduler()
        val handle = SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id))
        val viewModel = MeasurementEditorViewModel(handle, dao, scheduler)
        testScheduler.advanceUntilIdle()

        viewModel.setWeightKg("69.5")
        viewModel.save()
        testScheduler.advanceUntilIdle()

        assertEquals(69.5, dao.updated.single().weightKg!!, 1e-6)
        assertEquals(UploadStatus.UPLOADED, dao.updated.single().uploadStatus)
        assertTrue(scheduler.scheduled.isEmpty())
      }

  @Test
  fun `deleting an existing measurement removes only its local record`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val existing = BodyMeasurementEntity(id = "m1", measuredAt = 1_000, weightKg = 70.0)
        val dao = FakeBodyMeasurementDao(existing)
        val handle = SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id))
        val viewModel = MeasurementEditorViewModel(handle, dao, FakeMeasurementUploadScheduler())
        testScheduler.advanceUntilIdle()

        viewModel.delete()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("m1"), dao.deleted)
        assertFalse(dao.rows.value.any { it.id == "m1" })
      }

  private class FakeInBodyReportAiReader(vararg initial: InBodyReportAiResult) :
      InBodyReportAiReader {
    private val results = ArrayDeque(initial.toList())
    val uris = mutableListOf<Uri>()

    override suspend fun read(uri: Uri): InBodyReportAiResult {
      uris += uri
      return results.removeFirst()
    }
  }

  private class FakeBodyMeasurementDao(
      vararg initial: BodyMeasurementEntity,
      private val failOnInsert: Boolean = false,
  ) : BodyMeasurementDao {
    val rows = MutableStateFlow(initial.toList())
    val inserted = mutableListOf<BodyMeasurementEntity>()
    val updated = mutableListOf<BodyMeasurementEntity>()
    val deleted = mutableListOf<String>()

    override suspend fun insert(measurement: BodyMeasurementEntity) {
      if (failOnInsert) error("Room write failed")
      inserted += measurement
      rows.value = rows.value + measurement
    }

    override suspend fun update(measurement: BodyMeasurementEntity) {
      updated += measurement
      rows.value = rows.value.map { if (it.id == measurement.id) measurement else it }
    }

    override fun observeAll(): Flow<List<BodyMeasurementEntity>> = rows

    override suspend fun getById(id: String): BodyMeasurementEntity? =
        rows.value.find { it.id == id }

    override suspend fun setUploadStatus(
        measurementId: String,
        status: UploadStatus,
        error: String?,
    ) = Unit

    override suspend fun getNotUploaded(): List<String> = emptyList()

    override suspend fun delete(id: String) {
      deleted += id
      rows.value = rows.value.filterNot { it.id == id }
    }
  }

  private class FakeMeasurementUploadScheduler : MeasurementUploadScheduler {
    val scheduled = mutableListOf<String>()

    override fun schedule(measurementId: String) {
      scheduled += measurementId
    }

    override suspend fun retry(measurementId: String) = Unit

    override suspend fun scheduleAllPending(): Int = 0
  }

  private fun promptGate(repository: ProfileRepository) =
      AiProfilePromptGate(SettingsRepository(PromptDataStore()), repository, WallClock { 1L })

  private class PromptProfileRepository : ProfileRepository {
    private val snapshot =
        ProfileEditorSnapshot(ProfileEditTarget("owner", "owner", 1L), BasicProfile())

    override fun observeCurrent(): Flow<ProfileEditorSnapshot?> = flowOf(snapshot)

    override suspend fun openEditor(): ProfileEditorSnapshot = snapshot

    override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> = flowOf(BasicProfile())

    override suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult =
        ProfileSaveResult.Saved
  }

  private class PromptDataStore : DataStore<Preferences> {
    override val data = MutableStateFlow<Preferences>(emptyPreferences())

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
  }
}
