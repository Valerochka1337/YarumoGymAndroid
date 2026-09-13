package com.valerochka1337.valerochkagym.ui.calendarai

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class CalendarAiComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `paused preparation exposes retry at font scale two`() {
    var retries = 0
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          WorkoutPreparationCardContent(
              com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                  "owner",
                  "id",
                  "{}",
                  "[]",
                  state = "PAUSED_WAITING",
              ),
              onPrepare = { retries++ },
              onOpen = {},
          )
        }
      }
    }
    compose.onNodeWithText("Следующая тренировка").assertIsDisplayed()
    compose.onNodeWithText("Повторить").assertIsDisplayed().assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun `stale preparation retries saved conditions separately from editing`() {
    var retries = 0
    var edits = 0
    compose.setContent {
      GymTheme {
        WorkoutPreparationCardContent(
            com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                "owner",
                "id",
                "{}",
                "[]",
                state = "STALE",
            ),
            onPrepare = { edits++ },
            onOpen = {},
            onRetry = { retries++ },
        )
      }
    }
    compose.onNodeWithText("Повторить расчёт").performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(0, edits)
    }
    compose.onNodeWithText("Изменить условия").performClick()
    compose.runOnIdle { assertEquals(1, edits) }
  }

  @Test
  fun `calendar ai form sends selected gym and edited duration only when user creates proposal`() {
    var generated = 0
    var state = sampleState()
    compose.setContent {
      var displayed by mutableStateOf(state)
      GymTheme {
        CalendarAiContent(
            state = displayed,
            onBack = {},
            onDate = { value -> displayed = displayed.withForm { copy(date = value) } },
            onTime = { value -> displayed = displayed.withForm { copy(time = value) } },
            onZone = { value -> displayed = displayed.withForm { copy(timeZoneId = value) } },
            onGym = { id -> displayed = displayed.withForm { copy(gymIds = gymIds.toggle(id)) } },
            onExcludedExercise = { id ->
              displayed =
                  displayed.withForm { copy(excludedExerciseIds = excludedExerciseIds.toggle(id)) }
            },
            onExcludedEquipment = { id ->
              displayed =
                  displayed.withForm {
                    copy(excludedEquipmentIds = excludedEquipmentIds.toggle(id))
                  }
            },
            onPriorityMuscle = { id ->
              displayed = displayed.withForm { copy(priorityMuscles = priorityMuscles.toggle(id)) }
            },
            onIncludeNotes = { value ->
              displayed = displayed.withForm { copy(includeNotes = value) }
            },
            onDuration = { value ->
              displayed = displayed.withForm { copy(availableDurationMinutes = value) }
            },
            onCurrentState = { value ->
              displayed = displayed.withForm { copy(currentState = value) }
            },
            onPreferences = { value ->
              displayed = displayed.withForm { copy(preferences = value) }
            },
            onGenerate = {
              state = displayed
              generated++
            },
            onPromptVisible = {},
            onPromptFill = {},
            onPromptContinue = {},
            onPromptDisable = {},
            onPromptDismiss = {},
        )
      }
    }

    compose.onNodeWithText("Дом").performClick()
    compose.onNodeWithText("Доступное время, мин").performTextReplacement("75")
    compose.onNodeWithContentDescription("Начать расчёт").assertIsEnabled().performClick()

    compose.runOnIdle {
      assertEquals(1, generated)
      assertEquals(setOf("gym-home"), state.form.gymIds)
      assertEquals("75", state.form.availableDurationMinutes)
    }
  }

  @Test
  fun `calendar ai form keeps profile prompt actions distinct`() {
    var visible = 0
    var fill = 0
    var continueCalls = 0
    var disable = 0
    var dismiss = 0
    compose.setContent {
      GymTheme {
        CalendarAiContent(
            state = sampleState(profilePrompt = "prompt-1"),
            onBack = {},
            onDate = {},
            onTime = {},
            onZone = {},
            onGym = {},
            onExcludedExercise = {},
            onExcludedEquipment = {},
            onPriorityMuscle = {},
            onIncludeNotes = {},
            onDuration = {},
            onCurrentState = {},
            onPreferences = {},
            onGenerate = {},
            onPromptVisible = { visible++ },
            onPromptFill = { fill++ },
            onPromptContinue = { continueCalls++ },
            onPromptDisable = { disable++ },
            onPromptDismiss = { dismiss++ },
        )
      }
    }

    compose.onNodeWithContentDescription("Заполнить профиль").performClick()
    compose.onNodeWithContentDescription("Продолжить без заполнения").performClick()
    compose.onNodeWithContentDescription("Не предлагать профиль").performClick()
    compose.runOnIdle {
      assertEquals(1, visible)
      assertEquals(1, fill)
      assertEquals(1, continueCalls)
      assertEquals(1, disable)
      assertEquals(0, dismiss)
    }
  }

  @Test
  fun `calendar ai generate action stays reachable at font scale two`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          CalendarAiContent(
              state = sampleState(),
              onBack = {},
              onDate = {},
              onTime = {},
              onZone = {},
              onGym = {},
              onExcludedExercise = {},
              onExcludedEquipment = {},
              onPriorityMuscle = {},
              onIncludeNotes = {},
              onDuration = {},
              onCurrentState = {},
              onPreferences = {},
              onGenerate = {},
              onPromptVisible = {},
              onPromptFill = {},
              onPromptContinue = {},
              onPromptDisable = {},
              onPromptDismiss = {},
          )
        }
      }
    }

    compose.onNodeWithText("Когда тренироваться").assertIsDisplayed()
    compose.onNodeWithContentDescription("Начать расчёт").assertIsDisplayed().assertIsEnabled()
  }
}

private fun sampleState(profilePrompt: String? = null) =
    CalendarAiUiState(
        form = CalendarAiForm(date = "2027-01-02", timeZoneId = "Europe/Moscow"),
        gyms = listOf(CalendarAiChoice("gym-home", "Дом")),
        exercises = listOf(CalendarAiChoice("exercise-bench", "Жим лёжа")),
        equipment = listOf(CalendarAiChoice("barbell", "Штанга")),
        muscles = listOf(CalendarAiChoice("CHEST", "Грудь")),
        profilePrompt =
            profilePrompt?.let {
              com.valerochka1337.valerochkagym.ui.profile.AiProfilePromptUi(
                  it,
                  com.valerochka1337.valerochkagym.data.profile.AiProfilePromptKind.CALENDAR,
              )
            },
    )

private fun CalendarAiUiState.withForm(
    change: CalendarAiForm.() -> CalendarAiForm
): CalendarAiUiState = copy(form = form.change())

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value
