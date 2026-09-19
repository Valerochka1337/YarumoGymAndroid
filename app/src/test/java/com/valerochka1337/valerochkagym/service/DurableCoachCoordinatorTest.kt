package com.valerochka1337.valerochkagym.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DurableCoachCoordinatorTest : RoomDaoTest() {
  @Test
  fun `disabling live coach closes server initiative blocks messages and reenable restores it`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport()
        val settings = SettingsRepository(MemorySettings())
        val fixture = fixture(transport, settings)
        fixture.coordinator.deliverPending()
        assertTrue(transport.sessionUpdates.last()["active"]!!.jsonPrimitive.boolean)

        settings.setLiveCoachEnabled(false)
        assertFalse(fixture.coordinator.send(workout, "Подскажи"))
        fixture.coordinator.deliverPending()
        assertFalse(transport.sessionUpdates.last()["active"]!!.jsonPrimitive.boolean)
        assertFalse(transport.sessionUpdates.last()["initiativeEnabled"]!!.jsonPrimitive.boolean)
        assertTrue(transport.submissions.isEmpty())

        settings.setLiveCoachEnabled(true)
        fixture.coordinator.deliverPending()
        assertTrue(transport.sessionUpdates.last()["active"]!!.jsonPrimitive.boolean)
        assertTrue(transport.sessionUpdates.last()["initiativeEnabled"]!!.jsonPrimitive.boolean)
        assertTrue(fixture.coordinator.send(workout, "Подскажи"))
      }

  private class MemorySettings : DataStore<Preferences> {
    override val data = MutableStateFlow<Preferences>(emptyPreferences())

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
  }

  @Test
  fun `conversation confirms a proposal and undoes it through explicit user action`() = runTest {
    val workout = activeWorkout()
    val fixture = fixture(FakeTransport())
    val snapshot = fixture.reader.snapshot("user", workout, 0)!!
    val set = snapshot.exercises.single().sets.single()
    val packet =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, weightKg = 45.0))
        )
    val proposal =
        fixture.editor.saveProposal(
            "user",
            workout,
            packet,
            snapshot.revision,
            System.currentTimeMillis() + 60_000,
        )!!

    assertTrue(fixture.conversation.confirm(workout, proposal.id))
    assertEquals(
        45.0,
        db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single().weightKg!!,
        0.0,
    )
    assertTrue(
        db.coachDao().messages(workout).any {
          it.role == "system" && it.text.startsWith("APPLIED|")
        }
    )
    assertTrue(fixture.conversation.undo(workout))
    assertEquals(
        set.weightKg!!,
        db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single().weightKg!!,
        0.0,
    )
  }

  @Test
  fun `conversation rejects a proposal and disables server initiative without changing sets`() =
      runTest {
        val workout = activeWorkout()
        val fixture = fixture(FakeTransport())
        val snapshot = fixture.reader.snapshot("user", workout, 0)!!
        val set = snapshot.exercises.single().sets.single()
        val packet =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, weightKg = 45.0))
            )
        val proposal =
            fixture.editor.saveProposal(
                "user",
                workout,
                packet,
                snapshot.revision,
                System.currentTimeMillis() + 60_000,
            )!!

        assertFalse(fixture.conversation.cancel("another-workout", proposal.id))
        assertTrue(fixture.conversation.cancel(workout, proposal.id))
        assertNull(db.coachDao().pendingProposal(workout))
        assertEquals(
            set.weightKg!!,
            db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single().weightKg!!,
            0.0,
        )
        assertTrue(db.coachDao().messages(workout).any { it.text.startsWith("REJECTED|") })
        assertTrue(fixture.conversation.disableInitiative(workout))
        assertFalse(db.coachDao().context(workout)!!.initiativeEnabled)
        assertTrue(db.coachRunDao().dirtySessions().any { it.workoutId == workout })
      }

  @Test
  fun `sending offline persists immutable request and user message together before networking`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport()
        val fixture = fixture(transport)
        assertTrue(fixture.conversation.send(workout, "Что изменить?"))
        assertEquals(0, transport.submissions.size)
        val run = db.coachRunDao().pending("user").single()
        val request = Json.parseToJsonElement(run.requestJson).jsonObject
        assertEquals(run.requestId, request["requestId"]!!.jsonPrimitive.content)
        assertEquals(
            run.contextVersion,
            request["state"]!!.jsonObject["contextVersion"]!!.jsonPrimitive.content,
        )
        assertFalse(request.containsKey("history"))
        assertFalse(request.containsKey("automatic"))
        assertEquals("Что изменить?", db.coachDao().messages(workout).single().text)
        assertFalse(run.submitted)
        assertTrue(db.coachRunDao().dirtySessions().any { it.workoutId == workout })
        fixture.coordinator.detach()
        assertEquals(run, db.coachRunDao().pending("user").single())
      }

  @Test
  fun `recreated coordinator submits retained request and imports terminal answer once`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport()
        val first = fixture(transport)
        assertTrue(first.coordinator.send(workout, "Объясни тренировку"))
        val retained = db.coachRunDao().pending("user").single()
        first.coordinator.detach()
        val next = fixture(transport)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
          next.coordinator.attach(scope)
          withContext(Dispatchers.Default) {
            withTimeout(15_000) {
              db.coachDao().observeMessages(workout).first { rows ->
                rows.any { it.role == "assistant" }
              }
            }
          }
          assertEquals(listOf(retained.requestJson), transport.submissions)
          assertTrue(db.coachRunDao().run(retained.requestId)!!.imported)
          assertEquals(1, db.coachDao().messages(workout).count { it.role == "assistant" })
        } finally {
          next.coordinator.detach()
          scope.cancel()
        }
      }

  @Test
  fun `changed profile rejects confirmation and stores stale receipt without editing sets`() =
      runTest {
        val workout = activeWorkout()
        val fixture = fixture(FakeTransport())
        val snapshot = fixture.reader.snapshot("user", workout, 0)!!
        val id = UUID.randomUUID().toString()
        val proposalId = UUID.randomUUID().toString()
        val version = CoachToolCodec.contextVersion(snapshot)
        val run =
            CoachRunEntity(
                id,
                "user",
                workout,
                "{}",
                version,
                1,
                submitted = true,
                proposalId = proposalId,
            )
        db.coachRunDao().insert(run)
        val set = snapshot.exercises.single().sets.single()
        val result =
            fixture.editor.saveModelProposalResult(
                "user",
                workout,
                snapshot.revision,
                listOf(
                    CoachChangeIntent.EditSet(
                        set.syncId,
                        CoachSetValues(setOf("weight_kg"), weightKg = 45.0),
                        false,
                    )
                ),
                System.currentTimeMillis() + 60_000,
                0,
                serverProposalId = proposalId,
                expectedContextVersion = version,
            )
        assertTrue(result is ModelProposalSaveResult.Saved)
        assertEquals(proposalId, (result as ModelProposalSaveResult.Saved).proposal.id)
        db.profileDao().upsert(ProfileEntity("user", "profile", trainingGoal = "STRENGTH"))
        val receipt =
            fixture.editor.confirmProposal("user", proposalId, UUID.randomUUID().toString(), 0)
        assertEquals(CommandResult.STALE, receipt.result)
        assertEquals(
            50.0,
            db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single().weightKg!!,
            0.0,
        )
        val outbound = db.coachRunDao().receipts("user").single()
        assertEquals(id, outbound.runId)
        assertEquals(
            "STALE",
            Json.parseToJsonElement(outbound.payload).jsonObject["status"]!!.jsonPrimitive.content,
        )
      }

  @Test
  fun `server proposal import preserves identity and replays without creating another proposal`() =
      runTest {
        val workout = activeWorkout()
        val fixture = fixture(FakeTransport())
        val snapshot = fixture.reader.snapshot("user", workout, 0)!!
        val id = UUID.randomUUID().toString()
        val expires = System.currentTimeMillis() + 60_000
        val intents =
            listOf(
                CoachChangeIntent.EditSet(
                    snapshot.exercises.single().sets.single().syncId,
                    CoachSetValues(setOf("weight_kg"), weightKg = 45.0),
                    false,
                )
            )
        repeat(2) {
          val result =
              fixture.editor.saveModelProposalResult(
                  "user",
                  workout,
                  snapshot.revision,
                  intents,
                  expires,
                  0,
                  serverProposalId = id,
                  expectedContextVersion = CoachToolCodec.contextVersion(snapshot),
              )
          assertTrue(result is ModelProposalSaveResult.Saved)
          assertEquals(id, (result as ModelProposalSaveResult.Saved).proposal.id)
        }
        assertEquals(1, tableCount("coach_proposals"))
      }

  @Test
  fun `confirming remote proposal commits local changes and durable applied receipt together`() =
      runTest {
        val workout = activeWorkout()
        val fixture = fixture(FakeTransport())
        val snapshot = fixture.reader.snapshot("user", workout, 0)!!
        val proposalId = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString()
        val version = CoachToolCodec.contextVersion(snapshot)
        db.coachRunDao()
            .insert(
                CoachRunEntity(
                    runId,
                    "user",
                    workout,
                    "{}",
                    version,
                    1,
                    submitted = true,
                    proposalId = proposalId,
                )
            )
        val result =
            fixture.editor.saveModelProposalResult(
                "user",
                workout,
                snapshot.revision,
                listOf(
                    CoachChangeIntent.EditSet(
                        snapshot.exercises.single().sets.single().syncId,
                        CoachSetValues(setOf("weight_kg"), weightKg = 45.0),
                        false,
                    )
                ),
                System.currentTimeMillis() + 60_000,
                0,
                serverProposalId = proposalId,
                expectedContextVersion = version,
            )
        assertTrue(result is ModelProposalSaveResult.Saved)
        val receipt =
            fixture.editor.confirmProposal("user", proposalId, UUID.randomUUID().toString(), 0)
        assertEquals(CommandResult.APPLIED, receipt.result)
        assertEquals(
            45.0,
            db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single().weightKg!!,
            0.0,
        )
        assertNotNull(db.coachDao().receipt(receipt.operationId))
        val outbound = db.coachRunDao().receipts("user").single()
        assertEquals(runId, outbound.runId)
        assertEquals(
            "APPLIED",
            Json.parseToJsonElement(outbound.payload).jsonObject["status"]!!.jsonPrimitive.content,
        )
      }

  @Test
  fun `malformed proposal does not starve later request and admission order survives clock rollback`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport().apply { malformedFirst = true }
        val fixture = fixture(transport)
        assertTrue(fixture.coordinator.send(workout, "Первый запрос"))
        val first = db.coachRunDao().pending("user").single()
        val futureCreatedAt = System.currentTimeMillis() + 60_000
        db.coachRunDao().update(first.copy(createdAt = futureCreatedAt))
        assertTrue(fixture.coordinator.send(workout, "Второй запрос"))
        val ordered = db.coachRunDao().pending("user")
        assertEquals(first.requestId, ordered.first().requestId)
        assertTrue(ordered.last().createdAt > futureCreatedAt)
        fixture.coordinator.deliverPending()
        assertEquals(
            listOf("Первый запрос", "Второй запрос"),
            transport.submissions.map {
              Json.parseToJsonElement(it).jsonObject["message"]!!.jsonPrimitive.content
            },
        )
        assertTrue(db.coachRunDao().pending("user").isEmpty())
        assertEquals(2, db.coachDao().messages(workout).count { it.role == "assistant" })
        assertTrue(db.coachDao().messages(workout).any { it.text == "Сохранённый ответ" })
      }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `no change result creates no assistant message journal entry or alert`() = runTest {
    val workout = activeWorkout()
    val transport = FakeTransport().apply { resultKind = "no_change" }
    val fixture = fixture(transport)
    val alerts = mutableListOf<String>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      fixture.coordinator.alerts.collect { alerts += it }
    }
    assertTrue(fixture.coordinator.send(workout, "Оцени состояние"))
    val journalsBefore = tableCount("coach_journal")
    fixture.coordinator.deliverPending()
    assertTrue(db.coachRunDao().pending("user").isEmpty())
    assertEquals(0, db.coachDao().messages(workout).count { it.role == "assistant" })
    assertEquals(journalsBefore, tableCount("coach_journal"))
    assertTrue(alerts.isEmpty())
  }

  @Test
  fun `retrying terminal failure creates another request without duplicating user message`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport().apply { failedFirst = true }
        val fixture = fixture(transport)
        assertTrue(fixture.coordinator.send(workout, "Повтори анализ"))
        val original = db.coachRunDao().pending("user").single()
        fixture.coordinator.deliverPending()
        val error = db.coachDao().messages(workout).single { it.status == "ERROR" }
        assertEquals(
            UUID.nameUUIDFromBytes("coach-answer:${original.requestId}".toByteArray()).toString(),
            error.id,
        )
        assertTrue(fixture.coordinator.retry(workout, error.id))
        val retry = db.coachRunDao().pending("user").single()
        assertNotEquals(original.requestId, retry.requestId)
        assertEquals(1, db.coachDao().messages(workout).count { it.role == "user" })
        fixture.coordinator.deliverPending()
        assertEquals(2, transport.submissions.size)
        assertEquals(1, db.coachDao().messages(workout).count { it.role == "user" })
        assertEquals(1, db.coachDao().messages(workout).count { it.role == "assistant" })
      }

  @Test
  fun `follow up supersedes pending proposal and journals stale receipt before delivery`() =
      runTest {
        val workout = activeWorkout()
        val fixture = fixture(FakeTransport())
        val snapshot = fixture.reader.snapshot("user", workout, 0)!!
        val runId = UUID.randomUUID().toString()
        val proposalId = UUID.randomUUID().toString()
        val version = CoachToolCodec.contextVersion(snapshot)
        db.coachRunDao()
            .insert(
                CoachRunEntity(
                    runId,
                    "user",
                    workout,
                    "{}",
                    version,
                    1,
                    submitted = true,
                    imported = true,
                    proposalId = proposalId,
                )
            )
        assertTrue(
            fixture.editor.saveModelProposalResult(
                "user",
                workout,
                snapshot.revision,
                listOf(
                    CoachChangeIntent.EditSet(
                        snapshot.exercises.single().sets.single().syncId,
                        CoachSetValues(setOf("weight_kg"), weightKg = 45.0),
                        false,
                    )
                ),
                System.currentTimeMillis() + 60_000,
                0,
                serverProposalId = proposalId,
                expectedContextVersion = version,
            ) is ModelProposalSaveResult.Saved
        )
        assertTrue(fixture.coordinator.send(workout, "Лучше измени число повторений"))
        assertNull(db.coachDao().pendingProposal(workout))
        assertEquals("SUPERSEDED", db.coachDao().proposalForId(proposalId)!!.state)
        val receipt = db.coachRunDao().receipts("user").single()
        assertEquals(runId, receipt.runId)
        assertEquals(
            "STALE",
            Json.parseToJsonElement(receipt.payload).jsonObject["status"]!!.jsonPrimitive.content,
        )
        assertEquals(1, db.coachRunDao().pending("user").size)
        assertEquals(1, db.coachDao().messages(workout).count { it.role == "user" })
      }

  @Test
  fun `deleted workout sends inactive session using last durable snapshot`() = runTest {
    val workout = activeWorkout()
    val transport = FakeTransport()
    val fixture = fixture(transport)
    assertTrue(fixture.coordinator.changed(workout))
    fixture.coordinator.deliverPending()
    val initial = transport.sessionUpdates.single()
    assertEquals(true, initial["active"]!!.jsonPrimitive.boolean)
    db.workoutDao().deleteWorkout(workout)
    db.coachRunDao().markDirty(workout)
    fixture.coordinator.deliverPending()
    val closed = transport.sessionUpdates.last()
    assertEquals(2, transport.sessionUpdates.size)
    assertEquals(false, closed["active"]!!.jsonPrimitive.boolean)
    assertEquals(initial["snapshot"], closed["snapshot"])
    assertTrue(closed["sequence"]!!.jsonPrimitive.long > initial["sequence"]!!.jsonPrimitive.long)
    assertTrue(db.coachRunDao().dirtySessions().isEmpty())
    assertTrue(db.coachRunDao().pendingSessions("user").isEmpty())
  }

  @Test
  fun `proposal with explicit null reason imports without poisoning the run`() = runTest {
    val workout = activeWorkout()
    val setId = db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single().syncId
    val transport =
        FakeTransport().apply {
          resultKind = "proposal"
          proposalOperations = buildJsonArray {
            add(
                buildJsonObject {
                  put("action", "edit_set")
                  put("set_id", setId)
                  put("values", buildJsonObject { put("weight_kg", 45.0) })
                }
            )
          }
        }
    val fixture = fixture(transport)
    assertTrue(fixture.coordinator.send(workout, "Снизь вес"))
    fixture.coordinator.deliverPending()
    assertTrue(db.coachRunDao().pending("user").isEmpty())
    val proposal = db.coachDao().pendingProposal(workout)
    assertNotNull(proposal)
    assertEquals("PENDING", proposal!!.state)
    assertTrue(db.coachRunDao().receipts("user").isEmpty())
  }

  @Test
  fun `closed session discovery survives restart while known pending run still imports`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport()
        val initial = fixture(transport)
        assertTrue(initial.coordinator.changed(workout))
        initial.coordinator.deliverPending()
        db.workoutDao().deleteWorkout(workout)
        db.coachRunDao().markDirty(workout)
        // Recreate to remove in-memory discovery throttling, as after process death.
        fixture(transport).coordinator.deliverPending()
        assertTrue(db.coachRunDao().session(workout)!!.discoveryComplete)
        val discoveriesAfterClose = transport.listCalls.count { it == workout }
        assertEquals(2, discoveriesAfterClose)
        val runId = UUID.randomUUID().toString()
        db.coachRunDao()
            .insert(CoachRunEntity(runId, "user", workout, "{}", "context", 1, submitted = true))
        transport.polledStatuses[runId] = buildJsonObject {
          put("runId", runId)
          put("requestId", runId)
          put("workoutId", workout)
          put("contextVersion", "context")
          put("state", "SUCCEEDED")
          put(
              "result",
              buildJsonObject {
                put("kind", "no_change")
                put("text", "")
              },
          )
        }
        val recreated = fixture(transport)
        recreated.coordinator.deliverPending()
        recreated.coordinator.deliverPending()
        assertEquals(discoveriesAfterClose, transport.listCalls.count { it == workout })
        assertEquals(listOf(runId), transport.statusCalls)
        assertTrue(db.coachRunDao().run(runId)!!.imported)
        assertTrue(db.coachRunDao().sessionsNeedingDiscovery("user").isEmpty())
      }

  @Test
  fun `automatic completion stays invisible and cannot clear pending user status`() = runTest {
    val workout = activeWorkout()
    val transport = FakeTransport()
    val fixture = fixture(transport)
    val user = UUID.randomUUID().toString()
    val automatic = UUID.randomUUID().toString()
    db.coachRunDao()
        .insert(
            CoachRunEntity(
                user,
                "user",
                workout,
                "{}",
                "v",
                1,
                submitted = true,
                stage = "analyzing",
            )
        )
    db.coachRunDao()
        .insert(
            CoachRunEntity(
                automatic,
                "user",
                workout,
                "",
                "v",
                2,
                submitted = true,
                origin = "COACH",
            )
        )
    transport.polledStatuses[user] = buildJsonObject {
      put("runId", user)
      put("workoutId", workout)
      put("state", "RUNNING")
      put("stage", "analyzing")
      put("origin", "USER")
    }
    transport.polledStatuses[automatic] = buildJsonObject {
      put("runId", automatic)
      put("workoutId", workout)
      put("state", "FAILED")
      put("origin", "COACH")
    }
    fixture.coordinator.deliverPending()
    assertEquals(setOf(workout), fixture.coordinator.running.value)
    assertTrue(db.coachDao().messages(workout).isEmpty())
    assertTrue(fixture.coordinator.drafts.value.isEmpty())
    assertTrue(db.coachRunDao().run(automatic)!!.imported)
  }

  @Test
  fun `session stream commits text with cursor and discovers new work while idle`() = runTest {
    val transport = FakeTransport()
    val streamed = fixture(transport)
    withContext(Dispatchers.Default) {
      val workout = activeWorkout()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val id = UUID.randomUUID().toString()
        val active = buildJsonObject {
          put("runId", id)
          put("workoutId", workout)
          put("origin", "USER")
          put("contextVersion", "v")
          put("state", "RUNNING")
        }
        transport.streamEvents =
            listOf(
                buildJsonObject {
                  put("sequence", 1)
                  put("runId", id)
                  put("origin", "USER")
                  put("type", "created")
                  put("run", active)
                },
                buildJsonObject {
                  put("sequence", 2)
                  put("runId", id)
                  put("origin", "USER")
                  put("type", "text")
                  put("text", "Текст")
                  put("stage", "analyzing")
                },
            )
        streamed.coordinator.attach(scope)
        withTimeout(5_000) {
          while (streamed.coordinator.drafts.value[workout]?.text != "Текст") delay(10)
        }
        assertEquals(2L, db.coachRunDao().eventCursor("user", workout))
        assertEquals("Текст", db.coachRunDao().run(id)!!.draft)
        streamed.coordinator.detach()
        delay(50)
        streamed.coordinator.attach(scope)
        withTimeout(5_000) { while (transport.streamAfter.lastOrNull() != 2L) delay(10) }
        assertEquals(1, db.coachRunDao().runsForWorkout("user", workout).size)
      } finally {
        streamed.coordinator.detach()
        scope.cancel()
      }
    }
  }

  @Test
  fun `frequent saved changes coalesce and ordinary ticks do not send snapshots`() = runTest {
    val transport = FakeTransport()
    val fixture = fixture(transport)
    withContext(Dispatchers.Default) {
      val workout = activeWorkout()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        fixture.coordinator.attach(scope)
        withTimeout(5_000) { while (transport.sessionUpdates.isEmpty()) delay(10) }
        val initial = transport.sessionUpdates.size
        repeat(10) { fixture.coordinator.changed(workout, immediate = false) }
        assertEquals(initial, transport.sessionUpdates.size)
        withTimeout(2_000) { while (transport.sessionUpdates.size == initial) delay(10) }
        assertEquals(initial + 1, transport.sessionUpdates.size)
        delay(1_200)
        assertEquals(initial + 1, transport.sessionUpdates.size)
      } finally {
        fixture.coordinator.detach()
        scope.cancel()
      }
    }
  }

  @Test
  fun `lost snapshot acknowledgement retries identical payload while newer dirty state survives`() =
      runTest {
        val workout = activeWorkout()
        val transport = FakeTransport()
        val first = fixture(transport)
        transport.onPut = { throw java.io.IOException("lost acknowledgement") }
        first.coordinator.deliverPending()
        val original = db.coachRunDao().session(workout)!!
        assertFalse(original.delivered)
        transport.onPut = {
          db.openHelper.writableDatabase.execSQL("UPDATE workout_sets SET reps=9")
          db.coachRunDao().markDirty(workout)
        }
        val restarted = fixture(transport)
        restarted.coordinator.deliverPending()
        assertEquals(transport.sessionUpdates[0], transport.sessionUpdates[1])
        assertEquals(original.payload, db.coachRunDao().session(workout)!!.payload)
        assertTrue(db.coachRunDao().session(workout)!!.delivered)
        assertTrue(db.coachRunDao().dirtySessions().any { it.workoutId == workout })
        transport.onPut = {}
        restarted.coordinator.deliverPending()
        val newer = db.coachRunDao().session(workout)!!
        assertTrue(newer.sequence > original.sequence)
        assertNotEquals(original.contextVersion, newer.contextVersion)
        assertTrue(db.coachRunDao().dirtySessions().none { it.workoutId == workout })
      }

  private suspend fun activeWorkout(): String {
    val id = insertWorkout(UUID.randomUUID().toString())
    val exercise =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                )
            )
    insertSet(insertWorkoutExercise(id, exercise), 0, weightKg = 50.0, reps = 8)
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id,owner,generation,phase,initialMergeAcknowledged) VALUES(1,'user',0,'OWNED',1)"
    )
    return id
  }

  private data class Fixture(
      val coordinator: DurableCoachCoordinator,
      val editor: WorkoutEditor,
      val reader: CoachWorkoutReader,
      val conversation: CoachConversationService,
  )

  private fun TestScope.fixture(
      transport: FakeTransport,
      settings: SettingsRepository? = null,
  ): Fixture {
    val session = FakeSession()
    val timer = RestTimerEngine(backgroundScope, WallClock { System.currentTimeMillis() })
    val reader = CoachWorkoutReader(db, timer, session)
    val editor =
        WorkoutEditor(db, db.workoutDao(), db.coachDao(), timer, session, WorkoutWriteQueue())
    val coordinator =
        DurableCoachCoordinator(CoachRunsClient(transport), db, reader, editor, session, settings)
    return Fixture(
        coordinator,
        editor,
        reader,
        CoachConversationService(coordinator, reader, editor, db, session),
    )
  }

  private class FakeSession : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class FakeTransport : BackendTransport {
    override val json = Json
    val submissions = java.util.concurrent.CopyOnWriteArrayList<String>()
    val sessionUpdates = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    val listCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    val statusCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    val polledStatuses = mutableMapOf<String, JsonObject>()
    var streamEvents: List<JsonObject> = emptyList()
    val streamAfter = java.util.concurrent.CopyOnWriteArrayList<Long>()

    override fun authorizedGetEventStream(
        path: String,
        expectedOwner: String,
        expectedSessionEpoch: Long,
    ): Flow<BackendStreamEvent> = flow {
      val after = path.substringAfter("after=").toLong()
      streamAfter += after
      for (event in streamEvents) emit(
          BackendStreamEvent(
              event["type"]!!.jsonPrimitive.content,
              event.toString(),
              expectedOwner,
              expectedSessionEpoch,
              event["sequence"]!!.jsonPrimitive.content,
          )
      )
      awaitCancellation()
    }

    var onPut: suspend () -> Unit = {}
    var resultKind = "answer"
    var malformedFirst = false
    var failedFirst = false
    var proposalOperations: JsonArray? = null

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("not used")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("not used")

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
      val body =
          when {
            method == "PUT" -> {
              sessionUpdates.add(json.parseToJsonElement(rawBody.decodeToString()).jsonObject)
              onPut()
              buildJsonObject {}
            }
            method == "POST" && path.endsWith("/receipt") -> buildJsonObject {}
            method == "GET" && path.contains("/sessions/") -> {
              listCalls.add(path.substringAfter("/sessions/").substringBefore('/'))
              JsonArray(emptyList())
            }
            method == "GET" && path.contains("/runs/") -> {
              val id = path.substringAfterLast('/')
              statusCalls.add(id)
              checkNotNull(polledStatuses[id])
            }
            method == "POST" && (path.endsWith("/runs") || path.endsWith("/messages")) -> {
              val raw = rawBody.decodeToString()
              submissions.add(raw)
              val original = json.parseToJsonElement(raw).jsonObject
              val state = original["state"] as? JsonObject
              val request =
                  if (state == null) original
                  else
                      JsonObject(
                          original +
                              state +
                              mapOf("workoutId" to state["snapshot"]!!.jsonObject["workout_id"]!!)
                      )
              buildJsonObject {
                put("runId", request["requestId"]!!)
                put("requestId", request["requestId"]!!)
                put("workoutId", request["workoutId"]!!)
                put("contextVersion", request["contextVersion"]!!)
                put("state", if (failedFirst && submissions.size == 1) "FAILED" else "SUCCEEDED")
                put("stage", "Готово")
                put("lastEventSequence", 1)
                if (!(failedFirst && submissions.size == 1))
                    put(
                        "result",
                        buildJsonObject {
                          put(
                              "kind",
                              if (malformedFirst && submissions.size == 1) "proposal"
                              else resultKind,
                          )
                          put("text", "Сохранённый ответ")
                          put("quickReplies", JsonArray(emptyList()))
                          if (
                              (malformedFirst && submissions.size == 1) ||
                                  proposalOperations != null
                          )
                              put(
                                  "proposal",
                                  buildJsonObject {
                                    put("proposalId", UUID.randomUUID().toString())
                                    put("baseRevision", 0)
                                    put("contextVersion", request["contextVersion"]!!)
                                    put("expiresAtMillis", System.currentTimeMillis() + 60_000)
                                    put("reason", JsonNull)
                                    put(
                                        "operations",
                                        proposalOperations
                                            ?: buildJsonArray {
                                              add(
                                                  buildJsonObject {
                                                    put("action", "unknown_action")
                                                  }
                                              )
                                            },
                                    )
                                  },
                              )
                        },
                    )
              }
            }
            else -> error("Unexpected $method $path")
          }
      return BackendResponse(
          body,
          body.toString().encodeToByteArray(),
          emptySet(),
          expectedOwner,
          expectedSessionEpoch ?: 0,
      )
    }
  }
}
