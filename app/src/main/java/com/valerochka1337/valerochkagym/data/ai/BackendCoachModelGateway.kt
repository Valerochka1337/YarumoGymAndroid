package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CoachModelCatalog(
    val available: Boolean,
    val defaultModel: String?,
    val models: List<String>,
)

interface CoachModelCatalogSource {
  suspend fun catalog(expectedOwner: String, expectedSessionEpoch: Long?): CoachModelCatalog
}

/** Owner-bound proxy for the server-held coach credentials. It has no provider URL or secret. */
@Singleton
class BackendCoachModelGateway
@Inject
constructor(
    private val backend: BackendTransport,
    private val sessions: BackendSessionStore,
    private val settings: SettingsRepository,
) : CoachModelGateway, CoachModelCatalogSource {
  private val wireJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
  }

  override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?): String =
      CoachDiagnostics.trace("network.prompt", "method" to "GET", "path" to "/ai/coach-prompt") {
        loadSystemPrompt(expectedOwner, expectedSessionEpoch)
      }

  private suspend fun loadSystemPrompt(expectedOwner: String, expectedSessionEpoch: Long?): String {
    val epoch = expectedSessionEpoch ?: sessions.snapshot()?.epoch
    pin(expectedOwner, epoch)
    val response =
        backend.authorizedRawResponse(
            method = "GET",
            path = "/ai/coach-prompt",
            rawBody = ByteArray(0),
            expectedOwner = expectedOwner,
            expectedSessionEpoch = epoch,
            retryOnUnauthorized = true,
            maxResponseBytes = MAX_RESPONSE_BYTES,
        )
    requireContract(response.owner == expectedOwner && response.sessionEpoch == epoch) {
      "Coach prompt owner changed"
    }
    pin(expectedOwner, epoch)
    val prompt =
        wireJson
            .decodeFromString(CoachPromptResponse.serializer(), response.rawBody.decodeToString())
            .prompt
    requireContract(prompt.isNotBlank() && prompt.length <= 16000) { "Invalid coach prompt" }
    return prompt
  }

  override fun stream(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<AiApiMessage>,
      tools: List<AiApiTool>,
  ): kotlinx.coroutines.flow.Flow<CoachModelEvent> =
      kotlinx.coroutines.flow.flow {
        CoachDiagnostics.trace("network.turn", "messages" to messages.size, "tools" to tools.size) {
          val requestId = UUID.randomUUID().toString()
          val epoch = expectedSessionEpoch ?: sessions.snapshot()?.epoch
          requireContract(messages.size <= MAX_MESSAGES) { "Too many coach messages" }
          requireContract(tools.size <= MAX_TOOLS) { "Too many coach tools" }
          pin(expectedOwner, expectedSessionEpoch)
          val catalog = catalog(expectedOwner, expectedSessionEpoch)
          if (!catalog.available)
              throw BackendException(503, "coach_unconfigured", "Тренер не настроен на сервере")
          // DataStore is local, but the selected value still belongs to the session that started
          // this
          // turn. Check on both sides of the suspension before putting it on the wire.
          pin(expectedOwner, expectedSessionEpoch)
          val selected = settings.coachModel(expectedOwner).first()
          pin(expectedOwner, expectedSessionEpoch)
          requireContract(selected == null || selected in catalog.models) {
            "Saved coach model is not allowed"
          }
          val model =
              selected
                  ?: requireNotNull(catalog.defaultModel) { "Available coach has no default model" }
          val request =
              CoachTurnRequest(
                  requestId = requestId,
                  model = model,
                  messages = messages,
                  tools = tools,
              )
          val body =
              wireJson.encodeToString(CoachTurnRequest.serializer(), request).encodeToByteArray()
          requireContract(body.size <= MAX_REQUEST_BYTES) { "Coach request is too large" }
          pin(expectedOwner, expectedSessionEpoch)
          CoachDiagnostics.event(
              "network.stream.dispatch",
              "request" to requestId,
              "path" to "/ai/coach-turn/stream",
              "method" to "POST",
              "bytes" to body.size,
              "selected_model" to (selected != null),
              "last_role" to messages.lastOrNull()?.role,
              "system_after_dialog" to
                  messages.dropWhile { it.role == "system" }.any { it.role == "system" },
              "http_retry_limit" to 3,
          )
          var completed = false
          var deltaChars = 0
          backend
              .authorizedEventStream("/ai/coach-turn/stream", body, expectedOwner, epoch)
              .collect { response ->
                requireContract(!completed) { "Event after completion" }
                requireContract(response.owner == expectedOwner && response.sessionEpoch == epoch) {
                  "Coach response owner changed"
                }
                pin(expectedOwner, epoch)
                requireContract(response.data.encodeToByteArray().size <= MAX_RESPONSE_BYTES) {
                  "Coach event too large"
                }
                val value = wireJson.parseToJsonElement(response.data).jsonObject
                requireContract(value["requestId"]?.jsonPrimitive?.content == requestId) {
                  "Coach response correlation changed"
                }
                if (response.event != "error") {
                  requireContract(value["model"]?.jsonPrimitive?.content == model) {
                    "Coach response model changed"
                  }
                }
                when (response.event) {
                  "text_delta" -> {
                    val delta =
                        value["delta"]?.jsonPrimitive?.takeIf { it.isString }?.content
                            ?: error("Invalid text delta")
                    if (deltaChars == 0 && delta.isNotEmpty())
                        CoachDiagnostics.event("network.stream.first_text", "request" to requestId)
                    deltaChars += delta.length
                    requireContract(deltaChars <= MAX_RESPONSE_BYTES) { "Coach text too large" }
                    pin(expectedOwner, epoch)
                    emit(CoachModelEvent.TextDelta(delta))
                  }
                  "completed" -> {
                    val decoded =
                        wireJson.decodeFromString(CoachTurnResponse.serializer(), response.data)
                    pin(expectedOwner, epoch)
                    completed = true
                    CoachDiagnostics.event(
                        "network.stream.completed",
                        "request" to requestId,
                        "text_chars" to deltaChars,
                        "choices" to decoded.completion.choices.size,
                    )
                    emit(CoachModelEvent.Completed(decoded.completion))
                  }
                  "error" -> {
                    val code = value["code"]?.jsonPrimitive?.content ?: "ai_unavailable"
                    CoachDiagnostics.event(
                        "network.stream.error",
                        "request" to requestId,
                        "reason" to
                            when (code) {
                              "ai_timeout",
                              "unauthorized",
                              "ai_unavailable" -> code
                              else -> "provider_error"
                            },
                    )
                    if (code == "ai_timeout") throw java.io.InterruptedIOException("Coach timeout")
                    throw BackendException(
                        if (code == "unauthorized") 401 else 503,
                        code,
                        value["message"]?.jsonPrimitive?.content ?: "Сервер модели недоступен",
                    )
                  }
                  else -> error("Unexpected coach event")
                }
              }
          if (!completed)
              CoachDiagnostics.event("network.contract_rejected", "reason" to "missing_completion")
          check(completed) { "Coach stream ended without completion" }
        }
      }

  override suspend fun catalog(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
  ): CoachModelCatalog =
      CoachDiagnostics.trace("network.catalog", "method" to "GET", "path" to "/ai/coach-models") {
        loadCatalog(expectedOwner, expectedSessionEpoch)
      }

  private suspend fun loadCatalog(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
  ): CoachModelCatalog {
    pin(expectedOwner, expectedSessionEpoch)
    val response =
        backend.authorizedRawResponse(
            method = "GET",
            path = "/ai/coach-models",
            rawBody = ByteArray(0),
            expectedOwner = expectedOwner,
            expectedSessionEpoch = expectedSessionEpoch,
            retryOnUnauthorized = true,
            maxResponseBytes = MAX_RESPONSE_BYTES,
        )
    requireContract(
        response.owner == expectedOwner &&
            response.sessionEpoch == (expectedSessionEpoch ?: response.sessionEpoch)
    ) {
      "Coach catalog owner changed"
    }
    pin(expectedOwner, expectedSessionEpoch)
    val decoded =
        wireJson.decodeFromString(
            CoachModelsResponse.serializer(),
            response.rawBody.decodeToString(),
        )
    requireContract(decoded.availability in setOf("AVAILABLE", "UNCONFIGURED")) {
      "Coach availability is invalid"
    }
    val models = decoded.models.distinct()
    requireContract(
        models.size == decoded.models.size &&
            models.size <= MAX_MODELS &&
            models.all { it.isNotBlank() && it.length <= MAX_MODEL_ID_CHARS }
    ) {
      "Coach catalog is invalid"
    }
    when (decoded.availability) {
      "AVAILABLE" ->
          requireContract(decoded.defaultModel != null && decoded.defaultModel in models) {
            "Available coach has no allowed default"
          }
      "UNCONFIGURED" ->
          requireContract(decoded.defaultModel == null && models.isEmpty()) {
            "Unconfigured coach must not expose models"
          }
    }
    CoachDiagnostics.event(
        "network.catalog.available",
        "availability" to decoded.availability,
        "models" to models.size,
    )
    return CoachModelCatalog(decoded.availability == "AVAILABLE", decoded.defaultModel, models)
  }

  private inline fun requireContract(condition: Boolean, reason: () -> String) {
    if (!condition) CoachDiagnostics.event("network.contract_rejected", "reason" to reason())
    require(condition, reason)
  }

  private fun pin(expectedOwner: String, expectedSessionEpoch: Long?) {
    val current =
        sessions.snapshot() ?: throw BackendException(401, "unauthorized", "Войдите в аккаунт")
    if (
        current.tokens.userId != expectedOwner ||
            (expectedSessionEpoch != null && current.epoch != expectedSessionEpoch)
    ) {
      throw BackendException(401, "owner_changed", "Аккаунт изменился")
    }
  }

  companion object {
    private const val MAX_REQUEST_BYTES = 512 * 1024
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_MESSAGES = 80
    private const val MAX_TOOLS = 4
    private const val MAX_MODELS = 20
    private const val MAX_MODEL_ID_CHARS = 200
  }
}

@Serializable
private data class CoachModelsResponse(
    val availability: String,
    val defaultModel: String? = null,
    val models: List<String> = emptyList(),
)

@Serializable
private data class CoachTurnRequest(
    val requestId: String,
    val model: String,
    val messages: List<AiApiMessage>,
    val tools: List<AiApiTool>,
)

@Serializable
private data class CoachTurnResponse(
    val requestId: String,
    val model: String? = null,
    val completion: AiApiChatResponse,
)

@Serializable private data class CoachPromptResponse(val prompt: String)
