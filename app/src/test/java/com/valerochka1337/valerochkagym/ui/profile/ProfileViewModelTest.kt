package com.valerochka1337.valerochkagym.ui.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.StrengthPlannerRepository
import com.valerochka1337.valerochkagym.domain.StrengthExerciseCandidate
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.StrengthPlannerSaveResult
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

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
        val first = ProfileViewModel(repository, gate(repository), handle, strengthPlannerRepository = strength)
        advanceUntilIdle()
        first.setGoal(com.valerochka1337.valerochkagym.domain.TrainingGoal.STRENGTH)
        first.toggleKeyExercise(7)

        val recreated = ProfileViewModel(repository, gate(repository), handle, strengthPlannerRepository = strength)
        advanceUntilIdle()

        assertEquals(listOf(7L), recreated.uiState.value.keyExercises.mapNotNull { it.exerciseId })
      }

  private fun gate(repository: ProfileRepository) =
      AiProfilePromptGate(SettingsRepository(FakeDataStore()), repository, WallClock { 1L })
}

private class FakeProfileRepository : ProfileRepository {
  var target = ProfileEditTarget("owner-a", "owner-a", 1L)
  var saved: BasicProfile? = null

  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> =
      flowOf(ProfileEditorSnapshot(target, BasicProfile()))

  override suspend fun openEditor(): ProfileEditorSnapshot =
      ProfileEditorSnapshot(target, BasicProfile())

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> =
      flowOf(if (target == this.target) BasicProfile() else null)

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult {
    saved = profile
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

  override fun observe(target: ProfileEditTarget): Flow<List<KeyExerciseChoice>?> = flowOf(emptyList())

  override suspend fun save(
      target: ProfileEditTarget,
      profileGoal: com.valerochka1337.valerochkagym.domain.TrainingGoal?,
      choices: List<KeyExerciseChoice>,
  ): StrengthPlannerSaveResult = StrengthPlannerSaveResult.Saved
}
