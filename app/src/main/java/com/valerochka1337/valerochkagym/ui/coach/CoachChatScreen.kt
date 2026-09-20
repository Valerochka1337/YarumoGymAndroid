package com.valerochka1337.valerochkagym.ui.coach

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.service.WorkoutSessionService

@Composable
fun CoachChatScreen(
    onBack: () -> Unit,
    viewModel: CoachChatViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  DisposableEffect(context) {
    val activity =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
    val window = activity?.window
    val previousMode = window?.attributes?.softInputMode
    // Edge-to-edge delivers IME insets to Compose; disable Android's automatic panning.
    window?.setSoftInputMode(
        (previousMode ?: 0) and
            WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )
    onDispose { if (previousMode != null) window.setSoftInputMode(previousMode) }
  }
  // A restored active workout has durable Room state but no process-local conversation consumer.
  // Starting the foreground owner is safe only while the observed workout is still active.
  LaunchedEffect(state.readOnly) { if (!state.readOnly) WorkoutSessionService.start(context) }
  val chatHost = androidx.compose.runtime.remember { Any() }
  LifecycleResumeEffect(chatHost) {
    viewModel.chatResumed(chatHost)
    onPauseOrDispose { viewModel.chatPaused(chatHost) }
  }
  DisposableEffect(viewModel) { onDispose { viewModel.clearNewMessageHighlights() } }
  LifecycleResumeEffect(state.messages) {
    viewModel.markAssistantMessagesRead()
    onPauseOrDispose {}
  }
  CoachChatContent(
      state = state,
      onBack = onBack,
      onDraftChange = viewModel::changeDraft,
      onSend = viewModel::send,
      onRetry = viewModel::retry,
      onConfirm = viewModel::confirm,
      onAnswerQuestion = viewModel::answerQuestion,
      onCancel = viewModel::cancel,
      onCancelWithReason = viewModel::cancelWithReason,
      onUndo = viewModel::undo,
      onDisableInitiative = viewModel::disableInitiative,
  )
}
