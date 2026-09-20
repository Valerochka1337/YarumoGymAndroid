package com.valerochka1337.valerochkagym.data.trainingproposal

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.di.ApplicationScope
import com.valerochka1337.valerochkagym.service.WallClock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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

/** Process-local inbox state. Empty [Content.items] means the first list request completed. */
sealed interface ProposalInboxState {
  data class NotLoaded(
      val bindingGeneration: Long,
      val content: ProposalInboxContent? = null,
  ) : ProposalInboxState

  data class Loading(val content: ProposalInboxContent?) : ProposalInboxState

  data class Content(val content: ProposalInboxContent) : ProposalInboxState

  data class Error(
      val content: ProposalInboxContent?,
      val cause: Exception,
      val retryCursor: String? = null,
  ) : ProposalInboxState
}

data class ProposalInboxContent(
    val items: List<TrainingProposal>,
    val nextCursor: String?,
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
    @param:ApplicationScope private val inboxScope: CoroutineScope,
) {
  private val dao
    get() = database.trainingProposalDao()

  private val actions = Mutex()
  private val inboxMutex = Mutex()
  private val _inbox = MutableStateFlow<ProposalInboxState>(ProposalInboxState.NotLoaded(0))
  val inbox: StateFlow<ProposalInboxState> = _inbox.asStateFlow()

  private data class InboxKey(val owner: String, val epoch: Long)

  private var inboxKey: InboxKey? = null
  private var inboxBindingGeneration = 0L
  private var preparationObservation: Job? = null
  private val overlays = mutableMapOf<String, TrainingProposal>()

  init {
    inboxScope.launch {
      combine(sessions.sessionEpochs, sync.transfer) { _, _ -> Unit }
          .collect {
            inboxMutex.withLock {
              val current = sessions.snapshot()
              if (current == null || sync.owner() != current.tokens.userId) clearInboxLocked()
              else bindInboxLocked(current)
            }
          }
    }
  }

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

  /** Starts the sole initial GET for this owner/session epoch. Reopening only observes [inbox]. */
  fun ensureInitialLoad() {
    inboxScope.launch { startInitialLoad(retry = false) }
  }

  /** A visible inbox error is retried explicitly; it never discards already shown proposals. */
  fun retryInitialLoad() {
    inboxScope.launch { startInitialLoad(retry = true) }
  }

  fun loadMore() {
    inboxScope.launch { loadMoreNow() }
  }

  private suspend fun startInitialLoad(retry: Boolean) {
    val session =
        try {
          session()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          inboxMutex.withLock {
            if (sessions.snapshot() == null)
                _inbox.value = ProposalInboxState.Error(_inbox.value.contentOrNull(), error)
          }
          return
        }
    val key = session.inboxKey()
    var retryCursor: String? = null
    val shouldLoad =
        inboxMutex.withLock {
          if (!isSessionKeyCurrent(key)) return@withLock false
          bindInboxLocked(session)
          when {
            retry && _inbox.value is ProposalInboxState.Error -> {
              retryCursor = (_inbox.value as ProposalInboxState.Error).retryCursor
              _inbox.value = ProposalInboxState.Loading(_inbox.value.contentOrNull())
              true
            }
            !retry && _inbox.value is ProposalInboxState.NotLoaded -> {
              _inbox.value = ProposalInboxState.Loading(_inbox.value.contentOrNull())
              true
            }
            else -> false
          }
        }
    if (!shouldLoad) return
    try {
      guard(session)
      val result = withContext(Dispatchers.IO) { api.list(session, retryCursor) }
      guard(session)
      publishNetwork(key, result, more = retryCursor != null)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      publishError(key, error, retryCursor)
    }
  }

  private suspend fun loadMoreNow() {
    val session =
        try {
          session()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          inboxMutex.withLock {
            if (sessions.snapshot() == null)
                _inbox.value = ProposalInboxState.Error(_inbox.value.contentOrNull(), error)
          }
          return
        }
    val key = session.inboxKey()
    val content =
        inboxMutex.withLock {
          if (!isSessionKeyCurrent(key)) return@withLock null
          bindInboxLocked(session)
          val current = _inbox.value.contentOrNull() ?: return@withLock null
          if (_inbox.value is ProposalInboxState.Loading || current.nextCursor == null)
              return@withLock null
          _inbox.value = ProposalInboxState.Loading(current)
          current
        } ?: return
    try {
      guard(session)
      val result = withContext(Dispatchers.IO) { api.list(session, content.nextCursor) }
      guard(session)
      publishNetwork(key, result, more = true)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      publishError(key, error, content.nextCursor)
    }
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
        val editor =
            database.withTransaction {
              guard(session)
              val owner = session.tokens.userId
              val stored = dao.draft(owner, id, proposal.currentVersion)
              val draft =
                  stored?.let {
                    ProposalWire.decode<ApprovalDraft>(it.draftJson.encodeToByteArray())
                  } ?: proposal.snapshot.draft
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
        upsertInbox(session, proposal)
        editor
      }

  suspend fun explanation(editor: ProposalEditor): PlannerExplanation =
      withContext(Dispatchers.IO) {
        guard(editor.session)
        val result = api.explanation(editor.session, editor.proposal)
        guard(editor.session)
        result
      }

  suspend fun refine(editor: ProposalEditor, text: String, requestId: String): ProposalEditor =
      withContext(Dispatchers.IO) {
        actions.withLock {
          guard(editor.session)
          val proposal = editor.proposal
          require(
              proposal.status == ProposalStatus.PENDING && proposal.expiresAt > clock.nowMillis()
          )
          val next = api.refine(editor.session, proposal, text, requestId)
          guard(editor.session)
          database.withTransaction {
            dao.saveDraft(
                TrainingProposalDraftEntity(
                    editor.session.tokens.userId,
                    next.proposalId,
                    next.currentVersion,
                    ProposalWire.json.encodeToString(next),
                    ProposalWire.json.encodeToString(next.snapshot.draft),
                )
            )
          }
          editor.copy(proposal = next, draft = next.snapshot.draft, applied = false).also {
            upsertInbox(editor.session, next)
          }
        }
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
          upsertInbox(session, proposal.copy(status = ProposalStatus.APPROVED))
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
          val decision =
              api.reject(editor.session, editor.proposal.proposalId, editor.proposal.currentVersion)
          guard(editor.session)
          upsertInbox(
              editor.session,
              editor.proposal.copy(status = decision.status, updatedAt = decision.updatedAt),
          )
          decision
        }
      }

  private suspend fun publishNetwork(
      key: InboxKey,
      result: ProposalListResponse,
      more: Boolean,
  ) {
    inboxMutex.withLock {
      if (!isCurrentKey(key) || inboxKey != key) return
      val previous = _inbox.value.contentOrNull()
      val current =
          if (more) previous ?: ProposalInboxContent(emptyList(), null)
          else previous?.terminalItems() ?: ProposalInboxContent(emptyList(), null)
      val merged = mergeItems(current.items, result.items, overlays.values)
      _inbox.value = ProposalInboxState.Content(ProposalInboxContent(merged, result.nextCursor))
    }
  }

  private suspend fun publishError(key: InboxKey, error: Exception, retryCursor: String? = null) {
    inboxMutex.withLock {
      if (!isSessionKeyCurrent(key) || inboxKey != key) return
      _inbox.value = ProposalInboxState.Error(_inbox.value.contentOrNull(), error, retryCursor)
    }
  }

  private suspend fun upsertInbox(session: BackendSessionSnapshot, proposal: TrainingProposal) {
    val key = session.inboxKey()
    inboxMutex.withLock {
      if (!isSessionKeyCurrent(key)) return
      bindInboxLocked(session)
      if (!isCurrentKey(key) || inboxKey != key) return
      val existing = overlays[proposal.proposalId]
      overlays[proposal.proposalId] = if (existing == null) proposal else newer(existing, proposal)
      val content = _inbox.value.contentOrNull() ?: return
      val updated = content.copy(items = mergeItems(content.items, emptyList(), overlays.values))
      _inbox.value = _inbox.value.withContent(updated)
    }
  }

  private fun bindInboxLocked(session: BackendSessionSnapshot) {
    val key = session.inboxKey()
    if (inboxKey == key) return
    inboxKey = key
    overlays.clear()
    _inbox.value = ProposalInboxState.NotLoaded(++inboxBindingGeneration)
    preparationObservation?.cancel()
    preparationObservation =
        inboxScope.launch {
          database.preparationDao().observe(key.owner).collect { preparation ->
            val proposal =
                preparation
                    ?.takeIf { it.state == "READY" }
                    ?.proposalJson
                    ?.let { json ->
                      runCatching { ProposalWire.json.decodeFromString<TrainingProposal>(json) }
                          .getOrNull()
                    }
                    ?.takeIf { ProposalWire.valid(it) && it.recipientId == key.owner }
            if (proposal != null) publishReady(key, proposal)
          }
        }
  }

  private fun clearInboxLocked() {
    inboxKey = null
    overlays.clear()
    preparationObservation?.cancel()
    preparationObservation = null
    _inbox.value = ProposalInboxState.NotLoaded(++inboxBindingGeneration)
  }

  private suspend fun publishReady(key: InboxKey, proposal: TrainingProposal) {
    inboxMutex.withLock {
      if (!isCurrentKey(key) || inboxKey != key) return
      val existing = overlays[proposal.proposalId]
      overlays[proposal.proposalId] = if (existing == null) proposal else newer(existing, proposal)
      val content = _inbox.value.contentOrNull() ?: ProposalInboxContent(emptyList(), null)
      _inbox.value =
          _inbox.value.withContent(
              content.copy(items = mergeItems(content.items, emptyList(), overlays.values))
          )
    }
  }

  private fun mergeItems(
      current: List<TrainingProposal>,
      incoming: List<TrainingProposal>,
      localOverlays: Collection<TrainingProposal>,
  ): List<TrainingProposal> {
    val merged = LinkedHashMap<String, TrainingProposal>()
    current.forEach { merged[it.proposalId] = it }
    incoming.forEach { incomingItem ->
      merged[incomingItem.proposalId] =
          merged[incomingItem.proposalId]?.let { newer(it, incomingItem) } ?: incomingItem
    }
    localOverlays.forEach { overlay ->
      merged[overlay.proposalId] = merged[overlay.proposalId]?.let { newer(it, overlay) } ?: overlay
    }
    return merged.values.toList()
  }

  private fun newer(current: TrainingProposal, incoming: TrainingProposal): TrainingProposal =
      when {
        current.currentVersion > incoming.currentVersion -> current
        current.currentVersion < incoming.currentVersion -> incoming
        current.status.isTerminal() && incoming.status == ProposalStatus.PENDING -> current
        else -> incoming
      }

  private fun ProposalStatus.isTerminal(): Boolean = this != ProposalStatus.PENDING

  private fun BackendSessionSnapshot.inboxKey() = InboxKey(tokens.userId, epoch)

  private fun isCurrentKey(key: InboxKey): Boolean =
      isSessionKeyCurrent(key) && sync.owner() == key.owner

  private fun isSessionKeyCurrent(key: InboxKey): Boolean =
      sessions.snapshot()?.let { it.inboxKey() == key } == true

  private fun ProposalInboxState.contentOrNull(): ProposalInboxContent? =
      when (this) {
        is ProposalInboxState.Content -> content
        is ProposalInboxState.Loading -> content
        is ProposalInboxState.Error -> content
        is ProposalInboxState.NotLoaded -> content
      }

  private fun ProposalInboxContent.terminalItems(): ProposalInboxContent =
      copy(items = items.filter { it.status.isTerminal() })

  private fun ProposalInboxState.withContent(content: ProposalInboxContent): ProposalInboxState =
      when (this) {
        is ProposalInboxState.Content -> ProposalInboxState.Content(content)
        is ProposalInboxState.Loading -> ProposalInboxState.Loading(content)
        is ProposalInboxState.Error -> ProposalInboxState.Error(content, cause, retryCursor)
        is ProposalInboxState.NotLoaded -> ProposalInboxState.NotLoaded(bindingGeneration, content)
      }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
