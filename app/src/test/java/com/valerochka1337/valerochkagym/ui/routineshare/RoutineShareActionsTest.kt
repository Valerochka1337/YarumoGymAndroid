package com.valerochka1337.valerochkagym.ui.routineshare

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h800dp-xhdpi")
class RoutineShareActionsTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `large font actions announce saving and opening the saved copy correctly`() {
    checkActions()
  }

  @Test
  @Config(qualifiers = "w1200dp-h800dp-xhdpi")
  fun `wide screen retains the same accessible save and open actions`() {
    checkActions()
  }

  private fun checkActions() {
    var state by mutableStateOf(RoutineSharePreviewUiState(loading = false))
    var saves = 0
    var opened: Long? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Box(Modifier.widthIn(max = 840.dp)) {
            RoutineShareActions(state, { saves++ }, { opened = it })
          }
        }
      }
    }

    compose.onNodeWithContentDescription("Сохранить программу себе").assertIsDisplayed().performClick()
    assertEquals(1, saves)
    compose.runOnIdle { state = state.copy(importing = true) }
    compose.onNodeWithContentDescription("Сохранить программу себе").assertIsNotEnabled()
    compose.runOnIdle { state = state.copy(importing = false, importedRoutineId = 42) }
    compose.onNodeWithContentDescription("Сохранить программу себе").assertDoesNotExist()
    compose.onNodeWithContentDescription("Открыть мою программу").assertIsDisplayed().performClick()
    assertEquals(42L, opened)
    assertEquals(1, saves)
  }
}
