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
  suspend fun submit(rawJson: String, accountId: String, epoch: Long): JsonObject =
      request("POST", "/coach/runs", rawJson, accountId, epoch).jsonObject

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

  suspend fun cancel(runId: String, accountId: String, epoch: Long): JsonObject =
      request("POST", "/coach/runs/${id(runId)}/cancel", "{}", accountId, epoch).jsonObject

  /**
   * Cursor duplicates are suppressed; gaps fail closed so the caller can poll authoritative state.
   */
  fun events(runId: String, after: Long, accountId: String, epoch: Long): Flow<JsonObject> = flow {
    require(after >= 0)
    var cursor = after
    transport
        .authorizedGetEventStream(
            "/coach/runs/${id(runId)}/events?after=$after",
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
    val response =
        transport.authorizedRawResponse(
            method,
            path,
            rawJson.encodeToByteArray(),
            expectedOwner = accountId,
            expectedSessionEpoch = epoch,
            retryOnUnauthorized = true,
        )
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
