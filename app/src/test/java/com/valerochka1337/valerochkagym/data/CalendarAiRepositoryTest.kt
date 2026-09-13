package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.service.WallClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CalendarAiRepositoryTest : RoomDaoTest() {
  private class Ready : SyncReadySource {
    var current = true
    var blocked = false
    var failure: Exception? = null

    override suspend fun await(): SyncReady {
      failure?.let { throw it }
      return if (blocked) SyncReady.Blocked else receipt
    }

    override suspend fun isCurrent(ready: SyncReady.Ready) = current && ready == receipt

    val receipt = SyncReady.Ready(OWNER, 4, 7, 3, 1)
  }

  private class Server(val ready: Ready) : BackendTransport {
    override val json = ProposalWire.json
    var calls = 0
    var body: JsonObject? = null
    var stale = false
    var badContext = false
    var forbidden = false

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
      calls++
      assertEquals("/ai/calendar-drafts", path)
      assertFalse(retryOnUnauthorized)
      assertEquals(OWNER, expectedOwner)
      assertEquals(3L, expectedSessionEpoch)
      body = json.parseToJsonElement(rawBody.decodeToString()).jsonObject
      val request = body!!
      val draft =
          ApprovalDraft(
              "План",
              emptyList(),
              listOf(
                  ProposalPlannedExercise(
                      if (forbidden) OTHER else EXERCISE,
                      60,
                      listOf(ProposalPlannedSet(null, 10, null, null, null)),
                  )
              ),
              request.getValue("startsAtMillis").jsonPrimitive.long,
              "UTC",
          )
      val p =
          TrainingProposal(
              PROPOSAL,
              ProposalAuthor(ProposalSource.AI, null),
              OWNER,
              ProposalSource.AI,
              ProposalStatus.PENDING,
              1,
              100,
              100,
              10000000000000,
              ProposalSnapshot(1, draft, 4, 7, 100),
          )
      val response = buildJsonObject {
        put("requestId", request.getValue("requestId"))
        putJsonObject("context") {
          put("revision", if (badContext) 5 else 4)
          put("catalogRevision", 7)
          put("capturedAtMillis", 100)
        }
        put("proposal", json.encodeToJsonElement(p))
      }
      if (stale) ready.current = false
      return BackendResponse(
          response,
          response.toString().encodeToByteArray(),
          emptySet(),
          OWNER,
          3,
      )
    }
  }

  private suspend fun fixture(): Pair<Ready, Server> {
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
    val r = Ready()
    return r to Server(r)
  }

  private fun repo(r: Ready, s: Server) =
      CalendarAiRepository(
          s,
          r,
          db,
          object : WallClock {
            override fun nowMillis() = 100L
          },
      )

  private fun intent() =
      CalendarAiIntent(
          1893456000000,
          "UTC",
          emptyList(),
          includeNotes = false,
          currentState = "Хочу спокойно",
          preferences = "Без спешки",
      )

  @Test
  fun `calendar action sends only typed intent after Ready and returns unapplied proposal`() =
      runTest {
        val (r, s) = fixture()
        val result = repo(r, s).generate(intent())
        assertEquals(PROPOSAL, result.proposalId)
        assertEquals(
            setOf(
                "requestId",
                "expectedRevision",
                "expectedCatalogRevision",
                "startsAtMillis",
                "timeZoneId",
                "gymIds",
                "excludedExerciseIds",
                "excludedEquipmentIds",
                "priorityMuscles",
                "includeNotes",
                "availableDurationMinutes",
                "currentState",
                "preferences",
            ),
            s.body!!.keys,
        )
        assertFalse(s.body!!.getValue("includeNotes").jsonPrimitive.boolean)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM calendar_plans").use {
          it.moveToFirst()
          assertEquals(0, it.getInt(0))
        }
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM training_proposal_operations")
            .use {
              it.moveToFirst()
              assertEquals(0, it.getInt(0))
            }
      }

  @Test
  fun `blocked readiness and excluded candidate stop before dispatch`() = runTest {
    val (r, s) = fixture()
    r.blocked = true
    assertEquals(
        "ai_sync_failed",
        (runCatching { repo(r, s).generate(intent()) }.exceptionOrNull() as BackendException).code,
    )
    assertEquals(0, s.calls)
    r.blocked = false
    assertTrue(
        runCatching { repo(r, s).generate(intent().copy(excludedExerciseIds = listOf(EXERCISE))) }
            .isFailure
    )
    assertEquals(0, s.calls)
  }

  @Test
  fun `late context change mismatched revision and unavailable exercise reject complete response`() =
      runTest {
        val (r, s) = fixture()
        s.stale = true
        assertTrue(runCatching { repo(r, s).generate(intent()) }.isFailure)
        s.stale = false
        r.current = true
        s.badContext = true
        assertEquals(
            "ai_invalid_response",
            (runCatching { repo(r, s).generate(intent()) }.exceptionOrNull() as BackendException)
                .code,
        )
        s.badContext = false
        s.forbidden = true
        assertTrue(runCatching { repo(r, s).generate(intent()) }.isFailure)
      }

  @Test
  fun `active workout blocks calendar AI before provider request`() = runTest {
    val (r, s) = fixture()
    db.workoutDao().insertWorkout(WorkoutEntity(id = "active", name = "Тренировка", startedAt = 1))
    assertEquals(
        "workout_active",
        (runCatching { repo(r, s).generate(intent()) }.exceptionOrNull() as BackendException).code,
    )
    assertEquals(0, s.calls)
  }

  @Test
  fun `readiness cancellation propagates unchanged without dispatch`() = runTest {
    val (r, s) = fixture()
    val cancellation = kotlinx.coroutines.CancellationException("private")
    r.failure = cancellation
    assertSame(cancellation, runCatching { repo(r, s).generate(intent()) }.exceptionOrNull())
    assertEquals(0, s.calls)
  }

  @Test
  fun `intent validates future canonical zone duration and nullable text without a mandatory profile`() {
    assertTrue(intent().valid(100))
    assertFalse(intent().copy(startsAtMillis = 99).valid(100))
    assertFalse(intent().copy(timeZoneId = "+03:00").valid(100))
    assertFalse(intent().copy(availableDurationMinutes = 9).valid(100))
    assertFalse(intent().copy(currentState = " ").valid(100))
    assertTrue(intent().copy(currentState = null, preferences = null).valid(100))
  }

  companion object {
    const val OWNER = "11111111-1111-4111-8111-111111111111"
    const val PROPOSAL = "22222222-2222-4222-8222-222222222222"
    const val EXERCISE = "33333333-3333-4333-8333-333333333333"
    const val OTHER = "44444444-4444-4444-8444-444444444444"
  }
}
