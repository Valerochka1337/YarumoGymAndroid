package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

data class TrainingProposalUiState(
    val inbox: ProposalInboxUiState = ProposalInboxUiState(),
    val editor: ProposalEditor? = null,
    val explanation: PlannerExplanation? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val scheduleConflict: Boolean = false,
    val refinementExcludeIds: Set<String> = emptySet(),
    val refinementReplacementIds: Map<String, String> = emptyMap(),
    val refinementRequestId: String? = null,
    val copySaved: Boolean = false,
    val copyScheduled: Boolean = false,
    val error: String? = null,
    val exerciseChoices: List<Pair<String, String>> = emptyList(),
    val exerciseTypes: Map<String, com.valerochka1337.valerochkagym.data.db.entity.ExerciseType> =
        emptyMap(),
    val availableExerciseIds: Set<String> = emptySet(),
    val gymChoices: List<Pair<String, String>> = emptyList(),
)

data class ProposalInboxUiState(
    val items: List<TrainingProposal> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val bindingGeneration: Long = 0,
)

@HiltViewModel
class TrainingProposalViewModel
@Inject
constructor(
    private val repository: TrainingProposalRepository,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    exercises: ExerciseDao,
    gyms: GymDao,
    private val savedState: SavedStateHandle,
    private val gymRepository: com.valerochka1337.valerochkagym.domain.GymRepository,
    private val copies: ProposalCopyRepository,
) : ViewModel() {
  private val mutableState =
      MutableStateFlow(
          TrainingProposalUiState(
              refinementRequestId = savedState.get<String>("planner_request_id")
          )
      )
  private val edits = Mutex()
  private var generation = 0L
  private var load: Job? = null
  private var bound: BackendSessionSnapshot? = null
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val availableExercises =
      mutableState
          .map { it.editor?.draft?.gymIds.orEmpty().toSet() }
          .distinctUntilChanged()
          .flatMapLatest(gymRepository::observeAvailableExercises)

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val scheduleConflict =
      mutableState
          .map { it.editor?.draft }
          .distinctUntilChanged()
          .flatMapLatest { draft ->
            if (draft == null) flowOf(false) else copies.observeScheduleConflict(draft)
          }

  val uiState =
      combine(
              mutableState,
              repository.inbox,
              exercises.getAll(),
              gyms.observeGyms(),
              availableExercises,
          ) { state, inbox, exerciseList, gymList, available ->
            state.copy(
                inbox = inbox.toUiState(),
                exerciseTypes = exerciseList.associate { it.syncId to it.type },
                availableExerciseIds =
                    available.filterNot { it.archived }.map { it.syncId }.toSet(),
                exerciseChoices =
                    exerciseList.filterNot { it.archived }.map { it.syncId to it.name },
                gymChoices = gymList.filterNot { it.archived }.map { it.syncId to it.name },
            )
          }
          .combine(scheduleConflict) { state, conflict -> state.copy(scheduleConflict = conflict) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrainingProposalUiState())

  init {
    viewModelScope.launch {
      combine(sessions.session, sync.transfer) { _, _ -> Unit }
          .collect {
            if (bound?.let(repository::isCurrent) == false) {
              generation++
              load?.cancel()
              bound = null
              clearRefinementRoute()
              mutableState.value =
                  TrainingProposalUiState(error = "Аккаунт изменился. Обновите предложения")
            }
          }
    }
  }

  fun ensureInbox() = repository.ensureInitialLoad()

  fun retryInbox() = repository.retryInitialLoad()

  fun loadMore() = repository.loadMore()

  fun open(id: String) {
    savedState["proposalId"] = id
    val token = ++generation
    load?.cancel()
    mutableState.update {
      it.copy(editor = null, explanation = null, loading = true, saving = false, error = null)
    }
    load =
        viewModelScope.launch {
          try {
            val editor = repository.open(id)
            if (token != generation || !repository.isCurrent(editor.session)) return@launch
            bound = editor.session
            mutableState.update {
              it.copy(
                  editor = editor,
                  loading = false,
                  copySaved = false,
                  copyScheduled = false,
                  refinementExcludeIds = emptySet(),
                  refinementReplacementIds = emptyMap(),
              )
            }
            if (editor.proposal.source in setOf(ProposalSource.AI, ProposalSource.RULE_BASED)) {
              val explanation =
                  try {
                    repository.explanation(editor)
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (_: Exception) {
                    if (editor.proposal.source == ProposalSource.RULE_BASED)
                        mutableState.update {
                          it.copy(
                              error =
                                  "Пояснение плана не прошло проверку. Обновите предложение перед уточнением."
                          )
                        }
                    null
                  }
              if (token == generation && repository.isCurrent(editor.session))
                  mutableState.update { it.copy(explanation = explanation) }
            }
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            if (token == generation)
                mutableState.update { it.copy(loading = false, error = message(error)) }
          }
        }
  }

  /**
   * Keeps the navigation argument stable while a refinement opens one or more replacement
   * proposals. The replacement is state for this route, not a new route argument.
   */
  fun bindRouteRoot(requestedId: String) {
    val owner = sessions.snapshot()?.tokens?.userId
    val storedRoute = savedState.get<String>(REFINEMENT_ROUTE_ID)
    val storedOwner = savedState.get<String>(REFINEMENT_ROUTE_OWNER)
    if (storedRoute != null && (storedRoute != requestedId || storedOwner != owner)) {
      clearRefinementRoute()
    }
    if (savedState.get<String>(REFINEMENT_ROUTE_ID) == null) {
      savedState[REFINEMENT_ROUTE_ID] = requestedId
      if (owner == null) savedState.remove<String>(REFINEMENT_ROUTE_OWNER)
      else savedState[REFINEMENT_ROUTE_OWNER] = owner
      savedState[REFINEMENT_ORIGIN_ID] = requestedId
    }
  }

  fun restoredProposalId(requestedId: String): String =
      savedState
          .get<String>(REFINEMENT_ORIGIN_ID)
          ?.takeIf { it == requestedId }
          ?.let { savedState.get<String>(REFINEMENT_RESOLVED_ID) } ?: requestedId

  fun updateDraft(draft: ApprovalDraft) {
    val editor = mutableState.value.editor ?: return
    if (mutableState.value.saving) return
    if (draft == editor.draft) return
    val token = generation
    if (draft != editor.draft) savedState.remove<String>("${copyKey(editor)}.operation")
    mutableState.update {
      it.copy(
          editor = editor.copy(draft = draft),
          error = null,
          copySaved = false,
          copyScheduled = false,
      )
    }
    viewModelScope.launch {
      edits.withLock {
        if (token != generation || !repository.isCurrent(editor.session)) return@withLock
        try {
          repository.save(editor, draft)
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          if (token == generation) mutableState.update { it.copy(error = message(error)) }
        }
      }
    }
  }

  fun approve() = decision(true)

  fun reject() = decision(false)

  fun saveCopy() = copyPlan()

  fun scheduleCopy(startsAtMillis: Long, timeZoneId: String) = copyPlan(startsAtMillis, timeZoneId)

  fun applyCopy() {
    val draft = mutableState.value.editor?.draft ?: return
    copyPlan(draft.startsAtMillis, draft.timeZoneId)
  }

  fun delete(proposal: TrainingProposal) {
    if (mutableState.value.saving) return
    viewModelScope.launch {
      try {
        repository.delete(proposal)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        mutableState.update { it.copy(error = message(error)) }
      }
    }
  }

  private fun copyKey(editor: ProposalEditor) =
      "proposal_copy.${editor.session.tokens.userId}.${editor.session.epoch}.${editor.proposal.proposalId}.${editor.proposal.currentVersion}"

  private fun copyPlan(startsAtMillis: Long? = null, timeZoneId: String? = null) {
    val editor = mutableState.value.editor ?: return
    if (mutableState.value.saving) return
    val key = copyKey(editor)
    val operation =
        savedState.get<String>("$key.operation") ?: java.util.UUID.randomUUID().toString()
    savedState["$key.operation"] = operation
    val token = generation
    mutableState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      edits.withLock {
        try {
          if (token != generation || !repository.isCurrent(editor.session)) return@withLock
          copies.save(editor, operation, startsAtMillis, timeZoneId ?: editor.draft.timeZoneId)
          if (token == generation && repository.isCurrent(editor.session)) {
            savedState.remove<String>("$key.operation")
            mutableState.update { it.copy(copySaved = true) }
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          if (token == generation) mutableState.update { it.copy(error = message(error)) }
        } finally {
          if (token == generation) mutableState.update { it.copy(saving = false) }
        }
      }
    }
  }

  fun toggleRefinementExclusion(exerciseId: String) {
    if (!mutableState.value.saving)
        mutableState.update {
          it.copy(
              refinementExcludeIds =
                  if (exerciseId in it.refinementExcludeIds) it.refinementExcludeIds - exerciseId
                  else it.refinementExcludeIds + exerciseId,
              error = null,
          )
        }
  }

  fun replaceRefinementSelection(selectionId: String, exerciseId: String) {
    val current =
        mutableState.value.explanation?.ruleDetails?.slotSelections?.firstOrNull {
          it.selectionId == selectionId
        } ?: return
    if (mutableState.value.saving) return
    mutableState.update {
      it.copy(
          refinementReplacementIds =
              if (exerciseId == current.exerciseId) it.refinementReplacementIds - selectionId
              else it.refinementReplacementIds + (selectionId to exerciseId),
          error = null,
      )
    }
  }

  fun refine() {
    val editor = mutableState.value.editor ?: return
    val exclusions = mutableState.value.refinementExcludeIds
    val replacements = mutableState.value.refinementReplacementIds
    val selections = mutableState.value.explanation?.ruleDetails?.slotSelections.orEmpty()
    if ((exclusions.isEmpty() && replacements.isEmpty()) || mutableState.value.saving) return
    val key = refinementKey(editor) ?: return
    val requestId =
        savedState.get<String>("$key.requestId") ?: java.util.UUID.randomUUID().toString()
    savedState["$key.requestId"] = requestId
    val token = generation
    mutableState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      try {
        val queued =
            repository.refine(
                editor,
                buildList {
                  selections.forEach { selection ->
                    replacements[selection.selectionId]?.let { exerciseId ->
                      add(
                          kotlinx.serialization.json.buildJsonObject {
                            put("kind", JsonPrimitive("REPLACE"))
                            put("slotId", JsonPrimitive(selection.slotId))
                            put("selectionId", JsonPrimitive(selection.selectionId))
                            put("exerciseId", JsonPrimitive(exerciseId))
                          }
                      )
                    }
                  }
                  exclusions.sorted().forEach { id ->
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                          put("kind", JsonPrimitive("EXCLUDE"))
                          put("exerciseId", JsonPrimitive(id))
                        }
                    )
                  }
                },
                requestId,
                requireNotNull(mutableState.value.explanation).desiredMinutes,
            )
        if (token == generation && repository.isCurrent(editor.session)) {
          savedState.remove<String>("$key.requestId")
          mutableState.update {
            it.copy(
                refinementExcludeIds = emptySet(),
                refinementReplacementIds = emptyMap(),
                copySaved = false,
                copyScheduled = false,
            )
          }
          // The preparation journal owns progress and opens the resulting proposal by request ID.
          savedState["planner_request_id"] = queued
          mutableState.update { it.copy(refinementRequestId = queued) }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (token == generation) mutableState.update { it.copy(error = message(error)) }
      } finally {
        if (token == generation) mutableState.update { it.copy(saving = false) }
      }
    }
  }

  private fun refinementKey(editor: ProposalEditor?): String? =
      editor?.let {
        "proposal_refinement.${it.session.tokens.userId}.${it.session.epoch}.${it.proposal.proposalId}.${it.proposal.currentVersion}"
      }

  fun openRefinementProposal(
      preparation: com.valerochka1337.valerochkagym.data.ai.PreparationEntity
  ) {
    if (
        preparation.state != "READY" ||
            preparation.requestId != mutableState.value.refinementRequestId
    )
        return
    val proposal =
        preparation.proposalJson?.let {
          runCatching { ProposalWire.json.decodeFromString<TrainingProposal>(it) }.getOrNull()
        } ?: return
    val origin =
        savedState.get<String>(REFINEMENT_ORIGIN_ID)
            ?: mutableState.value.editor?.proposal?.proposalId
            ?: return
    savedState.remove<String>("planner_request_id")
    savedState[REFINEMENT_ORIGIN_ID] = origin
    savedState[REFINEMENT_RESOLVED_ID] = proposal.proposalId
    mutableState.update { it.copy(refinementRequestId = null) }
    open(proposal.proposalId)
  }

  fun resetRefinementRequest() {
    savedState.remove<String>("planner_request_id")
    mutableState.update { it.copy(refinementRequestId = null, error = null) }
  }

  private fun clearRefinementRoute() {
    savedState.remove<String>(REFINEMENT_ROUTE_ID)
    savedState.remove<String>(REFINEMENT_ROUTE_OWNER)
    savedState.remove<String>(REFINEMENT_ORIGIN_ID)
    savedState.remove<String>(REFINEMENT_RESOLVED_ID)
    savedState.remove<String>("planner_request_id")
    mutableState.update { it.copy(refinementRequestId = null) }
  }

  private fun decision(approve: Boolean) {
    val editor = mutableState.value.editor ?: return
    if (mutableState.value.saving) return
    val token = generation
    mutableState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      edits.withLock {
        try {
          if (token != generation || !repository.isCurrent(editor.session)) return@withLock
          if (approve) repository.approve(editor) else repository.reject(editor)
          if (token == generation && repository.isCurrent(editor.session))
              mutableState.update {
                it.copy(
                    editor =
                        editor.copy(
                            applied = approve,
                            proposal =
                                editor.proposal.copy(
                                    status =
                                        if (approve) ProposalStatus.APPROVED
                                        else ProposalStatus.REJECTED
                                ),
                        )
                )
              }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          if (token == generation) mutableState.update { it.copy(error = message(error)) }
        } finally {
          if (token == generation) mutableState.update { it.copy(saving = false) }
        }
      }
    }
  }

  private fun message(error: Exception): String =
      when ((error as? BackendException)?.code) {
        "proposal_copy_unavailable" -> "Упражнения или залы этого плана больше недоступны"
        "proposal_copy_date" -> "Выберите будущее время тренировки"
        "proposal_copy_calendar" -> error.message
        "proposal_copy_failed" -> "Не удалось сохранить тренировку. Повторите попытку"
        "active_workout",
        "workout_active" -> "Завершите тренировку перед применением"
        "proposal_expired",
        "proposal_stale",
        "proposal_version_conflict" -> "Предложение изменилось или устарело. Обновите его"
        "owner_changed",
        "unauthorized" -> "Войдите в нужный аккаунт и обновите предложение"
        "approval_pending",
        "proposal_operation_conflict" -> "Сначала проверьте ранее отправленное подтверждение"
        "proposal_revoked" -> "Автор отозвал предложение"
        "proposal_rejected" -> "Предложение отклонено"
        else -> "Не удалось завершить действие. Повторите попытку"
      }

  private fun ProposalInboxState.toUiState(): ProposalInboxUiState =
      when (this) {
        is ProposalInboxState.NotLoaded ->
            ProposalInboxUiState(
                items = content?.items.orEmpty(),
                nextCursor = content?.nextCursor,
                bindingGeneration = bindingGeneration,
            )
        is ProposalInboxState.Loading ->
            ProposalInboxUiState(content?.items.orEmpty(), content?.nextCursor, loading = true)
        is ProposalInboxState.Content ->
            ProposalInboxUiState(content.items, content.nextCursor, loading = false)
        is ProposalInboxState.Error ->
            ProposalInboxUiState(
                content?.items.orEmpty(),
                content?.nextCursor,
                loading = false,
                error = message(cause),
            )
      }

  private companion object {
    const val REFINEMENT_ROUTE_ID = "planner_refinement_route_id"
    const val REFINEMENT_ROUTE_OWNER = "planner_refinement_route_owner"
    const val REFINEMENT_ORIGIN_ID = "planner_refinement_origin_id"
    const val REFINEMENT_RESOLVED_ID = "planner_refinement_resolved_id"
  }
}
