package com.valerochka1337.valerochkagym.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreference
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
import com.valerochka1337.valerochkagym.domain.PlannerExerciseChoice
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.StrengthExerciseCandidate
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class ProfileScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `rep range choices stay reachable and can be cleared`() {
    var selected: Pair<String, String>? = null
    compose.setContent {
      GymTheme {
        ProfileScreenContent(
            state =
                ProfileEditorUiState(
                    isLoading = false,
                    target = ProfileEditTarget("guest", null, 1L),
                ),
            onBack = {},
            onGoal = {},
            onExperience = {},
            onSex = {},
            onBirthDate = {},
            onSessions = {},
            onDuration = {},
            onConstraints = {},
            onEquipment = {},
            onRepRange = { min, max -> selected = min to max },
        )
      }
    }
    compose.onNodeWithText("6–12").performScrollTo().performClick()
    org.junit.Assert.assertEquals("6" to "12", selected)
    compose.onNodeWithText("Не задан").performScrollTo().performClick()
    org.junit.Assert.assertEquals("" to "", selected)
  }

  @Test
  fun `profile prompt actions stay reachable at font scale two on expanded width`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            AiProfilePromptDialog(
                token = "token",
                onVisible = {},
                onFillProfile = {},
                onContinue = {},
                onDisable = {},
                onDismiss = {},
            )
          }
        }
      }
    }
    compose.onNodeWithContentDescription("Заполнить профиль").assertIsDisplayed().assertIsEnabled()
    compose
        .onNodeWithContentDescription("Продолжить без заполнения")
        .assertIsDisplayed()
        .assertIsEnabled()
    compose
        .onNodeWithContentDescription("Не предлагать профиль")
        .assertIsDisplayed()
        .assertIsEnabled()
  }

  @Test
  fun `actual profile content exposes clearable choices validation error and autosave on compact font scale two`() {
    var cleared: TrainingGoal? = TrainingGoal.STRENGTH
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          ProfileScreenContent(
              state =
                  ProfileEditorUiState(
                      isLoading = false,
                      target = ProfileEditTarget("guest", null, 1L),
                      trainingGoal = TrainingGoal.STRENGTH,
                      error = "Проверьте данные профиля",
                  ),
              onBack = {},
              onGoal = { cleared = it },
              onExperience = {},
              onSex = {},
              onBirthDate = {},
              onSessions = {},
              onDuration = {},
              onConstraints = {},
              onEquipment = {},
          )
        }
      }
    }
    compose.onNodeWithText("Сила").performScrollTo().assertIsDisplayed().performClick()
    compose.onAllNodesWithText("Не задано")[2].performClick()
    compose.runOnIdle { assertNull(cleared) }
    compose.onNodeWithText("Проверьте данные профиля").performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("Сохранить профиль").assertDoesNotExist()
    compose
        .onNodeWithText("Изменения сохраняются автоматически")
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun `actual profile content exposes loading status on expanded width`() {
    compose.setContent {
      GymTheme {
        ProfileScreenContent(
            state = ProfileEditorUiState(isLoading = true),
            onBack = {},
            onGoal = {},
            onExperience = {},
            onSex = {},
            onBirthDate = {},
            onSessions = {},
            onDuration = {},
            onConstraints = {},
            onEquipment = {},
        )
      }
    }
    compose.onNodeWithContentDescription("Загружаем профиль").assertIsDisplayed()
  }

  @Test
  fun `exercise accents stay available for every goal at font scale two`() {
    var selected: Pair<Long, ExerciseAccent>? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          ProfileScreenContent(
              state =
                  ProfileEditorUiState(
                      isLoading = false,
                      target = ProfileEditTarget("owner", "owner", 1),
                      trainingGoal = TrainingGoal.ENDURANCE,
                      showPlannerPreferences = true,
                      strengthExercises = listOf(StrengthExerciseCandidate(1, "press", "Жим")),
                      plannerExercises =
                          listOf(
                              StrengthExerciseCandidate(2, "run", "Бег"),
                              StrengthExerciseCandidate(1, "press", "Жим"),
                          ),
                      keyExercises =
                          listOf(KeyExerciseChoice(1, "press", KeyExercisePriority.HIGH)),
                      plannerPreferences =
                          listOf(
                              PlannerExerciseChoice(
                                  2,
                                  "run",
                                  PlannerExercisePreference.LESS,
                              )
                          ),
                  ),
              onBack = {},
              onGoal = {},
              onExperience = {},
              onSex = {},
              onBirthDate = {},
              onSessions = {},
              onDuration = {},
              onConstraints = {},
              onEquipment = {},
              onExerciseAccent = { id, accent -> selected = id to accent },
          )
        }
      }
    }
    compose.onAllNodesWithText("Акценты упражнений")[0].assertIsDisplayed()
    compose.onNodeWithContentDescription("Бег: Исключить").performClick()
    compose.runOnIdle { org.junit.Assert.assertEquals(2L to ExerciseAccent.EXCLUDE, selected) }
  }
}
