package com.valerochka1337.valerochkagym.ui.components

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class PlanningChoiceComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `large catalog searches and retains selection after closing at font scale two`() {
    val choices = (1..500).map { "id-$it" to "Упражнение $it" }
    var selected = emptySet<String>()
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        var values by remember { mutableStateOf(emptySet<String>()) }
        GymTheme {
          PlanningChoiceField(
              "Исключить упражнения",
              choices,
              values,
              { id ->
                values = if (id in values) values - id else values + id
                selected = values
              },
          )
        }
      }
    }
    compose.onNodeWithText("Упражнение 500").assertDoesNotExist()
    compose.onNodeWithText("Исключить упражнения").performClick()
    compose.onNodeWithText("Поиск").performTextReplacement("500")
    compose.onNodeWithText("Упражнение 500").performClick()
    compose.onNodeWithText("Готово").assertIsDisplayed().performClick()
    compose.onNodeWithText("Упражнение 500").assertIsDisplayed()
    compose.onNodeWithText("Исключить упражнения").performClick()
    compose.onNodeWithText("Выбрано: 1").performClick()
    compose
        .onNode(
            hasText("Упражнение 500") and
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.Checkbox,
                )
        )
        .assertIsOn()
        .performClick()
    compose.runOnIdle { assertEquals(emptySet<String>(), selected) }
  }

  @Test
  fun `single choice invokes only the searched selection and closes`() {
    var chosen: String? = null
    var dismissed = 0
    compose.setContent {
      GymTheme {
        PlanningChoiceSheet(
            "Добавить упражнение",
            listOf("squat" to "Присед", "bench" to "Жим лёжа"),
            emptySet(),
            { chosen = it },
            { dismissed++ },
            singleChoice = true,
        )
      }
    }
    compose.onNodeWithText("Поиск").performTextReplacement("жим")
    compose.onNodeWithText("Присед").assertDoesNotExist()
    compose.onNodeWithText("Жим лёжа").performClick()
    compose.runOnIdle {
      assertEquals("bench", chosen)
      assertEquals(1, dismissed)
    }
  }
}
