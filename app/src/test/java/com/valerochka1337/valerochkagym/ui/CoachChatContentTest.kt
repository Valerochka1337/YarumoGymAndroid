package com.valerochka1337.valerochkagym.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.coach.*
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

abstract class CoachChatSemanticsBase {
  @get:Rule val compose = createComposeRule()

  private fun content(
      initial: CoachChatUiState,
      applied: (String) -> Unit = {},
      sent: (String) -> Unit = {},
      retried: (String) -> Unit = {},
      canceled: (String) -> Unit = {},
      declined: ((String, com.valerochka1337.valerochkagym.domain.CoachRejectionReason) -> Unit)? =
          null,
  ): androidx.compose.runtime.MutableState<CoachChatUiState> {
    val state = mutableStateOf(initial)
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme {
          CoachChatContent(
              state = state.value,
              onBack = {},
              onDraftChange = { state.value = state.value.copy(draft = it) },
              onSend = sent,
              onRetry = retried,
              onConfirm = { id ->
                applied(id)
                state.value = state.value.copy(busy = true, status = "Применяем изменения…")
              },
              onCancel = canceled,
              onCancelWithReason = declined,
              onUndo = {},
              onDisableInitiative = { state.value = state.value.copy(initiativeEnabled = false) },
          )
        }
      }
    }
    return state
  }

  @Test
  fun `opening chat reveals the first new message with an accessible new label`() {
    val messages =
        (0..30).map { index ->
          CoachChatMessage("message-$index", "assistant", "Сообщение $index", isNew = index == 10)
        }
    content(CoachChatUiState(messages = messages))
    compose
        .onNodeWithTag("coach-message:message-10")
        .assertIsDisplayed()
        .assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "Новое сообщение",
            )
        )
    compose.onNodeWithText("Тренер · Новое").assertIsDisplayed()
  }

  @Test
  fun `decline reason is selectable without applying a proposal at large font`() {
    var selected: com.valerochka1337.valerochkagym.domain.CoachRejectionReason? = null
    content(
        CoachChatUiState(proposal = CoachChatProposal("proposal", "50 кг", "47.5 кг")),
        declined = { _, reason -> selected = reason },
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText("Отклонить с причиной"))
    compose.onNodeWithText("Отклонить с причиной").performScrollTo().performClick()
    compose.onNodeWithText("Нет такого веса").performClick()
    assertEquals(
        com.valerochka1337.valerochkagym.domain.CoachRejectionReason.UNAVAILABLE_WEIGHT,
        selected,
    )
  }

  @Test
  fun `streaming hides repeated talkback text and promotes one stable message at large font`() {
    val chat =
        content(
            CoachChatUiState(
                messages =
                    listOf(
                        CoachChatMessage(
                            "answer",
                            "assistant",
                            "Накопленный ответ",
                            streaming = true,
                        )
                    ),
                busy = true,
                status = "Формируем ответ…",
            )
        )
    compose.onNodeWithTag("coach-status").assertDoesNotExist()
    compose.onNodeWithContentDescription("Тренер отвечает", useUnmergedTree = true).assertExists()
    compose.runOnIdle {
      chat.value =
          chat.value.copy(
              messages =
                  listOf(CoachChatMessage("answer", "assistant", "Накопленный ответ полностью")),
              busy = false,
              status = null,
          )
    }
    compose.onAllNodesWithTag("coach-message:answer").assertCountEquals(1)
    compose.onNodeWithText("Накопленный ответ полностью").assertIsDisplayed()
  }

  @Test
  fun `reading history disables following incoming text`() {
    val history = (1..40).map { CoachChatMessage("m$it", "user", "Сообщение $it") }
    val chat =
        content(
            CoachChatUiState(
                messages =
                    history + CoachChatMessage("answer", "assistant", "Начало", streaming = true),
                busy = true,
            )
        )
    compose.onNodeWithTag("coach-conversation").performScrollToIndex(0)
    compose.onNodeWithText("Сообщение 1").assertIsDisplayed()
    compose.runOnIdle {
      chat.value =
          chat.value.copy(
              messages =
                  history +
                      CoachChatMessage(
                          "answer",
                          "assistant",
                          "Начало и продолжение",
                          streaming = true,
                      )
          )
    }
    compose.waitForIdle()
    compose.onNodeWithText("Сообщение 1").assertIsDisplayed()
    compose.runOnIdle {
      chat.value =
          chat.value.copy(
              messages = history + CoachChatMessage("answer", "assistant", "Начало и продолжение"),
              busy = false,
          )
    }
    compose.onNodeWithText("Сообщение 1").assertIsDisplayed()
  }

  @Test
  fun `keyboard raises composer and keeps latest message visible without moving header`() {
    val keyboardHeight = mutableStateOf(0)
    compose.setContent {
      GymTheme {
        CoachChatContent(
            state =
                CoachChatUiState(
                    messages =
                        (1..40).map { CoachChatMessage("message-$it", "user", "Сообщение $it") }
                ),
            onBack = {},
            onDraftChange = {},
            onSend = {},
            onConfirm = {},
            onCancel = {},
            onUndo = {},
            onDisableInitiative = {},
            imeInsets = WindowInsets(bottom = keyboardHeight.value),
        )
      }
    }
    val header = compose.onNodeWithText("Live Coach").fetchSemanticsNode().boundsInRoot
    val input = compose.onNodeWithTag("coach-input").fetchSemanticsNode().boundsInRoot
    val conversation = compose.onNodeWithTag("coach-conversation").fetchSemanticsNode().boundsInRoot
    compose.onNodeWithTag("coach-input").performClick()
    // Exercise intermediate animation frames as well as the fully open keyboard.
    for (height in listOf(160, 320, 480)) {
      compose.runOnIdle { keyboardHeight.value = height }
      compose.waitForIdle()
      assertEquals(header, compose.onNodeWithText("Live Coach").fetchSemanticsNode().boundsInRoot)
      val raisedInput = compose.onNodeWithTag("coach-input").fetchSemanticsNode().boundsInRoot
      assertEquals(input.top - height, raisedInput.top, 1f)
      val viewport = compose.onNodeWithTag("coach-conversation").fetchSemanticsNode().boundsInRoot
      assertEquals(conversation.top, viewport.top, 1f)
      val last = compose.onNodeWithTag("coach-message:message-40").fetchSemanticsNode().boundsInRoot
      assertTrue(last.top >= viewport.top)
      assertTrue(last.bottom <= viewport.bottom)
      assertTrue(last.bottom <= raisedInput.top)
    }
    compose.runOnIdle { keyboardHeight.value = 0 }
    compose.waitForIdle()
    assertEquals(input, compose.onNodeWithTag("coach-input").fetchSemanticsNode().boundsInRoot)
    compose.onNodeWithText("Сообщение 40", substring = true).assertIsDisplayed()
  }

  @Test
  fun `failed answer retries AI turn without sending a user message at large font scale`() {
    val sent = mutableListOf<String>()
    val retried = mutableListOf<String>()
    content(
        CoachChatUiState(
            messages =
                listOf(
                    CoachChatMessage("user", "user", "Перенеси Хаммер"),
                    CoachChatMessage(
                        "error",
                        "assistant",
                        "Не удалось обработать запрос",
                        failed = true,
                    ),
                )
        ),
        sent = { sent += it },
        retried = { retried += it },
    )
    compose.onNodeWithText("Не удалось обработать запрос", substring = true).assertIsDisplayed()
    compose.onNodeWithContentDescription("Повторить запрос").assertIsDisplayed().performClick()
    assertEquals(emptyList<String>(), sent)
    assertEquals(listOf("error"), retried)
  }

  @Test
  fun `retry is disabled while another request runs`() {
    content(
        CoachChatUiState(
            busy = true,
            messages =
                listOf(
                    CoachChatMessage("user", "user", "Перенеси Хаммер"),
                    CoachChatMessage(
                        "error",
                        "assistant",
                        "Не удалось обработать запрос",
                        failed = true,
                    ),
                ),
        )
    )
    compose.onNodeWithContentDescription("Повторить запрос").assertIsNotEnabled()
  }

  @Test
  fun `historical errors and finished workouts do not offer retry`() {
    val user = CoachChatMessage("user", "user", "Перенеси Хаммер")
    val error =
        CoachChatMessage("error", "assistant", "Не удалось обработать запрос", failed = true)
    assertEquals(
        null,
        CoachChatUiState(messages = listOf(user, error), readOnly = true).retryText(error),
    )
    assertEquals(
        null,
        CoachChatUiState(messages = listOf(user, error, user.copy(id = "new"))).retryText(error),
    )
    assertEquals(null, CoachChatUiState(messages = listOf(error)).retryText(error))
  }

  @Test
  fun `opening a long conversation shows the last message`() {
    content(
        CoachChatUiState(
            messages = (1..40).map { CoachChatMessage("message-$it", "user", "Сообщение $it") }
        )
    )
    compose.onNodeWithText("Сообщение 40", substring = true).assertIsDisplayed()
  }

  @Test
  fun `processing is hidden on user messages while interrupted status remains visible`() {
    content(
        CoachChatUiState(
            messages =
                listOf(
                    CoachChatMessage("processing", "user", "Замени упражнение", "Обрабатывается"),
                    CoachChatMessage("interrupted", "user", "Добавь подход", "Запрос прерван"),
                )
        )
    )
    compose.onNodeWithText("Обрабатывается", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Запрос прерван", substring = true).assertExists()
  }

  @Test
  fun `read only history exposes transcript and no executable controls`() {
    content(
        CoachChatUiState(
            messages =
                listOf(CoachChatMessage("remote", "assistant", "Импортированное предложение")),
            proposal = CoachChatProposal("untrusted", "Было", "Станет"),
            readOnly = true,
            canUndo = true,
        )
    )
    compose.onNodeWithText("Импортированное предложение", substring = true).assertExists()
    compose.onNodeWithTag("coach-read-only").assertExists()
    listOf("coach-input", "coach-send", "coach-apply", "coach-cancel", "coach-undo", "coach-mute")
        .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
  }

  @Test
  fun `proposal exposes full comparison and disables a repeated confirmation`() {
    val applied = mutableListOf<String>()
    content(
        CoachChatUiState(
            proposal =
                CoachChatProposal(
                    "proposal-id",
                    "Жим, подход 3: 60 кг × 8",
                    "Жим, подход 3: 55 кг × 8",
                )
        ),
        applied::add,
    )
    compose.onNodeWithText("Было").assertExists()
    compose.onNodeWithText("Станет").assertExists()
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-apply"))
    compose
        .onNodeWithTag("coach-apply")
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
        .performClick()
    assertEquals(listOf("proposal-id"), applied)
    compose.onNodeWithTag("coach-apply").assertIsNotEnabled()
    compose
        .onNodeWithTag("coach-cancel")
        .assertIsNotEnabled()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun `structured proposal displays exact changes and applies the entire packet`() {
    val applied = mutableListOf<String>()
    content(
        CoachChatUiState(
            proposal =
                CoachChatProposal(
                    "structured",
                    "legacy before",
                    "legacy after",
                    com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview(
                        listOf(
                            com.valerochka1337.valerochkagym.domain.ApprovalAction(
                                "edit",
                                "Жим · подход 3",
                                listOf("Вес: 60 кг → 55 кг"),
                            ),
                            com.valerochka1337.valerochkagym.domain.ApprovalAction(
                                "move",
                                "Переместить тягу в начало тренировки",
                            ),
                        )
                    ),
                )
        ),
        applied = applied::add,
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-proposal"))
    compose.onNodeWithText("Изменение").assertExists()
    compose.onNodeWithTag("coach-proposal-action:0").performClick()
    compose.onNodeWithText("Жим · подход 3").assertExists()
    compose.onNodeWithText("Вес: 60 кг → 55 кг").assertExists()
    compose.onNodeWithText("legacy before").assertDoesNotExist()
    compose.onNodeWithTag("coach-apply").performScrollTo().assertIsEnabled().performClick()
    assertEquals(listOf("structured"), applied)
    compose.onNodeWithTag("coach-apply").assertIsNotEnabled()
  }

  @Test
  fun `empty structured proposal disables apply and still allows rejecting the packet`() {
    val canceled = mutableListOf<String>()
    content(
        CoachChatUiState(
            proposal =
                CoachChatProposal(
                    "empty",
                    "",
                    "",
                    com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview(emptyList()),
                )
        ),
        canceled = canceled::add,
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-proposal"))
    compose.onNodeWithTag("coach-apply").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag("coach-cancel").performScrollTo().performClick()
    assertEquals(listOf("empty"), canceled)
  }

  @Test
  fun `busy and failed states remain readable and suppress repeated send`() {
    content(
        CoachChatUiState(
            draft = "Добавь подход",
            busy = true,
            status = "Тренер отвечает…",
            error = "Нет подключения",
        )
    )
    compose.onNodeWithTag("coach-status").assertExists()
    compose.onNodeWithTag("coach-error").assertExists()
    compose.onNodeWithTag("coach-send").assertIsNotEnabled()
  }

  @Test
  fun `quick phrase sends immediately and preserves editable input at font scale two`() {
    val sent = mutableListOf<String>()
    content(CoachChatUiState(draft = "Мой черновик"), sent = sent::add)
    compose.onNodeWithText("Добавь подход").assertHeightIsAtLeast(48.dp).performClick()
    assertEquals(listOf("Добавь подход"), sent)
    compose.onNodeWithTag("coach-input").assertTextContains("Мой черновик")
    compose.onNodeWithText("Осталось 20 минут").assertDoesNotExist()
    compose.onNodeWithText("Увеличь отдых").assertDoesNotExist()
  }

  @Test
  fun `contextual reply sends model text without bringing back initial phrases`() {
    val sent = mutableListOf<String>()
    content(
        CoachChatUiState(
            messages =
                listOf(
                    CoachChatMessage("u", "user", "Нужна замена"),
                    CoachChatMessage(
                        "a",
                        "assistant",
                        "Заменить жим на отжимания?",
                        quickReplies = listOf("Да, замени на отжимания", "Оставим жим"),
                    ),
                )
        ),
        sent = sent::add,
    )
    compose.onNodeWithText("Оставим жим").performClick()
    assertEquals(listOf("Оставим жим"), sent)
    compose.onNodeWithText("Тренажёр занят").assertDoesNotExist()
    compose.onNodeWithText("Добавь подход").assertDoesNotExist()
  }

  @Test
  fun `busy contextual replies are disabled`() {
    content(
        CoachChatUiState(
            busy = true,
            messages =
                listOf(
                    CoachChatMessage(
                        "a",
                        "assistant",
                        "Заменить?",
                        quickReplies = listOf("Да, замени"),
                    ),
                ),
        )
    )
    compose.onNodeWithText("Да, замени").assertIsNotEnabled()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class CoachChatContentTest : CoachChatSemanticsBase()

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1200dp-h900dp-xhdpi")
class ExpandedCoachChatContentTest : CoachChatSemanticsBase()

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w720dp-h900dp-xhdpi")
class MediumCoachChatContentTest : CoachChatSemanticsBase()
