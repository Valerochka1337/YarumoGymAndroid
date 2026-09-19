package com.valerochka1337.valerochkagym.ui.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.StrengthExerciseCandidate
import com.valerochka1337.valerochkagym.domain.StrengthPlannerRepository
import com.valerochka1337.valerochkagym.domain.StrengthPlannerSaveResult
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `rep range draft restores validates and clears`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository()
        val handle = SavedStateHandle()
        val first = ProfileViewModel(repository, gate(repository), handle)
        advanceUntilIdle()
        first.setRepRange("6", "12")
        val restored = ProfileViewModel(repository, gate(repository), handle)
        advanceUntilIdle()
        assertEquals("6", restored.uiState.value.preferredRepMin)
        assertEquals("12", restored.uiState.value.preferredRepMax)
        restored.save()
        advanceUntilIdle()
        assertEquals(6, repository.saved?.preferredRepMin)
        restored.setRepRange("12", "6")
        restored.save()
        assertNotNull(restored.uiState.value.error)
        restored.setRepRange("", "")
        restored.save()
        advanceUntilIdle()
        assertEquals(null, repository.saved?.preferredRepMin)
        assertEquals(null, repository.saved?.preferredRepMax)
      }

  @Test
  fun `invalid optional values show error and valid empty profile saves`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository()
        val viewModel = ProfileViewModel(repository, gate(repository), SavedStateHandle())
        advanceUntilIdle()
        viewModel.setSessions("8")
        viewModel.save()
        assertNotNull(viewModel.uiState.value.error)
        viewModel.setSessions("")
        viewModel.save()
        advanceUntilIdle()
        assertEquals(BasicProfile(), repository.saved)
      }

  @Test
  fun `saved draft is ignored when editor target changes`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository()
        val handle = SavedStateHandle()
        val first = ProfileViewModel(repository, gate(repository), handle)
        advanceUntilIdle()
        first.setConstraints("A draft")
        repository.target = ProfileEditTarget("owner-b", "owner-b", 2L)
        val restored = ProfileViewModel(repository, gate(repository), handle)
        advanceUntilIdle()
        assertTrue(restored.uiState.value.manualConstraints.isEmpty())
      }

  @Test
  fun `birth date validation uses UTC day across a device midnight boundary`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val originalZone = TimeZone.getDefault()
        try {
          TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
          val repository = FakeProfileRepository()
          val viewModel =
              ProfileViewModel(
                  repository,
                  gate(repository),
                  SavedStateHandle(),
                  WallClock { java.time.Instant.parse("2026-01-01T00:30:00Z").toEpochMilli() },
              )
          advanceUntilIdle()
          viewModel.setBirthDate("2026-01-01")
          viewModel.save()
          advanceUntilIdle()
          assertEquals("2026-01-01", repository.saved?.birthDate)
        } finally {
          TimeZone.setDefault(originalZone)
        }
      }

  @Test
  fun `key exercise draft restores across recreation`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository()
        val handle = SavedStateHandle()
        val strength = FakeStrengthPlannerRepository()
        val first =
            ProfileViewModel(
                repository,
                gate(repository),
                handle,
                strengthPlannerRepository = strength,
            )
        advanceUntilIdle()
        first.setGoal(com.valerochka1337.valerochkagym.domain.TrainingGoal.STRENGTH)
        first.toggleKeyExercise(7)

        val recreated =
            ProfileViewModel(
                repository,
                gate(repository),
                handle,
                strengthPlannerRepository = strength,
            )
        advanceUntilIdle()

        assertEquals(listOf(7L), recreated.uiState.value.keyExercises.mapNotNull { it.exerciseId })
      }

  @Test
  fun `edits save automatically and invalid input keeps the last saved value`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository()
        val viewModel = ProfileViewModel(repository, gate(repository), SavedStateHandle())
        advanceUntilIdle()
        viewModel.setSessions("3")
        advanceUntilIdle()
        assertEquals(3, repository.saved?.plannedSessionsPerWeek)
        viewModel.setSessions("9")
        advanceUntilIdle()
        assertEquals(3, repository.saved?.plannedSessionsPerWeek)
        assertNotNull(viewModel.uiState.value.error)
        viewModel.setSessions("4")
        viewModel.setDuration("60")
        viewModel.setConstraints("Текст")
        advanceUntilIdle()
        assertEquals(4, repository.saved?.plannedSessionsPerWeek)
        assertEquals(60, repository.saved?.preferredSessionDurationMinutes)
        assertEquals("Текст", repository.saved?.manualConstraints)
      }

  @Test
  fun `typing during a pending save persists the latest complete input`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository().apply { saveDelayMillis = 100 }
        val viewModel = ProfileViewModel(repository, gate(repository), SavedStateHandle())
        advanceUntilIdle()
        viewModel.setConstraints("П")
        runCurrent()
        assertTrue(viewModel.uiState.value.isSaving)
        viewModel.setConstraints("Полный текст")
        viewModel.setDuration("45")
        assertEquals("Полный текст", viewModel.uiState.value.manualConstraints)
        advanceUntilIdle()
        assertEquals("Полный текст", repository.saved?.manualConstraints)
        assertEquals(45, repository.saved?.preferredSessionDurationMinutes)
        assertEquals(false, viewModel.uiState.value.isSaving)
      }

  @Test
  fun `failed autosave retains input and the next edit retries`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository().apply { failSave = true }
        val viewModel = ProfileViewModel(repository, gate(repository), SavedStateHandle())
        advanceUntilIdle()
        viewModel.setSessions("3")
        advanceUntilIdle()
        assertEquals("3", viewModel.uiState.value.plannedSessionsPerWeek)
        assertNotNull(viewModel.uiState.value.error)
        repository.failSave = false
        viewModel.setSessions("4")
        advanceUntilIdle()
        assertEquals(4, repository.saved?.plannedSessionsPerWeek)
        assertEquals(null, viewModel.uiState.value.error)
      }

  @Test
  fun `invalid input retains its error when an earlier save emits`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeProfileRepository().apply { saveDelayMillis = 100 }
        val viewModel = ProfileViewModel(repository, gate(repository), SavedStateHandle())
        advanceUntilIdle()
        viewModel.setSessions("3")
        runCurrent()
        viewModel.setSessions("9")
        advanceUntilIdle()
        assertEquals(3, repository.saved?.plannedSessionsPerWeek)
        assertEquals("9", viewModel.uiState.value.plannedSessionsPerWeek)
        assertNotNull(viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isSaving)
      }

  private fun gate(repository: ProfileRepository) =
      AiProfilePromptGate(SettingsRepository(FakeDataStore()), repository, WallClock { 1L })
}

