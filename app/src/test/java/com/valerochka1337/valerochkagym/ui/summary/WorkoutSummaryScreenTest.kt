package com.valerochka1337.valerochkagym.ui.summary

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffort
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
class WorkoutSummaryScreenTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `all effort choices and clear remain reachable at compact large font scale`() {
    var selection: WorkoutEffort? = WorkoutEffort.HARD
    var minimumTargetPx = 0f
    composeRule.setContent {
      val density = LocalDensity.current
      minimumTargetPx = with(density) { 48.dp.toPx() }
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          WorkoutEffortCard(WorkoutEffort.HARD, false, null, onSelect = { selection = it })
        }
      }
    }
    listOf("Легко", "Умеренно", "Тяжело", "Очистить").forEach { label ->
      val node = composeRule.onNodeWithText(label).assertIsDisplayed()
      val bounds = node.fetchSemanticsNode().touchBoundsInRoot
      assertTrue(
          "$label: $bounds, minimum $minimumTargetPx",
          bounds.width + 0.5f >= minimumTargetPx && bounds.height + 0.5f >= minimumTargetPx,
      )
      node.performClick()
    }
    composeRule.onNodeWithText("Тяжело").assertIsSelected()
    assertEquals(null, selection)
  }

  @Test
  fun `summary exposes only the done action`() {
    var minimumTargetPx = 0f
    composeRule.setContent {
      val density = LocalDensity.current
      minimumTargetPx = with(density) { 48.dp.toPx() }
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column {
            WorkoutSummaryActions(
                onDone = {},
            )
          }
        }
      }
    }

    composeRule
        .onNodeWithContentDescription("Сохранить тренировку как программу")
        .assertDoesNotExist()
    composeRule.onNodeWithText("Сохранить").assertDoesNotExist()

    val done =
        composeRule.onNodeWithText("Готово").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    assertTrue(done.width >= minimumTargetPx && done.height >= minimumTargetPx)
  }

  @Test
  fun `save choice keeps every action accessible at large font scale`() {
    var minimumTargetPx = 0f
    composeRule.setContent {
      val density = LocalDensity.current
      minimumTargetPx = with(density) { 48.dp.toPx() }
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          SaveRoutineChoiceDialog(
              canReplace = true,
              isPreparing = false,
              error = "Программа изменилась.",
              onCreate = {},
              onReplace = {},
              onSkip = {},
              onDismiss = {},
          )
        }
      }
    }

    listOf("Новая", "Перезаписать", "Не сохранять").forEach { label ->
      val bounds =
          composeRule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
      assertTrue(bounds.width >= minimumTargetPx && bounds.height >= minimumTargetPx)
    }
    composeRule.onNodeWithText("Программа изменилась.").assertIsDisplayed()
  }
}
