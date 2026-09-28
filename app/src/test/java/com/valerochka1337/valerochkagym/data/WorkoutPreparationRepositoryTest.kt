package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.service.WallClock
import java.io.IOException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutPreparationRepositoryTest : RoomDaoTest() {
  private val sessions = Sessions()
  private val api = Server()
  private val ready = Ready()
  private var now = 100L
  private val clock = WallClock { now }

  private fun repository(sync: BackendSync) =
      WorkoutPreparationRepository(
          db,
          sessions,
          sync,
          CalendarAiRepository(api, ready, db, clock),
          ready,
          api,
          clock,
      )

  private suspend fun fixture(): Pair<BackendSync, WorkoutPreparationRepository> {
    val sync = BackendSync(db, api, sessions)
    sync.claim(OWNER)
    db.exerciseDao()
        .insert(
            ExerciseEntity(
                name = "Жим",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
                isCustom = true,
                syncId = EXERCISE,
                equipmentRequirementState = EquipmentRequirementState.KNOWN,
            )
        )
    return sync to repository(sync)
  }

  @Test
  fun `short status request validates and persists ready result`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    assertTrue(repo.step())
    api.readyResult = true
    assertFalse(repo.step())
    assertEquals("READY", db.preparationDao().get(OWNER)!!.state)
    assertNotNull(db.preparationDao().get(OWNER)!!.proposalJson)
    assertEquals(1, api.posts.size)
    assertEquals(1, api.gets)
  }

  @Test
  fun `unfinished synchronization keeps request waiting and resumes without reentering form`() =
      runTest {
        val (_, repo) = fixture()
        val id = repo.enqueue(intent())
        ready.blocked = true
        assertTrue(repo.step())
        assertEquals("WAITING", db.preparationDao().get(OWNER)!!.state)
        assertEquals("ai_sync_failed", db.preparationDao().get(OWNER)!!.errorCode)
        assertTrue(api.posts.isEmpty())
        ready.blocked = false
        assertTrue(repo.step())
        assertEquals(id, db.preparationDao().get(OWNER)!!.requestId)
        assertEquals("QUEUED", db.preparationDao().get(OWNER)!!.state)
      }

  @Test
  fun `local generation change keeps running job visible and reacknowledges unchanged server revisions`() =
      runTest {
        val (_, repo) = fixture()
        repo.enqueue(intent())
        repo.step()
        db.openHelper.writableDatabase.execSQL("UPDATE backend_state SET generation=generation+1")
        assertEquals("QUEUED", repo.current.first()!!.state)
        ready.receipt = ready.receipt.copy(cacheGeneration = 2)
        api.readyResult = true
        assertFalse(repo.step())
        assertEquals("READY", db.preparationDao().get(OWNER)!!.state)
        assertNotNull(db.preparationDao().get(OWNER)!!.proposalJson)
      }

  @Test
  fun `changed server revision rejects result and manual retry preserves conditions with a fresh identity`() =
      runTest {
        val (_, repo) = fixture()
        val old = repo.enqueue(intent())
        repo.step()
        ready.receipt = ready.receipt.copy(cacheGeneration = 2, revision = 5)
        api.readyResult = true
        assertFalse(repo.step())
        val stale = db.preparationDao().get(OWNER)!!
        assertEquals("STALE", stale.state)
        assertNull(stale.proposalJson)
        repo.retryCurrent(old)
        val next = db.preparationDao().get(OWNER)!!
        assertNotEquals(old, next.requestId)
        assertEquals(stale.intentJson, next.intentJson)
        assertEquals("WAITING", next.state)
        assertNull(next.requestJson)
        assertTrue(next.replacesJson.contains(old))
        repo.retryCurrent(old)
        assertEquals(next.requestId, db.preparationDao().get(OWNER)!!.requestId)
      }

  @Test
  fun `changed catalog revision rejects ready result`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    repo.step()
    ready.receipt = ready.receipt.copy(cacheGeneration = 2, catalogRevision = 8)
    api.readyResult = true
    assertFalse(repo.step())
    assertEquals("STALE", db.preparationDao().get(OWNER)!!.state)
    assertNull(db.preparationDao().get(OWNER)!!.proposalJson)
  }

  @Test
  fun `invalid typed result fails safely without replacing retained proposal`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    repo.step()
    api.readyResult = true
    api.invalidResult = true
    assertFalse(repo.step())
    assertEquals("FAILED", db.preparationDao().get(OWNER)!!.state)
    assertEquals("ai_invalid_response", db.preparationDao().get(OWNER)!!.errorCode)
    assertNull(db.preparationDao().get(OWNER)!!.proposalJson)
  }

  @Test
  fun `refreshed session retries exact bytes with a new dispatch epoch`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    api.beforeReply = {
      sessions.save(BackendTokens(OWNER, "", "refreshed", "refreshed"))
      throw BackendException(401, "unauthorized", "")
    }
    assertTrue(repo.step())
    api.beforeReply = null
    assertTrue(repo.step())
    assertEquals(api.posts[0], api.posts[1])
    assertEquals("QUEUED", db.preparationDao().get(OWNER)!!.state)
  }

  @Test
  fun `identical explicit launches are durable with independent identities`() = runTest {
    val (sync, repo) = fixture()
    val id = repo.enqueue(intent())
    val second = repo.enqueue(intent())
    assertNotEquals(id, second)
    assertEquals(
        setOf(id, second),
        repository(sync).all.first { it.size == 2 }.map { it.requestId }.toSet(),
    )
    assertEquals(0, api.posts.size)
    assertEquals("WAITING", db.preparationDao().get(OWNER)!!.state)
  }

  @Test
  fun `lost acknowledgement replays identical bytes after repository recreation`() = runTest {
    val (sync, repo) = fixture()
    repo.enqueue(intent())
    api.loseAck = true
    assertTrue(repo.step())
    val bytes = db.preparationDao().get(OWNER)!!.requestJson
    assertTrue(repository(sync).step())
    assertEquals(bytes, api.posts[0])
    assertEquals(api.posts[0], api.posts[1])
    assertEquals("QUEUED", db.preparationDao().get(OWNER)!!.state)
  }

  @Test
  fun `refinement lost acknowledgement and server failure replay same uuid and bytes after recreation`() =
      runTest {
        val (sync, repo) = fixture()
        val first =
            repo.enqueueRefinement(
                ruleProposal(),
                refinementDraft(),
                listOf(excludeChange()),
                75,
                REFINEMENT,
            )
        api.loseAck = true
        assertTrue(repo.step(first))
        val firstBytes = db.preparationDao().get(OWNER, first)!!.requestJson
        assertEquals("WAITING", db.preparationDao().get(OWNER, first)!!.state)
        assertTrue(repository(sync).step(first))
        assertEquals(first, jsonRequestId(api.posts[0]))
        assertEquals(api.posts[0], api.posts[1])
        assertEquals(firstBytes, api.posts[1])

        val second =
            repo.enqueueRefinement(
                ruleProposal(),
                refinementDraft(),
                listOf(excludeChange()),
                75,
                REFINEMENT_TWO,
            )
        api.failServer = true
        assertTrue(repo.step(second))
        val secondBytes = db.preparationDao().get(OWNER, second)!!.requestJson
        assertEquals("WAITING", db.preparationDao().get(OWNER, second)!!.state)
        assertTrue(repository(sync).step(second))
        assertEquals(second, jsonRequestId(api.posts[2]))
        assertEquals(api.posts[2], api.posts[3])
        assertEquals(secondBytes, api.posts[3])
      }

  @Test
  fun `late response cannot replace newer conditions and carries lineage`() = runTest {
    val (_, repo) = fixture()
    val first = repo.enqueue(intent())
    api.beforeReply = { repo.enqueue(intent().copy(availableDurationMinutes = 75)) }
    repo.step()
    val next = db.preparationDao().get(OWNER)!!
    assertNotEquals(first, next.requestId)
    assertEquals("WAITING", next.state)
    assertEquals("[]", next.replacesJson)
    api.beforeReply = null
    repo.step()
    assertTrue(api.posts.last().contains(next.requestId))
  }

  @Test
  fun `paused request retries its exact request without creating another identity`() = runTest {
    val (_, repo) = fixture()
    val id = repo.enqueue(intent())
    api.loseAck = true
    repo.step(id)
    val bytes = db.preparationDao().get(OWNER)!!.requestJson
    repo.pausePending(id)
    assertEquals("PAUSED_WAITING", db.preparationDao().get(OWNER)!!.state)
    repo.retryCurrent(id)
    assertEquals(bytes, db.preparationDao().get(OWNER)!!.requestJson)
    assertTrue(repo.step(id))
  }

  @Test
  fun `one request can pause while another continues`() = runTest {
    val (_, repo) = fixture()
    val old = repo.enqueue(intent())
    val next = repo.enqueue(intent().copy(availableDurationMinutes = 75))
    assertTrue(repo.step(old))
    repo.pausePending(old)
    assertEquals(next, db.preparationDao().get(OWNER)!!.requestId)
    assertEquals("WAITING", db.preparationDao().get(OWNER)!!.state)
    assertEquals("PAUSED_STATUS", db.preparationDao().get(OWNER, old)!!.state)
    assertEquals(1, api.posts.size)
  }

  @Test
  fun `expired request stops without contacting server or moving date`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    now = 3000
    assertFalse(repo.step())
    assertEquals("EXPIRED", db.preparationDao().get(OWNER)!!.state)
    assertTrue(api.posts.isEmpty())
  }

  @Test
  fun `changed owner does not publish or expose previous request`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    api.beforeReply = { sessions.save(BackendTokens(OTHER, "", "", "")) }
    repo.step()
    assertNull(repo.current.first())
    assertEquals("WAITING", db.preparationDao().get(OWNER)!!.state)
  }

  @Test
  fun `recalculation preserves existing proposal and editor draft`() = runTest {
    val (_, repo) = fixture()
    repo.enqueue(intent())
    val row = db.preparationDao().get(OWNER)!!
    db.preparationDao().save(row.copy(state = "READY", proposalJson = "retained typed result"))
    repo.enqueue(intent().copy(availableDurationMinutes = 90))
    assertEquals(
        "retained typed result",
        db.preparationDao().get(OWNER, row.requestId)!!.proposalJson,
    )
  }

  @Test
  fun `backward clock still makes the latest explicit launch the form default`() = runTest {
    val (_, repo) = fixture()
    now = 500L
    val first = repo.enqueue(intent())
    now = 100L
    val second = repo.enqueue(intent())
    val rows = db.preparationDao().observeAll(OWNER).first()
    assertEquals(second, db.preparationDao().get(OWNER)!!.requestId)
    assertTrue(
        checkNotNull(rows.firstOrNull { it.requestId == second }).createdAtMillis >
            checkNotNull(rows.firstOrNull { it.requestId == first }).createdAtMillis
    )
  }
}