private class FakeProfileRepository : ProfileRepository {
  var target = ProfileEditTarget("owner-a", "owner-a", 1L)
  var saved: BasicProfile? = null
  var saveDelayMillis = 0L
  var failSave = false
  private val profiles = mutableMapOf<ProfileEditTarget, MutableStateFlow<BasicProfile?>>()

  private fun profileFlow(target: ProfileEditTarget) =
      profiles.getOrPut(target) { MutableStateFlow(BasicProfile()) }

  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> =
      flowOf(ProfileEditorSnapshot(target, BasicProfile()))

  override suspend fun openEditor(): ProfileEditorSnapshot =
      ProfileEditorSnapshot(target, BasicProfile())

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> =
      if (target == this.target) profileFlow(target) else flowOf(null)

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult {
    delay(saveDelayMillis)
    if (failSave) error("Write failed")
    if (target != this.target) return ProfileSaveResult.StaleTarget
    saved = profile
    profileFlow(target).value = profile
    return ProfileSaveResult.Saved
  }
}

private class FakeDataStore : DataStore<Preferences> {
  override val data = MutableStateFlow<Preferences>(emptyPreferences())

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
}

private class FakeStrengthPlannerRepository : StrengthPlannerRepository {
  override fun observeLiveStrengthExercises(): Flow<List<StrengthExerciseCandidate>> =
      flowOf(listOf(StrengthExerciseCandidate(7, "sync-7", "Жим")))

  override fun observe(target: ProfileEditTarget): Flow<List<KeyExerciseChoice>?> =
      flowOf(emptyList())

  override suspend fun save(
      target: ProfileEditTarget,
      profileGoal: com.valerochka1337.valerochkagym.domain.TrainingGoal?,
      choices: List<KeyExerciseChoice>,
  ): StrengthPlannerSaveResult = StrengthPlannerSaveResult.Saved
}
