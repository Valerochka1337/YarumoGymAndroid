package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisScreenContent
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisUiState
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h900dp-xhdpi")
class AnalysisSectionsComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `sections fill one row equally and restore the selected tab`() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      GymTheme { AnalysisScreenContent(AnalysisUiState(loading = false), {}, {}, {}) }
    }
    assertSections()
    compose.onNodeWithText("Обзор").assertIsSelected()
    compose.onNodeWithText("Нагрузка").performClick().assertIsSelected()
    compose.onNodeWithText("Обзор").assertIsNotSelected()
    compose.onNodeWithText("Прогресс").performClick().assertIsSelected()
    compose.onNodeWithText("Нагрузка").assertIsNotSelected()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithText("Прогресс").assertIsSelected()
    compose.onNodeWithText("Пока нечего анализировать").assertIsDisplayed()
  }

  @Test
  fun `sections remain accessible with large text`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme { AnalysisScreenContent(AnalysisUiState(loading = false), {}, {}, {}) }
      }
    }
    assertSections()
    listOf("Обзор", "Нагрузка", "Прогресс").forEach { label ->
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
        it(layouts)
      }
      assertFalse("$label clips at large font scale", layouts.single().hasVisualOverflow)
    }
    compose.onNodeWithText("Прогресс").performClick().assertIsSelected()
  }

  @Test
  @Config(qualifiers = "w840dp-h900dp-xhdpi")
  fun `sections use the available width on expanded screens`() {
    compose.setContent {
      GymTheme { AnalysisScreenContent(AnalysisUiState(loading = false), {}, {}, {}) }
    }
    assertSections()
  }

  private fun assertSections() {
    compose.onNodeWithText("Здоровье").assertDoesNotExist()
    compose
        .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        .assertCountEquals(3)
    val bounds =
        listOf("Обзор", "Нагрузка", "Прогресс").map { label ->
          compose
              .onNodeWithText(label)
              .assertIsDisplayed()
              .assertHeightIsAtLeast(48.dp)
              .getUnclippedBoundsInRoot()
        }
    val root = compose.onRoot().getUnclippedBoundsInRoot()
    assertEquals(root.left.value + 20f, bounds.first().left.value, 1f)
    assertEquals(root.right.value - 20f, bounds.last().right.value, 1f)
    bounds.zipWithNext().forEach { (left, right) ->
      assertEquals((left.right - left.left).value, (right.right - right.left).value, 1f)
      assertEquals(left.top.value, right.top.value, 1f)
      assertEquals(left.right.value, right.left.value, 1f)
    }
  }
}
