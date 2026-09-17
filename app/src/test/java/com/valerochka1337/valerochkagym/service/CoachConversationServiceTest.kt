package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.AiApiChatResponse
import com.valerochka1337.valerochkagym.data.ai.AiApiChoice
import com.valerochka1337.valerochkagym.data.ai.AiApiMessage
import com.valerochka1337.valerochkagym.data.ai.AiApiResponseMessage
import com.valerochka1337.valerochkagym.data.ai.AiApiTool
import com.valerochka1337.valerochkagym.data.ai.CoachAgent
import com.valerochka1337.valerochkagym.data.ai.CoachModelGateway
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.CoachWorkoutReader
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachConversationServiceTest : RoomDaoTest() {
  @Test
  fun `first low rep set sends one AI request and saves an estimated proposal without a user message`() =
      runTest {
        val workout = activeWorkout()
        val next = db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single()
        db.workoutDao().updateSet(next.copy(setIndex = 1))
        db.workoutDao()
            .insertSet(
                next.copy(
                    id = 0,
                    syncId = java.util.UUID.randomUUID().toString(),
                    setIndex = 0,
                    reps = 2,
                    actualRir = 0,
                    setType = "WORK",
                    isCompleted = true,
                    completedAt = 1000,
                )
            )
        val gateway = AutomaticProposalGateway(next.syncId)
        val service = conversation(gateway)
        service.attach(
            kotlinx.coroutines.CoroutineScope(
                backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
            )
        )
        val decisions =
            kotlinx.coroutines.coroutineScope {
              listOf(
                      async { service.considerInitiative("user", workout) },
                      async { service.considerInitiative("user", workout) },
                  )
                  .map { it.await() }
            }
        assertEquals(1, decisions.count { it != null })
        gateway.started.await()
        assertNull(service.considerInitiative("user", workout))
        assertTrue(db.coachDao().messages(workout).isEmpty())
        gateway.release.complete(Unit)
        db.coachDao().observeMessages(workout).first {
          it.any { row -> row.text == "Предлагаю 45 кг, чтобы сохранить повторы." }
        }
        val proposal = db.coachDao().pendingProposal(workout)!!
        assertEquals(2, gateway.calls)
        assertEquals(50.0, db.workoutDao().getSet(next.id)!!.weightKg!!, 0.0)
        assertTrue(db.coachDao().messages(workout).all { it.role == "assistant" })
        assertFalse(
            db.coachDao().messages(workout).any {
              it.text.contains("настройки приложения") || it.text.contains("Я рядом")
            }
        )
        assertTrue(service.cancel(workout, proposal.id))
        assertNull(service.considerInitiative("user", workout))
      }

  @Test
  fun `disabling initiative discards an in flight automatic answer and proposal`() = runTest {
    val workout = activeWorkout()
    val next = db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single()
    db.workoutDao().updateSet(next.copy(setIndex = 1))
    db.workoutDao()
        .insertSet(
            next.copy(
                id = 0,
                syncId = java.util.UUID.randomUUID().toString(),
                reps = 16,
                setType = "WORK",
                isCompleted = true,
                completedAt = 1000,
            )
        )
    val gateway = AutomaticProposalGateway(next.syncId)
    val service = conversation(gateway)
    service.attach(
        kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
        )
    )
    assertNotNull(service.considerInitiative("user", workout))
    gateway.started.await()
    assertTrue(service.setInitiativeEnabled("user", workout, false))
    gateway.release.complete(Unit)
    service.runningWorkouts.first { workout !in it }
    assertTrue(db.coachDao().messages(workout).isEmpty())
    assertNull(db.coachDao().pendingProposal(workout))
  }

  @Test
  fun `missing effort and starting a workout do not greet or call AI`() = runTest {
    val workout = activeWorkout()
    val gateway = RecordingGateway()
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertNull(service.considerInitiative("user", workout))
    val set = db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single()
    db.workoutDao()
        .insertSet(
            set.copy(
                id = 0,
                syncId = java.util.UUID.randomUUID().toString(),
                setIndex = 1,
                isCompleted = true,
                completedAt = 1000,
                setType = "WORK",
                actualRir = null,
            )
        )
    assertNull(service.considerInitiative("user", workout))
    assertEquals(0, gateway.calls)
    assertTrue(db.coachDao().messages(workout).isEmpty())
  }

  @Test
  fun `streaming draft promotes to one stored message with the same id`() = runTest {
    val workout = activeWorkout()
    val gateway = StreamingGateway()
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Подскажи технику"))
    val draft = service.responseDrafts.first { it[workout]?.text == "Продолжай" }.getValue(workout)
    assertTrue(workout in service.runningWorkouts.value)
    assertTrue(draft.streaming)
    gateway.release.complete(Unit)
    val messages = db.coachDao().observeMessages(workout).first { it.size == 2 }
    assertEquals(draft.id, messages.last().id)
    assertEquals("Продолжай", messages.last().text)
    assertEquals(1, db.coachDao().pendingJournal("user", 100).count { it.id == draft.id })
    runCurrent()
    assertFalse(service.responseDrafts.value.getValue(workout).streaming)
    service.stopWorkout(workout)
    assertTrue(service.responseDrafts.value.isEmpty())
  }

  @Test
  fun `failed stream removes draft and preserves retryable error`() = runTest {
    val workout = activeWorkout()
    val gateway = StreamingGateway(fail = true)
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Подскажи технику"))
    service.responseDrafts.first { it[workout]?.text == "Продолжай" }
    gateway.release.complete(Unit)
    val messages = db.coachDao().observeMessages(workout).first { it.size == 2 }
    runCurrent()
    assertTrue(service.responseDrafts.value.isEmpty())
    assertEquals("ERROR", messages.last().status)
    org.junit.Assert.assertNotNull(messages.last().readAt)
    assertFalse(messages.last().text.contains("Продолжай"))
  }

  @Test
  fun `stopping workout clears an in flight draft`() = runTest {
    val workout = activeWorkout()
    val gateway = StreamingGateway()
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Подскажи технику"))
    service.responseDrafts.first { it[workout]?.streaming == true }
    service.stopWorkout(workout)
    assertTrue(service.responseDrafts.value.isEmpty())
    gateway.release.complete(Unit)
    runCurrent()
    assertEquals(1, db.coachDao().messages(workout).size)
  }

  private class StreamingGateway(private val fail: Boolean = false) : CoachModelGateway {
    override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?) =
        "Server coach prompt"

    val release = CompletableDeferred<Unit>()

    override fun stream(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ) =
        kotlinx.coroutines.flow.flow {
          emit(
              com.valerochka1337.valerochkagym.data.ai.CoachModelEvent.TextDelta(
                  "{\"text\":\"Продолжай"
              )
          )
          release.await()
          if (fail) throw java.io.EOFException("stream interrupted")
          emit(
              com.valerochka1337.valerochkagym.data.ai.CoachModelEvent.Completed(
                  AiApiChatResponse(
                      choices =
                          listOf(
                              AiApiChoice(
                                  AiApiResponseMessage(
                                      content = JsonPrimitive("{\"text\":\"Продолжай\"}")
                                  )
                              )
                          )
                  )
              )
          )
        }
  }

  @Test
  fun `retry calls AI again without duplicating user message or its journal entry`() = runTest {
    val workout = activeWorkout()
    val gateway = RecordingGateway("""{"text":"broken""")
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Перенеси Хаммер"))
    val failure = db.coachDao().observeMessages(workout).first { it.size == 2 }.last()
    runCurrent()
    val original = db.coachDao().messages(workout).first()
    gateway.answerText = "Проверил тренировку."
    assertTrue(service.retry(workout, failure.id))
    assertFalse(service.retry(workout, failure.id))
    assertFalse(db.coachDao().messages(workout).any { it.id == failure.id })
    val messages =
        db.coachDao().observeMessages(workout).first { it.size == 2 && it.last().id != failure.id }
    assertEquals(listOf(original.id), messages.filter { it.role == "user" }.map { it.id })
    assertEquals("Проверил тренировку.", messages.last().text)
    assertEquals(2, gateway.calls)
    assertEquals(gateway.requests.first(), gateway.requests.last())
    assertEquals(1, db.coachDao().pendingJournal("user", 100).count { it.id == original.id })
    runCurrent()
    assertFalse(service.retry(workout, failure.id))
  }

  @Test
  fun `repeated failures replace the retried error and keep the original journal`() = runTest {
    val workout = activeWorkout()
    val service = conversation(RecordingGateway("""{"text":"broken"""))
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Перенеси Хаммер"))
    var failure = db.coachDao().observeMessages(workout).first { it.size == 2 }.last()
    val originalId = db.coachDao().messages(workout).first().id
    repeat(3) {
      runCurrent()
      val oldId = failure.id
      assertTrue(service.retry(workout, oldId))
      val messages =
          db.coachDao().observeMessages(workout).first { it.size == 2 && it.last().id != oldId }
      assertEquals(originalId, messages.first().id)
      assertEquals("ERROR", messages.last().status)
      assertFalse(messages.any { it.id == oldId })
      assertTrue(db.coachDao().pendingJournal("user", 100).any { it.id == oldId })
      failure = messages.last()
    }
  }

  @Test
  fun `retry rejects errors from another account`() = runTest {
    val workout = activeWorkout()
    val gateway = RecordingGateway("""{"text":"broken""")
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Перенеси Хаммер"))
    val failure = db.coachDao().observeMessages(workout).first { it.size == 2 }.last()
    runCurrent()
    session.save(BackendTokens("other", "other@example.com", "access", "refresh"))
    assertFalse(service.retry(workout, failure.id))
    assertEquals(1, gateway.calls)
  }

  @Test
  fun `malformed reply persists a short error eligible for retry`() = runTest {
    val workout = activeWorkout()
    val service = conversation(RecordingGateway("""{"text":"broken"""))
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Перенеси Хаммер"))
    val answer = db.coachDao().observeMessages(workout).first { it.size == 2 }.last()
    assertEquals("Не удалось обработать запрос", answer.text)
    assertEquals("ERROR", answer.status)
  }

  @Test
  fun `text followup supersedes all pending proposals without adding rejection messages`() =
      runTest {
        val workout = activeWorkout()
        val proposal =
            com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity(
                id = "old",
                accountId = "user",
                workoutId = workout,
                baseRevision = 0,
                beforeSummary = "Было",
                afterSummary = "Замена",
                packetJson = "{}",
                expiresAt = Long.MAX_VALUE,
            )
        db.coachDao().saveProposal(proposal)
        db.coachDao().saveProposal(proposal.copy(id = "older"))
        val service = conversation(RecordingGateway())
        service.attach(backgroundScope)
        assertFalse(service.send(workout, " "))
        assertTrue(db.coachDao().pendingProposal(workout) != null)
        assertTrue(service.send(workout, "Предложи другую замену"))
        assertNull(db.coachDao().pendingProposal(workout))
        assertFalse(service.confirm(workout, "old"))
        assertFalse(service.confirm(workout, "older"))
        assertFalse(db.coachDao().messages(workout).any { it.text.startsWith("REJECTED|") })
      }

  @Test
  fun `sending immediately after attach is processed from the bounded queue`() = runTest {
    val workoutId = activeWorkout()
    val gateway = RecordingGateway()
    val conversation = conversation(gateway)

    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "Как идёт тренировка?"))
    val messages = db.coachDao().observeMessages(workoutId).first { it.size == 2 }
    assertEquals(listOf("user", "assistant"), messages.map { it.role })
    assertEquals("Готов помочь.", messages.last().text)
    assertEquals(1, gateway.calls)
    assertEquals("user", gateway.owners.single())
    assertEquals(0L, gateway.epochs.single())
  }

  @Test
  fun `model contextual replies persist separately from visible text and journal`() = runTest {
    val workout = activeWorkout()
    val gateway =
        RecordingGateway("""{"text":"Заменить жим?","quick_replies":["Да, замени","Нет"]}""")
    val service = conversation(gateway)
    service.attach(backgroundScope)
    assertTrue(service.send(workout, "Нужна замена"))
    val answer = db.coachDao().observeMessages(workout).first { it.size == 2 }.last()
    assertEquals("Заменить жим?", answer.text)
    assertEquals(
        listOf("Да, замени", "Нет"),
        com.valerochka1337.valerochkagym.domain.CoachReply.decodeQuickReplies(
            answer.quickRepliesJson
        ),
    )
    val journal = db.coachDao().pendingJournal("user", 100).single { it.id == answer.id }
    assertFalse(journal.payload.contains("quick_replies"))
    assertTrue(journal.payload.contains("Заменить жим?"))
  }

  @Test
  fun `oversized user text is rejected before it creates transcript or journal rows`() = runTest {
    val workoutId = activeWorkout()
    val conversation = conversation(RecordingGateway())
    conversation.attach(backgroundScope)

    assertFalse(conversation.send(workoutId, "x".repeat(4_001)))
    assertTrue(db.coachDao().messages(workoutId).isEmpty())
  }

  @Test
  fun `pending request from a previous process becomes interrupted and is not replayed`() =
      runTest {
        val workoutId = activeWorkout()
        val gateway = RecordingGateway()
        val conversation = conversation(gateway)
        conversation.appendMessage(
            "old-message",
            "user",
            workoutId,
            "user",
            "Не успел ответить",
            createdAt = 0,
            status = "PENDING",
        )

        conversation.attach(backgroundScope)
        assertEquals(
            "INTERRUPTED",
            db.coachDao()
                .observeMessages(workoutId)
                .first { it.singleOrNull()?.status == "INTERRUPTED" }
                .single()
                .status,
        )
        assertEquals(0, gateway.calls)
      }

  @Test
  fun `local command retains send-time anchors and rejects a changed revision`() = runTest {
    val workoutId = activeWorkout()
    val gateway = RecordingGateway()
    val conversation = conversation(gateway)
    val set = db.workoutDao().getWorkoutFull(workoutId)!!.exercises.single().sets.single()

    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "поставь в текущем подходе 55 кг"))
    db.workoutDao().updateSet(set.copy(weightKg = 70.0))
    db.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workoutId),
    )
    assertEquals(70.0, db.workoutDao().getSet(set.id)!!.weightKg!!, 0.0)
    assertEquals(0, gateway.calls)
    assertTrue(
        db.coachDao()
            .observeMessages(workoutId)
            .first { it.size == 2 }
            .last()
            .text
            .contains("состояние тренировки"),
    )
  }

  @Test
  fun `logout cancels a late model reply before it writes an assistant message`() = runTest {
    val workoutId = activeWorkout()
    val gateway = BlockingGateway()
    val conversation = conversation(gateway)
    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "Подожди ответ"))
    gateway.started.await()

    session.save(null)
    val messages =
        db.coachDao().observeMessages(workoutId).first {
          it.singleOrNull()?.status == "INTERRUPTED"
        }
    assertEquals(listOf("user"), messages.map { it.role })
    assertEquals("INTERRUPTED", messages.single().status)
    assertFalse(conversation.runningWorkouts.value.contains(workoutId))
  }

  @Test
  fun `finishing a workout cancels a late model reply`() = runTest {
    val workoutId = activeWorkout()
    val gateway = BlockingGateway()
    val conversation = conversation(gateway)
    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "Заканчиваю тренировку"))
    gateway.started.await()

    conversation.stopWorkout(workoutId)
    val messages =
        db.coachDao().observeMessages(workoutId).first {
          it.singleOrNull()?.status == "INTERRUPTED"
        }
    assertEquals(listOf("user"), messages.map { it.role })
    assertEquals("INTERRUPTED", messages.single().status)
  }

  @Test
  fun `stopping before collection drains the singleton queue instead of replaying on reattach`() =
      runTest {
        val workoutId = activeWorkout()
        val gateway = RecordingGateway()
        val conversation = conversation(gateway)
        conversation.attach(backgroundScope)
        assertTrue(conversation.send(workoutId, "Не выполняй после завершения"))

        conversation.stopWorkout(workoutId)
        conversation.detach()
        assertEquals(
            "INTERRUPTED",
            db.coachDao()
                .observeMessages(workoutId)
                .first { it.singleOrNull()?.status == "INTERRUPTED" }
                .single()
                .status,
        )
        conversation.attach(backgroundScope)
        assertEquals(0, gateway.calls)
      }

  @Test
  fun `model tool proposal changes Room only after confirmation`() = runTest {
    val workout = activeWorkout()
    val original = db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single()
    val set =
        original.copy(
            setIndex = 1,
            weightKg = 100.0,
            reps = 7,
            targetReps = 7,
            setType = "WORK",
        )
    db.workoutDao().updateSet(set)
    db.workoutDao()
        .insertSet(
            set.copy(
                id = 0,
                syncId = java.util.UUID.randomUUID().toString(),
                setIndex = 0,
                isCompleted = true,
                completedAt = 1000,
                actualRir = 1,
                reportedFeelingsJson = "[\"HARDER_THAN_EXPECTED\"]",
            )
        )
    val before = db.workoutDao().getWorkoutFull(workout)!!
    val gateway =
        object : RecordingGateway() {
          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            calls++
            return AiApiChatResponse(
                choices =
                    listOf(
                        AiApiChoice(
                            AiApiResponseMessage(
                                toolCalls =
                                    listOf(
                                        com.valerochka1337.valerochkagym.data.ai.AiApiToolCall(
                                            "proposal-tool",
                                            function =
                                                com.valerochka1337.valerochkagym.data.ai
                                                    .AiApiToolCallFunction(
                                                        "submit_workout_changes",
                                                        """{"base_revision":0,"reason":"Учитываю сообщение об усталости","operations":[{"action":"edit_set","set_id":"${set.syncId}","values":{"reps":6}}]}""",
                                                    ),
                                        )
                                    )
                            )
                        )
                    )
            )
          }
        }
    val conversation = conversation(gateway)
    conversation.attach(
        kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
        )
    )
    assertTrue(conversation.send(workout, "Предложи облегчить оставшийся подход"))
    val messages =
        db.coachDao().observeMessages(workout).first { it.firstOrNull()?.status == "DELIVERED" }
    val proposal = requireNotNull(db.coachDao().pendingProposal(workout)) { messages.toString() }
    assertEquals(before, db.workoutDao().getWorkoutFull(workout))
    assertTrue(proposal.afterSummary.contains("→ 6"))
    assertTrue(messages.last().text == "Учитываю сообщение об усталости")
    assertTrue(conversation.confirm(workout, proposal.id))
    assertEquals(6, db.workoutDao().getSet(set.id)!!.reps)
    assertEquals(1, gateway.calls)
    assertEquals(1L, db.workoutDao().getWorkoutFull(workout)!!.workout.coachRevision)
  }

  @Test
  fun `reorder missing completed warmup is corrected once and still requires confirmation`() =
      runTest {
        verifyReorderRecovery(corrected = true)
      }

  @Test
  fun `repeated incomplete reorder stops after one recovery without durable changes`() = runTest {
    verifyReorderRecovery(corrected = false)
  }

  @Test
  fun `invalid future set effort is corrected once before proposal confirmation`() = runTest {
    verifyInvalidParametersRecovery(corrected = true)
  }

  @Test
  fun `repeated invalid parameters stop after one correction without saving changes`() = runTest {
    verifyInvalidParametersRecovery(corrected = false)
  }

  private suspend fun TestScope.verifyInvalidParametersRecovery(corrected: Boolean) {
    val workout = activeWorkout()
    val before = db.workoutDao().getWorkoutFull(workout)!!
    val set = before.exercises.single().sets.single()
    val gateway =
        object : RecordingGateway() {
          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            calls++
            if (calls > 1) {
              val recovery = (messages.last().content as JsonPrimitive).content
              assertTrue(recovery.contains("invalid_change_parameters"))
              assertTrue(recovery.contains("current_state"))
              assertTrue(recovery.contains(set.syncId))
              assertNull(db.coachDao().pendingProposal(workout))
              assertEquals(before, db.workoutDao().getWorkoutFull(workout))
            }
            val values =
                if (corrected && calls == 2) """{"reps":6}""" else """{"reps":6,"actual_rir":2}"""
            return AiApiChatResponse(
                choices =
                    listOf(
                        AiApiChoice(
                            AiApiResponseMessage(
                                toolCalls =
                                    listOf(
                                        com.valerochka1337.valerochkagym.data.ai.AiApiToolCall(
                                            "proposal-$calls",
                                            function =
                                                com.valerochka1337.valerochkagym.data.ai
                                                    .AiApiToolCallFunction(
                                                        "submit_workout_changes",
                                                        """{"base_revision":0,"operations":[{"action":"edit_set","set_id":"${set.syncId}","values":$values}]}""",
                                                    ),
                                        )
                                    ),
                            )
                        )
                    )
            )
          }
        }
    val service = conversation(gateway)
    service.attach(
        kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
        )
    )
    assertTrue(service.send(workout, "Предложи корректировку следующих подходов"))
    db.coachDao().observeMessages(workout).first { rows ->
      rows.any { it.role == "user" && it.status == "DELIVERED" }
    }
    assertEquals(2, gateway.calls)
    assertEquals(before, db.workoutDao().getWorkoutFull(workout))
    val proposal = db.coachDao().pendingProposal(workout)
    if (corrected) {
      assertNotNull(proposal)
      assertTrue(service.confirm(workout, proposal!!.id))
      assertEquals(6, db.workoutDao().getSet(set.id)!!.reps)
      assertNull(db.workoutDao().getSet(set.id)!!.actualRir)
    } else assertNull(proposal)
  }

  private suspend fun TestScope.verifyReorderRecovery(corrected: Boolean) {
    val workout = activeWorkout()
    val initial = db.workoutDao().getWorkoutFull(workout)!!.exercises.single()
    db.workoutDao().updateSet(initial.sets.single().copy(isCompleted = true))
    val exercise = initial.exercise.id
    for (position in 1..7) {
      insertSet(insertWorkoutExercise(workout, exercise, position), 0, reps = 8)
    }
    val before = db.workoutDao().getWorkoutFull(workout)!!
    val ids =
        before.exercises
            .sortedBy { it.workoutExercise.position }
            .map { it.workoutExercise.sectionId }
    val incomplete = listOf(2, 4, 1, 3, 5, 6, 7).map { ids[it] }
    val fullOrder = listOf(ids.first()) + incomplete
    val gateway =
        object : RecordingGateway() {
          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            calls++
            if (calls > 1) {
              val recovery = (messages.last().content as JsonPrimitive).content
              assertTrue(recovery.contains("invalid_exercise_order"))
              assertTrue(recovery.contains(ids.first()))
              assertTrue(recovery.contains("current_state"))
              assertNull(db.coachDao().pendingProposal(workout))
              assertEquals(before, db.workoutDao().getWorkoutFull(workout))
            }
            val order = if (calls == 2 && corrected) fullOrder else incomplete
            val array = kotlinx.serialization.json.JsonArray(order.map { JsonPrimitive(it) })
            val arguments =
                """{"base_revision":0,"operations":[{"action":"reorder_exercises","section_ids":$array}]}"""
            return AiApiChatResponse(
                choices =
                    listOf(
                        AiApiChoice(
                            AiApiResponseMessage(
                                toolCalls =
                                    listOf(
                                        com.valerochka1337.valerochkagym.data.ai.AiApiToolCall(
                                            "reorder-$calls",
                                            function =
                                                com.valerochka1337.valerochkagym.data.ai
                                                    .AiApiToolCallFunction(
                                                        "submit_workout_changes",
                                                        arguments,
                                                    ),
                                        )
                                    ),
                            )
                        )
                    )
            )
          }
        }
    val service = conversation(gateway)
    service.attach(
        kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
        )
    )
    assertTrue(service.send(workout, "Тренажёр занят"))
    db.coachDao().observeMessages(workout).first { rows ->
      rows.any { it.role == "user" && it.status == "DELIVERED" }
    }
    assertEquals(db.coachDao().messages(workout).toString(), 2, gateway.calls)
    assertEquals(before, db.workoutDao().getWorkoutFull(workout))
    val proposal = db.coachDao().pendingProposal(workout)
    if (corrected) {
      assertTrue(proposal != null)
      assertTrue(service.confirm(workout, proposal!!.id))
      val after = db.workoutDao().getWorkoutFull(workout)!!
      assertEquals(
          fullOrder,
          after.exercises
              .sortedBy { it.workoutExercise.position }
              .map { it.workoutExercise.sectionId },
      )
      assertEquals(
          before.exercises.flatMap { it.sets }.sortedBy { it.id },
          after.exercises.flatMap { it.sets }.sortedBy { it.id },
      )
    } else {
      assertNull(proposal)
      assertTrue(db.coachDao().pendingJournal("user", 100).all { !it.payload.contains("packet") })
    }
  }

  @Test
  fun `live revision conflict returns fresh state for one corrected proposal`() = runTest {
    val workout = activeWorkout()
    val set = db.workoutDao().getWorkoutFull(workout)!!.exercises.single().sets.single()
    val firstRequestStarted = CompletableDeferred<Unit>()
    val releaseFirstResponse = CompletableDeferred<Unit>()
    val correctedRequestStarted = CompletableDeferred<Unit>()
    val releaseCorrectedResponse = CompletableDeferred<Unit>()
    val gateway =
        object : RecordingGateway() {
          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            calls++
            val arguments =
                if (calls == 1) {
                  firstRequestStarted.complete(Unit)
                  releaseFirstResponse.await()
                  """{"base_revision":0,"operations":[{"action":"edit_set","set_id":"${set.syncId}","values":{"set_type":"WORK"}}]}"""
                } else {
                  val recovery = (messages.last().content as JsonPrimitive).content
                  assertTrue(recovery.contains("revision_conflict"))
                  val revision =
                      requireNotNull(Regex("\\\"revision\\\":(\\d+)").find(recovery)).groupValues[1]
                  correctedRequestStarted.complete(Unit)
                  releaseCorrectedResponse.await()
                  """{"base_revision":$revision,"operations":[{"action":"edit_set","set_id":"${set.syncId}","values":{"set_type":"WORK"}}]}"""
                }
            return AiApiChatResponse(
                choices =
                    listOf(
                        AiApiChoice(
                            AiApiResponseMessage(
                                toolCalls =
                                    listOf(
                                        com.valerochka1337.valerochkagym.data.ai.AiApiToolCall(
                                            "proposal-$calls",
                                            function =
                                                com.valerochka1337.valerochkagym.data.ai
                                                    .AiApiToolCallFunction(
                                                        "submit_workout_changes",
                                                        arguments,
                                                    ),
                                        )
                                    )
                            )
                        )
                    )
            )
          }
        }
    val conversation = conversation(gateway)
    conversation.attach(
        kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
        )
    )

    assertTrue(conversation.send(workout, "Установи целевой запас шесть повторений"))
    firstRequestStarted.await()
    db.workoutDao().updateSet(set.copy(reps = 5))
    db.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workout),
    )
    val journalsBeforeRetry = db.coachDao().pendingJournal("user", 100)
    releaseFirstResponse.complete(Unit)
    correctedRequestStarted.await()
    assertNull(db.coachDao().pendingProposal(workout))
    assertEquals(journalsBeforeRetry, db.coachDao().pendingJournal("user", 100))
    assertEquals(0, tableCount("coach_command_receipts"))
    assertEquals(5, db.workoutDao().getSet(set.id)!!.reps)
    releaseCorrectedResponse.complete(Unit)
    db.coachDao().observeMessages(workout).first { it.firstOrNull()?.status == "DELIVERED" }
    val proposal = requireNotNull(db.coachDao().pendingProposal(workout))
    assertEquals(1L, proposal.baseRevision)
    assertTrue(proposal.afterSummary.contains("рабочий"))
    assertEquals(2, gateway.calls)
    assertEquals(5, db.workoutDao().getSet(set.id)!!.reps)
  }

  @Test
  fun `credential refresh keeps the active conversation able to answer and accept messages`() =
      runTest {
        val workout = activeWorkout()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway =
            object : RecordingGateway() {
              override suspend fun complete(
                  expectedOwner: String,
                  expectedSessionEpoch: Long?,
                  messages: List<AiApiMessage>,
                  tools: List<AiApiTool>,
              ): AiApiChatResponse {
                started.complete(Unit)
                release.await()
                return super.complete(expectedOwner, expectedSessionEpoch, messages, tools)
              }
            }
        val service = conversation(gateway)
        service.attach(backgroundScope)
        assertTrue(service.send(workout, "Подскажи"))
        started.await()
        session.refreshCredentials()
        runCurrent()
        release.complete(Unit)
        val messages = db.coachDao().observeMessages(workout).first { it.size == 2 }
        assertEquals("DELIVERED", messages.last().status)
        runCurrent()
        assertTrue(service.send(workout, "Продолжим"))
        db.coachDao().observeMessages(workout).first { it.size == 4 }
        assertEquals(2, gateway.calls)
      }

  @Test
  fun `session replacement interrupts the old request without appending assistant or journal`() =
      runTest {
        val workout = activeWorkout()
        val gateway = BlockingGateway()
        val conversation = conversation(gateway)
        conversation.attach(
            kotlinx.coroutines.CoroutineScope(
                backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
            )
        )
        assertTrue(conversation.send(workout, "Подожди"))
        gateway.started.await()
        val context = db.coachDao().context(workout)
        val journal = db.coachDao().pendingJournal("user", 100)
        session.save(BackendTokens("user", "user@example.com", "other-access", "other-refresh"))
        db.coachDao().observeMessages(workout).first { it.singleOrNull()?.status == "INTERRUPTED" }
        assertEquals(context, db.coachDao().context(workout))
        assertEquals(journal, db.coachDao().pendingJournal("user", 100))
        assertEquals(listOf("user"), db.coachDao().messages(workout).map { it.role })
      }

  @Test
  fun `detach interrupts an in flight request and preserves its transcript journal and context`() =
      runTest {
        val workout = activeWorkout()
        val gateway = BlockingGateway()
        val conversation = conversation(gateway)
        conversation.attach(
            kotlinx.coroutines.CoroutineScope(
                backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
            )
        )
        assertTrue(conversation.send(workout, "Подожди"))
        gateway.started.await()
        val context = db.coachDao().context(workout)
        val journal = db.coachDao().pendingJournal("user", 100)
        conversation.detach()
        db.coachDao().observeMessages(workout).first { it.singleOrNull()?.status == "INTERRUPTED" }
        assertEquals(context, db.coachDao().context(workout))
        assertEquals(journal, db.coachDao().pendingJournal("user", 100))
        assertEquals(listOf("user"), db.coachDao().messages(workout).map { it.role })
      }

  @Test
  fun `request becoming stale during append rolls back message context and journal together`() =
      runTest {
        val workout = activeWorkout()
        val conversation = conversation(RecordingGateway())
        var checks = 0
        try {
          conversation.appendMessage(
              "late",
              "user",
              workout,
              "assistant",
              "Поздно",
              isCurrent = { ++checks == 1 },
          )
          org.junit.Assert.fail("Late request must roll back")
        } catch (_: IllegalStateException) {}
        assertTrue(db.coachDao().messages(workout).isEmpty())
        assertTrue(db.coachDao().pendingJournal("user", 100).isEmpty())
        org.junit.Assert.assertNull(db.coachDao().context(workout))
      }

  @Test
  fun `concurrent startup checks remain silent without a signal`() = runTest {
    val workout = activeWorkout()
    val service = conversation(RecordingGateway())
    service.attach(backgroundScope)
    val decisions =
        kotlinx.coroutines.coroutineScope {
          listOf(
                  async { service.considerInitiative(workout) },
                  async { service.considerInitiative(workout) },
              )
              .map { it.await() }
        }
    assertTrue(decisions.none { it })
    assertTrue(db.coachDao().messages(workout).isEmpty())
    assertTrue(db.coachDao().pendingJournal("user", 100).isEmpty())
  }

  @Test
  fun `newer message supersedes a model request without recording its late answer`() = runTest {
    val workout = activeWorkout()
    val started = CompletableDeferred<Unit>()
    val gateway =
        object : RecordingGateway() {
          var first = true

          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            if (first) {
              first = false
              started.complete(Unit)
              return CompletableDeferred<AiApiChatResponse>().await()
            }
            return super.complete(expectedOwner, expectedSessionEpoch, messages, tools)
          }
        }
    val conversation = conversation(gateway)
    conversation.attach(
        kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default
        )
    )
    assertTrue(conversation.send(workout, "Первый вопрос"))
    started.await()
    assertTrue(conversation.send(workout, "Новый вопрос"))
    val messages =
        db.coachDao().observeMessages(workout).first { rows ->
          rows.size == 3 && rows.filter { it.role == "user" }.any { it.status == "DELIVERED" }
        }
    assertEquals("INTERRUPTED", messages.single { it.text == "Первый вопрос" }.status)
    assertEquals(1, messages.count { it.role == "assistant" })
    assertEquals(3, db.coachDao().pendingJournal("user", 100).size)
  }

  @Test
  fun `stop after message commit prevents its registration from reviving the request`() = runTest {
    val workout = activeWorkout()
    val gateway = RecordingGateway()
    val conversation = conversation(gateway)
    conversation.attach(backgroundScope)
    val dispatcher = PausingDispatcher()
    val sending = async(dispatcher) { conversation.send(workout, "Не возобновляй после остановки") }
    var stoppedAfterCommit = false
    while (!sending.isCompleted) {
      val continuation = dispatcher.tasks.receive()
      if (!stoppedAfterCommit && db.coachDao().messages(workout).any { it.status == "PENDING" }) {
        conversation.stopWorkout(workout)
        stoppedAfterCommit = true
      }
      continuation.run()
    }
    assertTrue(stoppedAfterCommit)
    assertFalse(sending.await())
    val message =
        db.coachDao()
            .observeMessages(workout)
            .first { it.singleOrNull()?.status == "INTERRUPTED" }
            .single()
    assertEquals("user", message.role)
    assertEquals(1, db.coachDao().pendingJournal("user", 100).size)
    conversation.detach()
    conversation.attach(backgroundScope)
    runCurrent()
    assertEquals(0, gateway.calls)
  }

  private class PausingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    val tasks =
        kotlinx.coroutines.channels.Channel<Runnable>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
      check(tasks.trySend(block).isSuccess)
    }
  }

  private suspend fun activeWorkout(): String {
    val workoutId = insertWorkout("active")
    val exercise =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                ),
            )
    val section = insertWorkoutExercise(workoutId, exercise)
    insertSet(section, 0, weightKg = 50.0, reps = 8)
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)",
    )
    return workoutId
  }

  private fun TestScope.conversation(gateway: CoachModelGateway): CoachConversationService {
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val writes = WorkoutWriteQueue()
    val editor = WorkoutEditor(db, db.workoutDao(), db.coachDao(), timer, session, writes)
    val reader = CoachWorkoutReader(db, timer, session)
    return CoachConversationService(CoachAgent(gateway), reader, editor, db, session, writes)
  }

  @Test
  fun `user reply clears an initiative wait when no proposal is pending`() = runTest {
    val workout = activeWorkout()
    db.coachDao()
        .saveContext(
            com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity(
                workout,
                "user",
                initiativePendingInteraction = true,
            )
        )
    assertTrue(
        conversation(RecordingGateway())
            .appendMessage("reply", "user", workout, "user", "Продолжаю")
    )
    assertFalse(db.coachDao().context(workout)!!.initiativePendingInteraction)
  }

  private val session = FakeSession()

  private class FakeSession : BackendSessionStore {
    private val state =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )
    override val session: StateFlow<BackendTokens?> = state
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    fun refreshCredentials() {
      state.value = state.value!!.copy(accessToken = "new-access", refreshToken = "new-refresh")
    }

    override fun save(tokens: BackendTokens?) {
      epoch++
      state.value = tokens
    }
  }

  private class AutomaticProposalGateway(private val setId: String) : RecordingGateway() {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ): AiApiChatResponse {
      calls++
      if (calls == 1) {
        started.complete(Unit)
        release.await()
      }
      val name = if (calls == 1) "get_workout_state" else "submit_workout_changes"
      val arguments =
          if (calls == 1) "{}"
          else
              """{"base_revision":0,"reason":"Предлагаю 45 кг, чтобы сохранить повторы.","operations":[{"action":"edit_set","set_id":"$setId","values":{"weight_kg":45}}]}"""
      return AiApiChatResponse(
          choices =
              listOf(
                  AiApiChoice(
                      AiApiResponseMessage(
                          toolCalls =
                              listOf(
                                  com.valerochka1337.valerochkagym.data.ai.AiApiToolCall(
                                      "auto-$calls",
                                      function =
                                          com.valerochka1337.valerochkagym.data.ai
                                              .AiApiToolCallFunction(name, arguments),
                                  )
                              )
                      )
                  )
              )
      )
    }
  }

  private open class RecordingGateway(var answerText: String = "Готов помочь.") :
      CoachConversationServiceTestGateway {
    var calls = 0
    val requests = mutableListOf<List<AiApiMessage>>()
    val owners = mutableListOf<String>()
    val epochs = mutableListOf<Long?>()

    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ): AiApiChatResponse {
      calls++
      requests += messages
      owners += expectedOwner
      epochs += expectedSessionEpoch
      return AiApiChatResponse(
          choices = listOf(AiApiChoice(AiApiResponseMessage(content = JsonPrimitive(answerText)))),
      )
    }
  }

  private class BlockingGateway : RecordingGateway() {
    val started = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<AiApiChatResponse>()

    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ): AiApiChatResponse {
      calls++
      owners += expectedOwner
      epochs += expectedSessionEpoch
      started.complete(Unit)
      return never.await()
    }
  }
}

/** Completed-only fixture; streaming behavior uses explicit event fakes below. */
private interface CoachConversationServiceTestGateway :
    com.valerochka1337.valerochkagym.data.ai.CoachModelGateway {
  override suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?) =
      "Server coach prompt"

  suspend fun complete(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<com.valerochka1337.valerochkagym.data.ai.AiApiMessage>,
      tools: List<com.valerochka1337.valerochkagym.data.ai.AiApiTool>,
  ): com.valerochka1337.valerochkagym.data.ai.AiApiChatResponse

  override fun stream(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<com.valerochka1337.valerochkagym.data.ai.AiApiMessage>,
      tools: List<com.valerochka1337.valerochkagym.data.ai.AiApiTool>,
  ) =
      kotlinx.coroutines.flow.flow {
        emit(
            com.valerochka1337.valerochkagym.data.ai.CoachModelEvent.Completed(
                complete(expectedOwner, expectedSessionEpoch, messages, tools)
            )
        )
      }
}
