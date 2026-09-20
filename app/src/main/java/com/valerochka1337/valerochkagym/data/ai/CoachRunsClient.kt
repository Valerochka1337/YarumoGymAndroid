package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Durable execution API. The caller journals request bytes and event cursors before acknowledging.
 */
@Singleton
class CoachRunsClient @Inject constructor(private val transport: BackendTransport) {
  private data class Retry(val until: Long, val failures: Int)

  private val retry = java.util.concurrent.ConcurrentHashMap<String, Retry>()

  suspend fun submit(rawJson: String, accountId: String, epoch: Long): JsonObject {
    val body = Json.parseToJsonElement(rawJson).jsonObject
    // Previously persisted requests keep their original contract and bytes.
    val path =
        if (body.containsKey("state"))
            "/coach/sessions/${id(body["state"]!!.jsonObject["snapshot"]!!.jsonObject["workout_id"]!!.jsonPrimitive.content)}/messages"
        else "/coach/runs"
    return request("POST", path, rawJson, accountId, epoch).jsonObject
  }

  suspend fun modelCheck(model: String?, accountId: String, epoch: Long): JsonObject =
      request(
              "POST",
              "/coach/model-check",
              buildJsonObject { model?.let { put("model", it) } }.toString(),
              accountId,
              epoch,
          )
          .jsonObject

  suspend fun status(runId: String, accountId: String, epoch: Long): JsonObject =
      request("GET", "/coach/runs/${id(runId)}", "", accountId, epoch).jsonObject

  suspend fun list(workoutId: String, accountId: String, epoch: Long): List<JsonObject> {
    val result = mutableListOf<JsonObject>()
    var cursor = 0L
    do {
      val page =
          request(
                  "GET",
                  "/coach/sessions/${id(workoutId)}/runs?after=$cursor",
                  "",
                  accountId,
                  epoch,
              )
              .jsonArray
              .map { it.jsonObject }
      result.addAll(page)
      if (page.size < 200) break
      val next = page.maxOf { requireNotNull(it["ordinal"]?.jsonPrimitive?.longOrNull) }
      require(next > cursor) { "Invalid coach runs cursor" }
      cursor = next
    } while (true)
    return result
  }

  suspend fun putSession(workoutId: String, rawJson: String, accountId: String, epoch: Long) {
    request("PUT", "/coach/sessions/${id(workoutId)}", rawJson, accountId, epoch)
  }

  suspend fun receipt(runId: String, rawJson: String, accountId: String, epoch: Long) {
    request("POST", "/coach/runs/${id(runId)}/receipt", rawJson, accountId, epoch)
  }

  suspend fun answer(
      workoutId: String,
      questionId: String,
      rawJson: String,
      accountId: String,
      epoch: Long,
  ): JsonObject =
      request(
              "POST",
              "/coach/sessions/${id(workoutId)}/questions/${id(questionId)}/answers",
              rawJson,
              accountId,
              epoch,
          )
          .jsonObject

  suspend fun interventionReceipt(
      workoutId: String,
      proposalId: String,
      rawJson: String,
      accountId: String,
      epoch: Long,
  ) {
    request(
        "POST",
        "/coach/sessions/${id(workoutId)}/proposals/${id(proposalId)}/receipt",
        rawJson,
        accountId,
        epoch,
    )
  }

  suspend fun cancel(runId: String, accountId: String, epoch: Long): JsonObject =
      request("POST", "/coach/runs/${id(runId)}/cancel", "{}", accountId, epoch).jsonObject

  /**
   * Cursor duplicates are suppressed; gaps fail closed so the caller can poll authoritative state.
   */
  fun events(runId: String, after: Long, accountId: String, epoch: Long): Flow<JsonObject> =
      eventStream("/coach/runs/${id(runId)}/events", after, accountId, epoch)

  fun sessionEvents(
      workoutId: String,
      after: Long,
      accountId: String,
      epoch: Long,
  ): Flow<JsonObject> =
      eventStream("/coach/sessions/${id(workoutId)}/events", after, accountId, epoch)

  private fun eventStream(
      path: String,
      after: Long,
      accountId: String,
      epoch: Long,
  ): Flow<JsonObject> = flow {
    require(after >= 0)
    var cursor = after
    transport
        .authorizedGetEventStream(
            "$path?after=$after",
            accountId,
            epoch,
        )
        .collect { event ->
          checkOwner(event.owner, event.sessionEpoch, accountId, epoch)
          val body = transport.json.parseToJsonElement(event.data).jsonObject
          val sequence = requireNotNull(body["sequence"]?.jsonPrimitive?.longOrNull)
          require(event.id?.toLongOrNull() == sequence) { "Invalid coach event ID" }
          if (sequence > cursor) {
            require(sequence == cursor + 1) { "Missing coach events" }
            emit(body)
            cursor = sequence
          }
        }
  }

  private suspend fun request(
      method: String,
      path: String,
      rawJson: String,
      accountId: String,
      epoch: Long,
  ): JsonElement {
    val key = "$accountId:$epoch:$method:${path.substringBefore('?')}"
    val previous = retry[key]
    val remaining = (previous?.until ?: 0) - System.currentTimeMillis()
    if (remaining > 0)
        throw BackendException(
            429,
            "delivery_backoff",
            "Ожидаем соединение…",
            retryAfterMillis = remaining,
        )
    val response =
        try {
          transport.authorizedRawResponse(
              method,
              path,
              rawJson.encodeToByteArray(),
              expectedOwner = accountId,
              expectedSessionEpoch = epoch,
              retryOnUnauthorized = true,
          )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (error !is BackendException || error.status == 429 || error.status >= 500) {
            val failures = ((previous?.failures ?: 0) + 1).coerceAtMost(6)
            val wait =
                maxOf(
                    (error as? BackendException)?.retryAfterMillis ?: 0,
                    (1_000L shl failures).coerceAtMost(60_000),
                ) + kotlin.random.Random.nextLong(0, 500)
            retry[key] = Retry(System.currentTimeMillis() + wait, failures)
          }
          throw error
        }
    retry[key] = Retry(System.currentTimeMillis() + (response.retryAfterMillis ?: 0), 0)
    checkOwner(response.owner, response.sessionEpoch, accountId, epoch)
    return response.body
  }

  private fun checkOwner(owner: String?, epoch: Long, expectedOwner: String, expectedEpoch: Long) {
    if (owner != expectedOwner || epoch != expectedEpoch)
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  private fun id(value: String): String {
    require(UUID.fromString(value).toString().equals(value, ignoreCase = true))
    return value
  }
}

/** Version of semantic context, independent of sampling clock and JSON object key order. */
object CoachContextFingerprint {
  fun of(snapshotJson: String): String {
    val root = Json.parseToJsonElement(snapshotJson).jsonObject
    val ignored =
        setOf("elapsed_seconds", "observed_at_millis", "pulse") +
            if (root["available_time_ends_at_millis"] != null) setOf("available_time_minutes")
            else emptySet()
    val stable =
        JsonObject(
            root
                .filterKeys { it !in ignored }
                .mapValues { (key, value) ->
                  if (key == "rest")
                      JsonObject(value.jsonObject.filterKeys { it != "remaining_seconds" })
                  else value
                }
        )
    val bytes = canonical(stable).toString().encodeToByteArray()
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it.toInt() and 255)
    }
  }

  private fun canonical(value: JsonElement): JsonElement =
      when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
      }
}
