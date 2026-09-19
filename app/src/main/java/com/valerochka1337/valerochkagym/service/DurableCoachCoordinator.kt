package com.valerochka1337.valerochkagym.service

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.worker.CoachDeliveryScheduler
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** The service owns delivery only. Cancelling this scope never cancels a server run. */
@Singleton
class DurableCoachCoordinator
@Inject
constructor(
    private val client: CoachRunsClient,
    private val database: GymDatabase,
    private val reader: CoachWorkoutReader,
    private val editor: WorkoutEditor,
    private val sessions: BackendSessionStore,
    private val settings: SettingsRepository? = null,
) {
  val running = MutableStateFlow<Set<String>>(emptySet())
  val drafts = MutableStateFlow<Map<String, CoachDraft>>(emptyMap())
  val stages = MutableStateFlow<Map<String, String>>(emptyMap())
  val alerts = MutableSharedFlow<String>(extraBufferCapacity = 4)
  @Inject lateinit var deliveryScheduler: CoachDeliveryScheduler

  private fun schedule() {
    if (::deliveryScheduler.isInitialized) deliveryScheduler.enqueue()
  }

  private val mutex = Mutex()
  private val deliveryMutex = Mutex()
  private var worker: Job? = null
  private var sessionGuard: Job? = null
  private var displayedSession: Pair<String, Long>? = null
  private val lastDiscoveryAttempt = mutableMapOf<Triple<String, String, Boolean>, Long>()
  private val dao
    get() = database.coachRunDao()

  fun attach(scope: CoroutineScope) {
    detach()
    sessionGuard =
        scope.launch {
          sessions.sessionEpochs.collect {
            val live = sessions.snapshot()?.let { it.tokens.userId to it.epoch }
            if (displayedSession != live) {
              displayedSession = live
              drafts.value = emptyMap()
              stages.value = emptyMap()
              running.value = emptySet()
            }
          }
        }
    worker =
        scope.launch {
          while (isActive) {
            val session = sessions.snapshot()
            if (session != null) {
              try {
                deliverPending()
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (error: Exception) {
                CoachDiagnostics.failure("runs.delivery.retry", error)
              }
            }
            delay(2_000)
          }
        }
  }

  fun detach() {
    worker?.cancel()
    sessionGuard?.cancel()
    sessionGuard = null
    worker = null
    running.value = emptySet()
    drafts.value = emptyMap()
    stages.value = emptyMap()
    schedule()
  }

  fun stopWorkout(workoutId: String) {
    // The persistent dirty marker for the finished workout sends active=false on reconnect.
    drafts.value -= workoutId
    schedule()
  }

  private fun current(owner: String, epoch: Long): Boolean =
      sessions.snapshot()?.let { it.tokens.userId == owner && it.epoch == epoch } == true

  suspend fun send(workoutId: String, text: String): Boolean {
    if (text.isBlank() || text.length > 4_000) return false
    val session = sessions.snapshot() ?: return false
    val owner = session.tokens.userId
    val selectedModel = settings?.coachModel(owner)?.first()
    return mutex
        .withLock {
          database.withTransaction {
            if (!current(owner, session.epoch)) return@withTransaction false
            val snapshot =
                reader.snapshot(owner, workoutId, session.epoch) ?: return@withTransaction false
            val requestId = UUID.randomUUID().toString()
            val version = CoachToolCodec.contextVersion(snapshot)
            val history =
                database
                    .coachDao()
                    .messages(workoutId)
                    .filter { it.role in setOf("user", "assistant") && it.status != "ERROR" }
                    .takeLast(40)
            val payload =
                buildJsonObject {
                      put("requestId", requestId)
                      put("workoutId", workoutId)
                      put("contextVersion", version)
                      put(
                          "snapshot",
                          Json.parseToJsonElement(CoachToolCodec.snapshotJson(snapshot)),
                      )
                      put("message", text)
                      put("automatic", false)
                      selectedModel?.let { put("model", it) }
                      put(
                          "history",
                          buildJsonArray {
                            history.forEach { row ->
                              add(
                                  buildJsonObject {
                                    put("role", row.role)
                                    put("text", row.text)
                                  }
                              )
                            }
                          },
                      )
                    }
                    .toString()
            val now = nextCreatedAt(owner, workoutId)
            val context =
                database.coachDao().context(workoutId)
                    ?: CoachSessionContextEntity(workoutId, owner)
            database.coachDao().saveContext(context.copy(initiativePendingInteraction = false))
            database
                .coachDao()
                .pendingProposal(workoutId)
                ?.takeIf { it.accountId == owner }
                ?.let { old ->
                  database.coachDao().setProposalState(old.id, "SUPERSEDED")
                  dao.proposal(old.id)?.let { queueReceipt(it, old.id, "STALE", session.epoch) }
                }
            saveMessage(
                CoachMessageEntity(requestId, owner, workoutId, "user", text, now, "PENDING")
            )
            dao.insert(CoachRunEntity(requestId, owner, workoutId, payload, version, now))
            dao.markDirty(workoutId)
            check(current(owner, session.epoch))
            true
          }
        }
        .also { if (it) schedule() }
  }

  suspend fun retry(workoutId: String, messageId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val owner = session.tokens.userId
    val stored =
        dao.runsForWorkout(owner, workoutId).firstOrNull {
          answerId(it.requestId) == messageId || it.requestId == messageId
        } ?: return false
    if (!stored.imported) {
      schedule()
      return true
    }
    val error =
        database.coachDao().messages(workoutId).firstOrNull {
          it.id == messageId && it.status == "ERROR"
        } ?: return false
    if (stored.requestJson.isBlank()) return false
    val original = Json.parseToJsonElement(stored.requestJson).jsonObject
    val text = original.string("message") ?: return false
    // A terminal run is immutable. Retrying creates a new request with fresh context while
    // keeping the original user message and immutable journal intact.
    return mutex
        .withLock {
          database.withTransaction {
            if (!current(owner, session.epoch)) return@withTransaction false
            val snapshot =
                reader.snapshot(owner, workoutId, session.epoch) ?: return@withTransaction false
            val id = UUID.randomUUID().toString()
            val version = CoachToolCodec.contextVersion(snapshot)
            val payload =
                JsonObject(
                        original.toMutableMap().apply {
                          put("requestId", JsonPrimitive(id))
                          put("contextVersion", JsonPrimitive(version))
                          put(
                              "snapshot",
                              Json.parseToJsonElement(CoachToolCodec.snapshotJson(snapshot)),
                          )
                          put("message", JsonPrimitive(text))
                        }
                    )
                    .toString()
            dao.insert(
                CoachRunEntity(
                    id,
                    owner,
                    workoutId,
                    payload,
                    version,
                    nextCreatedAt(owner, workoutId),
                )
            )
            database.coachDao().deleteAssistantMessage(error.id, owner, workoutId)
            check(current(owner, session.epoch))
            true
          }
        }
        .also { if (it) schedule() }
  }

  suspend fun changed(workoutId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val workout = database.workoutDao().getWorkoutFull(workoutId) ?: return false
    val context = database.coachDao().context(workoutId)
    if (context != null && context.accountId != session.tokens.userId) return false
    dao.markDirty(workout.workout.id)
    schedule()
    return true
  }

  private suspend fun capture(workoutId: String, owner: String, epoch: Long) =
      mutex.withLock {
        database.withTransaction {
          if (!current(owner, epoch)) return@withTransaction
          val full = database.workoutDao().getWorkoutFull(workoutId)
          val context = database.coachDao().context(workoutId)
          if (context != null && context.accountId != owner) return@withTransaction
          val old = dao.session(workoutId)
          if (old != null && old.accountId != owner) return@withTransaction
          // Do not overwrite an unacknowledged payload: its idempotency identity is immutable.
          if (old != null && !old.delivered) return@withTransaction
          val snapshot = reader.snapshot(owner, workoutId, epoch)
          val active = full != null && full.workout.finishedAt == null
          if (snapshot == null && active) return@withTransaction
          val snapshotJson =
              snapshot?.let { Json.parseToJsonElement(CoachToolCodec.snapshotJson(it)) }
                  ?: old?.let { Json.parseToJsonElement(it.payload).jsonObject["snapshot"] }
                  ?: run {
                    // A removed workout never sent to the server has no remote session to close.
                    dao.dirtySessions()
                        .firstOrNull { it.workoutId == workoutId }
                        ?.let { dao.clean(workoutId, it.generation) }
                    return@withTransaction
                  }
          val version = snapshot?.let(CoachToolCodec::contextVersion) ?: old!!.contextVersion
          val sequence = (old?.sequence ?: 0) + 1
          val payload =
              buildJsonObject {
                    put("eventId", UUID.randomUUID().toString())
                    put("sequence", sequence)
                    put("contextVersion", version)
                    put("snapshot", snapshotJson)
                    put("initiativeEnabled", context?.initiativeEnabled ?: true)
                    put("active", active)
                  }
                  .toString()
          dao.saveSession(CoachSessionOutboxEntity(workoutId, owner, sequence, version, payload))
          check(current(owner, epoch))
          dao.dirtySessions()
              .firstOrNull { it.workoutId == workoutId }
              ?.let { dao.clean(workoutId, it.generation) }
        }
      }

  /** One recoverable delivery pass, also used by WorkManager when the foreground service stops. */
  suspend fun deliverPending(): Boolean =
      deliveryMutex.withLock {
        val session = sessions.snapshot() ?: return@withLock false
        val owner = session.tokens.userId
        val epoch = session.epoch
        if (displayedSession != (owner to epoch)) {
          displayedSession = owner to epoch
          drafts.value = emptyMap()
          stages.value = emptyMap()
          running.value = emptySet()
        }
        try {
          pump(owner, epoch)
        } finally {
          if (!current(owner, epoch)) {
            drafts.value = emptyMap()
            stages.value = emptyMap()
            running.value = emptySet()
          }
        }
        current(owner, epoch) &&
            (dao.pending(owner).isNotEmpty() ||
                dao.pendingSessions(owner).isNotEmpty() ||
                dao.receipts(owner).isNotEmpty() ||
                dao.dirtySessions().isNotEmpty() ||
                dao.sessionsNeedingDiscovery(owner).any { !isActiveSession(it) })
      }

  private suspend fun attempt(block: suspend () -> Unit) {
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      CoachDiagnostics.failure("runs.delivery.retry", error)
    }
  }

  private suspend fun pump(owner: String, epoch: Long) {
    if (!current(owner, epoch)) return
    val activeId = database.workoutDao().getActiveWorkoutId()
    // Rescan on attachment also covers changes made while no service was running.
    if (activeId != null) {
      val snapshot = reader.snapshot(owner, activeId, epoch)
      if (snapshot != null) {
        val prior = dao.session(activeId)
        val observed =
            prior?.payload?.let {
              runCatching {
                    Json.parseToJsonElement(it)
                        .jsonObject["snapshot"]
                        ?.jsonObject
                        ?.get("observed_at_millis")
                        ?.jsonPrimitive
                        ?.longOrNull
                  }
                  .getOrNull()
            } ?: 0L
        // Freshness is separate from semantic context. A long unchanged rest still permits a
        // server time reminder while this device continues to publish its current state.
        if (
            prior?.contextVersion != CoachToolCodec.contextVersion(snapshot) ||
                System.currentTimeMillis() - observed >= 60_000L
        )
            dao.markDirty(activeId)
      }
    }
    for (dirty in dao.dirtySessions()) capture(dirty.workoutId, owner, epoch)
    for (outbox in dao.pendingSessions(owner)) {
      if (!current(owner, epoch)) return
      attempt {
        client.putSession(outbox.workoutId, outbox.payload, owner, epoch)
        if (current(owner, epoch)) dao.delivered(outbox.workoutId, outbox.sequence)
      }
    }
    for (receipt in dao.receipts(owner)) {
      if (!current(owner, epoch)) return
      attempt {
        client.receipt(receipt.runId, receipt.payload, owner, epoch)
        if (current(owner, epoch)) dao.deliveredReceipt(receipt.receiptId)
      }
    }
    // Closed sessions need one successful final discovery, persisted across app launches.
    // Bound discovery separately from status polling so a long history cannot exhaust rate limits.
    val discoveryCandidates = dao.sessionsNeedingDiscovery(owner)
    val now = System.currentTimeMillis()
    val candidates =
        discoveryCandidates
            .filter { session ->
              val last =
                  lastDiscoveryAttempt[Triple(owner, session.workoutId, isActiveSession(session))]
                      ?: Long.MIN_VALUE
              last == Long.MIN_VALUE ||
                  now - last >= if (session.workoutId == activeId) 10_000L else 30_000L
            }
            .sortedBy { if (it.workoutId == activeId) 0 else 1 }
            .take(2)
    for (discovery in candidates) {
      val discoveryId = discovery.workoutId
      lastDiscoveryAttempt[Triple(owner, discoveryId, isActiveSession(discovery))] = now
      val discovered =
          try {
            client.list(discoveryId, owner, epoch)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            continue
          }
      for (status in discovered) {
        if (!current(owner, epoch)) return
        val id = status.string("runId") ?: continue
        if (dao.run(id) == null)
            dao.insert(
                CoachRunEntity(
                    id,
                    owner,
                    discoveryId,
                    "",
                    status.string("contextVersion") ?: "",
                    System.currentTimeMillis(),
                    submitted = true,
                )
            )
        importStatus(status, owner, epoch)
      }
      if (current(owner, epoch) && !isActiveSession(discovery))
          dao.discoveryComplete(discoveryId, discovery.sequence)
    }
    val pending = dao.pending(owner)
    running.value = pending.map { it.workoutId }.toSet()
    // Submission order is durable. The server serializes execution within the workout.
    for (stored in pending) {
      if (!current(owner, epoch)) return
      val status =
          try {
            if (!stored.submitted) client.submit(stored.requestJson, owner, epoch)
            else client.status(stored.requestId, owner, epoch)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            if (error is BackendException && error.status in setOf(400, 403, 404, 409, 413, 422)) {
              importStatus(
                  buildJsonObject {
                    put("runId", stored.requestId)
                    put("workoutId", stored.workoutId)
                    put("state", "FAILED")
                    put("errorCode", error.code)
                  },
                  owner,
                  epoch,
              )
            } else stages.value += stored.workoutId to "Ожидаем соединение…"
            // Preserve FIFO admission: a later user message must not overtake an unacknowledged
            // send.
            if (!stored.submitted && dao.run(stored.requestId)?.imported != true) break
            continue
          }
      if (!current(owner, epoch)) return
      val latest = dao.run(stored.requestId) ?: continue
      dao.update(latest.copy(submitted = true))
      importStatus(status, owner, epoch)
      val run = dao.run(stored.requestId) ?: continue
      if (!run.imported) {
        try {
          withTimeoutOrNull(2_000) {
            client.events(run.requestId, run.cursor, owner, epoch).collect { event ->
              if (!current(owner, epoch)) return@collect
              val sequence = event["sequence"]?.jsonPrimitive?.longOrNull ?: return@collect
              val fresh = dao.run(run.requestId) ?: return@collect
              if (sequence <= fresh.cursor) return@collect
              event.string("stage")?.let { stages.value += run.workoutId to stageLabel(it) }
              event
                  .string("text")
                  ?.takeIf { run.requestJson.isNotBlank() }
                  ?.let { text ->
                    drafts.value +=
                        run.workoutId to
                            CoachDraft(
                                answerId(run.requestId),
                                run.requestId,
                                run.workoutId,
                                owner,
                                epoch,
                                text,
                            )
                  }
              (event["run"] as? JsonObject)?.let { importStatus(it, owner, epoch) }
              val after = dao.run(run.requestId) ?: return@collect
              dao.update(after.copy(cursor = maxOf(after.cursor, sequence)))
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          CoachDiagnostics.failure("runs.sse.polling_fallback", error)
        }
      }
    }
    running.value = dao.pending(owner).map { it.workoutId }.toSet()
  }

  private suspend fun importStatus(status: JsonObject, owner: String, epoch: Long) {
    if (!current(owner, epoch)) return
    val id = status.string("runId") ?: return
    val run = dao.run(id) ?: return
    if (run.accountId != owner || run.imported || status.string("workoutId") != run.workoutId)
        return
    status.string("stage")?.let { stages.value += run.workoutId to stageLabel(it) }
    val state = status.string("state") ?: return
    if (state !in setOf("SUCCEEDED", "FAILED", "CANCELLED", "SUPERSEDED")) return
    val result = status["result"] as? JsonObject
    val proposal = result?.get("proposal") as? JsonObject
    var proposalId: String? = null
    var staleProposal = false
    var invalidResult = false
    try {
      if (state == "SUCCEEDED") {
        require(result != null && result.string("kind") in setOf("answer", "proposal", "no_change"))
        require(result.string("kind") != "proposal" || proposal != null)
      }
      if (state == "SUCCEEDED" && proposal != null) {
        proposalId = requireNotNull(proposal.string("proposalId"))
        val version = requireNotNull(proposal.string("contextVersion"))
        val expires = requireNotNull(proposal["expiresAtMillis"]?.jsonPrimitive?.longOrNull)
        val arguments = buildJsonObject {
          put("base_revision", requireNotNull(proposal["baseRevision"]))
          put("operations", requireNotNull(proposal["operations"]))
          (proposal["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("reason", it) }
        }
        val decoded =
            CoachToolCodec.decode(
                AiApiToolCall(
                    id = proposalId,
                    function =
                        AiApiToolCallFunction("submit_workout_changes", arguments.toString()),
                )
            ) as CoachToolRequest.Submit
        // Never ask the phone to calculate a recommendation represented by an opaque intent.
        database.withTransaction {
          if (!current(owner, epoch)) return@withTransaction
          dao.update(run.copy(proposalId = proposalId))
          check(current(owner, epoch))
        }
        if (!current(owner, epoch)) return
        val alreadySaved =
            database.openHelper.writableDatabase
                .query(
                    "SELECT accountId, workoutId, baseRevision, expiresAt FROM coach_proposals WHERE id=?",
                    arrayOf(proposalId),
                )
                .use { rows ->
                  rows.moveToFirst() &&
                      rows.getString(0) == owner &&
                      rows.getString(1) == run.workoutId &&
                      rows.getLong(2) == decoded.baseRevision &&
                      rows.getLong(3) == expires
                }
        val saved =
            if (alreadySaved) null
            else if (
                decoded.operations.any { it is CoachChangeIntent.Autoregulate } ||
                    version != run.contextVersion ||
                    expires <= System.currentTimeMillis()
            )
                ModelProposalSaveResult.Stale
            else
                editor.saveModelProposalResult(
                    owner,
                    run.workoutId,
                    decoded.baseRevision,
                    decoded.operations,
                    expires,
                    epoch,
                    serverProposalId = proposalId,
                    expectedContextVersion = version,
                )
        if (!alreadySaved && saved !is ModelProposalSaveResult.Saved) {
          queueReceipt(run, proposalId, "STALE", epoch)
          staleProposal = true
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      // A malformed terminal result must not poison the entire durable queue.
      CoachDiagnostics.failure("runs.result.invalid", error)
      invalidResult = true
      if (proposalId != null && runCatching { UUID.fromString(proposalId) }.isSuccess) {
        queueReceipt(run, proposalId, "STALE", epoch)
      }
    }
    var visible = false
    database.withTransaction {
      if (!current(owner, epoch)) return@withTransaction
      val latest = dao.run(id) ?: return@withTransaction
      if (latest.imported) return@withTransaction
      val workoutExists = database.workoutDao().getWorkoutFull(run.workoutId) != null
      val text =
          if (invalidResult) "Не удалось проверить ответ тренера. Можно повторить запрос."
          else if (staleProposal)
              "Предложение устарело: состояние тренировки изменилось. Отправьте новое сообщение для пересчёта."
          else
              result?.string("text")
                  ?: if (state == "FAILED") "Не удалось завершить анализ. Можно повторить запрос."
                  else ""
      if (workoutExists && text.isNotBlank() && result?.string("kind") != "no_change") {
        val replies =
            (result?.get("quickReplies") as? JsonArray)?.mapNotNull {
              (it as? JsonPrimitive)?.contentOrNull
            }
        saveMessage(
            CoachMessageEntity(
                answerId(id),
                owner,
                run.workoutId,
                "assistant",
                text,
                System.currentTimeMillis(),
                if (state == "FAILED" || invalidResult) "ERROR" else "DELIVERED",
                CoachReply.encodeQuickReplies(replies.orEmpty()),
            )
        )
        visible = true
      }
      database.coachDao().setMessageStatus(id, "DELIVERED")
      dao.update(latest.copy(imported = true, proposalId = proposalId))
      check(current(owner, epoch))
    }
    drafts.value -= run.workoutId
    stages.value -= run.workoutId
    if (visible) alerts.tryEmit(run.workoutId)
  }

  private fun isActiveSession(session: CoachSessionOutboxEntity): Boolean =
      Json.parseToJsonElement(session.payload).jsonObject["active"]?.jsonPrimitive?.booleanOrNull !=
          false

  private suspend fun nextCreatedAt(owner: String, workoutId: String): Long =
      maxOf(
          System.currentTimeMillis(),
          (dao.runsForWorkout(owner, workoutId).maxOfOrNull { it.createdAt } ?: 0) + 1,
      )

  private suspend fun queueReceipt(
      run: CoachRunEntity,
      proposalId: String,
      status: String,
      epoch: Long,
  ) {
    database.withTransaction {
      if (!current(run.accountId, epoch)) return@withTransaction
      val id =
          UUID.nameUUIDFromBytes("${run.requestId}:$proposalId:$status".toByteArray()).toString()
      dao.receipt(
          CoachReceiptOutboxEntity(
              id,
              run.accountId,
              run.requestId,
              buildJsonObject {
                    put("proposalId", proposalId)
                    put("status", status)
                    put("receiptId", id)
                  }
                  .toString(),
          )
      )
      check(current(run.accountId, epoch))
    }
  }

  private suspend fun saveMessage(message: CoachMessageEntity) {
    database.coachDao().saveMessage(message)
    database
        .coachDao()
        .saveJournal(
            CoachJournalEntity(
                message.id,
                message.accountId,
                message.workoutId,
                message.createdAt,
                buildJsonObject {
                      put("kind", "message")
                      put("role", message.role)
                      put("text", message.text)
                    }
                    .toString(),
            )
        )
  }

  private fun answerId(runId: String) =
      UUID.nameUUIDFromBytes("coach-answer:$runId".toByteArray()).toString()

  private fun stageLabel(stage: String): String =
      when (stage) {
        "queued" -> "Ожидаем анализа…"
        "analyzing" -> "Анализируем тренировку…"
        "completed" -> "Анализ завершён"
        "failed" -> "Не удалось завершить анализ"
        else -> "Готовим ответ…"
      }

  private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
