package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.analysis.MuscleLoadSummary
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisScreenContent
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisUiState
import com.valerochka1337.valerochkagym.ui.analysis.MuscleFrequencyCard
import com.valerochka1337.valerochkagym.ui.analysis.MuscleHeatmapCard
import com.valerochka1337.valerochkagym.ui.analysis.MuscleVolumeCard
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  @Test
  fun `muscle summary stays beside the figure with large text`() {
    val load =
        MuscleLoadSummary(
            muscle = Muscle.UPPER_CHEST,
            weeklySets = 6.0,
            totalSets = 12.0,
            tonnageKg = 2400.0,
            zone = VolumeZone.WORKING,
            sessionsPerWeek = 2.0,
            daysSinceLast = 3,
            topExercises = listOf("Жим штанги лёжа", "Жим гантелей на наклонной скамье"),
        )
    val initial = AnalysisUiState(loading = false)
    val state =
        initial.copy(
            report = initial.report.copy(muscleLoads = listOf(load)),
            selectedMuscle = load.muscle,
        )
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme {
          Column(
              modifier = Modifier.verticalScroll(rememberScrollState()),
          ) {
            MuscleHeatmapCard(state, {})
          }
        }
      }
    }
    val figure =
        compose.onNodeWithContentDescription("Карта тела, спереди").getUnclippedBoundsInRoot()
    compose.onNodeWithText("2.4 т").assertDoesNotExist()
    compose.onNodeWithText("рабочий объём").assertDoesNotExist()
    val summary = compose.onNodeWithText("Объём").getUnclippedBoundsInRoot()
    assertTrue(summary.left >= figure.right)
    listOf("Объём", "Подходы", "Пауза", "Рабочий", "12", "3 дн.").forEach { label ->
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
        it(layouts)
      }
      val layout = layouts.single()
      assertFalse("$label clips vertically", layout.didOverflowHeight)
      repeat(layout.lineCount) { line ->
        assertFalse("$label is ellipsized", layout.isLineEllipsized(line))
        assertTrue("$label clips horizontally", layout.getLineRight(line) <= layout.size.width + 1f)
      }
    }
    compose.onNodeWithText("Как считаем").assertDoesNotExist()
    compose.onNodeWithText("Жим гантелей на наклонной скамье").assertDoesNotExist()
    compose.onNodeWithText("Основной вклад").assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "w600dp-h900dp-xhdpi")
  fun `body takes additional width without squeezing the summary`() {
    val load =
        MuscleLoadSummary(
            muscle = Muscle.UPPER_CHEST,
            weeklySets = 12.0,
            totalSets = 24.0,
            tonnageKg = 2400.0,
            zone = VolumeZone.GROWTH_GUIDE,
            sessionsPerWeek = 2.0,
            daysSinceLast = 3,
            topExercises = emptyList(),
        )
    val initial = AnalysisUiState(loading = false)
    val state =
        initial.copy(
            report = initial.report.copy(muscleLoads = listOf(load)),
            selectedMuscle = load.muscle,
        )
    compose.setContent {
      GymTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          MuscleHeatmapCard(state, {}, Modifier.width(320.dp))
          MuscleHeatmapCard(state, {}, Modifier.width(560.dp))
        }
      }
    }
    val figures = compose.onAllNodesWithContentDescription("Карта тела, спереди")
    val narrow = figures[0].getUnclippedBoundsInRoot()
    val wide = figures[1].getUnclippedBoundsInRoot()
    assertEquals(240f, ((wide.right - wide.left) - (narrow.right - narrow.left)).value, 1f)
    val summaries = compose.onAllNodesWithText("Эталонный")
    val first = summaries[0].getUnclippedBoundsInRoot()
    val second = summaries[1].getUnclippedBoundsInRoot()
    assertEquals((first.right - first.left).value, (second.right - second.left).value, 1f)
    assertTrue(first.left >= narrow.right)
    assertTrue(second.left >= wide.right)
  }

  @Test
  fun `muscle cards expand independently and restore their state`() {
    val initial = AnalysisUiState(loading = false)
    val load =
        MuscleLoadSummary(
            muscle = Muscle.UPPER_CHEST,
            weeklySets = 6.0,
            totalSets = 12.0,
            tonnageKg = 2400.0,
            zone = VolumeZone.WORKING,
            sessionsPerWeek = 2.0,
            daysSinceLast = 3,
            topExercises = emptyList(),
        )
    val state = initial.copy(report = initial.report.copy(muscleLoads = listOf(load)))
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      GymTheme {
        Column {
          MuscleVolumeCard(state, {})
          MuscleFrequencyCard(state)
        }
      }
    }
    val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свернуто")
    val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развернуто")
    compose.onNodeWithText("Объём по мышцам").assert(collapsed).performClick().assert(expanded)
    compose.onNodeWithText("Частота и пауза").assert(collapsed)
    compose.onNodeWithText("6").assertIsDisplayed()
    compose.onNodeWithText("3 д").assertDoesNotExist()
    compose.onNodeWithText("Частота и пауза").performClick().assert(expanded)
    compose.onNodeWithText("3 д").assertIsDisplayed()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithText("Объём по мышцам").assert(expanded).performClick().assert(collapsed)
    compose.onNodeWithText("6").assertDoesNotExist()
    compose.onNodeWithText("Частота и пауза").assert(expanded)
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
