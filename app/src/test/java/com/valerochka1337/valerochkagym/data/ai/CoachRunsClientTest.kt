package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoachRunsClientTest {
  @Test
  fun `submit preserves journaled bytes and pins refreshed requests to login`() = runTest {
    val transport = RunTransport()
    val raw = "{  \"requestId\" : \"$RUN\", \"message\":\"Привет\" }"
    CoachRunsClient(transport).submit(raw, "owner", 7)
    assertArrayEquals(raw.encodeToByteArray(), transport.bytes)
    assertEquals("POST /coach/runs", transport.request)
    assertEquals("owner", transport.owner)
    assertEquals(7L, transport.epoch)
    assertTrue(transport.refresh)
  }

  @Test
  fun `discovery paginates beyond first two hundred runs`() = runTest {
    val transport = RunTransport().apply { paginated = true }
    val runs = CoachRunsClient(transport).list(RUN, "owner", 7)
    assertEquals(201, runs.size)
    assertEquals("GET /coach/sessions/$RUN/runs?after=200", transport.request)
  }

  @Test
  fun `response from another login is rejected`() = runTest {
    val transport = RunTransport().apply { responseEpoch = 8 }
    val failure =
        runCatching { CoachRunsClient(transport).status(RUN, "owner", 7) }.exceptionOrNull()
    assertTrue(failure is BackendException)
  }

  @Test
  fun `resumed events suppress duplicates and preserve durable sequence`() = runTest {
    val transport = RunTransport().apply { sequences = listOf(4, 5, 5, 6) }
    val events = CoachRunsClient(transport).events(RUN, 4, "owner", 7).toList()
    assertEquals(listOf(5L, 6L), events.map { it.getValue("sequence").jsonPrimitive.long })
    assertEquals("/coach/runs/$RUN/events?after=4", transport.request)
  }

  @Test
  fun `event gaps fail so status polling can recover authoritative result`() = runTest {
    val transport = RunTransport().apply { sequences = listOf(5, 7) }
    assertTrue(
        runCatching { CoachRunsClient(transport).events(RUN, 4, "owner", 7).toList() }.isFailure
    )
  }

  @Test
  fun `fingerprint ignores ticking clocks but detects rest anchor and decision changes`() {
    val one =
        """{"revision":2,"elapsed_seconds":12,"observed_at_millis":100,"pulse":{"bpm":100},"rest":{"remaining_seconds":20,"start_id":"a"},"decisions":[]}"""
    val tick =
        """{"decisions":[],"rest":{"start_id":"a","remaining_seconds":10},"revision":2,"elapsed_seconds":22,"observed_at_millis":200}"""
    assertEquals(CoachContextFingerprint.of(one), CoachContextFingerprint.of(tick))
    assertNotEquals(
        CoachContextFingerprint.of(one),
        CoachContextFingerprint.of(tick.replace("\"a\"", "\"b\"")),
    )
    assertNotEquals(
        CoachContextFingerprint.of(one),
        CoachContextFingerprint.of(tick.replace("[]", "[\"rejected\"]")),
    )
  }

  @Test
  fun `snapshot carries excluded catalogue UUIDs even outside active exercises`() {
    val snapshot =
        com.valerochka1337.valerochkagym.domain.WorkoutSnapshot(
            "owner",
            RUN,
            1,
            emptyList(),
            excludedExerciseIds = setOf(123),
            excludedExerciseSyncIds = setOf(RUN),
            futureRestSeconds = 90,
            availableTimeEndsAtMillis = 12345,
        )
    val value = Json.parseToJsonElement(CoachToolCodec.snapshotJson(snapshot)).jsonObject
    assertEquals(
        listOf(RUN),
        value.getValue("excluded_exercise_ids").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(90, value.getValue("future_rest_seconds").jsonPrimitive.int)
    assertEquals(12345L, value.getValue("available_time_ends_at_millis").jsonPrimitive.long)
    assertEquals(
        CoachToolCodec.contextVersion(snapshot),
        CoachToolCodec.contextVersion(snapshot.copy(availableTimeMinutes = 5)),
    )
    assertNotEquals(
        CoachToolCodec.contextVersion(snapshot),
        CoachToolCodec.contextVersion(snapshot.copy(futureRestSeconds = 120)),
    )
  }

  companion object {
    const val RUN = "00000000-0000-0000-0000-000000000001"
  }
}

private class RunTransport : BackendTransport {
  override val json = Json
  var bytes = byteArrayOf()
  var request = ""
  var owner: String? = null
  var epoch: Long? = null
  var refresh = false
  var responseEpoch = 7L
  var sequences = emptyList<Int>()
  var paginated = false

  override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
      error("unused")

  override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
      error("unused")

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
    bytes = rawBody.copyOf()
    request = "$method $path"
    owner = expectedOwner
    epoch = expectedSessionEpoch
    refresh = retryOnUnauthorized
    val body =
        if (paginated)
            buildJsonArray {
              val range = if (path.endsWith("after=0")) 1..200 else 201..201
              range.forEach { add(buildJsonObject { put("ordinal", it) }) }
            }
        else buildJsonObject {}
    return BackendResponse(body, "{}".encodeToByteArray(), emptySet(), "owner", responseEpoch)
  }

  override fun authorizedGetEventStream(
      path: String,
      expectedOwner: String,
      expectedSessionEpoch: Long,
  ): Flow<BackendStreamEvent> = flow {
    request = path
    sequences.forEach { sequence ->
      emit(
          BackendStreamEvent(
              "progress",
              """{"sequence":$sequence,"type":"progress"}""",
              "owner",
              7,
              sequence.toString(),
          )
      )
    }
  }
}
