package com.valerochka1337.valerochkagym.data.trainingproposal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.calendar.ProposalScheduleConflict
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.service.WallClock
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TrainingProposalRepositoryTest : RoomDaoTest() {
  private val repositoryScopes = mutableListOf<CoroutineScope>()
  private val proposalStore = FakeDataStore()

  @After
  fun cancelRepositoryScopes() {
    repositoryScopes.forEach(CoroutineScope::cancel)
  }

  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(BackendTokens(OWNER, "a@b", "a", "r"))
    override var sessionEpoch = 1L
    override val sessionEpochs = MutableStateFlow(sessionEpoch)

    override fun save(tokens: BackendTokens?) {
      sessionEpoch++
      session.value = tokens
      sessionEpochs.value = sessionEpoch
    }
  }

  private class Server(val store: Store, val draft: ApprovalDraft) : BackendTransport {
    override val json = ProposalWire.json
    var accepted = false
    var loseApproval = false
    var rejectApproval = false
    var beforeSync: (suspend () -> Unit)? = null
    var beforeDetail: (() -> Unit)? = null
    var listFailure: Exception? = null
    var listGate: CompletableDeferred<Unit>? = null
    var listStarted: CompletableDeferred<Unit>? = null
    var listFinished: CompletableDeferred<Unit>? = null
    var listCalls = 0
    val listResponses = ArrayDeque<ProposalListResponse>()
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
            path.startsWith("/training-proposals?") -> {
              listCalls++
              listStarted?.complete(Unit)
              listGate?.await()
              listFailure?.let {
                listFinished?.complete(Unit)
                throw it
              }
              json.encodeToJsonElement(
                  if (listResponses.isEmpty()) ProposalListResponse(listOf(proposal()), null)
                  else listResponses.removeFirst()
              )
            }
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
            path.endsWith("/reject") ->
                json.encodeToJsonElement(
                    ProposalDecision(PROPOSAL, 1, ProposalStatus.REJECTED, updatedAt = 2)
                )
            else -> {
              beforeDetail?.invoke()
              json.encodeToJsonElement(proposal())
            }
          }
      listFinished?.complete(Unit)
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
      val scope: CoroutineScope,
  )

  private suspend fun fixture(
      readyBeforeRepository: ((Server) -> TrainingProposal)? = null,
  ): Fixture {
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
    readyBeforeRepository?.invoke(server)?.let { proposal ->
      db.preparationDao()
          .save(
              com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                  OWNER,
                  "00000000-0000-4000-8000-000000000076",
                  "{}",
                  "[]",
                  state = "READY",
                  proposalJson = ProposalWire.json.encodeToString(proposal),
              )
          )
    }
    val repo =
        TrainingProposalRepository(
            db,
            TrainingProposalApi(server, store),
            store,
            sync,
            WallClock { 1000 },
            proposalStore,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(repositoryScopes::add),
        )
    return Fixture(store, server, sync, repo, repositoryScopes.last())
  }

  @Test
  fun `empty inbox is loaded once and reused by repeated observers`() = runTest {
    val f = fixture()
    f.server.listResponses += ProposalListResponse(emptyList(), null)

    f.repository.ensureInitialLoad()
    awaitInbox(f.repository) { it is ProposalInboxState.Content }
    f.repository.ensureInitialLoad()

    assertEquals(1, f.server.listCalls)
    assertEquals(
        emptyList<TrainingProposal>(),
        (f.repository.inbox.value as ProposalInboxState.Content).content.items,
    )
  }

  @Test
  fun `nonlatest ready proposal opens offline and stays deleted after recreation with another ready row`() =
      runTest {
        val f = fixture(readyBeforeRepository = Server::proposal)
        val proposal = f.server.proposal()
        val olderPreparation = db.preparationDao().get(OWNER)!!
        db.preparationDao().save(olderPreparation.copy(state = "RUNNING", proposalJson = null))
        val other = proposal.copy(proposalId = "66666666-6666-4666-8666-666666666666")
        db.preparationDao()
            .save(
                com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                    OWNER,
                    "00000000-0000-4000-8000-000000000077",
                    "{}",
                    "[]",
                    state = "READY",
                    proposalJson = ProposalWire.json.encodeToString(other),
                    createdAtMillis = 1,
                )
            )
        // The older request finishes after the newer request without replacing its result.
        db.preparationDao().save(olderPreparation)
        awaitInbox(f.repository) {
          it is ProposalInboxState.NotLoaded && it.content?.items?.size == 2
        }
        f.server.beforeDetail = { throw IOException("offline") }
        assertEquals(PROPOSAL, f.repository.open(PROPOSAL).proposal.proposalId)
        f.repository.delete(proposal)
        val recreated =
            TrainingProposalRepository(
                db,
                TrainingProposalApi(f.server, f.store),
                f.store,
                f.sync,
                WallClock { 1000 },
                proposalStore,
                f.scope,
            )

        recreated.ensureInitialLoad()
        val inbox =
            awaitInbox(recreated) { it is ProposalInboxState.Content && it.content.items.size == 1 }

        assertEquals(
            listOf(other.proposalId),
            (inbox as ProposalInboxState.Content).content.items.map { it.proposalId },
        )
        assertTrue(runCatching { recreated.open(PROPOSAL) }.isFailure)
      }

  @Test
  fun `ready retained before initial ensure still fetches and merges the inbox`() = runTest {
    val retainedId = "66666666-6666-4666-8666-666666666666"
    val f = fixture { server -> server.proposal().copy(proposalId = retainedId) }
    awaitInbox(f.repository) {
      it is ProposalInboxState.NotLoaded &&
          it.content?.items?.singleOrNull()?.proposalId == retainedId
    }
    f.server.listGate = CompletableDeferred()
    f.server.listStarted = CompletableDeferred()
    f.repository.ensureInitialLoad()
    awaitServer(f.server.listStarted!!)
    assertEquals(1, f.server.listCalls)
    f.server.listGate?.complete(Unit)
    val content =
        awaitInbox(f.repository) { it is ProposalInboxState.Content && it.content.items.size == 2 }
            as ProposalInboxState.Content

    assertEquals(setOf(PROPOSAL, retainedId), content.content.items.map { it.proposalId }.toSet())
  }

  @Test
  fun `concurrent initial ensures and a new collector share one request`() = runTest {
    val f = fixture()
    f.server.listGate = CompletableDeferred()
    f.server.listStarted = CompletableDeferred()
    f.repository.ensureInitialLoad()
    f.repository.ensureInitialLoad()
    awaitServer(f.server.listStarted!!)
    withContext(Dispatchers.Default) {
      withTimeout(5_000) { f.repository.inbox.first { it is ProposalInboxState.Loading } }
    }
    assertEquals(1, f.server.listCalls)

    f.server.listGate?.complete(Unit)
    awaitInbox(f.repository) { it is ProposalInboxState.Content }
    withContext(Dispatchers.Default) {
      withTimeout(5_000) { f.repository.inbox.first { it is ProposalInboxState.Content } }
    }
    f.repository.ensureInitialLoad()
    assertEquals(1, f.server.listCalls)
  }

  @Test
  fun `late list from an old A B A epoch cannot publish into the current inbox`() = runTest {
    val f = fixture()
    f.server.listGate = CompletableDeferred()
    f.server.listStarted = CompletableDeferred()
    f.server.listFinished = CompletableDeferred()
    f.repository.ensureInitialLoad()
    awaitServer(f.server.listStarted!!)

    val original = requireNotNull(f.store.session.value)
    f.store.save(original.copy(userId = OTHER))
    f.store.save(original)
    awaitInbox(f.repository) { it is ProposalInboxState.NotLoaded }
    f.server.listGate?.complete(Unit)
    awaitServer(f.server.listFinished!!)

    assertTrue(f.repository.inbox.value is ProposalInboxState.NotLoaded)
    assertEquals(1, f.server.listCalls)
  }

  @Test
  fun `failed next page retries its cursor without discarding loaded proposals`() = runTest {
    val f = fixture()
    val next = f.server.proposal().copy(proposalId = "66666666-6666-4666-8666-666666666666")
    f.server.listResponses += ProposalListResponse(listOf(f.server.proposal()), "cursor-2")

    f.repository.ensureInitialLoad()
    awaitInbox(f.repository) {
      it is ProposalInboxState.Content && it.content.nextCursor == "cursor-2"
    }
    f.server.listFailure = IOException("offline")
    f.repository.loadMore()
    awaitInbox(f.repository) { it is ProposalInboxState.Error }
    f.server.listFailure = null
    f.server.listResponses += ProposalListResponse(listOf(next), null)
    f.repository.retryInitialLoad()
    val content =
        awaitInbox(f.repository) { it is ProposalInboxState.Content && it.content.items.size == 2 }
            as ProposalInboxState.Content

    assertEquals(listOf(PROPOSAL, next.proposalId), content.content.items.map { it.proposalId })
    assertEquals(3, f.server.listCalls)
  }

  @Test
  fun `ready and terminal overlays survive older list publications`() = runTest {
    val f = fixture()
    f.server.listResponses += ProposalListResponse(listOf(f.server.proposal()), "cursor-2")
    f.repository.ensureInitialLoad()
    awaitInbox(f.repository) { it is ProposalInboxState.Content }

    val newerReady =
        f.server
            .proposal()
            .copy(
                currentVersion = 2,
                snapshot = f.server.proposal().snapshot.copy(version = 2),
            )
    f.server.listGate = CompletableDeferred()
    f.repository.loadMore()
    db.preparationDao()
        .save(
            com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                OWNER,
                "00000000-0000-4000-8000-000000000077",
                "{}",
                "[]",
                state = "READY",
                proposalJson = ProposalWire.json.encodeToString(newerReady),
            )
        )
    awaitInbox(f.repository) {
      it.contentOrNullForTest()?.items?.singleOrNull()?.currentVersion == 2
    }
    f.server.listResponses += ProposalListResponse(listOf(f.server.proposal()), null)
    f.server.listGate?.complete(Unit)
    awaitInbox(f.repository) {
      it is ProposalInboxState.Content && it.content.items.single().currentVersion == 2
    }

    assertEquals(2, f.server.listCalls)
  }

  @Test
  fun `ready proposal stays visible when the first list request fails`() = runTest {
    val f = fixture()
    f.server.listGate = CompletableDeferred()
    f.server.listStarted = CompletableDeferred()
    f.server.listFinished = CompletableDeferred()
    f.server.listFailure = IOException("offline")
    f.repository.ensureInitialLoad()
    awaitServer(f.server.listStarted!!)
    db.preparationDao()
        .save(
            com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                OWNER,
                "00000000-0000-4000-8000-000000000079",
                "{}",
                "[]",
                state = "READY",
                proposalJson = ProposalWire.json.encodeToString(f.server.proposal()),
            )
        )
    awaitInbox(f.repository) {
      it is ProposalInboxState.Loading && it.content?.items?.singleOrNull()?.proposalId == PROPOSAL
    }
    f.server.listGate?.complete(Unit)
    awaitServer(f.server.listFinished!!)
    awaitInbox(f.repository) {
      it is ProposalInboxState.Error && it.content?.items?.singleOrNull()?.proposalId == PROPOSAL
    }
  }

  @Test
  fun `terminal decision survives ready replay and stale next page`() = runTest {
    val f = fixture()
    f.server.listResponses += ProposalListResponse(listOf(f.server.proposal()), "cursor-2")
    f.repository.ensureInitialLoad()
    awaitInbox(f.repository) {
      it is ProposalInboxState.Content && it.content.items.single().status == ProposalStatus.PENDING
    }
    f.repository.reject(f.repository.open(PROPOSAL))
    db.preparationDao()
        .save(
            com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                OWNER,
                "00000000-0000-4000-8000-000000000078",
                "{}",
                "[]",
                state = "READY",
                proposalJson = ProposalWire.json.encodeToString(f.server.proposal()),
            )
        )
    f.server.listResponses += ProposalListResponse(listOf(f.server.proposal()), null)
    f.repository.loadMore()
    awaitInbox(f.repository) {
      it is ProposalInboxState.Content &&
          it.content.nextCursor == null &&
          it.content.items.single().status == ProposalStatus.REJECTED
    }
    assertEquals(2, f.server.listCalls)
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
        db.preparationDao()
            .save(
                com.valerochka1337.valerochkagym.data.ai.PreparationEntity(
                    OWNER,
                    "00000000-0000-4000-8000-000000000078",
                    "{}",
                    "[]",
                    generation = generation,
                    state = "READY",
                    proposalJson =
                        ProposalWire.json.encodeToString(
                            editor.proposal.copy(
                                proposalId = "66666666-6666-4666-8666-666666666666"
                            )
                        ),
                    createdAtMillis = 1,
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
                proposalStore,
                f.scope,
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

  private fun copies(f: Fixture, calendarReady: Boolean = true) =
      ProposalCopyRepository(
          db,
          f.store,
          f.sync,
          com.valerochka1337.valerochkagym.data.GymRepositoryImpl(
              db,
              db.gymDao(),
              db.exerciseDao(),
              db.exerciseMuscleDao(),
              db.routineDao(),
              db.workoutDao(),
          ),
          object : com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate {
            override suspend fun ensureReady() = calendarReady
          },
          com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler,
          WallClock { 1000 },
          Dispatchers.Unconfined,
      )

  @Test
  fun `expired proposal saves a personal copy and replays without duplicates or approval`() =
      runTest {
        val f = fixture()
        val editor =
            f.repository.open(PROPOSAL).let {
              it.copy(
                  proposal = it.proposal.copy(status = ProposalStatus.STALE, expiresAt = 2),
                  draft = it.draft.copy(startsAtMillis = 0),
              )
            }
        val copies = copies(f)
        val operation = java.util.UUID.randomUUID().toString()
        val first = copies.save(editor, operation)
        val second = copies.save(editor, operation)

        assertEquals(first.id, second.id)
        assertEquals("PERSONAL", first.origin)
        val routine = db.routineDao().getRoutineWithExercises(first.id)!!
        assertEquals(10, routine.exercises.single().routineExercise.plannedSets.single().reps)
        assertEquals(20.0, routine.exercises.single().routineExercise.plannedSets.single().weightKg)
        assertEquals(1, db.routineDao().observeRoutinesFull().first().size)
        assertTrue(db.calendarPlanDao().plansWithRoutines().isEmpty())
        assertTrue(f.server.posts.isEmpty())
      }

  @Test
  fun `previous proposal schedules its saved copy at a new date without duplicating the program`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        val copies = copies(f)
        val operation = java.util.UUID.randomUUID().toString()
        val routine = copies.save(editor, operation)
        copies.save(editor, operation, editor.draft.startsAtMillis, "UTC")
        copies.save(editor, operation, editor.draft.startsAtMillis, "UTC")

        assertEquals(1, db.routineDao().observeRoutinesFull().first().size)
        assertEquals(routine.id, db.calendarPlanDao().plansWithRoutines().single().plan.routineId)
        assertTrue(f.server.posts.isEmpty())
      }

  @Test
  fun `overlapping scheduled copy rolls back while an unscheduled second copy remains valid`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        val copies = copies(f)
        copies.save(editor, "66666666-6666-4666-8666-666666666666", editor.draft.startsAtMillis)

        assertTrue(
            runCatching {
                  copies.save(
                      editor,
                      "77777777-7777-4777-8777-777777777777",
                      editor.draft.startsAtMillis,
                  )
                }
                .isFailure
        )
        assertEquals(1, db.routineDao().observeRoutinesFull().first().size)
        assertEquals(1, db.calendarPlanDao().planCount())

        copies.save(editor, "77777777-7777-4777-8777-777777777777")
        assertEquals(2, db.routineDao().observeRoutinesFull().first().size)
        assertEquals(1, db.calendarPlanDao().planCount())
      }

  @Test
  fun `same start conflicts with an empty routine plan`() = runTest {
    val f = fixture()
    val editor = f.repository.open(PROPOSAL)
    val emptyRoutineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Пустая программа"))
    db.calendarPlanDao()
        .insertPlan(
            CalendarPlanEntity(
                "88888888-8888-4888-8888-888888888888",
                emptyRoutineId,
                editor.draft.startsAtMillis,
                "UTC",
            )
        )

    assertTrue(
        runCatching {
              copies(f)
                  .save(
                      editor,
                      "99999999-9999-4999-8999-999999999999",
                      editor.draft.startsAtMillis,
                  )
            }
            .isFailure
    )
    assertEquals(1, db.routineDao().observeRoutinesFull().first().size)
    assertEquals(1, db.calendarPlanDao().planCount())
  }

  @Test
  fun `recurring plan conflicts with a candidate when no operation plan is excluded`() = runTest {
    val f = fixture()
    val date = LocalDate.of(2030, 1, 7)
    val start = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val editor =
        f.repository
            .open(PROPOSAL)
            .copy(draft = f.repository.open(PROPOSAL).draft.copy(startsAtMillis = start))
    val routine = copies(f).save(editor, java.util.UUID.randomUUID().toString())
    db.calendarPlanDao()
        .insertRule(
            CalendarRuleEntity(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                routine.id,
                date.dayOfWeek.value,
                "00:00",
                "UTC",
                date.toString(),
            )
        )

    assertTrue(
        ProposalScheduleConflict.hasConflict(
            editor.draft,
            db.calendarPlanDao().plansWithRoutines(),
            db.calendarPlanDao().rulesWithRoutines(),
            db.calendarPlanDao().allExceptions(),
            db.routineDao().routinesFullOnce(),
        )
    )
  }

  @Test
  fun `calendar copy failure rolls back the new program and an account change blocks copying`() =
      runTest {
        val f = fixture()
        val editor = f.repository.open(PROPOSAL)
        val failed = runCatching {
          copies(f).save(editor, java.util.UUID.randomUUID().toString(), startsAtMillis = 999)
        }
        assertTrue(failed.isFailure)
        assertTrue(db.routineDao().observeRoutinesFull().first().isEmpty())
        f.store.save(null)
        assertTrue(
            runCatching { copies(f).save(editor, java.util.UUID.randomUUID().toString()) }.isFailure
        )
        assertTrue(db.routineDao().observeRoutinesFull().first().isEmpty())
      }

  companion object {
    const val OWNER = "11111111-1111-4111-8111-111111111111"
    const val OTHER = "99999999-9999-4999-8999-999999999999"
    const val PROPOSAL = "22222222-2222-4222-8222-222222222222"
    const val ROUTINE = "33333333-3333-4333-8333-333333333333"
    const val PLAN = "44444444-4444-4444-8444-444444444444"
    const val EXERCISE = "55555555-5555-4555-8555-555555555555"
  }

  private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
  }
}

private fun ProposalInboxState.contentOrNullForTest(): ProposalInboxContent? =
    when (this) {
      is ProposalInboxState.Content -> content
      is ProposalInboxState.Error -> content
      is ProposalInboxState.Loading -> content
      is ProposalInboxState.NotLoaded -> null
    }

private suspend fun awaitInbox(
    repository: TrainingProposalRepository,
    predicate: (ProposalInboxState) -> Boolean,
): ProposalInboxState =
    withContext(Dispatchers.Default) { withTimeout(5_000) { repository.inbox.first(predicate) } }

private suspend fun awaitServer(signal: CompletableDeferred<Unit>) {
  withContext(Dispatchers.Default) { withTimeout(5_000) { signal.await() } }
}
