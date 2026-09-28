package com.valerochka1337.valerochkagym.data.plannermapping

import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerExerciseMappingRepositoryTest {
  @Test
  fun `repository lists saves and deletes owner mapping through v2 protocol`() = runTest {
    val sessions = Sessions()
    val transport = Transport()
    val repository =
        PlannerExerciseMappingRepository(PlannerExerciseMappingApi(transport, sessions), sessions)

    assertEquals(1, repository.list().size)
    val saved = repository.save(mapping(revision = 0))
    assertEquals(2L, saved.revision)
    assertFalse(
        Json.parseToJsonElement(transport.bodies.last().decodeToString())
            .jsonObject
            .containsKey("revision")
    )

    repository.delete(EXERCISE)
    assertEquals(
        listOf(
            "GET /planning/v2/exercise-mappings",
            "PUT /planning/v2/exercise-mappings/$EXERCISE",
            "DELETE /planning/v2/exercise-mappings/$EXERCISE",
        ),
        transport.calls,
    )
    assertTrue(transport.headers.all { it == mapOf("X-Planner-Protocol" to "2") })
  }

  private class Sessions : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(BackendTokens(OWNER, "a@b", "access", "refresh"))
    override val sessionEpoch = 1L

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class Transport : BackendTransport {
    override val json = Json
    val calls = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()
    val bodies = mutableListOf<ByteArray>()

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
      calls += "$method $path"
      this.headers += headers
      bodies += rawBody
      val body =
          when (method) {
            "GET" -> "{\"items\":[${mappingJson(1)}]}"
            "PUT" -> mappingJson(2)
            "DELETE" -> ""
            else -> error("unexpected method")
          }
      return BackendResponse(
          body =
              if (body.isEmpty()) Json.parseToJsonElement("{}") else Json.parseToJsonElement(body),
          rawBody = body.encodeToByteArray(),
          acceptedCapabilities = setOf("deterministic-workout-planner-v2"),
          owner = OWNER,
          sessionEpoch = 1L,
      )
    }
  }

  companion object {
    private const val OWNER = "11111111-1111-4111-8111-111111111111"
    private const val EXERCISE = "22222222-2222-4222-8222-222222222222"

    private fun mapping(revision: Long) =
        PlannerExerciseMapping(
            EXERCISE,
            PlannerMovementClass.HORIZONTAL_PUSH,
            listOf(PlannerExerciseRole.PRIMARY),
            listOf(PlannerSupportedGoal.STRENGTH),
            ExerciseType.STRENGTH,
            listOf("barbell"),
            revision,
        )

    private fun mappingJson(revision: Long) =
        """{"exerciseId":"$EXERCISE","movementClass":"HORIZONTAL_PUSH","roles":["PRIMARY"],"supportedGoals":["STRENGTH"],"exerciseType":"STRENGTH","equipmentIds":["barbell"],"revision":$revision}"""
  }
}
