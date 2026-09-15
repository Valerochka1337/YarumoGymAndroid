package com.valerochka1337.valerochkagym.data.trainingproposal

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.service.WallClock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

data class ProposalEditor(
    val session: BackendSessionSnapshot,
    val proposal: TrainingProposal,
    val draft: ApprovalDraft,
    val applied: Boolean,
)

@Singleton
class TrainingProposalRepository
@Inject
constructor(
    private val database: GymDatabase,
    private val api: TrainingProposalApi,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val clock: WallClock,
) {
  private val dao
    get() = database.trainingProposalDao()

  private val actions = Mutex()

  fun session(): BackendSessionSnapshot =
      sessions.snapshot() ?: throw BackendException(401, "unauthorized", "Войдите в аккаунт")

  fun isCurrent(expected: BackendSessionSnapshot): Boolean =
      sessions.snapshot()?.let {
        it.tokens.userId == expected.tokens.userId && it.epoch == expected.epoch
      } == true

  private fun guard(expected: BackendSessionSnapshot) {
    if (!isCurrent(expected) || sync.owner() != expected.tokens.userId)
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  suspend fun list(cursor: String? = null): Pair<BackendSessionSnapshot, ProposalListResponse> =
      withContext(Dispatchers.IO) {
        val session = session()
        guard(session)
        val result = api.list(session, cursor)
        guard(session)
        session to result
      }

  suspend fun open(id: String): ProposalEditor =
      withContext(Dispatchers.IO) {
        val session = session()
        guard(session)
        val proposal =
            try {
              api.detail(session, id)
            } catch (error: java.io.IOException) {
              guard(session)
              val cached =
                  database.preparationDao().get(session.tokens.userId)?.proposalJson?.let {
                    ProposalWire.json.decodeFromString<TrainingProposal>(it)
                  }
              cached?.takeIf { it.proposalId == id } ?: throw error
            }
        database.withTransaction {
          guard(session)
          val owner = session.tokens.userId
          val stored = dao.draft(owner, id, proposal.currentVersion)
          val draft =
              stored?.let { ProposalWire.decode<ApprovalDraft>(it.draftJson.encodeToByteArray()) }
                  ?: proposal.snapshot.draft
          dao.saveDraft(
              TrainingProposalDraftEntity(
                  owner,
                  id,
                  proposal.currentVersion,
                  ProposalWire.json.encodeToString(proposal),
                  ProposalWire.json.encodeToString(draft),
              )
          )
          ProposalEditor(
              session,
              proposal,
              draft,
              dao.projection(owner, id, proposal.currentVersion) != null,
          )
        }
      }

  suspend fun explanation(editor: ProposalEditor): PlannerExplanation =
      withContext(Dispatchers.IO) {
        guard(editor.session)
        val result = api.explanation(editor.session, editor.proposal)
        guard(editor.session)
        result
      }

  suspend fun save(editor: ProposalEditor, draft: ApprovalDraft): ProposalEditor =
      withContext(Dispatchers.IO) {
        actions.withLock {
          database.withTransaction {
            guard(editor.session)
            val owner = editor.session.tokens.userId
            val proposal = editor.proposal
            if (dao.operation(owner, proposal.proposalId, proposal.currentVersion) != null)
                throw BackendException(
                    409,
                    "approval_pending",
                    "Подтверждение уже отправлено. Сначала проверьте его результат",
                )
            require(
                proposal.status == ProposalStatus.PENDING && proposal.expiresAt > clock.nowMillis()
            )
            // An editor draft can be temporarily incomplete (for example after a type change).
            // Only approval canonicalizes and validates a request for the server.
            val draftJson = ProposalWire.json.encodeToString(draft)
            require(draftJson.toByteArray().size <= ProposalWire.REQUEST_LIMIT)
            dao.saveDraft(
                TrainingProposalDraftEntity(
                    owner,
                    proposal.proposalId,
                    proposal.currentVersion,
                    ProposalWire.json.encodeToString(proposal),
                    draftJson,
                )
            )
            editor.copy(draft = draft)
          }
        }
      }

  suspend fun approve(editor: ProposalEditor): AcceptedProposalResult =
      withContext(Dispatchers.IO) {
        actions.withLock {
          val session = editor.session
          val proposal = editor.proposal
          val owner = session.tokens.userId
          val operation =
              sync.mutex.withLock {
                database.withTransaction {
                  guard(session)
                  if (database.healthDao().hasActiveWorkout())
                      throw BackendException(
                          409,
                          "active_workout",
                          "Завершите тренировку перед применением",
                      )
                  dao.operation(owner, proposal.proposalId, proposal.currentVersion)
                      ?: run {
                        val preparation = database.preparationDao().get(owner)
                        val preparedProposal =
                            preparation?.proposalJson?.let {
                              ProposalWire.json.decodeFromString<TrainingProposal>(it)
                            }
                        if (
                            preparedProposal?.proposalId == proposal.proposalId &&
                                (preparation.state != "READY" ||
                                    preparation.generation !=
                                        database.preparationDao().generation(owner))
                        )
                            throw BackendException(
                                409,
                                "proposal_stale",
                                "Предложение нужно обновить",
                            )
                        if (
                            proposal.status !in
                                setOf(ProposalStatus.PENDING, ProposalStatus.APPROVED) ||
                                (proposal.status == ProposalStatus.PENDING &&
                                    (proposal.expiresAt <= clock.nowMillis() ||
                                        editor.draft.startsAtMillis <= clock.nowMillis()))
                        )
                            throw BackendException(
                                409,
                                "proposal_stale",
                                "Предложение нужно обновить",
                            )
                        val id = UUID.randomUUID().toString()
                        val bytes =
                            ProposalWire.canonical(
                                ApprovalRequest(id, proposal.currentVersion, editor.draft)
                            )
                        TrainingProposalOperationEntity(
                                owner,
                                proposal.proposalId,
                                proposal.currentVersion,
                                id,
                                bytes,
                                sha256(bytes),
                                null,
                            )
                            .also { dao.insertOperation(it) }
                      }
                }
              }
          check(sha256(operation.requestBytes) == operation.requestSha256)
          val storedDraft = ProposalWire.decode<ApprovalRequest>(operation.requestBytes).draft
          val requestedDraft =
              ProposalWire.decode<ApprovalRequest>(
                      ProposalWire.canonical(
                          ApprovalRequest(operation.operationId, operation.version, editor.draft)
                      )
                  )
                  .draft
          if (storedDraft != requestedDraft)
              throw BackendException(
                  409,
                  "proposal_operation_conflict",
                  "Сначала проверьте ранее отправленное подтверждение",
              )
          guard(session)
          if (sync.hasActiveWorkout())
              throw BackendException(
                  409,
                  "active_workout",
                  "Завершите тренировку перед применением",
              )
          val result =
              operation.acceptedResultJson?.let {
                ProposalWire.decode<AcceptedProposalResult>(it.encodeToByteArray())
              }
                  ?: try {
                    api.acceptedResult(session, proposal.proposalId)
                  } catch (error: BackendException) {
                    if (
                        proposal.status == ProposalStatus.PENDING &&
                            error.status == 409 &&
                            error.code == "proposal_not_approved"
                    )
                        try {
                          api.approve(session, proposal.proposalId, operation.requestBytes)
                        } catch (rejection: BackendException) {
                          if (rejection.status == 400 && rejection.code == "invalid_request") {
                            database.withTransaction {
                              guard(session)
                              dao.rejectOperation(operation.operationId)
                            }
                          }
                          throw rejection
                        }
                    else throw error
                  }
          require(
              ProposalWire.valid(result) &&
                  result.proposalId == operation.proposalId &&
                  result.version == operation.version
          )
          database.withTransaction {
            guard(session)
            dao.accept(
                owner,
                proposal.proposalId,
                proposal.currentVersion,
                ProposalWire.json.encodeToString(result),
            )
          }
          sync.applyApprovedProposal(session, result) { revision ->
            guard(session)
            dao.project(
                TrainingProposalProjectionEntity(
                    owner,
                    proposal.proposalId,
                    proposal.currentVersion,
                    result.routineId,
                    result.calendarPlanId,
                    revision,
                )
            )
          }
          guard(session)
          result
        }
      }

  suspend fun reject(editor: ProposalEditor) =
      withContext(Dispatchers.IO) {
        actions.withLock {
          guard(editor.session)
          if (
              dao.operation(
                  editor.session.tokens.userId,
                  editor.proposal.proposalId,
                  editor.proposal.currentVersion,
              ) != null
          )
              throw BackendException(
                  409,
                  "approval_pending",
                  "Сначала проверьте результат подтверждения",
              )
          api.reject(editor.session, editor.proposal.proposalId, editor.proposal.currentVersion)
        }
      }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
