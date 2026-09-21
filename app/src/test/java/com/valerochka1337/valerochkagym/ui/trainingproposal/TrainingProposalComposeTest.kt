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
            preparation = preparation.value,
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
  fun `stopped calculation leaves AI action reachable without progress at large font`() {
    var create = 0
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
              onCreateAi = { create++ },
              preparation = preparation.value,
          )
        }
      }
    }
    compose.onNodeWithContentDescription("Расчёт предложения").assertIsNotEnabled()
    compose
        .onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        )
        .assertCountEquals(0)
    compose.onNodeWithText("Пока нет предложений").assertDoesNotExist()
    compose.onNodeWithText("Составить с ИИ").performClick()
    compose.runOnIdle { assertEquals(1, create) }
    compose.runOnIdle { preparation.value = preparation.value.copy(state = "SUPERSEDED") }
    compose.onNodeWithContentDescription("Расчёт предложения").assertIsNotEnabled()
    compose
        .onNodeWithText("Расчёт заменён другим запросом. При необходимости составьте план ещё раз.")
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
  fun `inbox has fixed AI action and confirms proposal deletion`() {
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
    compose.onNodeWithText("Составить с ИИ").performClick()
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
