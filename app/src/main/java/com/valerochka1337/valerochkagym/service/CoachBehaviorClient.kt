package com.valerochka1337.valerochkagym.service

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.domain.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Local projection and exact-byte answer outbox; never invents an AI run for a policy event. */
internal class CoachBehaviorClient(
    private val client: CoachRunsClient,
    private val db: GymDatabase,
    private val reader: CoachWorkoutReader,
    private val editor: WorkoutEditor,
    private val sessions: BackendSessionStore,
) {
  private val dao
    get() = db.coachRunDao()

  private fun live(owner: String, epoch: Long) =
      sessions.snapshot()?.let { it.tokens.userId == owner && it.epoch == epoch } == true

  private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

  /** Caller persists the SSE cursor in the same Room transaction. */
  suspend fun accept(owner: String, workout: String, epoch: Long, event: JsonObject): Boolean {
    if (!live(owner, epoch)) return false
    when (event.text("type")) {
      "concern" -> {
        val id = requireNotNull(event.text("eventId"))
        require(UUID.fromString(id).toString() == id)
        if (dao.behavior(id) == null) {
          dao.saveBehavior(CoachBehaviorEntity(id, owner, workout, "concern", event.toString()))
          message(
              id,
              owner,
              workout,
              event.text("text") ?: "Вы сообщили о боли или нарушении техники.",
          )
          return true
        }
      }
      "intervention",
      "intervention_answer" -> {
        val result = event["result"] as? JsonObject ?: return false
        result.text("questionId")?.let { id ->
          dao.behavior(id)
              ?.takeIf { it.accountId == owner && it.workoutId == workout }
              ?.let {
                dao.saveBehavior(
                    it.copy(status = result.text("status") ?: "ANSWERED", requestJson = null)
                )
              }
        }
        val question = result["question"] as? JsonObject
        if (question != null) {
          val id = requireNotNull(question.text("questionId"))
          require(UUID.fromString(id).toString() == id)
          if (dao.behavior(id) == null) {
            val snapshot = reader.snapshot(owner, workout, epoch)
            val relevant =
                snapshot?.previousSetId == question.text("setId") &&
                    (question["expiresAtMillis"]?.jsonPrimitive?.longOrNull ?: 0) >
                        System.currentTimeMillis()
            dao.saveBehavior(
                CoachBehaviorEntity(
                    id,
                    owner,
                    workout,
                    "question",
                    result.toString(),
                    if (relevant) "OPEN" else "STALE",
                )
            )
            if (relevant)
                message(id, owner, workout, result.text("text") ?: "Уточните результат подхода")
            return relevant
          }
        }
        val proposal = result["proposal"] as? JsonObject
        if (proposal != null) {
          val id = requireNotNull(proposal.text("proposalId"))
          require(UUID.fromString(id).toString() == id)
          if (dao.behavior(id) != null) return false
          require(proposal["version"]?.jsonPrimitive?.longOrNull == 1L)
          val expires = requireNotNull(proposal["expiresAtMillis"]?.jsonPrimitive?.longOrNull)
          val version = requireNotNull(proposal.text("contextVersion"))
          val args = buildJsonObject {
            put("base_revision", requireNotNull(proposal["baseRevision"]))
            put("operations", requireNotNull(proposal["operations"]))
            proposal["reason"]?.let { put("reason", it) }
          }
          val decoded =
              CoachToolCodec.decode(
                  AiApiToolCall(
                      id,
                      function = AiApiToolCallFunction("submit_workout_changes", args.toString()),
                  )
              ) as CoachToolRequest.Submit
          val entry = CoachBehaviorEntity(id, owner, workout, "proposal", result.toString())
          dao.saveBehavior(entry)
          val snapshot = reader.snapshot(owner, workout, epoch)
          val saved =
              if (
                  snapshot == null ||
                      snapshot.paused ||
                      snapshot.phase == "IN_SET" ||
                      CoachToolCodec.contextVersion(snapshot) != version ||
                      expires <= System.currentTimeMillis() ||
                      decoded.operations.any { it is CoachChangeIntent.Autoregulate }
              )
                  ModelProposalSaveResult.Stale
              else
                  editor.saveModelProposalResult(
                      owner,
                      workout,
                      decoded.baseRevision,
                      decoded.operations,
                      expires,
                      epoch,
                      serverProposalId = id,
                      expectedContextVersion = version,
                  )
          if (saved !is ModelProposalSaveResult.Saved) {
            dao.saveBehavior(entry.copy(status = "STALE"))
            receipt(entry, "STALE")
            return false
          }
          message(id, owner, workout, result.text("text") ?: "Предлагаю изменить оставшийся план")
          return true
        }
      }
      "intervention_receipt" -> {
        val result = event["result"] as? JsonObject ?: return false
        val id = result.text("proposalId") ?: return false
        val existing =
            dao.behavior(id)?.takeIf { it.accountId == owner && it.workoutId == workout }
                ?: return false
        val status = result.text("status") ?: return false
        // A server notification cannot establish local APPLIED or override a locally committed
        // decision.
        if (status in setOf("STALE", "EXPIRED") && db.coachDao().pendingProposalForId(id) != null) {
          db.coachDao().setProposalState(id, status)
          dao.saveBehavior(existing.copy(status = status))
        }
      }
    }
    return false
  }

  suspend fun answer(workout: String, id: String, option: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val owner = session.tokens.userId
    return db.withTransaction {
      if (!live(owner, session.epoch)) return@withTransaction false
      val row =
          dao.behavior(id)?.takeIf {
            it.accountId == owner && it.workoutId == workout && it.kind == "question"
          } ?: return@withTransaction false
      if (row.status != "OPEN") return@withTransaction false
      val q = Json.parseToJsonElement(row.payload).jsonObject["question"]!!.jsonObject
      if (q["options"]!!.jsonArray.none { it.jsonObject.text("id") == option })
          return@withTransaction false
      val snapshot = reader.snapshot(owner, workout, session.epoch)
      if (
          snapshot?.previousSetId != q.text("setId") ||
              (q["expiresAtMillis"]?.jsonPrimitive?.longOrNull ?: 0) <= System.currentTimeMillis()
      ) {
        dao.saveBehavior(row.copy(status = "STALE"))
        return@withTransaction false
      }
      val request = buildJsonObject {
        put("answerId", UUID.randomUUID().toString())
        put("expectedVersion", requireNotNull(q["version"]))
        put("answer", option)
      }
      dao.saveBehavior(row.copy(status = "PENDING", requestJson = request.toString()))
      check(live(owner, session.epoch))
      true
    }
  }

  suspend fun resolve(workout: String, id: String): Boolean {
    val session = sessions.snapshot() ?: return false
    return db.withTransaction {
      val owner = session.tokens.userId
      if (!live(owner, session.epoch)) return@withTransaction false
      val row =
          dao.behavior(id)?.takeIf {
            it.accountId == owner &&
                it.workoutId == workout &&
                it.kind == "concern" &&
                it.status == "OPEN"
          } ?: return@withTransaction false
      val keys =
          Json.parseToJsonElement(row.payload)
              .jsonObject["decision"]!!
              .jsonObject["state"]!!
              .jsonObject["openConcerns"]!!
              .jsonArray
      val phase = dao.phase(owner, workout) ?: CoachPhaseEntity(owner, workout)
      val resolved =
          (Json.parseToJsonElement(phase.resolvedConcernKeys).jsonArray + keys).distinct()
      dao.savePhase(phase.copy(resolvedConcernKeys = JsonArray(resolved).toString()))
      dao.saveBehavior(row.copy(status = "RESOLVED"))
      dao.markDirty(workout)
      check(live(owner, session.epoch))
      true
    }
  }

  suspend fun phase(workout: String, phase: String): Boolean {
    require(phase in setOf("READY", "IN_SET", "BETWEEN_EXERCISES", "PAUSED", "RESUME"))
    val session = sessions.snapshot() ?: return false
    return db.withTransaction {
      if (
          !live(session.tokens.userId, session.epoch) ||
              db.workoutDao().getActiveWorkoutId() != workout
      )
          return@withTransaction false
      val old =
          dao.phase(session.tokens.userId, workout)
              ?: CoachPhaseEntity(session.tokens.userId, workout)
      dao.savePhase(
          old.copy(
              phase = if (phase in setOf("PAUSED", "RESUME")) old.phase else phase,
              paused = phase == "PAUSED",
          )
      )
      dao.markDirty(workout)
      check(live(session.tokens.userId, session.epoch))
      true
    }
  }

  suspend fun deliver(owner: String, epoch: Long) {
    for (row in dao.pendingAnswers(owner)) {
      if (!live(owner, epoch)) return
      try {
        val result =
            client.answer(row.workoutId, row.id, requireNotNull(row.requestJson), owner, epoch)
        db.withTransaction {
          if (!live(owner, epoch)) return@withTransaction
          accept(
              owner,
              row.workoutId,
              epoch,
              buildJsonObject {
                put("type", "intervention_answer")
                put("result", result)
              },
          )
          val latest = dao.behavior(row.id) ?: return@withTransaction
          dao.saveBehavior(
              latest.copy(status = result.text("status") ?: "ANSWERED", requestJson = null)
          )
          check(live(owner, epoch))
        }
      } catch (error: BackendException) {
        if (error.status !in setOf(400, 404, 409, 422)) throw error
        db.withTransaction {
          if (!live(owner, epoch)) return@withTransaction
          dao.saveBehavior(row.copy(status = "STALE", requestJson = null))
        }
      }
    }
  }

  private suspend fun receipt(entry: CoachBehaviorEntity, status: String) {
    val id = UUID.nameUUIDFromBytes("intervention:${entry.id}:$status".toByteArray()).toString()
    dao.receipt(
        CoachReceiptOutboxEntity(
            id,
            entry.accountId,
            entry.id,
            buildJsonObject {
                  put("receiptId", id)
                  put("version", 1)
                  put("status", status)
                }
                .toString(),
            entry.workoutId,
        )
    )
  }

  private suspend fun message(id: String, owner: String, workout: String, text: String) {
    db.coachDao()
        .saveMessage(
            CoachMessageEntity(
                id,
                owner,
                workout,
                "assistant",
                text,
                System.currentTimeMillis(),
                "DELIVERED",
            )
        )
    db.coachDao()
        .saveJournal(
            CoachJournalEntity(
                id,
                owner,
                workout,
                System.currentTimeMillis(),
                buildJsonObject {
                      put("kind", "message")
                      put("role", "assistant")
                      put("text", text)
                    }
                    .toString(),
            )
        )
  }
}
