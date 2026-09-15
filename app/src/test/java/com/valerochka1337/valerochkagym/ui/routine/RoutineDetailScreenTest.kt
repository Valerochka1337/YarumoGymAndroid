package com.valerochka1337.valerochkagym.ui.routine

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class RoutineDetailScreenTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `standard detail opens exercise navigation without edit`() {
    var opened: Long? = null
    var clones = 0
    compose.setContent {
      GymTheme {
        Column(Modifier.fillMaxSize()) {
          RoutineDetailHeader(
              routine("STANDARD"),
              onBack = {},
              onEdit = {},
              onClone = { clones++ },
          )
          RoutineDetailContent(
              routine = routine("STANDARD"),
              onExerciseClick = { opened = it },
              windowWidthClass = GymWindowWidthClass.Compact,
              modifier = Modifier.weight(1f),
          )
        }
      }
    }

    compose.onNodeWithContentDescription("Редактировать программу").assertDoesNotExist()
    compose.onNodeWithContentDescription("Меню программы").performClick()
    compose.onNodeWithText("Клонировать").performClick()
    compose.onNodeWithContentDescription("Открыть карточку упражнения").performClick()

    assertEquals(1L, opened)
    assertEquals(1, clones)
  }

  @Test
  @Config(application = Application::class, qualifiers = "w1200dp-h800dp-xhdpi")
  fun `personal detail keeps edit and complete content reachable at font scale two`() {
    var origin by mutableStateOf("PERSONAL")
    var edits = 0
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.fillMaxSize()) {
            RoutineDetailHeader(
                routine(origin),
                onBack = {},
                onEdit = { edits++ },
                onClone = {},
            )
            RoutineDetailContent(
                routine = routine(origin),
                onExerciseClick = {},
                windowWidthClass = GymWindowWidthClass.Expanded,
                modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }

    compose.onNodeWithContentDescription("Редактировать программу").performClick()
    compose.onNodeWithText("Заметка").assertDoesNotExist()
    compose.onNodeWithText("Разминка").assertDoesNotExist()
    compose
        .onNodeWithContentDescription("Открыть карточку упражнения")
        .performScrollTo()
        .performClick()
    assertEquals(1, edits)

    compose.runOnIdle { origin = "STANDARD" }
    compose.onNodeWithContentDescription("Редактировать программу").assertDoesNotExist()
  }

  @Test
  fun `shared viewer hides gyms and does not expose local exercise navigation`() {
    compose.setContent {
      GymTheme {
        RoutineDetailContent(
            routine = routine("SHARED"),
            onExerciseClick = null,
            windowWidthClass = GymWindowWidthClass.Expanded,
            showGyms = false,
        )
      }
    }

    compose.onNodeWithText("Залы").assertDoesNotExist()
    compose.onNodeWithContentDescription("Открыть карточку упражнения").assertDoesNotExist()
    compose.onNodeWithText("Приседания").assertExists()
  }

  private fun routine(origin: String) =
      RoutineDetailRoutine(
          id = 7,
          syncId = "routine-7",
          origin = origin,
          name = "Ноги",
          note = "Разминка",
          gymNames = listOf("Дом"),
          exercises =
              listOf(
                  RoutineDetailExercise(
                      id = 1,
                      name = "Приседания",
                      type = ExerciseType.STRENGTH,
                      restSeconds = 90,
                      plannedSets = listOf(PlannedSet(weightKg = 80.0, reps = 5)),
                  )
              ),
      )
}
