package com.valerochka1337.valerochkagym.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackendCoachModelGatewayTest {
  @Test
  fun `gateway sends all registered coach tools within the backend contract`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))

    val result =
        gateway.complete(
            "owner",
            7,
            listOf(AiApiMessage.text("user", "Привет")),
            CoachToolCodec.tools,
        )

    assertEquals("Ответ", (result.choices.single().message!!.content as JsonPrimitive).content)
    assertEquals(CoachToolCodec.tools, backend.turn.tools)
    assertEquals(
        setOf(
            "get_workout_state",
            "find_exercises",
            "get_exercise_history",
            "submit_workout_changes",
        ),
        backend.turn.tools.map { it.function.name }.toSet(),
    )
    assertEquals(4, backend.turn.tools.size)
  }

  @Test
  fun `invalid or foreign prompt is rejected`() = runTest {
    for (backend in
        listOf(
            FakeBackend(promptBody = """{"prompt":" "}"""),
            FakeBackend(promptOwner = "other"),
        )) {
      val gateway =
          BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))
      try {
        gateway.systemPrompt("owner", 7)
        fail("Invalid prompt is accepted")
      } catch (_: IllegalArgumentException) {}
    }
  }

  @Test
  fun `system prompt is fetched through owner bound endpoint`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))
    assertEquals("Remote prompt", gateway.systemPrompt("owner", 7))
    assertEquals(listOf("/ai/coach-prompt"), backend.paths)
    assertEquals(listOf("owner"), backend.owners)
    assertEquals(listOf(7L), backend.epochs)
  }

  @Test
  fun `gateway emits text before completion and rejects stale session before any delta`() =
      runTest {
        val sessions = FakeSessions()
        val events =
            BackendCoachModelGateway(FakeBackend(), sessions, SettingsRepository(FakeStore()))
                .stream("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())
                .toList()
        assertEquals(
            listOf(CoachModelEvent.TextDelta::class, CoachModelEvent.Completed::class),
            events.map { it::class },
        )
        val stale =
            BackendCoachModelGateway(
                FakeBackend(beforeDelta = { sessions.save(null) }),
                sessions,
                SettingsRepository(FakeStore()),
            )
        val received = mutableListOf<CoachModelEvent>()
        try {
          stale
              .stream("owner", 7, listOf(AiApiMessage.text("user", "Hi")), emptyList())
              .toList(received)
          fail("Expected stale session")
        } catch (_: com.valerochka1337.valerochkagym.data.backend.BackendException) {
          assertEquals(emptyList<CoachModelEvent>(), received)
        }
      }

  @Test
  fun `gateway maps terminal errors and rejects missing completion`() = runTest {
    for (code in listOf("unauthorized", "ai_timeout", "ai_unavailable", null)) {
      val gateway =
          BackendCoachModelGateway(
              FakeBackend(terminalError = code, eof = code == null),
              FakeSessions(),
              SettingsRepository(FakeStore()),
          )
      val failure =
          runCatching {
                gateway
                    .stream("owner", 7, listOf(AiApiMessage.text("user", "Hi")), emptyList())
                    .toList()
              }
              .exceptionOrNull()
      org.junit.Assert.assertNotNull(failure)
      when (code) {
        "unauthorized" ->
            assertEquals(
                401,
                (failure as com.valerochka1337.valerochkagym.data.backend.BackendException).status,
            )
        "ai_timeout" -> org.junit.Assert.assertTrue(failure is java.io.InterruptedIOException)
      }
    }
  }

  @Test
  fun `gateway uses account scoped selected allowed model with pinned owner and epoch`() = runTest {
    val settings = SettingsRepository(FakeStore())
    settings.setCoachModel("owner", "model-b")
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), settings)

    val result =
        gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())

    assertEquals(
        "Ответ",
        result.choices.single().message?.content?.let { (it as JsonPrimitive).content },
    )
    assertEquals(listOf("/ai/coach-models", "/ai/coach-turn/stream"), backend.paths)
    assertEquals("model-b", backend.turn.model)
    assertEquals("owner", backend.owners.last())
    assertEquals(7L, backend.epochs.last())
  }

  @Test
  fun `gateway rejects an allowed model different from its requested model`() = runTest {
    val settings = SettingsRepository(FakeStore())
    settings.setCoachModel("owner", "model-b")
    val backend = FakeBackend(returnedModel = "model-a")
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), settings)

    try {
      gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())
      fail("Expected an allowlist rejection")
    } catch (_: IllegalArgumentException) {
      // Expected: a response must correspond to the model this request selected.
    }
  }

  @Test
  fun `gateway sends the server default when account has no selection`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))

    gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())

    assertEquals("model-a", backend.turn.model)
  }

  @Test
  fun `gateway rejects a mismatched response request id`() = runTest {
    val gateway =
        BackendCoachModelGateway(
            FakeBackend(responseRequestId = "other-request"),
            FakeSessions(),
            SettingsRepository(FakeStore()),
        )

    try {
      gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())
      fail("Expected response correlation rejection")
    } catch (_: IllegalArgumentException) {
      // Expected: only the response for this exact generated request may reach the agent.
    }
  }

  @Test
  fun `coach model selections stay isolated by account`() = runTest {
    val settings = SettingsRepository(FakeStore())

    settings.setCoachModel("account-a", "model-a")
    settings.setCoachModel("account-b", "model-b")

    assertEquals("model-a", settings.coachModel("account-a").first())
    assertEquals("model-b", settings.coachModel("account-b").first())
  }

  @Test
  fun `gateway rejects an oversized turn before it reaches the server`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))

    try {
      gateway.complete("owner", 7, List(81) { AiApiMessage.text("user", "x") }, emptyList())
      fail("Expected message limit rejection")
    } catch (_: IllegalArgumentException) {
      assertEquals(emptyList<String>(), backend.paths)
    }
  }

  @Test
  fun `gateway serializes tool protocol defaults in its raw request`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))
    val tool =
        AiApiTool(
            function =
                AiApiToolFunction(
                    name = "get_workout_state",
                    description = "state",
                    parameters = buildJsonObject {},
                ),
        )
    val assistant =
        AiApiMessage(
            role = "assistant",
            toolCalls =
                listOf(
                    AiApiToolCall(
                        id = "call-1",
                        function = AiApiToolCallFunction("get_workout_state", "{}"),
                    ),
                ),
        )

    gateway.complete(
        "owner",
        7,
        listOf(AiApiMessage.text("user", "Привет"), assistant),
        listOf(tool),
    )

    val raw = backend.turnRaw.jsonObject
    assertEquals(
        "function",
        raw["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content,
    )
    val toolCall =
        raw["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
    assertEquals("function", toolCall["type"]!!.jsonPrimitive.content)
  }

  private class FakeSessions : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner", "o@example.com", "access", "refresh")
        )
    override val sessionEpoch: Long = 7L

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class FakeBackend(
      private val promptBody: String = """{"prompt":"Remote prompt"}""",
      private val promptOwner: String = "owner",
      private val returnedModel: String? = null,
      private val responseRequestId: String? = null,
      private val terminalError: String? = null,
      private val eof: Boolean = false,
      private val beforeDelta: () -> Unit = {},
  ) : BackendTransport {
    override val json = Json { explicitNulls = false }
    val paths = mutableListOf<String>()
    val owners = mutableListOf<String?>()
    val epochs = mutableListOf<Long?>()
    lateinit var turn: Request
    lateinit var turnRaw: JsonElement

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("unused")

    override fun authorizedEventStream(
        path: String,
        rawBody: ByteArray,
        expectedOwner: String,
        expectedSessionEpoch: Long?,
    ) =
        kotlinx.coroutines.flow.flow {
          val response =
              authorizedRawResponse(
                  "POST",
                  path,
                  rawBody,
                  emptyMap(),
                  expectedOwner,
                  expectedSessionEpoch,
                  false,
                  256 * 1024,
              )
          beforeDelta()
          emit(
              com.valerochka1337.valerochkagym.data.backend.BackendStreamEvent(
                  "text_delta",
                  buildJsonObject {
                        put("requestId", JsonPrimitive(responseRequestId ?: turn.requestId))
                        put("model", JsonPrimitive(returnedModel ?: turn.model))
                        put("delta", JsonPrimitive("{\"text\":\"Ответ"))
                      }
                      .toString(),
                  "owner",
                  7,
              )
          )
          if (eof) return@flow
          if (terminalError != null) {
            emit(
                com.valerochka1337.valerochkagym.data.backend.BackendStreamEvent(
                    "error",
                    buildJsonObject {
                          put("requestId", JsonPrimitive(turn.requestId))
                          put("code", JsonPrimitive(terminalError))
                          put("message", JsonPrimitive("Unavailable"))
                        }
                        .toString(),
                    "owner",
                    7,
                )
            )
            return@flow
          }
          emit(
              com.valerochka1337.valerochkagym.data.backend.BackendStreamEvent(
                  "completed",
                  response.rawBody.decodeToString(),
                  "owner",
                  7,
              )
          )
        }

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      if (path == "/ai/coach-models" || path == "/ai/coach-prompt") assertTrue(retryOnUnauthorized)
      paths += path
      owners += expectedOwner
      epochs += expectedSessionEpoch
      val response =
          if (path == "/ai/coach-prompt") {
            promptBody
          } else if (path == "/ai/coach-models") {
            """{"availability":"AVAILABLE","defaultModel":"model-a","models":["model-a","model-b"]}"""
          } else {
            turnRaw = json.parseToJsonElement(rawBody.decodeToString())
            turn = json.decodeFromString(Request.serializer(), rawBody.decodeToString())
            json.encodeToString(
                Response.serializer(),
                Response(
                    responseRequestId ?: turn.requestId,
                    returnedModel ?: turn.model,
                    AiApiChatResponse(
                        choices =
                            listOf(
                                AiApiChoice(AiApiResponseMessage(content = JsonPrimitive("Ответ")))
                            )
                    ),
                ),
            )
          }
      return BackendResponse(
          body = json.parseToJsonElement(response),
          rawBody = response.encodeToByteArray(),
          acceptedCapabilities = emptySet(),
          owner = if (path == "/ai/coach-prompt") promptOwner else "owner",
          sessionEpoch = 7,
      )
    }
  }

  @kotlinx.serialization.Serializable
  private data class Request(
      val requestId: String,
      val model: String,
      val messages: List<AiApiMessage>,
      val tools: List<AiApiTool>,
  )

  @kotlinx.serialization.Serializable
  private data class Response(
      val requestId: String,
      val model: String?,
      val completion: AiApiChatResponse,
  )

  private class FakeStore(initial: Preferences = mutablePreferencesOf()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
  }
}

private suspend fun CoachModelGateway.complete(
    owner: String,
    epoch: Long?,
    messages: List<AiApiMessage>,
    tools: List<AiApiTool>,
): AiApiChatResponse =
    (stream(owner, epoch, messages, tools).filterIsInstance<CoachModelEvent.Completed>().single()
            as CoachModelEvent.Completed)
        .completion
