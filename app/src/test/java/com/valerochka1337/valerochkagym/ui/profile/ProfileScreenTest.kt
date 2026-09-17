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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.domain.KeyExerciseChoice
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
            onPromptDisabled = {},
            onRepRange = { min, max -> selected = min to max },
            onSave = {},
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
  fun `actual profile content exposes clearable choices validation error and save on compact font scale two`() {
    var cleared: TrainingGoal? = TrainingGoal.STRENGTH
    var saves = 0
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
              onPromptDisabled = {},
              onSave = { saves++ },
          )
        }
      }
    }
    compose
        .onNodeWithContentDescription("Цель тренировок: Сила")
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle { assertNull(cleared) }
    compose.onNodeWithText("Проверьте данные профиля").performScrollTo().assertIsDisplayed()
    compose
        .onNodeWithContentDescription("Сохранить профиль")
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle { org.junit.Assert.assertEquals(1, saves) }
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
            onPromptDisabled = {},
            onSave = {},
        )
      }
    }
    compose.onNodeWithContentDescription("Загружаем профиль").assertIsDisplayed()
  }

  @Test
  fun `strength key choices remain removable at font scale two`() {
    var removed: String? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          ProfileScreenContent(
              state =
                  ProfileEditorUiState(
                      isLoading = false,
                      target = ProfileEditTarget("owner", "owner", 1),
                      trainingGoal = TrainingGoal.STRENGTH,
                      keyExercises =
                          listOf(
                              KeyExerciseChoice(null, "deleted-exercise", KeyExercisePriority.HIGH)
                          ),
                      strengthExercises = listOf(StrengthExerciseCandidate(1, "live", "Жим")),
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
              onPromptDisabled = {},
              onSave = {},
              onRemoveKeyExercise = { removed = it },
          )
        }
      }
    }
    compose.onNodeWithText("Недоступное упражнение").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Удалить").performScrollTo().performClick()
    compose.runOnIdle { org.junit.Assert.assertEquals("deleted-exercise", removed) }
  }

  @Test
  fun `live strength name and priority remain accessible at font scale two`() {
    var removed: String? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          ProfileScreenContent(
              state =
                  ProfileEditorUiState(
                      isLoading = false,
                      target = ProfileEditTarget("owner", "owner", 1),
                      trainingGoal = TrainingGoal.STRENGTH,
                      keyExercises = listOf(KeyExerciseChoice(1, "live", KeyExercisePriority.HIGH)),
                      strengthExercises =
                          listOf(
                              StrengthExerciseCandidate(
                                  1,
                                  "live",
                                  "Жим штанги лёжа на горизонтальной скамье",
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
              onPromptDisabled = {},
              onSave = {},
              onRemoveKeyExercise = { removed = it },
          )
        }
      }
    }
    compose
        .onNodeWithText("Жим штанги лёжа на горизонтальной скамье")
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Высокий").performScrollTo().assertIsDisplayed().performClick()
    compose.onNodeWithText("Удалить").performScrollTo().performClick()
    compose.runOnIdle { org.junit.Assert.assertEquals("live", removed) }
  }
}