private fun intent() = CalendarAiIntent(2000, "UTC", emptyList())

private const val OWNER = "00000000-0000-4000-8000-000000000001"
private const val OTHER = "00000000-0000-4000-8000-000000000002"
private const val EXERCISE = "00000000-0000-4000-8000-000000000003"
private const val REFINEMENT = "00000000-0000-4000-8000-000000000004"
private const val REFINEMENT_TWO = "00000000-0000-4000-8000-000000000005"

private fun refinementDraft() =
    ApprovalDraft(
        "План",
        emptyList(),
        listOf(
            ProposalPlannedExercise(
                EXERCISE,
                60,
                listOf(ProposalPlannedSet(null, 10, null, null, null)),
            )
        ),
        2000,
        "UTC",
    )

private fun ruleProposal() =
    TrainingProposal(
        "00000000-0000-4000-8000-000000000008",
        ProposalAuthor(ProposalSource.RULE_BASED, null),
        OWNER,
        ProposalSource.RULE_BASED,
        ProposalStatus.PENDING,
        1,
        100,
        100,
        10000,
        ProposalSnapshot(1, refinementDraft(), 4, 7, 100),
    )

private fun excludeChange() = buildJsonObject {
  put("kind", JsonPrimitive("EXCLUDE"))
  put("exerciseId", JsonPrimitive(EXERCISE))
}

