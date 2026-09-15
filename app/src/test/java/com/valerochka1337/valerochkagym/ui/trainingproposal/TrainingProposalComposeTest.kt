package com.valerochka1337.valerochkagym.ui.trainingproposal

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.trainingproposal.ApprovalDraft
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalAuthor
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedExercise
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedSet
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalSnapshot
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalSource
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalStatus
import com.valerochka1337.valerochkagym.data.trainingproposal.TrainingProposal
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class TrainingProposalComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  @Config(qualifiers = "w360dp-h900dp-xhdpi")
  fun `ai explanation stays readable at large font and disappears after editing the plan`() {
    val ai = proposal()
    val explanation =
        com.valerochka1337.valerochkagym.data.trainingproposal.PlannerExplanation(
            ai.proposalId,
            ai.currentVersion,
            "planner-duration-v1",
            60,
            45,
            2880,
            listOf("UPPER_CHEST"),
            listOf("bench"),
            100,
            1,
            "GOAL_BALANCE",
            "CONTINUITY",
            "VOLUME_LIMIT",
        )
    var current by mutableStateOf(draft())
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
        GymTheme {
          TrainingProposalDetailContent(
              ai,
              current,
              false,
              false,
              null,
              listOf("bench" to "Жим лёжа"),
              emptyList(),
              {},
              {},
              {},
              {},
              {},
              explanation = explanation,
          )
        }
      }
    }
    compose.onNodeWithText("Почему такой план").performScrollTo().assertIsDisplayed()
    compose
        .onNodeWithText("Повтор из последней тренировки: Жим лёжа")
        .performScrollTo()
        .assertIsDisplayed()
    compose.runOnIdle { current = current.copy(name = "Изменённый план") }
    compose
        .onNodeWithText(
            "Вы изменили план. Обоснование ИИ относится к исходному варианту; оценка времени выше пересчитана."
        )
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Повтор из последней тренировки: Жим лёжа").assertDoesNotExist()
  }

  @Test
  fun `program button reflects opening and closing its form`() {
    compose.setContent {
      var manual by remember { mutableStateOf(false) }
      GymTheme {
        TrainingProposalInboxContent(
            emptyList(),
            false,
            null,
            false,
            {},
            {},
            {},
            {},
            onCreateAi = {},
            onManual = { manual = !manual },
            manualSelected = manual,
        )
      }
    }
    compose.onNodeWithText("Из программы").assertIsNotSelected().performClick()
    compose.onNodeWithText("Из программы").assertIsSelected().performClick()
    compose.onNodeWithText("Из программы").assertIsNotSelected()
  }

  @Test
  fun `status icon explains the decision without opening the proposal`() {
    var opened = 0
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            listOf(proposal()),
            false,
            null,
            false,
            {},
            {},
            { opened++ },
            {},
        )
      }
    }
    compose.onNodeWithText("Ожидает решения").assertDoesNotExist()
    compose.onNodeWithContentDescription("Статус: Ожидает решения").performClick()
    compose.onNodeWithText("Ожидает решения").assertIsDisplayed()
    compose.onNodeWithText("Проверьте план, затем примите или отклоните его.").assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, opened) }
    compose.onNodeWithText("Понятно").performClick()
    compose.onNodeWithText("Силовой план").performClick()
    compose.runOnIdle { assertEquals(1, opened) }
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp-xhdpi")
  fun `planning hub exposes both creation actions with empty proposals at large font`() {
    var ai = 0
    var manual = 0
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme {
          TrainingProposalInboxContent(
              emptyList(),
              false,
              null,
              false,
              {},
              {},
              {},
              {},
              onCreateAi = { ai++ },
              onManual = { manual++ },
          )
        }
      }
    }
    compose.onNodeWithText("Составить с ИИ").performScrollTo().performClick()
    compose.onNodeWithText("Из программы").performScrollTo().performClick()
    compose.onNodeWithText("Пока нет предложений").performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("Обновить предложения").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(1, ai)
      assertEquals(1, manual)
    }
  }

  @Test
  fun `detail exposes original source version and distinct apply reject back callbacks`() {
    var applyCalls = 0
    var rejectCalls = 0
    var backCalls = 0
    compose.setContent {
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = draft(),
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = listOf("gym" to "Дом"),
            onDraftChange = {},
            onApply = { applyCalls++ },
            onReject = { rejectCalls++ },
            onBack = { backCalls++ },
            onRetry = {},
        )
      }
    }

    compose
        .onNodeWithContentDescription("Статус: Ожидает решения")
        .assertIsDisplayed()
        .performClick()
    compose.onNodeWithText("Ожидает решения").assertIsDisplayed()
    compose.onNodeWithText("Понятно").performClick()
    compose.onNodeWithText("Ожидает решения").assertDoesNotExist()
    compose.onNodeWithText("План тренировки").assertIsDisplayed()
    compose.onNodeWithText("Версия 3").assertIsDisplayed()
    compose.onNodeWithContentDescription("Применить предложение").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Отклонить предложение").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Назад").performClick()

    compose.runOnIdle {
      assertEquals(1, applyCalls)
      assertEquals(1, rejectCalls)
      assertEquals(1, backCalls)
    }
  }

  @Test
  fun `edited set weight reaches draft callback`() {
    var latestDraft = draft()
    compose.setContent {
      var displayedDraft by mutableStateOf(draft())
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = displayedDraft,
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = emptyList(),
            onDraftChange = {
              latestDraft = it
              displayedDraft = it
            },
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Изменить план").performScrollTo().performClick()
    compose.onNodeWithText("Вес подхода 1, кг").performScrollTo().performTextReplacement("70.5")

    compose.runOnIdle {
      assertEquals(70.5, latestDraft.exercises.first().plannedSets.first().weightKg)
    }
  }

  @Test
  fun `adding exercise waits for explicit search selection`() {
    var latest = draft()
    compose.setContent {
      var displayed by remember { mutableStateOf(draft()) }
      GymTheme {
        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
          ProposalDraftForm(
              displayed,
              listOf("bench" to "Жим лёжа", "squat" to "Присед", "row" to "Тяга"),
              emptyList(),
              {
                displayed = it
                latest = it
              },
              {},
          )
        }
      }
    }
    compose.onNodeWithText("Добавить упражнение").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(1, latest.exercises.size) }
    compose.onNodeWithText("Поиск").performTextReplacement("Тяг")
    compose.onNodeWithText("Тяга").performClick()
    compose.runOnIdle {
      assertEquals(listOf("bench", "row"), latest.exercises.map { it.exerciseId })
    }
  }

  @Test
  fun `clearing duration preserves timed input until user finishes editing`() {
    compose.setContent {
      var displayed by remember {
        mutableStateOf(
            draft()
                .copy(
                    exercises =
                        listOf(
                            ProposalPlannedExercise(
                                "bench",
                                60,
                                listOf(ProposalPlannedSet(null, null, 60, null, null)),
                            )
                        )
                )
        )
      }
      GymTheme {
        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
          ProposalDraftForm(
              displayed,
              listOf("bench" to "Жим лёжа"),
              emptyList(),
              { displayed = it },
              {},
          )
        }
      }
    }
    compose
        .onNodeWithText("Длительность подхода 1, сек")
        .performScrollTo()
        .performTextReplacement("")
    compose
        .onNodeWithText("Длительность подхода 1, сек")
        .assertExists()
        .performTextReplacement("90")
    compose.onNodeWithText("Вес подхода 1, кг").assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp-xhdpi")
  fun `replacement searches only eligible exercises cancels cleanly and adapts typed sets at large font`() {
    var latest = draft()
    var applyCalls = 0
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        var value by remember { mutableStateOf(draft()) }
        GymTheme {
          TrainingProposalDetailContent(
              proposal(),
              value,
              false,
              false,
              null,
              listOf(
                  "bench" to "Жим лёжа",
                  "plank" to "Планка",
                  "blocked" to "Недоступный тренажёр",
              ) + (1..500).map { "id-$it" to "Упражнение $it" },
              emptyList(),
              {
                latest = it
                value = it
              },
              { applyCalls++ },
              {},
              {},
              {},
              exerciseTypes =
                  mapOf("bench" to ExerciseType.STRENGTH, "plank" to ExerciseType.TIMED),
              availableExerciseIds = setOf("bench", "plank"),
          )
        }
      }
    }
    compose.onNodeWithText("Упражнение 500").assertDoesNotExist()
    compose.onNodeWithText("Изменить план").performScrollTo().performClick()
    compose.onNodeWithText("Часовой пояс").assertDoesNotExist()
    compose.onNodeWithText("Планка").assertDoesNotExist()
    compose.onNodeWithText("Заменить").performScrollTo().performClick()
    compose.onNodeWithText("Недоступный тренажёр").assertDoesNotExist()
    compose.onNodeWithText("Поиск").performTextReplacement("нет совпадений")
    compose.onNodeWithText("Ничего не найдено. Измените поиск или фильтр.").assertExists()
    compose.onNodeWithText("Готово").performClick()
    compose.runOnIdle { assertEquals(draft(), latest) }
    compose.onNodeWithText("Заменить").performScrollTo().performClick()
    compose.onNodeWithText("Поиск").performTextReplacement("План")
    compose.onNodeWithText("Планка").performClick()
    compose.onNodeWithText("Вес подхода 1, кг").assertDoesNotExist()
    compose
        .onNodeWithText("Длительность подхода 1, сек")
        .performScrollTo()
        .performTextReplacement("90")
    compose.runOnIdle {
      assertEquals("plank", latest.exercises.single().exerciseId)
      assertEquals(90, latest.exercises.single().plannedSets.single().durationSec)
      assertNull(latest.exercises.single().plannedSets.single().weightKg)
      assertNull(latest.exercises.single().plannedSets.single().reps)
      assertEquals(0, applyCalls)
    }
  }

  @Test
  fun `expanded font scale two keeps editable proposal actions reachable`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          TrainingProposalDetailContent(
              proposal = proposal(),
              draft = draft(),
              saving = false,
              applied = false,
              error = null,
              exerciseChoices = listOf("bench" to "Жим лёжа"),
              gymChoices = listOf("gym" to "Дом"),
              onDraftChange = {},
              onApply = {},
              onReject = {},
              onBack = {},
              onRetry = {},
          )
        }
      }
    }

    compose.onNodeWithText("План тренировки").assertIsDisplayed()
    compose
        .onNodeWithContentDescription("Применить предложение")
        .performScrollTo()
        .assertIsEnabled()
    compose
        .onNodeWithContentDescription("Отклонить предложение")
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun `approved proposal exposes result recovery without editing or rejection`() {
    var recoverCalls = 0
    compose.setContent {
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(status = ProposalStatus.APPROVED),
            draft = draft(),
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = emptyList(),
            onDraftChange = {},
            onApply = { recoverCalls++ },
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose
        .onNodeWithContentDescription("Загрузить результат")
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    compose
        .onNodeWithText("Этот вариант больше нельзя редактировать.")
        .performScrollTo()
        .assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, recoverCalls) }
  }

  @Test
  fun `inbox exposes loading error retry and proposal callback`() {
    var retries = 0
    var opened: String? = null
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            items = listOf(proposal()),
            loading = true,
            error = "Не удалось обновить",
            hasMore = true,
            onRefresh = { retries++ },
            onMore = {},
            onOpen = { opened = it },
            onBack = {},
        )
      }
    }

    compose.onNodeWithContentDescription("Загружаем предложения").assertIsDisplayed()
    compose.onNodeWithText("Не удалось обновить").assertIsDisplayed()
    compose.onNodeWithText("Повторить").performClick()
    compose.onNodeWithText("Силовой план").performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals("proposal-1", opened)
    }
  }

  @Test
  fun `detail keeps retry reachable when loading fails before a proposal arrives`() {
    var retries = 0
    compose.setContent {
      GymTheme {
        TrainingProposalDetailContent(
            proposal = null,
            draft = null,
            saving = false,
            applied = false,
            error = "Предложение не загружено",
            exerciseChoices = emptyList(),
            gymChoices = emptyList(),
            onDraftChange = {},
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = { retries++ },
        )
      }
    }

    compose.onNodeWithText("Предложение не загружено").assertIsDisplayed()
    compose.onNodeWithText("Повторить").performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun `gym choice deselects instead of duplicating its id`() {
    var latestDraft = draft()
    compose.setContent {
      var displayedDraft by mutableStateOf(draft())
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = displayedDraft,
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = listOf("gym" to "Дом"),
            onDraftChange = {
              latestDraft = it
              displayedDraft = it
            },
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Изменить план").performScrollTo().performClick()
    compose.onNodeWithText("Дом").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(emptyList<String>(), latestDraft.gymIds) }
  }

  @Test
  fun `removing invalid set clears its transient error and restores apply`() {
    var latestDraft = twoSetDraft()
    compose.setContent {
      var displayedDraft by mutableStateOf(twoSetDraft())
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = displayedDraft,
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = emptyList(),
            onDraftChange = {
              latestDraft = it
              displayedDraft = it
            },
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Изменить план").performScrollTo().performClick()
    compose.onNodeWithText("Вес подхода 2, кг").performScrollTo().performTextReplacement("не число")
    compose.onAllNodesWithText("Удалить подход")[1].performScrollTo().performClick()

    compose
        .onNodeWithContentDescription("Применить предложение")
        .performScrollTo()
        .assertIsEnabled()
    compose.runOnIdle { assertEquals(1, latestDraft.exercises.first().plannedSets.size) }
  }
}

private fun draft() =
    ApprovalDraft(
        name = "Силовой план",
        gymIds = listOf("gym"),
        exercises =
            listOf(
                ProposalPlannedExercise(
                    exerciseId = "bench",
                    restSeconds = 90,
                    plannedSets = ProposalPlannedSet(60.0, 8, null, null, null).let(::listOf),
                )
            ),
        startsAtMillis = 1_800_000_000_000L,
        timeZoneId = "Europe/Moscow",
    )

private fun proposal(status: ProposalStatus = ProposalStatus.PENDING) =
    TrainingProposal(
        proposalId = "proposal-1",
        author = ProposalAuthor(ProposalSource.AI, null),
        recipientId = "recipient-1",
        source = ProposalSource.AI,
        status = status,
        currentVersion = 3,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        expiresAt = Long.MAX_VALUE,
        snapshot = ProposalSnapshot(3, draft(), 4L, 9L, 1_700_000_000_000L),
    )

private fun twoSetDraft() =
    draft()
        .copy(
            exercises =
                draft().exercises.map { exercise ->
                  exercise.copy(
                      plannedSets =
                          exercise.plannedSets + ProposalPlannedSet(65.0, 7, null, null, null)
                  )
                }
        )
