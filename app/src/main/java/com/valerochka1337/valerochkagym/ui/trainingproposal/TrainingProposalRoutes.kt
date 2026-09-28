package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.calendarai.WorkoutPreparationPolling
import com.valerochka1337.valerochkagym.ui.calendarai.WorkoutPreparationViewModel
import com.valerochka1337.valerochkagym.ui.components.GlowBackground

@Composable
fun TrainingProposalInboxScreen(
    onCreateAi: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
    preparationViewModel: WorkoutPreparationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val preparations by preparationViewModel.all.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel, state.inbox.bindingGeneration) { viewModel.ensureInbox() }
  preparations
      .filter {
        it.state in
            com.valerochka1337.valerochkagym.data.ai.WorkoutPreparationRepository.activeStates
      }
      .forEach { WorkoutPreparationPolling(it, preparationViewModel::refreshWhileVisible) }
  GlowBackground {
    TrainingProposalInboxContent(
        state.inbox.items,
        state.inbox.loading,
        state.error ?: state.inbox.error,
        state.inbox.nextCursor != null,
        viewModel::retryInbox,
        viewModel::loadMore,
        onOpen,
        onBack,
        onCreateAi = onCreateAi,
        onDelete = viewModel::delete,
        preparations = preparations,
    )
  }
}

@Composable
fun TrainingProposalDetailScreen(
    id: String,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
    preparationViewModel: WorkoutPreparationViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val preparations by preparationViewModel.all.collectAsStateWithLifecycle()
  val refinement =
      preparations.firstOrNull {
        it.requestId == state.refinementRequestId &&
            it.owner == state.editor?.session?.tokens?.userId
      }
  LaunchedEffect(viewModel, id) {
    viewModel.bindRouteRoot(id)
    viewModel.open(viewModel.restoredProposalId(id))
  }
  WorkoutPreparationPolling(refinement, preparationViewModel::refreshWhileVisible)
  LaunchedEffect(refinement?.requestId, refinement?.state, refinement?.proposalJson) {
    refinement?.let(viewModel::openRefinementProposal)
  }
  GlowBackground {
    TrainingProposalDetailContent(
        state.editor?.proposal,
        state.editor?.draft,
        state.saving,
        state.editor?.applied == true,
        state.error,
        state.exerciseChoices,
        state.gymChoices,
        viewModel::updateDraft,
        viewModel::applyCopy,
        {},
        onBack,
        { viewModel.open(viewModel.restoredProposalId(id)) },
        refinementExcludeIds = state.refinementExcludeIds,
        onToggleRefinementExclusion = viewModel::toggleRefinementExclusion,
        refinementReplacementIds = state.refinementReplacementIds,
        onReplaceRefinementSelection = viewModel::replaceRefinementSelection,
        onRefine = viewModel::refine,
        explanation = state.explanation,
        exerciseTypes = state.exerciseTypes,
        availableExerciseIds = state.availableExerciseIds,
        onSaveCopy = viewModel::saveCopy,
        scheduleConflict = state.scheduleConflict,
        copySaved = state.copySaved,
        refinement = refinement,
        onRetryRefinement = refinement?.let { { preparationViewModel.retry(it.requestId) } },
        onOpenRefinement = viewModel::openRefinementProposal,
        onResetRefinement = viewModel::resetRefinementRequest,
    )
  }
}
