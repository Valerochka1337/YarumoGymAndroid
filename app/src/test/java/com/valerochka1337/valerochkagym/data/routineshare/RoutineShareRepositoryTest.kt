package com.valerochka1337.valerochkagym.data.routineshare

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.backend.SyncReady
import com.valerochka1337.valerochkagym.data.backend.SyncReadySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutineShareRepositoryTest : RoomDaoTest() {
  private val routineId = "00000000-0000-0000-0000-000000000011"
  private val operationId = "00000000-0000-0000-0000-000000000012"

  @Test
  fun `create sends account head revision and pins the ready session`() = runTest {
    val transport = FakeTransport()
    val sessions = FakeSessions(epochValue = 7)
    val ready = SyncReady.Ready("user-a", revision = 42, catalogRevision = 9, sessionEpoch = 7)
    val repository = RoutineShareRepository(transport, FakeReady(ready), sessions, BackendSync(db, transport, sessions))

    val created = repository.create(routineId, operationId, null, null) { _, _ -> }

    assertEquals(routineId, created.routineId)
    assertEquals(42L, transport.request!!.getValue("expectedRevision").jsonPrimitive.long)
    assertEquals(9L, transport.request!!.getValue("catalogRevision").jsonPrimitive.long)
    assertEquals("user-a", transport.expectedOwner)
    assertEquals(7L, transport.expectedEpoch)
  }

  @Test
  fun `create fails closed when the ready receipt is no longer current`() = runTest {
    val transport = FakeTransport()
    val sessions = FakeSessions(epochValue = 7)
    val ready = SyncReady.Ready("user-a", revision = 42, catalogRevision = 9, sessionEpoch = 7)
    val repository = RoutineShareRepository(transport, FakeReady(ready, current = false), sessions, BackendSync(db, transport, sessions))

    try {
      repository.create(routineId, operationId, null, null) { _, _ -> }
      fail("Expected a stale ready receipt to fail before dispatch")
    } catch (_: RoutineShareException) {}
    assertTrue(transport.request == null)
  }

  @Test
  fun `preview rejects private fields instead of silently accepting them`() = runTest {
    val transport = FakeTransport().apply {
      preview = buildJsonObject { put("title", "Ноги"); put("estimatedDurationSeconds", 90); put("exercises", kotlinx.serialization.json.JsonArray(emptyList())); put("author", "private") }
    }
    val sessions = FakeSessions(epochValue = 7)
    val repository = RoutineShareRepository(transport, FakeReady(SyncReady.Ready("user-a", 1, 1, 7)), sessions, BackendSync(db, transport, sessions))

    try {
      repository.preview("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE")
      fail("Expected an allowlist violation to fail")
    } catch (_: RoutineShareException) {}
  }

  private class FakeReady(private val ready: SyncReady.Ready, private val current: Boolean = true) : SyncReadySource {
    override suspend fun await(): SyncReady = ready
    override suspend fun isCurrent(ready: SyncReady.Ready): Boolean = current
  }

  private class FakeSessions(private val epochValue: Long) : BackendSessionStore {
    private val tokens = BackendTokens("user-a", "a@example.com", "access", "refresh")
    override val session = MutableStateFlow<BackendTokens?>(tokens)
    override val sessionEpoch: Long get() = epochValue
    override fun snapshot() = BackendSessionSnapshot(tokens, epochValue)
    override fun save(tokens: BackendTokens?) { session.value = tokens }
  }

  private class FakeTransport : BackendTransport {
    override val json = Json { encodeDefaults = true }
    var request: JsonObject? = null
    var expectedOwner: String? = null
    var expectedEpoch: Long? = null
    var preview: JsonElement = buildJsonObject { put("title", "Ноги"); put("estimatedDurationSeconds", 90); put("exercises", kotlinx.serialization.json.JsonArray(emptyList())) }

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement = preview
    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement = error("raw response is required")
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
      this.expectedOwner = expectedOwner
      this.expectedEpoch = expectedSessionEpoch
      request = json.parseToJsonElement(rawBody.decodeToString()).jsonObject
      return BackendResponse(
          body = buildJsonObject { put("shareId", "00000000-0000-0000-0000-000000000013"); put("url", "https://api.valerochkagym.tech/r/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ABCDE"); put("routineId", "00000000-0000-0000-0000-000000000011"); put("createdAt", 1) },
          rawBody = ByteArray(0),
          acceptedCapabilities = emptySet(),
          owner = expectedOwner,
          sessionEpoch = expectedSessionEpoch ?: 0,
      )
    }
  }
}
