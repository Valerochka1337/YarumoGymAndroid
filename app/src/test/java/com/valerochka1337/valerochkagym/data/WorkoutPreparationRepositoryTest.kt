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
  fun `offline enqueue is durable and repeated taps keep one identity`() = runTest {
    val (sync, repo) = fixture()
    val id = repo.enqueue(intent())
    assertEquals(id, repo.enqueue(intent()))
    assertEquals(id, repository(sync).current.first()!!.requestId)
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
  fun `late response cannot replace newer conditions and carries lineage`() = runTest {
    val (_, repo) = fixture()
    val first = repo.enqueue(intent())
    api.beforeReply = { repo.enqueue(intent().copy(availableDurationMinutes = 75)) }
    repo.step()
    val next = db.preparationDao().get(OWNER)!!
    assertNotEquals(first, next.requestId)
    assertEquals("WAITING", next.state)
    assertTrue(next.replacesJson.contains(first))
    api.beforeReply = null
    repo.step()
    assertTrue(api.posts.last().contains(first))
  }

  @Test
  fun `exhausted delivery is explicit and manual retry keeps exact request`() = runTest {
    val (_, repo) = fixture()
    val id = repo.enqueue(intent())
    api.loseAck = true
    repo.step(id)
    val bytes = db.preparationDao().get(OWNER)!!.requestJson
    repo.pausePending(id)
    assertEquals("PAUSED_WAITING", db.preparationDao().get(OWNER)!!.state)
    assertEquals(id, repo.enqueue(intent()))
    assertEquals(bytes, db.preparationDao().get(OWNER)!!.requestJson)
    assertTrue(repo.step(id))
  }

  @Test
  fun `obsolete worker cannot process or pause replacement`() = runTest {
    val (_, repo) = fixture()
    val old = repo.enqueue(intent())
    val next = repo.enqueue(intent().copy(availableDurationMinutes = 75))
    assertFalse(repo.step(old))
    repo.pausePending(old)
    assertEquals(next, db.preparationDao().get(OWNER)!!.requestId)
    assertEquals("WAITING", db.preparationDao().get(OWNER)!!.state)
    assertTrue(api.posts.isEmpty())
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
    assertEquals("retained typed result", db.preparationDao().get(OWNER)!!.proposalJson)
  }
}

private fun intent() = CalendarAiIntent(2000, "UTC", emptyList())

private const val OWNER = "00000000-0000-4000-8000-000000000001"
private const val OTHER = "00000000-0000-4000-8000-000000000002"
private const val EXERCISE = "00000000-0000-4000-8000-000000000003"

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
    if (method == "POST") {
      assertEquals("/ai/calendar-draft-jobs", path)
      posts += rawBody.decodeToString()
    } else {
      assertTrue(path.startsWith("/ai/calendar-draft-jobs/"))
      assertTrue(rawBody.isEmpty())
      gets++
    }
    if (loseAck) {
      loseAck = false
      throw IOException("lost ACK")
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
        emptySet(),
        expectedOwner,
        expectedSessionEpoch!!,
    )
  }
}
