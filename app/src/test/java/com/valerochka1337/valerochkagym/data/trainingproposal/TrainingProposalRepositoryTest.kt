package com.valerochka1337.valerochkagym.data.trainingproposal

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.service.WallClock
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TrainingProposalRepositoryTest : RoomDaoTest() {
  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(BackendTokens(OWNER, "a@b", "a", "r"))
    override var sessionEpoch = 1L

    override fun save(tokens: BackendTokens?) {
      sessionEpoch++
      session.value = tokens
    }
  }

  private class Server(val store: Store, val draft: ApprovalDraft) : BackendTransport {
    override val json = ProposalWire.json
    var accepted = false
    var loseApproval = false
    var rejectApproval = false
    var beforeSync: (suspend () -> Unit)? = null
    var beforeDetail: (() -> Unit)? = null
    var snapshot = CloudSnapshot(12, emptyList())
    val posts = mutableListOf<ByteArray>()

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
      val epoch = store.sessionEpoch
      val owner = store.session.value!!.userId
      val body: JsonElement =
          when {
            path == "/sync" -> {
              beforeSync?.invoke()
              json.encodeToJsonElement(snapshot)
            }
            path.endsWith("/accepted-result") ->
                if (accepted) json.encodeToJsonElement(result())
                else throw BackendException(409, "proposal_not_approved", "not approved")
            path.endsWith("/approve") -> {
              posts += rawBody.copyOf()
              if (rejectApproval) {
                rejectApproval = false
                throw BackendException(400, "invalid_request", "invalid draft")
              }
              accepted = true
              if (loseApproval) {
                loseApproval = false
                throw IOException("response lost")
              }
              json.encodeToJsonElement(result())
            }
            else -> {
              beforeDetail?.invoke()
              json.encodeToJsonElement(proposal())
            }
          }
      return BackendResponse(
          body,
          body.toString().encodeToByteArray(),
          setOf(
              "calendar-plans",
              "exercise-hint",
              "annotated-workout-writes",
              "profile",
              "health-ledger-v1",
          ),
          owner,
          epoch,
      )
    }

    fun proposal() =
        TrainingProposal(
            PROPOSAL,
            ProposalAuthor(ProposalSource.AI, null),
            OWNER,
            ProposalSource.AI,
            if (accepted) ProposalStatus.APPROVED else ProposalStatus.PENDING,
            1,
            1,
            1,
            Long.MAX_VALUE,
            ProposalSnapshot(1, draft, 0, 0, 1),
        )

    fun result() = AcceptedProposalResult(PROPOSAL, 1, ROUTINE, PLAN, 7, 2)
  }

  private data class Fixture(
      val store: Store,
      val server: Server,
      val sync: BackendSync,
      val repository: TrainingProposalRepository,
  )

  private suspend fun fixture(): Fixture {
    val store = Store()
    val draft =
        ApprovalDraft(
            "Исходный план",
            emptyList(),
            listOf(
                ProposalPlannedExercise(
                    EXERCISE,
                    90,
                    listOf(ProposalPlannedSet(20.0, 10, null, null, null)),
                )
            ),
            1893456000000,
            "UTC",
        )
    val server = Server(store, draft)
    val sync = BackendSync(db, server, store)
    sync.claim(OWNER)
    val exercise =
        ExerciseEntity(
            name = "Приседание",
            muscleGroup = MuscleGroup.LEGS,
            type = ExerciseType.STRENGTH,
            isCustom = true,
            syncId = EXERCISE,
            updatedAt = 1,
            equipmentRequirementState = EquipmentRequirementState.KNOWN,
        )
    db.exerciseDao().insert(exercise)
    val exercisePayload =
        PortableData(db.openHelper.writableDatabase).snapshot().getValue("exercise:$EXERCISE")
    val routine = buildJsonObject {
      put("name", "Принятый план")
      put("note", "")
      put("updatedAt", 2)
      put("origin", "PERSONAL")
      put("archived", false)
      put("gymIds", JsonArray(emptyList()))
      put(
          "exercises",
          JsonArray(
              listOf(
                  buildJsonObject {
                    put("exerciseId", EXERCISE)
                    put("position", 0)
                    put("restSeconds", 90)
                    put(
                        "plannedSets",
                        ProposalWire.json.encodeToJsonElement(draft.exercises.single().plannedSets),
                    )
                  }
              )
          ),
      )
    }
    val plan = buildJsonObject {
      put("routineId", ROUTINE)
      put("startsAtMillis", draft.startsAtMillis)
      put("timeZoneId", "UTC")
      put("legacyScheduleId", JsonNull)
    }
    server.snapshot =
        CloudSnapshot(
            12,
            listOf(
                CloudRecord("exercise", EXERCISE, 1, payload = exercisePayload),
                CloudRecord("routine", ROUTINE, 7, payload = routine),
                CloudRecord("calendar_plan", PLAN, 7, payload = plan),
                CloudRecord(
                    "routine",
                    "77777777-7777-4777-8777-777777777777",
                    12,
                    payload = JsonObject(routine + ("name" to JsonPrimitive("Другая программа"))),
                ),
            ),
        )
    val repo =
        TrainingProposalRepository(
            db,
            TrainingProposalApi(server, store),
            store,
            sync,
            WallClock { 1000 },
        )
    return Fixture(store, server, sync, repo)
  }

  @Test
  fun `unsynced local edits block prepared proposal approval before an operation is created`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        val generation = db.preparationDao().generation(OWNER)!!
        db.preparationDao()
            .save(
                com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                    OWNER,
                    "00000000-0000-4000-8000-000000000077",
                    "{}",
                    "[]",
                    generation = generation,
                    state = "READY",
                    proposalJson =
                        ProposalWire.json.encodeToJsonElement(editor.proposal).toString(),
                )
            )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET generation=generation+1 WHERE id=1"
        )
        try {
          f.repository.approve(editor)
          fail("Stale preparation accepted")
        } catch (error: BackendException) {
          assertEquals("proposal_stale", error.code)
        }
        assertTrue(f.server.posts.isEmpty())
        assertNull(
            db.trainingProposalDao().operation(OWNER, PROPOSAL, editor.proposal.currentVersion)
        )
      }

  @Test
  fun `incomplete replacement survives reopening without sending approval`() = runTest {
    val f = fixture()
    val editor = f.repository.open(PROPOSAL)
    val incomplete =
        editor.draft.copy(
            exercises =
                editor.draft.exercises.map {
                  it.copy(plannedSets = listOf(ProposalPlannedSet(null, null, null, null, null)))
                }
        )
    f.repository.save(editor, incomplete)
    assertEquals(incomplete, f.repository.open(PROPOSAL).draft)
    assertTrue(f.server.posts.isEmpty())
    assertFalse(ProposalWire.validDraft(incomplete))
  }

  @Test
  fun `edited draft survives recreation and approval imports one linked projection preserving existing outbox`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        f.repository.save(editor, editor.draft.copy(name = "Мой план"))
        val recreated =
            TrainingProposalRepository(
                db,
                TrainingProposalApi(f.server, f.store),
                f.store,
                f.sync,
                WallClock { 1000 },
            )
        val restored = recreated.open(PROPOSAL)
        assertEquals("Мой план", restored.draft.name)
        assertEquals("Исходный план", restored.proposal.snapshot.draft.name)
        val held = " { \"operationId\" : \"held\", \"changes\" : [] } "
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,?,?)",
            arrayOf(OWNER, held),
        )
        recreated.approve(restored)
        assertEquals(1, tableCount("calendar_plans"))
        assertEquals(2, tableCount("routines"))
        assertEquals(12L, db.trainingProposalDao().projection(OWNER, PROPOSAL, 1)?.syncRevision)
        db.openHelper.writableDatabase
            .query("SELECT requestJson FROM backend_outbox WHERE id=1")
            .use {
              it.moveToFirst()
              assertEquals(held, it.getString(0))
            }
        val operation = requireNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1))
        assertArrayEquals(operation.requestBytes, f.server.posts.single())
        assertEquals(
            "Мой план",
            ProposalWire.decode<ApprovalRequest>(operation.requestBytes).draft.name,
        )
        recreated.approve(restored)
        assertEquals(1, f.server.posts.size)
        assertEquals(1, tableCount("training_proposal_projections"))
      }

  @Test
  fun `lost approval response recovers accepted result after recreation without another POST`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        f.server.loseApproval = true
        assertTrue(runCatching { f.repository.approve(editor) }.exceptionOrNull() is IOException)
        val raw =
            requireNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1))
                .requestBytes
                .copyOf()
        assertEquals(0, tableCount("calendar_plans"))
        val recovered = f.repository.open(PROPOSAL)
        f.repository.approve(recovered)
        assertEquals(1, f.server.posts.size)
        assertArrayEquals(
            raw,
            requireNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1)).requestBytes,
        )
        assertEquals(1, tableCount("calendar_plans"))
      }

  @Test
  fun `active workout beginning after full response leaves accepted journal recoverable without projection`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        f.server.beforeSync = { insertWorkout("active") }
        assertTrue(
            runCatching { f.repository.approve(editor) }.exceptionOrNull() is BackendException
        )
        assertNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1)?.acceptedResultJson)
        assertEquals(0, tableCount("calendar_plans"))
        assertEquals(0, tableCount("training_proposal_projections"))
      }

  @Test
  fun `changed draft after captured approval denies before another network mutation`() = runTest {
    val f = fixture()
    val editor = f.repository.open(PROPOSAL)
    f.server.loseApproval = true
    runCatching { f.repository.approve(editor) }
    val failure =
        runCatching {
              f.repository.approve(editor.copy(draft = editor.draft.copy(name = "Другой")))
            }
            .exceptionOrNull()
    assertEquals("proposal_operation_conflict", (failure as BackendException).code)
    assertEquals(1, f.server.posts.size)
  }

  @Test
  fun `definite validation rejection permits corrected operation while retaining original bytes`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        f.server.rejectApproval = true
        assertTrue(
            runCatching { f.repository.approve(editor) }.exceptionOrNull() is BackendException
        )
        val rejectedBytes = f.server.posts.single().copyOf()
        val corrected = f.repository.save(editor, editor.draft.copy(name = "Исправленный план"))
        f.repository.approve(corrected)
        assertEquals(2, f.server.posts.size)
        assertFalse(f.server.posts[0].contentEquals(f.server.posts[1]))
        db.openHelper.writableDatabase
            .query("SELECT requestBytes FROM training_proposal_operations WHERE rejected=1")
            .use {
              assertTrue(it.moveToFirst())
              assertArrayEquals(rejectedBytes, it.getBlob(0))
            }
      }

  @Test
  fun `late A B A detail cannot persist or expose stale editor`() = runTest {
    val f = fixture()
    f.server.beforeDetail = {
      val original = f.store.session.value
      f.store.save(original?.copy(userId = OTHER))
      f.store.save(original)
    }
    assertTrue(runCatching { f.repository.open(PROPOSAL) }.exceptionOrNull() is BackendException)
    assertEquals(0, tableCount("training_proposal_drafts"))
  }

  @Test
  fun `missing local aggregate invalidates applied marker while keeping recovery journal`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        f.repository.approve(editor)
        db.openHelper.writableDatabase.execSQL("DELETE FROM calendar_plans")
        assertFalse(f.repository.open(PROPOSAL).applied)
        assertNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1))
      }

  @Test
  fun `unrelated manual conflict retains accepted operation and leaves all proposal records unapplied`() =
      runTest {
        val f = fixture()
        val gymId = "88888888-8888-4888-8888-888888888888"
        db.gymDao()
            .insertGym(
                GymEntity(
                    syncId = gymId,
                    name = "Локальный зал",
                    updatedAt = 3,
                    inventoryConfigured = true,
                )
            )
        val current = PortableData(db.openHelper.writableDatabase).snapshot().getValue("gym:$gymId")
        val old =
            CloudRecord(
                "gym",
                gymId,
                1,
                payload = JsonObject(current + ("name" to JsonPrimitive("Раньше"))),
            )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO backend_baseline(key,recordJson) VALUES(?,?)",
            arrayOf(old.key, ProposalWire.json.encodeToJsonElement(old).toString()),
        )
        f.server.snapshot =
            f.server.snapshot.copy(
                records =
                    f.server.snapshot.records +
                        CloudRecord(
                            "gym",
                            gymId,
                            10,
                            payload =
                                JsonObject(current + ("name" to JsonPrimitive("Удалённый зал"))),
                        )
            )
        val editor = f.repository.open(PROPOSAL)
        assertEquals(
            "revision_conflict",
            (runCatching { f.repository.approve(editor) }.exceptionOrNull() as BackendException)
                .code,
        )
        assertNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1)?.acceptedResultJson)
        assertEquals(0, tableCount("training_proposal_projections"))
        assertEquals(0, tableCount("calendar_plans"))
        assertEquals("Локальный зал", db.gymDao().getGymBySyncId(gymId)?.name)
      }

  @Test
  fun `clean owner round trip removes only projection marker and recovers accepted result`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        f.repository.approve(editor)
        val bytes =
            requireNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1))
                .requestBytes
                .copyOf()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
        )
        // Model the ordinary sync ACK of the imported, locally normalized snapshot.
        val raw = db.openHelper.writableDatabase
        PortableData(raw).snapshot().forEach { (key, payload) ->
          val record =
              CloudRecord(key.substringBefore(':'), key.substringAfter(':'), 12, false, payload)
          raw.execSQL(
              "INSERT OR REPLACE INTO backend_baseline(`key`,recordJson) VALUES(?,?)",
              arrayOf(key, ProposalWire.json.encodeToJsonElement(record).toString()),
          )
        }
        assertTrue(f.sync.claim(OTHER) is GuestClaimResult.Claimed)
        f.store.save(f.store.session.value?.copy(userId = OTHER))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE backend_state SET phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
        )
        assertTrue(f.sync.claim(OWNER) is GuestClaimResult.Claimed)
        f.store.save(f.store.session.value?.copy(userId = OWNER))
        val restored = f.repository.open(PROPOSAL)
        assertFalse(restored.applied)
        assertArrayEquals(
            bytes,
            requireNotNull(db.trainingProposalDao().operation(OWNER, PROPOSAL, 1)).requestBytes,
        )
        f.repository.approve(restored)
        assertTrue(f.repository.open(PROPOSAL).applied)
        assertEquals(1, f.server.posts.size)
      }

  companion object {
    const val OWNER = "11111111-1111-4111-8111-111111111111"
    const val OTHER = "99999999-9999-4999-8999-999999999999"
    const val PROPOSAL = "22222222-2222-4222-8222-222222222222"
    const val ROUTINE = "33333333-3333-4333-8333-333333333333"
    const val PLAN = "44444444-4444-4444-8444-444444444444"
    const val EXERCISE = "55555555-5555-4555-8555-555555555555"
  }
}
