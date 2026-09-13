package com.valerochka1337.valerochkagym.data.ai

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.service.WallClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class PreparationResponse(
    val requestId: String,
    val state: String,
    val errorCode: String? = null,
    val result: CalendarResponse? = null,
)

@Singleton
class WorkoutPreparationRepository
@Inject
constructor(
    private val db: GymDatabase,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val calendar: CalendarAiRepository,
    private val readySource: SyncReadySource,
    private val api: BackendTransport,
    private val clock: WallClock,
) {
  private val dao
    get() = db.preparationDao()

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val current: Flow<PreparationEntity?> =
      sessions.session.flatMapLatest { session ->
        if (session == null) flowOf(null)
        else
            combine(dao.observe(session.userId), dao.observeGeneration(session.userId)) { row, _ ->
              row?.takeIf { sync.owner() == session.userId }
            }
      }

  /** No HTTP here: a committed row is the only acknowledgement to the form. */
  suspend fun enqueue(intent: CalendarAiIntent): String {
    if (!intent.valid(clock.nowMillis())) throw BackendException(400, "ai_invalid_intent", "")
    val session = sessions.snapshot() ?: throw BackendException(401, "unauthorized", "")
    return db.withTransaction {
      guard(session)
      val owner = session.tokens.userId
      val old = dao.get(owner)
      val encoded = ProposalWire.json.encodeToString(intent)
      if (old?.intentJson == encoded && old.state.startsWith("PAUSED_")) {
        dao.save(
            old.copy(
                state = if (old.state == "PAUSED_WAITING") "WAITING" else "QUEUED",
                errorCode = null,
            )
        )
        return@withTransaction old.requestId
      }
      if (
          old?.intentJson == encoded &&
              old.state in activeStates &&
              (old.generation == null || old.generation == dao.generation(owner))
      )
          return@withTransaction old.requestId
      val ancestors =
          old?.let {
                (if (it.state in setOf("QUEUED", "RUNNING", "READY", "PAUSED_STATUS")) emptyList()
                else ProposalWire.json.decodeFromString<List<String>>(it.replacesJson)) +
                    it.requestId
              }
              .orEmpty()
              .distinct()
      if (ancestors.size > 1000) throw BackendException(409, "ai_sync_failed", "")
      val id = UUID.randomUUID().toString()
      dao.save(
          PreparationEntity(
              owner,
              id,
              encoded,
              ProposalWire.json.encodeToString(ancestors),
              proposalJson = old?.proposalJson,
          )
      )
      id
    }
  }

  suspend fun retryCurrent(expectedId: String) {
    val session = sessions.snapshot() ?: throw BackendException(401, "unauthorized", "")
    db.withTransaction {
      guard(session)
      val row = dao.get(session.tokens.userId) ?: return@withTransaction
      if (row.requestId != expectedId || row.state in activeStates || row.state == "READY")
          return@withTransaction
      enqueue(ProposalWire.json.decodeFromString<CalendarAiIntent>(row.intentJson))
      guard(session)
    }
  }

  private val processing = Mutex()

  suspend fun step(expectedId: String? = null): Boolean =
      processing.withLock { process(expectedId) }

  private suspend fun process(expectedId: String?): Boolean {
    val session = sessions.snapshot() ?: return false
    val row = dao.get(session.tokens.userId) ?: return false
    if (expectedId != null && row.requestId != expectedId) return false
    if (row.state !in activeStates && row.state != "READY") return false
    try {
      guard(session)
      val intent = ProposalWire.json.decodeFromString<CalendarAiIntent>(row.intentJson)
      if (!intent.valid(clock.nowMillis())) {
        update(session, row) { it.copy(state = "EXPIRED", errorCode = "ai_invalid_intent") }
        return false
      }
      var prepared = row
      if (prepared.requestJson == null) {
        val (ready, request) = calendar.prepare(intent, row.requestId)
        guard(session)
        if (ready.owner != row.owner || ready.sessionEpoch != session.epoch)
            throw BackendException(409, "owner_changed", "")
        val json =
            JsonObject(
                    ProposalWire.json.encodeToJsonElement(request).jsonObject +
                        ("replacesRequestIds" to
                            ProposalWire.json.parseToJsonElement(row.replacesJson))
                )
                .toString()
        prepared =
            row.copy(
                requestJson = json,
                revision = ready.revision,
                catalogRevision = ready.catalogRevision,
                generation = ready.cacheGeneration,
            )
        if (!update(session, row) { prepared }) return false
      }
      // Journaled bytes are replayed unchanged after a lost acknowledgement.
      val post = prepared.state == "WAITING"
      val response =
          api.authorizedRawResponse(
              if (post) "POST" else "GET",
              if (post) "/ai/calendar-draft-jobs" else "/ai/calendar-draft-jobs/${row.requestId}",
              if (post) prepared.requestJson!!.encodeToByteArray() else ByteArray(0),
              expectedOwner = row.owner,
              expectedSessionEpoch = session.epoch,
              retryOnUnauthorized = true,
              maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
          )
      guard(session)
      if (response.owner != row.owner || response.sessionEpoch != session.epoch)
          throw BackendException(409, "owner_changed", "")
      val result = ProposalWire.decode<PreparationResponse>(response.rawBody)
      if (result.requestId != row.requestId || result.state !in serverStates)
          throw BackendException(502, "ai_invalid_response", "")
      var proposalJson = prepared.proposalJson
      if (result.state == "READY") {
        val ready =
            SyncReady.Ready(
                row.owner,
                prepared.revision!!,
                prepared.catalogRevision!!,
                session.epoch,
                prepared.generation!!,
            )
        val payload = result.result ?: throw BackendException(502, "ai_invalid_response", "")
        if (
            payload.requestId != row.requestId ||
                payload.context.revision != ready.revision ||
                payload.context.catalogRevision != ready.catalogRevision ||
                payload.context.capturedAtMillis < 0
        )
            throw BackendException(502, "ai_invalid_response", "")
        // Cache generation can change during a background sync without changing the server
        // context. Re-acknowledge it instead of treating a local write counter as a revision.
        val validationReady =
            if (readySource.isCurrent(ready)) ready
            else {
              val refreshed =
                  when (val value = readySource.await()) {
                    is SyncReady.Ready -> value
                    SyncReady.Blocked -> throw BackendException(409, "ai_sync_failed", "")
                    is SyncReady.Failure ->
                        throw (value.cause ?: BackendException(409, "ai_sync_failed", ""))
                  }
              guard(session)
              if (refreshed.owner != ready.owner || refreshed.sessionEpoch != session.epoch)
                  throw BackendException(401, "owner_changed", "")
              if (
                  refreshed.revision != ready.revision ||
                      refreshed.catalogRevision != ready.catalogRevision
              )
                  throw BackendException(409, "ai_context_stale", "")
              refreshed
            }
        val proposal = calendar.validate(intent, validationReady, payload)
        proposalJson = ProposalWire.json.encodeToString(proposal)
      }
      update(session, row) {
        it.copy(
            state = result.state,
            errorCode = safeCode(result.errorCode),
            proposalJson = proposalJson,
        )
      }
      return result.state in activeStates
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      val code = (error as? BackendException)?.code
      val retry =
          error is java.io.IOException ||
              (error is BackendException &&
                  code !in setOf("ai_invalid_response", "response_too_large") &&
                  (error.status == 401 ||
                      error.status >= 500 ||
                      error.status == 429 ||
                      code in setOf("ai_sync_failed", "workout_active")))
      update(session, row) {
        it.copy(
            state = if (retry) it.state else if (code == "ai_context_stale") "STALE" else "FAILED",
            errorCode =
                safeCode(code)
                    ?: if (error is java.io.IOException) "network_error" else "ai_unknown_error",
        )
      }
      return retry
    }
  }

  suspend fun pausePending(expectedId: String) {
    val session = sessions.snapshot() ?: return
    val row = dao.get(session.tokens.userId) ?: return
    if (row.requestId != expectedId) return
    update(session, row) { latest ->
      if (latest.state in activeStates)
          latest.copy(
              state = if (latest.state == "WAITING") "PAUSED_WAITING" else "PAUSED_STATUS",
              errorCode = "network_error",
          )
      else latest
    }
  }

  private fun guard(session: BackendSessionSnapshot) {
    if (
        sessions.snapshot()?.let {
          it.tokens.userId == session.tokens.userId && it.epoch == session.epoch
        } != true || sync.owner() != session.tokens.userId
    )
        throw BackendException(401, "owner_changed", "")
  }

  private suspend fun update(
      session: BackendSessionSnapshot,
      expected: PreparationEntity,
      change: (PreparationEntity) -> PreparationEntity,
  ): Boolean =
      db.withTransaction {
        if (
            sessions.snapshot()?.let {
              it.tokens.userId == session.tokens.userId && it.epoch == session.epoch
            } != true || sync.owner() != session.tokens.userId
        )
            return@withTransaction false
        val latest = dao.get(expected.owner) ?: return@withTransaction false
        if (latest.requestId != expected.requestId) return@withTransaction false
        dao.save(change(latest))
        true
      }

  companion object {
    val activeStates = setOf("WAITING", "QUEUED", "RUNNING")
    private val serverStates =
        setOf("QUEUED", "RUNNING", "READY", "FAILED", "SUPERSEDED", "STALE", "EXPIRED")

    private fun safeCode(code: String?): String? =
        code?.takeIf {
          it in
              setOf(
                  "ai_context_stale",
                  "ai_sync_failed",
                  "workout_active",
                  "unauthorized",
                  "owner_changed",
                  "ai_unavailable",
                  "ai_busy",
                  "ai_timeout",
                  "ai_invalid_response",
                  "ai_no_candidates",
                  "ai_gym_unavailable",
                  "ai_invalid_intent",
                  "ai_context_too_large",
                  "ai_interrupted",
                  "ai_request_conflict",
              )
        }
  }
}
