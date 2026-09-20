package com.valerochka1337.valerochkagym.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import com.valerochka1337.valerochkagym.ui.update.AppUpdateUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `one profile and account entry opens the combined screen without calendar settings`() {
    var opened = false
    compose.setContent {
      GymTheme {
        Column {
          SettingsCategoryList(onSelect = {}, onOpenGyms = {}, onOpenProfile = { opened = true })
        }
      }
    }
    compose.onNodeWithText("Профиль и аккаунт").performClick()
    assertTrue(opened)
    compose.onNodeWithText("Профиль").assertDoesNotExist()
    compose.onNodeWithText("Подключения").assertDoesNotExist()
    compose.onNodeWithText("Google Calendar").assertDoesNotExist()
  }

  @Test
  fun `live coach toggle is reachable with large text and has no model picker`() {
    var enabled = true
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme { LiveCoachCard(enabled, { enabled = it }) }
      }
    }
    compose.onNodeWithContentDescription("Тренер во время тренировки").performClick()
    assertFalse(enabled)
    compose.onNodeWithText("Модель тренера").assertDoesNotExist()
  }

  @Test
  fun `system back returns every settings category to tiles and root delegates once`() {
    val viewModel = settingsViewModel()
    var backCount = 0
    compose.activity.onBackPressedDispatcher.addCallback(
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            backCount++
          }
        },
    )
    compose.setContent {
      GymTheme {
        SettingsScreen(
            onBack = {},
            onOpenGyms = {},
            appUpdateState = AppUpdateUiState(),
            onCheckUpdate = {},
            onDownloadUpdate = {},
            onInstallUpdate = {},
            onRetryUpdate = {},
            viewModel = viewModel,
        )
      }
    }

    compose.waitUntil {
      compose.onAllNodesWithText("Тренировка").fetchSemanticsNodes().isNotEmpty()
    }
    listOf("Тренировка", "Вид и отклик", "О приложении").forEach { category ->
      compose.onNodeWithText(category).performClick()
      compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Настройки").assertIsDisplayed()
      compose.onNodeWithText("Профиль и аккаунт").assertIsDisplayed()
      assertEquals(0, backCount)
    }

    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    assertEquals(1, backCount)
  }

  @Test
  fun `header back matches system back for a settings category`() {
    val viewModel = settingsViewModel()
    var backCount = 0
    compose.activity.onBackPressedDispatcher.addCallback(
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            backCount++
          }
        },
    )
    compose.setContent {
      GymTheme {
        SettingsScreen(
            onBack = {},
            onOpenGyms = {},
            appUpdateState = AppUpdateUiState(),
            onCheckUpdate = {},
            onDownloadUpdate = {},
            onInstallUpdate = {},
            onRetryUpdate = {},
            viewModel = viewModel,
        )
      }
    }

    compose.waitUntil {
      compose.onAllNodesWithText("Вид и отклик").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Вид и отклик").performClick()
    compose.onNodeWithContentDescription("Назад").performClick()

    compose.onNodeWithText("Настройки").assertIsDisplayed()
    compose.onNodeWithText("Профиль и аккаунт").assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    assertEquals(1, backCount)
  }

  private fun settingsViewModel() =
      SettingsViewModel(
          SettingsRepository(FakeSettingsStore()),
          FakeDatabaseExporter(),
          FakeClearDataUseCase(),
      )
}

private class FakeSettingsStore : DataStore<Preferences> {
  private val state = MutableStateFlow<Preferences>(emptyPreferences())

  override val data: Flow<Preferences> = state

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(state.value).also { state.value = it }
}

private class FakeDatabaseExporter : DatabaseExporter {
  override suspend fun export(target: android.net.Uri): ExportResult = ExportResult.Success
}

private class FakeClearDataUseCase : ClearDataUseCase {
  override suspend fun invoke() = Unit
}
