package com.valerochka1337.valerochkagym.ui.coach

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.CoachDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.domain.CoachReply
import com.valerochka1337.valerochkagym.service.CoachConversationService
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CoachChatViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val coachDao: CoachDao,
    private val workoutDao: WorkoutDao,
    private val conversation: CoachConversationService,
    private val coachAlertNotifier: com.valerochka1337.valerochkagym.service.CoachAlertNotifier,
) : ViewModel() {
  private val workoutId = requireNotNull(savedStateHandle.get<String>(GymRoutes.WORKOUT_ID_ARG))
  private val draft = MutableStateFlow(savedStateHandle.get<String>(DRAFT) ?: "")
  private val status = MutableStateFlow<String?>(null)
  private val error = MutableStateFlow<String?>(null)
  private val busyAction = MutableStateFlow(false)
  private val highlightedIds = MutableStateFlow<Set<String>>(emptySet())

  private val basePersisted =
      combine(
          coachDao.observeMessages(workoutId),
          coachDao.observePendingProposal(workoutId),
          coachDao.observeContext(workoutId),
          draft,
          highlightedIds,
      ) { messages, proposal, context, draftValue, highlighted ->
        PersistedChat(
            messages.map {
              CoachChatMessage(
                  it.id,
                  it.role,
                  if (it.text.startsWith("Не удалось обработать запрос тренера."))
                      "Не удалось обработать запрос"
                  else it.text,
                  it.status.toUiStatus(),
                  it.quickRepliesJson?.let(CoachReply::decodeQuickReplies),
                  unread = it.role == "assistant" && it.readAt == null && it.status == "DELIVERED",
                  isNew =
                      it.role == "assistant" &&
                          it.status == "DELIVERED" &&
                          (it.readAt == null || it.id in highlighted),
                  failed =
                      it.role == "assistant" &&
                          (it.status == "ERROR" ||
                              it.text.startsWith("Не удалось обработать запрос тренера.")),
              )
            },
            proposal?.let {
              CoachChatProposal(
                  it.id,
                  it.beforeSummary,
                  it.afterSummary,
                  com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview.decode(
                      it.previewJson
                  ),
              )
            },
            context,
            draftValue,
        )
      }
  private val persisted =
      combine(basePersisted, coachDao.observeBehavior(workoutId)) { chat, behavior ->
        chat.copy(behavior = behavior)
      }
  private val transient =
      combine(status, error, busyAction, conversation.responseDrafts) {
          statusValue,
          errorValue,
          actionBusy,
          responses ->
        TransientChat(statusValue, errorValue, actionBusy, responses[workoutId])
      }

  val uiState: StateFlow<CoachChatUiState> =
      combine(
              persisted,
              transient,
              conversation.runningWorkouts,
              conversation.runningStages,
              workoutDao.observeWorkout(workoutId),
          ) { persistedValue, transientValue, running, stages, workout ->
            CoachChatUiState(
                workoutName = workout?.name ?: "Тренировка",
                messages =
                    persistedValue.messages.let { messages ->
                      val response = transientValue.response
                      if (
                          response == null ||
                              workout == null ||
                              workout.finishedAt != null ||
                              messages.any { it.id == response.id }
                      )
                          messages
                      else
                          messages +
                              CoachChatMessage(
                                  response.id,
                                  "assistant",
                                  response.text,
                                  quickReplies = response.quickReplies,
                                  streaming = response.streaming,
                              )
                    },
                proposal = persistedValue.proposal,
                behavior = persistedValue.behavior,
                draft = persistedValue.draft,
                busy = transientValue.actionBusy || workoutId in running,
                status = transientValue.status ?: stages[workoutId],
                error = transientValue.error,
                readOnly = workout == null || workout.finishedAt != null,
                initiativeEnabled = persistedValue.context?.initiativeEnabled ?: true,
                canUndo =
                    workout?.finishedAt == null && persistedValue.context?.lastUndoRevision != null,
            )
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              CoachChatUiState(readOnly = true),
          )

  fun changeDraft(value: String) {
    draft.value = value
    savedStateHandle[DRAFT] = value
  }

  fun send(text: String) {
    if (text.isBlank() || busyAction.value) return
    busyAction.value = true
    error.value = null
    status.value = "Отправляем сообщение тренеру…"
    viewModelScope.launch {
      try {
        if (conversation.send(workoutId, text)) {
          if (draft.value.trim() == text.trim()) changeDraft("")
          status.value = null
        } else {
          error.value = "Тренер доступен только в активной тренировке с подключённым аккаунтом."
        }
      } catch (_: Exception) {
        error.value = "Не удалось отправить сообщение тренеру. Попробуйте ещё раз."
      } finally {
        busyAction.value = false
      }
    }
  }

  fun retry(errorMessageId: String) = action { conversation.retry(workoutId, errorMessageId) }

  fun confirm(id: String) = action { conversation.confirm(workoutId, id) }

  fun cancel(id: String) = action { conversation.cancel(workoutId, id) }

  fun cancelWithReason(
      id: String,
      reason: com.valerochka1337.valerochkagym.domain.CoachRejectionReason,
  ) = action { conversation.cancel(workoutId, id, reason) }

  fun answerQuestion(id: String, option: String) = action {
    conversation.answerQuestion(workoutId, id, option)
  }

  fun resolveConcern(id: String) = action { conversation.resolveConcern(workoutId, id) }

  fun phase(value: String) = action { conversation.setPhase(workoutId, value) }

  fun undo() = action { conversation.undo(workoutId) }

  fun disableInitiative() = action { conversation.disableInitiative(workoutId) }

  fun chatResumed(host: Any) = coachAlertNotifier.chatResumed(workoutId, host)

  fun chatPaused(host: Any) = coachAlertNotifier.chatPaused(host)

  fun clearNewMessageHighlights() {
    highlightedIds.value = emptySet()
  }

  /** Capture before marking read; new arrivals outside this UI snapshot stay unread. */
  fun markAssistantMessagesRead() {
    coachAlertNotifier.chatViewed(workoutId)
    val ids = uiState.value.messages.filter { it.unread }.map { it.id }
    if (ids.isEmpty()) return
    highlightedIds.value = highlightedIds.value + ids
    viewModelScope.launch { coachDao.markAssistantMessagesReadByIds(workoutId, ids) }
  }

  private fun action(block: suspend () -> Boolean) {
    if (busyAction.value) return
    busyAction.value = true
    error.value = null
    viewModelScope.launch {
      try {
        if (!block()) error.value = "Действие больше недоступно. Обновите состояние тренировки."
      } catch (_: Exception) {
        error.value = "Не удалось выполнить действие. Попробуйте ещё раз."
      } finally {
        busyAction.value = false
      }
    }
  }

  private data class PersistedChat(
      val messages: List<CoachChatMessage>,
      val proposal: CoachChatProposal?,
      val context: com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity?,
      val draft: String,
      val behavior: List<com.valerochka1337.valerochkagym.data.db.entity.CoachBehaviorEntity> =
          emptyList(),
  )

  private data class TransientChat(
      val status: String?,
      val error: String?,
      val actionBusy: Boolean,
      val response: com.valerochka1337.valerochkagym.service.CoachDraft?,
  )

  private fun String.toUiStatus(): String? =
      when (this) {
        "PENDING",
        "PROCESSING" -> "Обрабатывается"
        "INTERRUPTED" -> "Запрос прерван"
        else -> null
      }

  private companion object {
    const val DRAFT = "coach_draft"
  }
}
