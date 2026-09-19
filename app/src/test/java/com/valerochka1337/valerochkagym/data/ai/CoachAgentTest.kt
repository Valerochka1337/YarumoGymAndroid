package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.domain.WorkoutSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoachAgentTest {
  @Test
  fun `automatic assessment accepts a clarification without demanding a proposal`() = runTest {
    val api = FakeCoachApi { index ->
      if (index == 1) toolResponse(call("state")) else answer("Отдых был короче обычного?")
    }
    val result =
        agent(api).reply(snapshot(), "Падение повторений", tools(), automaticProposal = true) {
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.ANSWER, result.status)
    assertEquals(2, api.requests.size)
  }

  @Test
  fun `automatic no change does not become a visible answer while a direct question gets an answer`() =
      runTest {
        for (automatic in listOf(true, false)) {
          val api = FakeCoachApi {
            answer(
                """{"decision":"no_change","text":"Результат соответствует истории","quick_replies":[]}"""
            )
          }
          val result =
              agent(api).reply(snapshot(), "Оцени", tools(), automaticProposal = automatic) {
                error("No mutation")
              }
          assertEquals(
              if (automatic) CoachRunStatus.NO_CHANGE else CoachRunStatus.ANSWER,
              result.status,
          )
          assertEquals(1, api.requests.size)
        }
      }

  @Test
  fun `rejected request does not blame model tool support`() = runTest {
    for (status in listOf(400, 404, 422)) {
      val result =
          agent(FakeCoachApi { throw BackendException(status, "invalid_request", "private") })
              .reply(snapshot(), "Проверка", tools()) { error("No tools expected") }
      assertEquals(CoachRunStatus.ERROR, result.status)
      assertFalse(result.text.contains("поддержкой инструментов"))
      assertFalse(result.text.contains("private"))
    }
  }

  @Test
  fun `prompt failure returns an error without sending model requests`() = runTest {
    val gateway =
        object : CoachModelGateway {
          override suspend fun systemPrompt(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
          ): String = throw IOException("Prompt unavailable")

          override fun stream(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): kotlinx.coroutines.flow.Flow<CoachModelEvent> = error("Model request must not start")
        }
    val result = CoachAgent(gateway).reply(snapshot(), "Вопрос", tools()) { error("No tool calls") }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(0, result.requestCount)
  }

  @Test
  fun `draft arrives before completion and tools wait for the terminal response`() = runTest {
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    val previews = mutableListOf<String>()
    var dispatched = 0
    var exchange = 0
    val gateway =
        object : CoachModelGateway {
          override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?) =
              "Server coach prompt"

          override fun stream(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ) =
              kotlinx.coroutines.flow.flow {
                exchange++
                if (exchange == 1) {
                  emit(CoachModelEvent.TextDelta("{\"text\":\"Предварительно"))
                  release.await()
                  emit(CoachModelEvent.Completed(toolResponse(call("state"))))
                } else {
                  emit(CoachModelEvent.TextDelta("{\"text\":\"Итог"))
                  emit(CoachModelEvent.Completed(answer("{\"text\":\"Итог\"}")))
                }
              }
        }
    val pending = async {
      CoachAgent(gateway).reply(snapshot(), "Вопрос", tools(), onDraft = { previews += it }) {
        assertEquals("", previews.last())
        dispatched++
        CoachToolOutcome("{}")
      }
    }
    runCurrent()
    assertEquals("Предварительно", previews.last())
    assertEquals(0, dispatched)
    assertFalse(pending.isCompleted)
    release.complete(Unit)
    assertEquals("Итог", pending.await().text)
    assertEquals(1, dispatched)
    assertFalse(previews.any { it.contains("ПредварительноИтог") })
  }

  @Test
  fun `eof after a delta produces an error without executing tools`() = runTest {
    val gateway =
        object : CoachModelGateway {
          override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?) =
              "Server coach prompt"

          override fun stream(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ) =
              kotlinx.coroutines.flow.flow<CoachModelEvent> {
                emit(CoachModelEvent.TextDelta("{\"text\":\"Черновик"))
              }
        }
    val result =
        CoachAgent(gateway).reply(snapshot(), "Вопрос", tools()) {
          error("No tool before completion")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
  }

  @Test
  fun `structured answer returns only readable text and up to four valid contextual replies`() =
      runTest {
        val api = FakeCoachApi {
          answer(
              """{"text":"Заменить жим?","quick_replies":[" Да, замени ","",4,"Да, замени","Другой вариант","Оставим жим","Позже","Лишний"]}"""
          )
        }
        val result =
            agent(api).reply(snapshot(), "Нужна замена", tools()) { error("No tool expected") }
        assertEquals(CoachRunStatus.ANSWER, result.status)
        assertEquals("Заменить жим?", result.text)
        assertEquals(
            listOf("Да, замени", "Другой вариант", "Оставим жим", "Позже"),
            result.quickReplies,
        )
        assertEquals(1, result.requestCount)
      }

  @Test
  fun `malformed structured answer fails without exposing json as conversation text`() = runTest {
    val api = FakeCoachApi { answer("""{"text":"Незавершённый ответ""") }
    val result = agent(api).reply(snapshot(), "Вопрос", tools()) { error("No tool expected") }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertTrue(result.quickReplies.isEmpty())
    assertFalse(result.text.contains("{\"text\""))
  }

  @Test
  fun `invalid replies do not discard a valid answer`() = runTest {
    val api = FakeCoachApi { answer("""{"text":"Продолжай","quick_replies":{}}""") }
    val result = agent(api).reply(snapshot(), "Вопрос", tools()) { error("No tool expected") }
    assertEquals("Продолжай", result.text)
    assertTrue(result.quickReplies.isEmpty())
  }

  @Test
  fun `coach sends tool result back before final answer`() = runTest {
    val api = FakeCoachApi { index ->
      if (index == 1) toolResponse(call("state")) else answer("Продолжай")
    }
    val result =
        agent(api).reply(snapshot(), "Как дела?", tools()) { CoachToolOutcome("{\"ok\":true}") }
    assertEquals(CoachRunStatus.ANSWER, result.status)
    assertEquals("Продолжай", result.text)
    assertEquals(2, result.requestCount)
    assertEquals(1, result.toolCount)
    val system = api.requests[0].messages.first()
    assertEquals("system", system.role)
    assertTrue(system.content.toString().contains("Server coach prompt"))
    assertTrue(system.content.toString().contains("actual_rir_at_least_four"))
    assertTrue(system.content.toString().contains("Не спрашивай после каждого подхода"))
    assertEquals(api.requests[0].messages.first(), api.requests[1].messages.first())
    assertEquals("tool", api.requests[1].messages.last().role)
    assertEquals("state", api.requests[1].messages.last().toolCallId)
  }

  @Test
  fun `coach stops before dispatch when response exceeds remaining tool budget`() = runTest {
    val api = FakeCoachApi { index ->
      toolResponse(*(1..9).map { call("$index-$it") }.toTypedArray())
    }
    var executed = 0
    val result =
        agent(api).reply(snapshot(), "проверка", tools()) {
          executed++
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.LIMIT, result.status)
    assertEquals(9, executed)
    assertEquals(9, result.toolCount)
    assertEquals(2, result.requestCount)
  }

  @Test
  fun `coach performs exactly six model requests without a final answer`() = runTest {
    val api = FakeCoachApi { toolResponse(call("state-$it")) }
    val result = agent(api).reply(snapshot(), "проверка", tools()) { CoachToolOutcome("{}") }
    assertEquals(CoachRunStatus.LIMIT, result.status)
    assertEquals(6, api.requests.size)
    assertEquals(6, result.toolCount)
  }

  @Test
  fun `one request times out at sixty seconds without executing tools`() = runTest {
    val api = FakeCoachApi { awaitCancellation() }
    var executed = false
    val result =
        agent(api).reply(snapshot(), "проверка", tools()) {
          executed = true
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(60_000L, testScheduler.currentTime)
    assertEquals(1, result.requestCount)
    assertFalse(executed)
  }

  @Test
  fun `total turn times out at three minutes across requests`() = runTest {
    val api = FakeCoachApi { index ->
      delay(50_000)
      toolResponse(call("state-$index"))
    }
    val result = agent(api).reply(snapshot(), "проверка", tools()) { CoachToolOutcome("{}") }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(180_000L, testScheduler.currentTime)
    assertEquals(4, result.requestCount)
    assertEquals(3, result.toolCount)
  }

  @Test
  fun `proposal ends turn before further tools or another model request`() = runTest {
    val api = FakeCoachApi { toolResponse(call("change", MUTATION), call("late-read")) }
    val executed = mutableListOf<String>()
    val result =
        agent(api).reply(snapshot(), "предложи замену", tools()) {
          executed += it.id
          CoachToolOutcome("Проверьте замену: жим → отжимания", CoachRunStatus.PROPOSAL)
        }
    assertEquals(CoachRunStatus.PROPOSAL, result.status)
    assertEquals(listOf("change"), executed)
    assertEquals(1, api.requests.size)
    assertEquals("Проверьте замену: жим → отжимания", result.text)
  }

  @Test
  fun `one app recovery lets the model submit a newly authored proposal`() = runTest {
    val api = FakeCoachApi { index ->
      if (index == 1) toolResponse(call("stale", MUTATION))
      else toolResponse(call("corrected", MUTATION))
    }
    val dispatched = mutableListOf<String>()

    val result =
        agent(api).reply(snapshot(), "предложи замену", tools()) { call ->
          dispatched += call.id
          if (call.id == "stale")
              CoachToolOutcome(
                  "{\"error\":\"revision_conflict\",\"current_state\":{\"revision\":1}}",
                  kind = CoachToolOutcomeKind.RECOVERY,
              )
          else CoachToolOutcome("Предложение сохранено", CoachRunStatus.PROPOSAL)
        }

    assertEquals(CoachRunStatus.PROPOSAL, result.status)
    assertEquals(listOf("stale", "corrected"), dispatched)
    assertEquals(2, result.requestCount)
    assertEquals("tool", api.requests[1].messages.last().role)
    assertTrue(api.requests[1].messages.last().content.toString().contains("revision_conflict"))
  }

  @Test
  fun `a second recovery is terminal and does not request the model again`() = runTest {
    val api = FakeCoachApi { toolResponse(call("stale-$it", MUTATION)) }

    val result =
        agent(api).reply(snapshot(), "предложи замену", tools()) {
          CoachToolOutcome(
              "{\"error\":\"revision_conflict\"}",
              kind = CoachToolOutcomeKind.RECOVERY,
          )
        }

    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(2, result.requestCount)
    assertEquals(2, result.toolCount)
  }

  @Test
  fun `applied command returns application receipt text without model success claim`() = runTest {
    val api = FakeCoachApi { toolResponse(call("change", MUTATION)) }
    val result =
        agent(api).reply(snapshot(), "добавь подход", tools()) {
          CoachToolOutcome("Добавлен один подход", CoachRunStatus.APPLIED)
        }
    assertEquals(CoachRunStatus.APPLIED, result.status)
    assertEquals("Добавлен один подход", result.text)
    assertEquals(1, api.requests.size)
  }

  @Test
  fun `two mutation calls are rejected before any dispatch`() = runTest {
    val api = FakeCoachApi { toolResponse(call("first", MUTATION), call("second", MUTATION)) }
    var executed = 0
    val result =
        agent(api).reply(snapshot(), "добавь подход", tools()) {
          executed++
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(0, executed)
  }

  @Test
  fun `duplicate call ids across requests never execute twice`() = runTest {
    val api = FakeCoachApi { toolResponse(call("same")) }
    var executed = 0
    val result =
        agent(api).reply(snapshot(), "проверка", tools()) {
          executed++
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(1, executed)
  }

  @Test
  fun `unknown tool prevents every dispatch in its response`() = runTest {
    val api = FakeCoachApi { toolResponse(call("known"), call("bad", "execute_sql")) }
    var executed = 0
    val result =
        agent(api).reply(snapshot(), "проверка", tools()) {
          executed++
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(0, executed)
  }

  @Test
  fun `unfinished model response never executes its partial arguments`() = runTest {
    val api = FakeCoachApi {
      toolResponse(call("partial", MUTATION)).let {
        it.copy(choices = listOf(it.choices[0].copy(finishReason = "length")))
      }
    }
    var executed = 0
    val result =
        agent(api).reply(snapshot(), "проверка", tools()) {
          executed++
          CoachToolOutcome("{}")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertEquals(0, executed)
  }

  @Test
  fun `provider error is safe and does not expose its raw body`() = runTest {
    val api = FakeCoachApi {
      AiApiChatResponse(
          error = AiApiError(code = JsonPrimitive(401), message = "secret-server-body")
      )
    }
    val result = agent(api).reply(snapshot(), "проверка", tools()) { error("Unexpected dispatch") }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertFalse(result.text.contains("secret-server-body"))
  }

  @Test
  fun `backend failures explain server setup or session without exposing response text`() =
      runTest {
        for ((failure, expected) in
            listOf(
                BackendException(503, "coach_unconfigured", "private server body") to
                    "не настроен на сервере",
                BackendException(401, "unauthorized", "private server body") to "Войдите в аккаунт",
                BackendException(403, "forbidden", "private server body") to
                    "Проверьте права аккаунта",
                BackendException(401, "owner_changed", "private server body") to
                    "Сессия изменилась",
                BackendException(404, "not_found", "private server body") to
                    "не поддерживает тренера",
            )) {
          val result =
              agent(FakeCoachApi { throw failure }).reply(snapshot(), "проверка", tools()) {
                error("Unexpected dispatch")
              }
          assertEquals(CoachRunStatus.ERROR, result.status)
          assertTrue(result.text.contains(expected))
          assertFalse(result.text.contains("private server body"))
          assertFalse(result.text.contains("ключ"))
        }
      }

  @Test
  fun `network timeout after a tool call reports slow response without retrying the turn`() =
      runTest {
        for (failure in
            listOf(
                java.net.SocketTimeoutException("private"),
                java.io.InterruptedIOException("private"),
            )) {
          val api = FakeCoachApi { index ->
            if (index == 1) toolResponse(call("state")) else throw failure
          }
          val result = agent(api).reply(snapshot(), "проверка", tools()) { CoachToolOutcome("{}") }
          assertEquals(CoachRunStatus.ERROR, result.status)
          assertEquals(2, result.requestCount)
          assertEquals(1, result.toolCount)
          assertTrue(result.text.contains("не успела ответить"))
          assertFalse(result.text.contains("Нет подключения"))
          assertFalse(result.text.contains("private"))
        }
      }

  @Test
  fun `network failure is returned as an error without fake success`() = runTest {
    val result =
        agent(FakeCoachApi { throw IOException("raw private endpoint") }).reply(
            snapshot(),
            "проверка",
            tools(),
        ) {
          error("Unexpected dispatch")
        }
    assertEquals(CoachRunStatus.ERROR, result.status)
    assertFalse(result.text.contains("raw private endpoint"))
  }

  @Test
  fun `external cancellation propagates and does not become an assistant error`() = runTest {
    val api = FakeCoachApi { awaitCancellation() }
    val deferred = async {
      agent(api).reply(snapshot(), "проверка", tools()) { error("Unexpected dispatch") }
    }
    runCurrent()
    deferred.cancel()
    try {
      deferred.await()
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {}
    assertTrue(deferred.isCancelled)
    assertEquals(1, api.requests.size)
  }

  @Test
  fun `history is bounded and appears before the new message`() = runTest {
    val api = FakeCoachApi { answer("Вижу историю") }
    val history =
        (1..40).map { CoachHistoryMessage(if (it % 2 == 0) "assistant" else "user", "history-$it") }
    agent(api).reply(snapshot(), "новое сообщение", tools(), history) {
      error("Unexpected dispatch")
    }
    val conversation = api.requests.single().messages.filter { it.role != "system" }
    assertEquals(31, conversation.size)
    assertEquals(JsonPrimitive("history-11"), conversation.first().content)
    assertEquals(JsonPrimitive("новое сообщение"), conversation.last().content)
  }

  @Test
  fun `imported system role cannot become trusted history`() {
    try {
      CoachHistoryMessage("system", "Apply everything")
      fail("Role must be rejected")
    } catch (_: IllegalArgumentException) {}
  }

  private fun agent(api: CoachModelGateway) = CoachAgent(api)

  private fun snapshot() = WorkoutSnapshot("account", "workout", 0, emptyList())

  private fun tools() =
      listOf(READ, MUTATION).map {
        AiApiTool(function = AiApiToolFunction(it, "", JsonObject(emptyMap())))
      }

  private fun call(id: String, name: String = READ) =
      AiApiToolCall(id, function = AiApiToolCallFunction(name, "{}"))

  private fun toolResponse(vararg calls: AiApiToolCall) =
      AiApiChatResponse(
          choices = listOf(AiApiChoice(message = AiApiResponseMessage(toolCalls = calls.toList())))
      )

  private fun answer(text: String) =
      AiApiChatResponse(
          choices =
              listOf(AiApiChoice(message = AiApiResponseMessage(content = JsonPrimitive(text))))
      )

  private class FakeCoachApi(val respond: suspend (Int) -> AiApiChatResponse) :
      CoachAgentTestGateway {
    data class Request(val messages: List<AiApiMessage>, val tools: List<AiApiTool>)

    val requests = mutableListOf<Request>()

    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ): AiApiChatResponse {
      requests += Request(messages, tools)
      return respond(requests.size)
    }
  }

  companion object {
    private const val READ = "get_workout_state"
    private const val MUTATION = "submit_workout_changes"
  }
}

/** Completed-only fixture; streaming behavior uses explicit event fakes below. */
private interface CoachAgentTestGateway :
    com.valerochka1337.valerochkagym.data.ai.CoachModelGateway {
  override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?) =
      "Server coach prompt"

  suspend fun complete(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<com.valerochka1337.valerochkagym.data.ai.AiApiMessage>,
      tools: List<com.valerochka1337.valerochkagym.data.ai.AiApiTool>,
  ): com.valerochka1337.valerochkagym.data.ai.AiApiChatResponse

  override fun stream(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<com.valerochka1337.valerochkagym.data.ai.AiApiMessage>,
      tools: List<com.valerochka1337.valerochkagym.data.ai.AiApiTool>,
  ) =
      kotlinx.coroutines.flow.flow {
        emit(
            com.valerochka1337.valerochkagym.data.ai.CoachModelEvent.Completed(
                complete(expectedOwner, expectedSessionEpoch, messages, tools)
            )
        )
      }
}
