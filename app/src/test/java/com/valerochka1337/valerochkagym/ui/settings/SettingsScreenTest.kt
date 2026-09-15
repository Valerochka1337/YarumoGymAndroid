package com.valerochka1337.valerochkagym.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h640dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `account settings omit relation entry while profile remains reachable`() {
    compose.setContent {
      GymTheme { SettingsCategoryList(onSelect = {}, onOpenGyms = {}, onOpenProfile = {}) }
    }

    compose.onNodeWithText("Профиль").assertIsDisplayed()
    compose.onNodeWithText("Связи с тренером").assertDoesNotExist()
    compose.onNodeWithText("Тренеры и подопечные").assertDoesNotExist()
  }

  @Test
  fun `calendar account state and actions remain reachable at font scale two`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            GoogleAccountCard(
                connectedEmail = "owner@example.com",
                authBusy = false,
                authError = null,
                onConnect = {},
                onDisconnect = {},
            )
          }
        }
      }
    }

    compose.onNodeWithText("owner@example.com").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Сменить аккаунт").performScrollTo().assertIsEnabled()
    compose.onNodeWithText("Отключить календарь").performScrollTo().assertIsEnabled()
  }

  @Test
  fun `calendar actions share the busy gate`() {
    compose.setContent {
      GymTheme {
        GoogleAccountCard(
            connectedEmail = null,
            authBusy = true,
            authError = null,
            onConnect = {},
            onDisconnect = {},
        )
      }
    }

    compose.onNodeWithText("Подключить календарь").assertIsNotEnabled()
    compose.onNodeWithText("Сменить аккаунт").assertDoesNotExist()
  }

  @Test
  @Config(application = android.app.Application::class, qualifiers = "w840dp-h900dp-xhdpi")
  fun `medium window exposes disconnected calendar state and actions to TalkBack`() {
    compose.setContent {
      GymTheme {
        GoogleAccountCard(
            connectedEmail = null,
            authBusy = false,
            authError = null,
            onConnect = {},
            onDisconnect = {},
        )
      }
    }

    compose.onNodeWithContentDescription("Подключить Google Calendar").assertIsEnabled().also {
      assertEquals(
          "Календарь не подключён",
          it.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
      )
    }
    compose.onNodeWithContentDescription("Подключить другой Google-аккаунт").assertDoesNotExist()
  }

  @Test
  @Config(application = android.app.Application::class, qualifiers = "w1200dp-h800dp-xhdpi")
  fun `expanded window exposes connected account state and actions to TalkBack`() {
    compose.setContent {
      GymTheme {
        GoogleAccountCard(
            connectedEmail = "owner@example.com",
            authBusy = false,
            authError = null,
            onConnect = {},
            onDisconnect = {},
        )
      }
    }

    compose.onNodeWithContentDescription("Отключить Google Calendar").assertIsEnabled().also {
      assertEquals(
          "Подключён к owner@example.com",
          it.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
      )
    }
    compose.onNodeWithContentDescription("Подключить другой Google-аккаунт").assertIsEnabled()
  }

  @Test
  fun `coach model controls remain reachable at font scale two`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            CoachModelCard(
                state =
                    CoachModelUiState(
                        available = true,
                        defaultModel = "server-default",
                        models = listOf("server-default", "tool-model"),
                        selectedModel = "tool-model",
                    ),
                onSelect = {},
                onVerify = {},
                onRefresh = {},
            )
          }
        }
      }
    }

    compose.onNodeWithText("Текущая модель: tool-model").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Проверить модель тренера").performScrollTo().assertIsEnabled()
  }

  @Test
  fun `coach model selection is locked while its synthetic check is running`() {
    compose.setContent {
      GymTheme {
        CoachModelCard(
            state =
                CoachModelUiState(
                    available = true,
                    defaultModel = "server-default",
                    models = listOf("server-default", "tool-model"),
                    selectedModel = "tool-model",
                    checking = true,
                ),
            onSelect = {},
            onVerify = {},
            onRefresh = {},
        )
      }
    }

    compose.onNodeWithText("Использовать модель сервера").assertIsNotEnabled()
    compose.onNodeWithText("tool-model").assertIsNotEnabled()
    compose.onNodeWithText("Проверяем модель…").assertIsNotEnabled()
  }

  @Test
  fun `coach model shows an explanatory unavailable state`() {
    compose.setContent {
      GymTheme {
        CoachModelCard(
            state =
                CoachModelUiState(available = false, status = "Войдите в аккаунт Yarumo coach."),
            onSelect = {},
            onVerify = {},
            onRefresh = {},
        )
      }
    }

    compose.onNodeWithText("Войдите в аккаунт Yarumo coach.").assertIsDisplayed()
    compose.onNodeWithText("Повторить").assertIsEnabled()
  }
}