private fun jsonRequestId(bytes: String) =
    ProposalWire.json
        .parseToJsonElement(bytes)
        .jsonObject
        .getValue("requestId")
        .jsonPrimitive
        .content

private class Sessions : BackendSessionStore {
  override val session = MutableStateFlow<BackendTokens?>(BackendTokens(OWNER, "", "", ""))
  override var sessionEpoch = 1L

  override fun save(tokens: BackendTokens?) {
    sessionEpoch++
    session.value = tokens
  }
}

private class Ready : SyncReadySource {
  var receipt = SyncReady.Ready(OWNER, 4, 7, 1, 1)
  var blocked = false

  override suspend fun await(): SyncReady = if (blocked) SyncReady.Blocked else receipt

  override suspend fun isCurrent(ready: SyncReady.Ready) = !blocked && ready == receipt
}

private class Server : BackendTransport {
  override val json = ProposalWire.json
  val posts = mutableListOf<String>()
  var readyResult = false
  var invalidResult = false
  var gets = 0
  var loseAck = false
  var failServer = false
  var beforeReply: (suspend () -> Unit)? = null

  override suspend fun public(method: String, path: String, body: JsonElement?) =
      error("unexpected")

  override suspend fun authorized(method: String, path: String, body: JsonElement?) =
      error("unexpected")

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
    assertTrue(retryOnUnauthorized)
    if (path == "/planning/v2/capabilities") {
      assertEquals("GET", method)
      assertEquals(mapOf("X-Planner-Protocol" to "2"), headers)
      val body =
          "{\"schemaVersion\":2,\"protocol\":2,\"capability\":\"deterministic-workout-planner-v2\"}"
      return BackendResponse(
          json.parseToJsonElement(body),
          body.encodeToByteArray(),
          setOf("deterministic-workout-planner-v2"),
          expectedOwner,
          requireNotNull(expectedSessionEpoch),
      )
    }
    assertEquals(mapOf("X-Planner-Protocol" to "2"), headers)
    if (method == "POST") {
      assertTrue(
          path == "/planning/v2/jobs" ||
              path == "/planning/v2/proposals/00000000-0000-4000-8000-000000000008/refinements"
      )
      posts += rawBody.decodeToString()
    } else {
      assertTrue(path.startsWith("/planning/v2/jobs/"))
      assertTrue(rawBody.isEmpty())
      gets++
    }
    if (loseAck) {
      loseAck = false
      throw IOException("lost ACK")
    }
    if (failServer) {
      failServer = false
      throw BackendException(503, "server_unavailable", "")
    }
    beforeReply?.invoke()
    val id = json.parseToJsonElement(posts.last()).jsonObject.getValue("requestId")
    val result = buildJsonObject {
      put("requestId", id)
      put("state", if (readyResult) "READY" else "QUEUED")
      put("errorCode", JsonNull)
      put(
          "result",
          if (!readyResult) JsonNull
          else
              buildJsonObject {
                put("requestId", id)
                putJsonObject("context") {
                  put("revision", 4)
                  put("catalogRevision", 7)
                  put("capturedAtMillis", 100)
                }
                val draft =
                    ApprovalDraft(
                        "План",
                        emptyList(),
                        listOf(
                            ProposalPlannedExercise(
                                if (invalidResult) OTHER else EXERCISE,
                                60,
                                listOf(ProposalPlannedSet(null, 10, null, null, null)),
                            )
                        ),
                        2000,
                        "UTC",
                    )
                val proposal =
                    TrainingProposal(
                        "00000000-0000-4000-8000-000000000009",
                        ProposalAuthor(ProposalSource.AI, null),
                        OWNER,
                        ProposalSource.AI,
                        ProposalStatus.PENDING,
                        1,
                        100,
                        100,
                        10000,
                        ProposalSnapshot(1, draft, 4, 7, 100),
                    )
                put("proposal", json.encodeToJsonElement(proposal))
              },
      )
    }
    return BackendResponse(
        result,
        result.toString().encodeToByteArray(),
        setOf("deterministic-workout-planner-v2"),
        expectedOwner,
        expectedSessionEpoch!!,
    )
  }
}
