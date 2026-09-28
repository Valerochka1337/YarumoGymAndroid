package com.valerochka1337.valerochkagym.ui.trainingproposal

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.ai.PreparationEntity
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h900dp-xhdpi")
class TrainingProposalComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `calculation is disabled and becomes an openable proposal when ready`() {
    val preparation = mutableStateOf(PreparationEntity("owner", "request", "{}", "[]"))
    val ready = proposal()
    var opened: String? = null
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            if (preparation.value.state == "READY") listOf(ready) else emptyList(),
            false,
            null,
            false,
            {},
            {},
            { opened = it },
            {},
            onCreateAi = {},
            preparations = listOf(preparation.value),
        )
      }
    }
    compose
        .onNodeWithContentDescription("Расчёт предложения")
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    compose.onNodeWithText("Пока нет предложений").assertDoesNotExist()
    compose.onNodeWithText("Ожидаем отправки и синхронизации…").assertIsDisplayed()
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "RUNNING") }
    compose.onNodeWithText("Составляем тренировку…").assertIsDisplayed()
    compose
        .onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        )
        .assertCountEquals(1)
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "READY") }
    compose.onNodeWithContentDescription("Расчёт предложения").assertDoesNotExist()
    compose.onNodeWithText(ready.snapshot.draft.name).performClick()
    compose.runOnIdle { assertEquals(ready.proposalId, opened) }
  }

  @Test
  fun `terminal calculations remain visible and retry once at large font`() {
    val retrying = mutableStateOf(false)
    val retryError = mutableStateOf<String?>(null)
    val retried = mutableListOf<String>()
    val preparation =
        mutableStateOf(PreparationEntity("owner", "request", "{}", "[]", state = "FAILED"))
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
              preparations = listOf(preparation.value),
              onRetryPreparation = { retried += it },
              retryingPreparationId = if (retrying.value) "request" else null,
              preparationRetryError = retryError.value,
              preparationRetryErrorId = if (retryError.value != null) "request" else null,
          )
        }
      }
    }
    compose.onNodeWithContentDescription("Расчёт предложения").assertIsDisplayed()
    compose.onNodeWithText("Не удалось завершить расчёт.").assertIsDisplayed()
    compose.onNodeWithText("Повторить").performClick()
    compose.runOnIdle { assertEquals(listOf("request"), retried) }
    compose.runOnIdle { retrying.value = true }
    compose.onNodeWithText("Повторяем…").assertIsNotEnabled()
    compose.runOnIdle {
      retrying.value = false
      retryError.value = "Не удалось повторить расчёт"
      preparation.value = preparation.value.copy(state = "PAUSED_WAITING")
    }
    compose.onNodeWithText("Расчёт приостановлен.").assertIsDisplayed()
    compose.onNodeWithText("Не удалось повторить расчёт").assertIsDisplayed()
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "STALE") }
    compose.onNodeWithText("Условия расчёта устарели. Составьте новый план.").assertIsDisplayed()
    compose.onNodeWithText("Повторить расчёт").assertIsDisplayed()
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "SUPERSEDED") }
    compose.onNodeWithText("Расчёт заменён другим запросом.").assertIsDisplayed()
    compose.onNodeWithText("Повторить расчёт").assertIsDisplayed()
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "EXPIRED") }
    compose.onNodeWithText("Условия расчёта устарели. Составьте новый план.").assertIsDisplayed()
    compose.onNodeWithText("Повторить расчёт").assertDoesNotExist()
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "IMPOSSIBLE") }
    compose
        .onNodeWithText(
            "С этими условиями готовый план не найден. Измените условия или отредактируйте тренировку вручную."
        )
        .assertIsDisplayed()
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "UPDATE_REQUIRED") }
    compose
        .onNodeWithText("Для планирования нужна более новая версия приложения.")
        .assertIsDisplayed()
    compose.runOnIdle {
      preparation.value = preparation.value.copy(state = "FAILED", errorCode = "ai_busy")
    }
    compose
        .onNodeWithText("Сервис планирования временно занят. Попробуйте позже. Код: ai_busy")
        .assertIsDisplayed()
    compose
        .onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        )
        .assertCountEquals(0)
  }

  @Test
  fun `retry lineage hides every replaced ancestor while ready remains distinct`() {
    val retry2 = mutableStateOf(PreparationEntity("owner", "retry-2", "{}", "[\"retry-1\"]"))
    val proposals = mutableStateOf(emptyList<TrainingProposal>())
    var opened: String? = null
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            items = proposals.value,
            loading = false,
            error = null,
            hasMore = false,
            onRetry = {},
            onMore = {},
            onOpen = { opened = it },
            onBack = {},
            preparations =
                listOf(
                    PreparationEntity("owner", "old", "{}", "[]", state = "FAILED"),
                    PreparationEntity("owner", "retry-1", "{}", "[\"old\"]", state = "FAILED"),
                    retry2.value,
                ),
        )
      }
    }
    compose.onAllNodesWithContentDescription("Расчёт предложения").assertCountEquals(1)
    compose.onNodeWithText("Не удалось завершить расчёт.").assertDoesNotExist()
    compose.runOnIdle {
      retry2.value = retry2.value.copy(state = "READY")
      proposals.value = listOf(proposal())
    }
    compose.onAllNodesWithContentDescription("Расчёт предложения").assertCountEquals(0)
    compose.onNodeWithText("Силовой план").performClick()
    compose.runOnIdle { assertEquals("proposal-1", opened) }
  }

  @Test
  fun `retry feedback belongs only to the selected calculation`() {
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            items = emptyList(),
            loading = false,
            error = null,
            hasMore = false,
            onRetry = {},
            onMore = {},
            onOpen = {},
            onBack = {},
            preparations =
                listOf(
                    PreparationEntity("owner", "first", "{}", "[]", state = "FAILED"),
                    PreparationEntity("owner", "second", "{}", "[]", state = "STALE"),
                ),
            retryingPreparationId = "second",
            preparationRetryError = "Не удалось повторить расчёт",
            preparationRetryErrorId = "second",
        )
      }
    }
    compose.onNodeWithText("Повторяем…").assertIsDisplayed().assertIsNotEnabled()
    compose.onNodeWithText("Не удалось повторить расчёт").assertIsDisplayed()
    compose.onNodeWithText("Повторить").assertIsNotEnabled()
    compose.onAllNodesWithText("Не удалось повторить расчёт").assertCountEquals(1)
  }

  @Test
  fun `each active calculation has its own card and ready leaves the other active`() {
    val first = mutableStateOf(PreparationEntity("owner", "request-1", "{}", "[]"))
    val second =
        mutableStateOf(PreparationEntity("owner", "request-2", "{}", "[]", state = "RUNNING"))
    val proposals = mutableStateOf(emptyList<TrainingProposal>())
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            items = proposals.value,
            loading = false,
            error = null,
            hasMore = false,
            onRetry = {},
            onMore = {},
            onOpen = {},
            onBack = {},
            preparations = listOf(first.value, second.value),
        )
      }
    }
    compose.onAllNodesWithContentDescription("Расчёт предложения").assertCountEquals(2)
    compose.runOnIdle {
      first.value = first.value.copy(state = "READY")
      proposals.value = listOf(proposal())
    }
    compose.onAllNodesWithContentDescription("Расчёт предложения").assertCountEquals(1)
    compose.onNodeWithText("Силовой план").assertIsDisplayed()
  }

  @Test
  fun `inbox has planner action and confirms proposal deletion`() {
    var create = 0
    var deleted: String? = null
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            listOf(proposal()),
            false,
            null,
            false,
            {},
            {},
            {},
            {},
            onCreateAi = { create++ },
            onDelete = { deleted = it.proposalId },
        )
      }
    }
    compose.onNodeWithText("Новая тренировка").assertDoesNotExist()
    compose.onNodeWithText("Составить тренировку").performClick()
    compose.onNodeWithContentDescription("Меню предложения").performClick()
    compose.onNodeWithText("Удалить").performClick()
    compose.onNodeWithText("Удалить предложение?").assertIsDisplayed()
    compose.onNodeWithText("Удалить").performClick()
    compose.runOnIdle {
      assertEquals(1, create)
      assertEquals("proposal-1", deleted)
    }
  }

  @Test
  fun `proposal hides statuses and shared preview expands`() {
    compose.setContent { GymTheme { detail(proposal(status = ProposalStatus.REJECTED)) } }
    compose.onNodeWithText("Версия 3").assertDoesNotExist()
    compose.onNodeWithText("Отклонить").assertDoesNotExist()
    compose.onNodeWithText("Жим лёжа").performScrollTo().performClick()
    compose.onNodeWithText("Отдых: 90 сек").assertIsDisplayed()
    compose.onNodeWithText("Изменить план").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `conflict disables apply and leaves repeatable save available`() {
    compose.setContent { GymTheme { detail(proposal(), conflict = true, save = {}) } }
    compose
        .onNodeWithContentDescription("Применить предложение")
        .performScrollTo()
        .assertIsNotEnabled()
    compose.onNodeWithText("Тренировка на это время уже запланирована").assertIsDisplayed()
    compose.onNodeWithText("Сохранить в тренировки").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `large font keeps proposal actions reachable`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme { detail(proposal(), save = {}) }
      }
    }
    compose
        .onNodeWithContentDescription("Применить предложение")
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Сохранить в тренировки").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `rule based refinement exposes typed replacement at large font`() {
    var replacement: Pair<String, String>? = null
    val ruleProposal =
        proposal()
            .copy(
                author = ProposalAuthor(ProposalSource.RULE_BASED, null),
                source = ProposalSource.RULE_BASED,
            )
    val explanation =
        PlannerExplanation(
            ruleProposal.proposalId,
            ruleProposal.currentVersion,
            "planner-duration-v2",
            60,
            1,
            1,
            emptyList(),
            emptyList(),
            null,
            1,
            "RULE_BASED",
            "NONE",
            "NONE",
            RuleBasedPlannerDetails(
                "deterministic-planner-v2",
                2,
                "fingerprint",
                "STRENGTH",
                null,
                false,
                1,
                1,
                emptyList(),
                emptyList(),
                listOf(RuleBasedSlotSelection("upper-push", "upper-push-1", "bench")),
            ),
        )
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme {
          TrainingProposalDetailContent(
              proposal = ruleProposal,
              draft = ruleProposal.snapshot.draft,
              saving = false,
              applied = false,
              error = null,
              exerciseChoices = listOf("bench" to "Жим лёжа", "row" to "Тяга"),
              gymChoices = emptyList(),
              onDraftChange = {},
              onApply = {},
              onReject = {},
              onBack = {},
              onRetry = {},
              explanation = explanation,
              availableExerciseIds = setOf("bench", "row"),
              onReplaceRefinementSelection = { selectionId, exerciseId ->
                replacement = selectionId to exerciseId
              },
          )
        }
      }
    }
    compose.onNodeWithText("Заменить в слотах").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("upper-push: Жим лёжа").performScrollTo().performClick()
    compose.onNodeWithText("Тяга").performClick()
    compose.runOnIdle { assertEquals("upper-push-1" to "row", replacement) }
  }

  @androidx.compose.runtime.Composable
  private fun detail(
      proposal: TrainingProposal,
      conflict: Boolean = false,
      save: (() -> Unit)? = null,
  ) =
      TrainingProposalDetailContent(
          proposal,
          proposal.snapshot.draft,
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
          onSaveCopy = save,
          scheduleConflict = conflict,
      )

  private fun draft() =
      ApprovalDraft(
          "Силовой план",
          emptyList(),
          listOf(
              ProposalPlannedExercise(
                  "bench",
                  90,
                  listOf(ProposalPlannedSet(60.0, 8, null, null, null)),
              )
          ),
          System.currentTimeMillis() + 60_000,
          "Europe/Moscow",
      )

  private fun proposal(status: ProposalStatus = ProposalStatus.PENDING): TrainingProposal {
    val draft = draft()
    return TrainingProposal(
        "proposal-1",
        ProposalAuthor(ProposalSource.AI, "coach"),
        "user",
        ProposalSource.AI,
        status,
        3,
        1,
        1,
        1,
        ProposalSnapshot(3, draft, 1, 1, 1),
    )
  }
}
