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

@Serializable
internal data class PlannerCapabilities(
    val schemaVersion: Int,
    val protocol: Int,
    val capability: String,
)

@Serializable
data class PlannerRefinementRequest(
    val requestId: String,
    val variant: Int = 0,
    val expectedRevision: Long,
    val expectedCatalogRevision: Long,
    val baseProposalVersion: Int,
    val approvalDraft: ApprovalDraft,
    val changes: List<JsonObject>,
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
            combine(dao.observeLatest(session.userId), dao.observeGeneration(session.userId)) {
                row,
                _ ->
              row?.takeIf { sync.owner() == session.userId }
            }
      }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val all: Flow<List<PreparationEntity>> =
      sessions.session.flatMapLatest { session ->
        if (session == null) flowOf(emptyList())
        else
            combine(
                dao.observeAll(session.userId).onStart { emit(emptyList()) },
                dao.observeGeneration(session.userId),
            ) { rows, _ ->
              if (sync.owner() == session.userId) rows else emptyList()
            }
      }

  /** No HTTP here: a committed row is the only acknowledgement to the form. */
  suspend fun enqueue(intent: CalendarAiIntent): String {
    if (!intent.valid(clock.nowMillis())) throw BackendException(400, "ai_invalid_intent", "")
    val session = sessions.snapshot() ?: throw BackendException(401, "unauthorized", "")
    return db.withTransaction {
      guard(session)
      val owner = session.tokens.userId
      val encoded = ProposalWire.json.encodeToString(intent)
      val id = UUID.randomUUID().toString()
      val previous = dao.get(owner)
      dao.save(
          PreparationEntity(
              owner,
              id,
              encoded,
              replacesJson = "[]",
              protocolVersion = PLANNER_PROTOCOL,
              createdAtMillis =
                  maxOf(clock.nowMillis(), (previous?.createdAtMillis ?: Long.MIN_VALUE) + 1),
          )
      )
      id
    }
  }

  suspend fun retryCurrent(expectedId: String) {
    val session = sessions.snapshot() ?: throw BackendException(401, "unauthorized", "")
    db.withTransaction {
      guard(session)
      val row = dao.get(session.tokens.userId, expectedId) ?: return@withTransaction
      if (row.state in activeStates || row.state == "READY") return@withTransaction
      if (!row.state.startsWith("PAUSED_") && dao.replacementOf(row.owner, row.requestId) != null)
          return@withTransaction
      // A terminal refinement is bound to its captured proposal version and literal request bytes.
      // It cannot be turned into a new create request; the user must submit a new typed change.
      if (
          row.protocolVersion == PLANNER_PROTOCOL &&
              row.proposalId != null &&
              !row.state.startsWith("PAUSED_")
      )
          return@withTransaction
      if (row.state.startsWith("PAUSED_")) {
        dao.save(
            row.copy(
                state = if (row.state == "PAUSED_WAITING") "WAITING" else "QUEUED",
                errorCode = null,
            )
        )
      } else {
        val previous = dao.get(row.owner)
        dao.save(
            PreparationEntity(
                owner = row.owner,
                requestId = UUID.randomUUID().toString(),
                intentJson = row.intentJson,
                replacesJson = ProposalWire.json.encodeToString(listOf(row.requestId)),
                protocolVersion = row.protocolVersion,
                createdAtMillis =
                    maxOf(clock.nowMillis(), (previous?.createdAtMillis ?: Long.MIN_VALUE) + 1),
            )
        )
      }
      guard(session)
    }
  }

  /** Journals a closed typed refinement before the worker performs its V2 request. */
  suspend fun enqueueRefinement(
      proposal: TrainingProposal,
      draft: ApprovalDraft,
      changes: List<JsonObject>,
      desiredMinutes: Int,
      requestId: String = UUID.randomUUID().toString(),
      variant: Int = 0,
  ): String {
    require(proposal.source == ProposalSource.RULE_BASED)
    require(
        ProposalWire.uuid(requestId) &&
            variant >= 0 &&
            desiredMinutes in 10..240 &&
            ProposalWire.validDraft(draft)
    )
    require(changes.size in 1..100 && changes.all(::validRefinementChange))
    val session = sessions.snapshot() ?: throw BackendException(401, "unauthorized", "")
    val request =
        PlannerRefinementRequest(
            requestId,
            variant,
            proposal.snapshot.ownerRevision,
            proposal.snapshot.catalogRevision,
            proposal.currentVersion,
            draft,
            changes,
        )
    val intent =
        CalendarAiIntent(
            startsAtMillis = draft.startsAtMillis,
            timeZoneId = draft.timeZoneId,
            gymIds = draft.gymIds,
            includeNotes = false,
            availableDurationMinutes = desiredMinutes,
        )
    db.withTransaction {
      guard(session)
      val owner = session.tokens.userId
      val previous = dao.get(owner)
      dao.save(
          PreparationEntity(
              owner = owner,
              requestId = requestId,
              intentJson = ProposalWire.json.encodeToString(intent),
              replacesJson = "[]",
              requestJson = ProposalWire.json.encodeToString(request),
              protocolVersion = PLANNER_PROTOCOL,
              proposalId = proposal.proposalId,
              revision = proposal.snapshot.ownerRevision,
              catalogRevision = proposal.snapshot.catalogRevision,
              generation = dao.generation(owner),
              createdAtMillis =
                  maxOf(clock.nowMillis(), (previous?.createdAtMillis ?: Long.MIN_VALUE) + 1),
          )
      )
    }
    return requestId
  }

  private val processing = Mutex()

  suspend fun step(expectedId: String? = null, expectedOwner: String? = null): Boolean =
      processing.withLock { process(expectedId, expectedOwner) }

  private suspend fun process(expectedId: String?, expectedOwner: String?): Boolean {
    val session = sessions.snapshot() ?: return false
    if (expectedOwner != null && expectedOwner != session.tokens.userId) return false
    val row =
        (if (expectedId == null) dao.get(session.tokens.userId)
        else dao.get(session.tokens.userId, expectedId)) ?: return false
    if (row.state !in activeStates && row.state != "READY") return false
    try {
      guard(session)
      val intent = ProposalWire.json.decodeFromString<CalendarAiIntent>(row.intentJson)
      if (!intent.valid(clock.nowMillis())) {
        update(session, row) { it.copy(state = "EXPIRED", errorCode = "ai_invalid_intent") }
        return false
      }
      if (row.protocolVersion == PLANNER_PROTOCOL) return processPlanner(session, row, intent)
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
        val validationReady = reacknowledgeReady(session, ready)
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

  private suspend fun processPlanner(
      session: BackendSessionSnapshot,
      row: PreparationEntity,
      intent: CalendarAiIntent,
  ): Boolean {
    try {
      val capabilities = plannerCapabilities(session)
      if (
          capabilities.schemaVersion != PLANNER_PROTOCOL ||
              capabilities.protocol != PLANNER_PROTOCOL ||
              capabilities.capability != PLANNER_CAPABILITY
      )
          throw BackendException(426, "planner_update_required", "")

      var prepared = row
      if (prepared.requestJson == null) {
        val planner = calendar.preparePlanner(intent, row.requestId)
        guard(session)
        if (planner.ready.owner != row.owner || planner.ready.sessionEpoch != session.epoch)
            throw BackendException(401, "owner_changed", "")
        prepared =
            row.copy(
                requestJson = ProposalWire.json.encodeToString(planner.request),
                revision = planner.ready.revision,
                catalogRevision = planner.ready.catalogRevision,
                generation = planner.ready.cacheGeneration,
            )
        if (!update(session, row) { prepared }) return false
      }

      val post = prepared.state == "WAITING"
      val path =
          if (post) {
            prepared.proposalId?.let { "$PLANNER_ROOT/proposals/$it/refinements" }
                ?: "$PLANNER_ROOT/jobs"
          } else "$PLANNER_ROOT/jobs/${prepared.requestId}"
      val response =
          api.authorizedRawResponse(
              method = if (post) "POST" else "GET",
              path = path,
              rawBody = if (post) prepared.requestJson!!.encodeToByteArray() else ByteArray(0),
              headers = plannerHeaders,
              expectedOwner = row.owner,
              expectedSessionEpoch = session.epoch,
              retryOnUnauthorized = true,
              maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
          )
      guard(session)
      if (response.owner != row.owner || response.sessionEpoch != session.epoch)
          throw BackendException(401, "owner_changed", "")
      if (PLANNER_CAPABILITY !in response.acceptedCapabilities)
          throw BackendException(426, "planner_update_required", "")
      val result = ProposalWire.decode<PreparationResponse>(response.rawBody)
      if (result.requestId != row.requestId || result.state !in plannerServerStates)
          throw BackendException(502, "ai_invalid_response", "")
      var proposalJson = prepared.proposalJson
      if (result.state == "READY") {
        val ready =
            SyncReady.Ready(
                row.owner,
                requireNotNull(prepared.revision),
                requireNotNull(prepared.catalogRevision),
                session.epoch,
                requireNotNull(prepared.generation),
            )
        val payload = result.result ?: throw BackendException(502, "ai_invalid_response", "")
        if (
            payload.requestId != row.requestId ||
                payload.context.revision != ready.revision ||
                payload.context.catalogRevision != ready.catalogRevision ||
                payload.context.capturedAtMillis < 0
        )
            throw BackendException(502, "ai_invalid_response", "")
        val validationReady = reacknowledgeReady(session, ready)
        proposalJson =
            ProposalWire.json.encodeToString(calendar.validate(intent, validationReady, payload))
      }
      update(session, row) {
        it.copy(
            state = result.state,
            errorCode = safeCode(result.errorCode),
            proposalJson = proposalJson,
        )
      }
      return result.state in activeStates
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      val code = (error as? BackendException)?.code
      val updateRequired = error is BackendException && error.status in setOf(404, 405, 426)
      val retry =
          !updateRequired &&
              (error is java.io.IOException ||
                  (error is BackendException &&
                      code !in setOf("ai_invalid_response", "response_too_large") &&
                      (error.status == 401 ||
                          error.status >= 500 ||
                          error.status == 429 ||
                          code in setOf("ai_sync_failed", "workout_active"))))
      update(session, row) {
        it.copy(
            state =
                when {
                  retry -> it.state
                  updateRequired -> "UPDATE_REQUIRED"
                  code == "ai_context_stale" -> "STALE"
                  else -> "FAILED"
                },
            errorCode =
                safeCode(if (updateRequired) "planner_update_required" else code)
                    ?: "ai_unknown_error",
        )
      }
      return retry
    }
  }

  private suspend fun plannerCapabilities(session: BackendSessionSnapshot): PlannerCapabilities {
    try {
      val response =
          api.authorizedRawResponse(
              method = "GET",
              path = "$PLANNER_ROOT/capabilities",
              rawBody = ByteArray(0),
              headers = plannerHeaders,
              expectedOwner = session.tokens.userId,
              expectedSessionEpoch = session.epoch,
              retryOnUnauthorized = true,
              maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
          )
      guard(session)
      if (response.owner != session.tokens.userId || response.sessionEpoch != session.epoch)
          throw BackendException(401, "owner_changed", "")
      if (PLANNER_CAPABILITY !in response.acceptedCapabilities)
          throw BackendException(426, "planner_update_required", "")
      return ProposalWire.decode(response.rawBody)
    } catch (error: BackendException) {
      if (error.status in setOf(404, 405, 426))
          throw BackendException(426, "planner_update_required", "")
      throw error
    }
  }

  /** Reuses legacy READY validation semantics after the local cache generation changes. */
  private suspend fun reacknowledgeReady(
      session: BackendSessionSnapshot,
      ready: SyncReady.Ready,
  ): SyncReady.Ready {
    if (readySource.isCurrent(ready)) return ready
    val refreshed =
        when (val value = readySource.await()) {
          is SyncReady.Ready -> value
          SyncReady.Blocked -> throw BackendException(409, "ai_sync_failed", "")
          is SyncReady.Failure -> throw (value.cause ?: BackendException(409, "ai_sync_failed", ""))
        }
    guard(session)
    if (refreshed.owner != ready.owner || refreshed.sessionEpoch != session.epoch)
        throw BackendException(401, "owner_changed", "")
    if (refreshed.revision != ready.revision || refreshed.catalogRevision != ready.catalogRevision)
        throw BackendException(409, "ai_context_stale", "")
    return refreshed
  }

  suspend fun pausePending(expectedId: String, expectedOwner: String? = null) {
    val session = sessions.snapshot() ?: return
    if (expectedOwner != null && expectedOwner != session.tokens.userId) return
    val row = dao.get(session.tokens.userId, expectedId) ?: return
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
        val latest = dao.get(expected.owner, expected.requestId) ?: return@withTransaction false
        dao.save(change(latest))
        true
      }

  companion object {
    val activeStates = setOf("WAITING", "QUEUED", "RUNNING")
    private val serverStates =
        setOf("QUEUED", "RUNNING", "READY", "FAILED", "SUPERSEDED", "STALE", "EXPIRED")
    private val plannerServerStates = serverStates + "IMPOSSIBLE"
    private const val PLANNER_PROTOCOL = 2
    private const val PLANNER_CAPABILITY = "deterministic-workout-planner-v2"
    private const val PLANNER_ROOT = "/planning/v2"
    private val plannerHeaders = mapOf("X-Planner-Protocol" to PLANNER_PROTOCOL.toString())

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
                  "planner_update_required",
                  "NO_FEASIBLE_PLAN",
                  "PLANNER_LIMIT_REACHED",
                  "PLANNER_TIMEOUT",
              )
        }

    private fun validRefinementChange(change: JsonObject): Boolean =
        when (change["kind"]?.jsonPrimitive?.contentOrNull) {
          "REPLACE" ->
              change.keys == setOf("kind", "slotId", "selectionId", "exerciseId") &&
                  listOf("slotId", "selectionId").all {
                    change[it]?.jsonPrimitive?.contentOrNull?.let { value ->
                      value.isNotBlank() && value.length <= 255
                    } == true
                  } &&
                  ProposalWire.uuid(change["exerciseId"]?.jsonPrimitive?.contentOrNull.orEmpty())
          "EXCLUDE" ->
              change.keys == setOf("kind", "exerciseId") &&
                  ProposalWire.uuid(change["exerciseId"]?.jsonPrimitive?.contentOrNull.orEmpty())
          else -> false
        }
  }
}
