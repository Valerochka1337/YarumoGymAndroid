package com.valerochka1337.valerochkagym.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
  @get:Rule val compose = createComposeRule()

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
}
