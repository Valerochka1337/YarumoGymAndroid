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

data class TrainingProposalUiState(
    val inbox: ProposalInboxUiState = ProposalInboxUiState(),
    val editor: ProposalEditor? = null,
    val explanation: PlannerExplanation? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val scheduleConflict: Boolean = false,
    val refinement: String = "",
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
  private val mutableState = MutableStateFlow(TrainingProposalUiState())
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
                  refinement =
                      refinementKey(editor)?.let { key -> savedState.get<String>(key) }.orEmpty(),
              )
            }
            if (editor.proposal.source == ProposalSource.AI) {
              val explanation =
                  try {
                    repository.explanation(editor)
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (_: Exception) {
                    null // Optional resource, including older servers and offline viewing.
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

  fun setRefinement(value: String) {
    if (!mutableState.value.saving) {
      refinementKey(mutableState.value.editor)?.let { key ->
        if (savedState.get<String>(key) != value) savedState.remove<String>("$key.requestId")
      }
      mutableState.update { it.copy(refinement = value, error = null) }
      refinementKey(mutableState.value.editor)?.let { savedState[it] = value }
    }
  }

  fun refine() {
    val editor = mutableState.value.editor ?: return
    val text = mutableState.value.refinement.trim()
    if (text.isEmpty() || text.length > 2000 || mutableState.value.saving) return
    val key = refinementKey(editor) ?: return
    val requestId =
        savedState.get<String>("$key.requestId") ?: java.util.UUID.randomUUID().toString()
    savedState["$key.requestId"] = requestId
    val token = generation
    mutableState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      try {
        val next = repository.refine(editor, text, requestId)
        if (token == generation && repository.isCurrent(next.session)) {
          savedState.remove<String>("$key.requestId")
          savedState.remove<String>(key)
          mutableState.update {
            it.copy(editor = next, refinement = "", copySaved = false, copyScheduled = false)
          }
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
}
