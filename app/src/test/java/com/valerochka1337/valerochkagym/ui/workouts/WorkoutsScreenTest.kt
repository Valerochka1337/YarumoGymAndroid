package com.valerochka1337.valerochkagym.ui.workouts

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class WorkoutsScreenTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `empty catalog starts a workout without creating a program`() {
    var starts = 0
    var creates = 0
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
      ) {
        GymTheme {
          EmptyWorkoutsState(onStartEmpty = { starts++ }, onCreateRoutine = { creates++ })
        }
      }
    }

    compose.onNodeWithText("Начать без программы").performClick()
    assertEquals(1, starts)
    assertEquals(0, creates)
    compose.onNodeWithText("Создать программу").performClick()
    assertEquals(1, starts)
    assertEquals(1, creates)
  }

  @Test
  fun `collapsing templates keeps the selected standard routine`() {
    var selected: Long? = null
    compose.setContent {
      GymTheme {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
        WorkoutRoutinesList(
            routines = routines(),
            selectedRoutineId = selectedId,
            templatesExpanded = expanded,
            onTemplatesExpandedChange = { expanded = it },
            onRoutineSelected = {
              selectedId = it
              selected = it
            },
            onOpenRoutine = {},
            onDuplicateRoutine = {},
            onDeleteRoutine = {},
        )
      }
    }

    val templates = compose.onNodeWithContentDescription("Встроенные, 1")
    templates.assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Встроенные свернуты"),
    )
    templates.performClick()
    templates.assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Встроенные раскрыты"),
    )
    compose.onNodeWithText("Шаблон на всё тело").performClick()
    templates.performClick()

    compose.onNodeWithText("Шаблон на всё тело").assertDoesNotExist()
    assertEquals(1L, selected)
    compose.onNodeWithText("Личная программа").assertExists()
  }

  @Test
  fun `expanded templates restore after recreation`() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      GymTheme {
        var expanded by rememberSaveable { mutableStateOf(false) }
        WorkoutRoutinesList(
            routines = routines(),
            selectedRoutineId = null,
            templatesExpanded = expanded,
            onTemplatesExpandedChange = { expanded = it },
            onRoutineSelected = {},
            onOpenRoutine = {},
            onDuplicateRoutine = {},
            onDeleteRoutine = {},
        )
      }
    }

    compose.onNodeWithContentDescription("Встроенные, 1").performClick()
    restoration.emulateSavedInstanceStateRestore()

    compose.onNodeWithText("Шаблон на всё тело").assertExists()
  }

  @Test
  fun `viewing any origin opens detail without changing selection`() {
    val opened = mutableListOf<Long>()
    var selected: Long? = null
    compose.setContent {
      GymTheme {
        WorkoutRoutinesList(
            routines = routines(),
            selectedRoutineId = selected,
            templatesExpanded = true,
            onTemplatesExpandedChange = {},
            onRoutineSelected = { selected = it },
            onOpenRoutine = { opened += it },
            onDuplicateRoutine = {},
            onDeleteRoutine = {},
        )
      }
    }

    compose.onAllNodesWithContentDescription("Меню программы")[0].performClick()
    compose.onNodeWithText("Открыть").performClick()
    compose.onAllNodesWithContentDescription("Меню программы")[1].performClick()
    compose.onNodeWithText("Открыть").performClick()

    assertEquals(listOf(1L, 2L), opened)
    assertEquals(null, selected)
  }

  @Test
  fun `both origins expose the same open and clone menu labels`() {
    val cloned = mutableListOf<Long>()
    compose.setContent {
      GymTheme {
        WorkoutRoutinesList(
            routines = routines(),
            selectedRoutineId = null,
            templatesExpanded = true,
            onTemplatesExpandedChange = {},
            onRoutineSelected = {},
            onOpenRoutine = {},
            onDuplicateRoutine = { cloned += it },
            onDeleteRoutine = {},
        )
      }
    }

    compose.onAllNodesWithContentDescription("Меню программы")[0].performClick()
    compose.onNodeWithText("Открыть").assertExists()
    compose.onNodeWithText("Клонировать").performClick()
    compose.onAllNodesWithContentDescription("Меню программы")[1].performClick()
    compose.onNodeWithText("Открыть").assertExists()
    compose.onNodeWithText("Клонировать").performClick()

    assertEquals(listOf(1L, 2L), cloned)
  }

  @Test
  fun `only a personal routine exposes share in its menu`() {
    val shared = mutableListOf<Long>()
    compose.setContent {
      GymTheme {
        WorkoutRoutinesList(
            routines = routines(),
            selectedRoutineId = null,
            templatesExpanded = true,
            onTemplatesExpandedChange = {},
            onRoutineSelected = {},
            onOpenRoutine = {},
            onShareRoutine = { shared += it },
            onDuplicateRoutine = {},
            onDeleteRoutine = {},
        )
      }
    }

    compose.onAllNodesWithContentDescription("Меню программы")[0].performClick()
    compose.onNodeWithText("Поделиться").assertDoesNotExist()
    compose.onAllNodesWithContentDescription("Меню программы")[1].performClick()
    compose.onNodeWithText("Поделиться").performClick()

    assertEquals(listOf(2L), shared)
  }

  private fun routines() =
      listOf(
          RoutineCardUi(
              id = 1,
              name = "Шаблон на всё тело",
              exerciseCount = 3,
              estimatedMinutes = 30,
              origin = "STANDARD",
          ),
          RoutineCardUi(
              id = 2,
              name = "Личная программа",
              exerciseCount = 2,
              estimatedMinutes = 20,
          ),
      )
}
