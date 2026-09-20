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
      "concern_resolved" -> {
        val keys =
            event["resolvedConcernKeys"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonPrimitive.content }
                .toSet()
        for (row in dao.concerns(owner, workout)) {
          val payload = Json.parseToJsonElement(row.payload).jsonObject
          val decision = payload["decision"]!!.jsonObject
          val state = decision["state"]!!.jsonObject
          val remaining =
              state["openConcerns"]!!.jsonArray.filter { it.jsonPrimitive.content !in keys }
          val updated =
              JsonObject(
                  payload +
                      ("decision" to
                          JsonObject(
                              decision +
                                  ("state" to
                                      JsonObject(state + ("openConcerns" to JsonArray(remaining))))
                          ))
              )
          dao.saveBehavior(
              row.copy(
                  payload = updated.toString(),
                  status = if (remaining.isEmpty()) "RESOLVED" else row.status,
              )
          )
        }
      }
      "intervention",
      "intervention_answer" -> {
        val result = event["result"] as? JsonObject ?: return false
        result.text("questionId")?.let { id ->
          dao.behavior(id)
              ?.takeIf { it.accountId == owner && it.workoutId == workout }
              ?.let {
                if (it.status == "PENDING" && result.text("status") in setOf("STALE", "EXPIRED"))
                    return@let
                dao.saveBehavior(
                    it.copy(status = result.text("status") ?: "ANSWERED", requestJson = null)
                )
              }
        }
        result.text("answerId")?.let { answerId ->
          result.text("userText")?.let { message("answer:$answerId", owner, workout, it, "user") }
          result
              .text("text")
              ?.takeIf { it.isNotBlank() }
              ?.let { message("answer-reply:$answerId", owner, workout, it) }
        }
        val question = result["question"] as? JsonObject
        if (question != null) {
          val id = requireNotNull(question.text("questionId"))
          require(UUID.fromString(id).toString() == id)
          if (dao.behavior(id) == null) {
            val snapshot = reader.snapshot(owner, workout, epoch)
            val relevant =
                snapshot?.exercises?.any { e ->
                  e.sets.any { it.syncId == question.text("setId") }
                } == true
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
      if (row.status !in setOf("OPEN", "STALE", "EXPIRED")) return@withTransaction false
      val q = Json.parseToJsonElement(row.payload).jsonObject["question"]!!.jsonObject
      if (q["options"]!!.jsonArray.none { it.jsonObject.text("id") == option })
          return@withTransaction false
      val request = buildJsonObject {
        put("answerId", UUID.randomUUID().toString())
        put("expectedVersion", requireNotNull(q["version"]))
        put("answer", option)
      }
      dao.saveBehavior(row.copy(status = "PENDING", requestJson = request.toString()))
      val label =
          q["options"]!!
              .jsonArray
              .first { it.jsonObject.text("id") == option }
              .jsonObject
              .text("text")!!
      message("answer:${request.text("answerId")}", owner, workout, label, "user")
      check(live(owner, session.epoch))
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

  private suspend fun message(
      id: String,
      owner: String,
      workout: String,
      text: String,
      role: String = "assistant",
  ) {
    val stableId =
        runCatching { UUID.fromString(id).toString() }
            .getOrElse { UUID.nameUUIDFromBytes(id.toByteArray()).toString() }
    if (db.coachDao().messages(workout).any { it.id == stableId }) return
    db.coachDao()
        .saveMessage(
            CoachMessageEntity(
                stableId,
                owner,
                workout,
                role,
                text,
                System.currentTimeMillis(),
                "DELIVERED",
            )
        )
    db.coachDao()
        .saveJournal(
            CoachJournalEntity(
                stableId,
                owner,
                workout,
                System.currentTimeMillis(),
                buildJsonObject {
                      put("kind", "message")
                      put("role", role)
                      put("text", text)
                    }
                    .toString(),
            )
        )
  }
}
