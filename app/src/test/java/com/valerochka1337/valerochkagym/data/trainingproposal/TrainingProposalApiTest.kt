package com.valerochka1337.valerochkagym.data.trainingproposal

import com.valerochka1337.valerochkagym.data.backend.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TrainingProposalApiTest {
  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(BackendTokens(OWNER, "a@b", "access", "refresh"))
    override var sessionEpoch = 1L

    override fun save(tokens: BackendTokens?) {
      sessionEpoch++
      session.value = tokens
    }
  }

  private class Transport(val store: Store) : BackendTransport {
    override val json = Json
    val bodies = mutableListOf<ByteArray>()
    var beforeReturn: () -> Unit = {}
    var cancellation = false
    var responseBody = RESULT
    var supportsPlanner = false
    val retryFlags = mutableListOf<Boolean>()
    val paths = mutableListOf<String>()
    val requestHeaders = mutableListOf<Map<String, String>>()

    override suspend fun public(method: String, path: String, body: JsonElement?) = error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?) =
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
      paths += path
      requestHeaders += headers
      if (path == "/planning/v2/capabilities") {
        if (!supportsPlanner) throw BackendException(404, "not_found", "legacy server")
        val capability =
            "{\"schemaVersion\":2,\"protocol\":2,\"capability\":\"deterministic-workout-planner-v2\"}"
        return BackendResponse(
            Json.parseToJsonElement(capability),
            capability.encodeToByteArray(),
            setOf("deterministic-workout-planner-v2"),
            expectedOwner,
            requireNotNull(expectedSessionEpoch),
        )
      }
      assertEquals(OWNER, expectedOwner)
      assertEquals(store.sessionEpoch, expectedSessionEpoch)
      assertEquals(method == "GET", retryOnUnauthorized)
      retryFlags += retryOnUnauthorized
      assertEquals(1_048_576, maxResponseBytes)
      bodies += rawBody.copyOf()
      if (cancellation) throw CancellationException("cancelled")
      beforeReturn()
      val response = Json.parseToJsonElement(responseBody)
      return BackendResponse(
          response,
          responseBody.encodeToByteArray(),
          if (supportsPlanner) setOf("calendar-plans", "deterministic-workout-planner-v2")
          else setOf("calendar-plans"),
          expectedOwner,
          requireNotNull(expectedSessionEpoch),
      )
    }
  }

  @Test
  fun `reading proposals allows bounded auth refresh while approval never auto replays`() =
      runTest {
        val store = Store()
        val transport = Transport(store)
        val api = TrainingProposalApi(transport, store)
        val session = requireNotNull(store.snapshot())
        transport.responseBody = """{"items":[],"nextCursor":null}"""
        assertTrue(api.list(session).items.isEmpty())
        transport.responseBody = RESULT
        api.approve(session, PROPOSAL, rawRequest())
        assertEquals(listOf(true, false), transport.retryFlags)
      }

  @Test
  fun `approval sends literal durable bytes on first dispatch and retry`() = runTest {
    val store = Store()
    val transport = Transport(store)
    val api = TrainingProposalApi(transport, store)
    val bytes = rawRequest()
    val session = requireNotNull(store.snapshot())
    assertEquals(PROPOSAL, api.approve(session, PROPOSAL, bytes).proposalId)
    api.approve(session, PROPOSAL, bytes)
    assertEquals(2, transport.bodies.size)
    transport.bodies.forEach { assertArrayEquals(bytes, it) }
  }

  @Test
  fun `confirmed planner server lists proposals with protocol two and never retries v1`() =
      runTest {
        val store = Store()
        val transport = Transport(store).also { it.supportsPlanner = true }
        val api = TrainingProposalApi(transport, store)
        transport.responseBody = "{\"items\":[],\"nextCursor\":null}"

        assertTrue(api.list(requireNotNull(store.snapshot())).items.isEmpty())
        assertEquals(
            listOf("/planning/v2/capabilities", "/training-proposals?limit=50"),
            transport.paths,
        )
        assertEquals(mapOf("X-Planner-Protocol" to "2"), transport.requestHeaders.first())
        assertEquals(
            mapOf("X-Gym-Capabilities" to "calendar-plans", "X-Planner-Protocol" to "2"),
            transport.requestHeaders.last(),
        )
      }

  @Test
  fun `late A B A result rejects monotonic epoch and cancellation propagates`() = runTest {
    val store = Store()
    val transport = Transport(store)
    val api = TrainingProposalApi(transport, store)
    val session = requireNotNull(store.snapshot())
    transport.beforeReturn = {
      val original = store.session.value
      store.save(original?.copy(userId = OTHER))
      store.save(original)
    }
    val failure = runCatching { api.approve(session, PROPOSAL, rawRequest()) }.exceptionOrNull()
    assertEquals("owner_changed", (failure as BackendException).code)
    transport.beforeReturn = {}
    transport.cancellation = true
    assertTrue(
        runCatching { api.approve(requireNotNull(store.snapshot()), PROPOSAL, rawRequest()) }
            .exceptionOrNull() is CancellationException
    )
  }

  private fun rawRequest(): ByteArray {
    val fixture =
        Json.parseToJsonElement(
                requireNotNull(
                        javaClass.classLoader.getResourceAsStream(
                            "training-proposals-contract.json"
                        )
                    )
                    .bufferedReader()
                    .use { it.readText() }
            )
            .jsonObject
    val canonical =
        fixture
            .getValue("canonicalApprovalRequest")
            .jsonObject
            .getValue("vectors")
            .jsonArray[0]
            .jsonObject
            .getValue("utf8")
            .jsonPrimitive
            .content
    return (" \n" + canonical + "\t ").encodeToByteArray()
  }

  companion object {
    const val OWNER = "11111111-1111-4111-8111-111111111111"
    const val OTHER = "99999999-9999-4999-8999-999999999999"
    const val PROPOSAL = "22222222-2222-4222-8222-222222222222"
    const val RESULT =
        """{"proposalId":"22222222-2222-4222-8222-222222222222","version":1,"routineId":"33333333-3333-4333-8333-333333333333","calendarPlanId":"44444444-4444-4444-8444-444444444444","revision":3,"approvedAt":1}"""
  }
}
