package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.calendarai.WorkoutPreparationPolling
import com.valerochka1337.valerochkagym.ui.components.GlowBackground

@Composable
fun TrainingProposalInboxScreen(
    onCreateAi: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel, state.inbox.bindingGeneration) { viewModel.ensureInbox() }
  // The inbox intentionally hides the preparation card, but active requests must keep polling.
  WorkoutPreparationPolling()
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
    )
  }
}

@Composable
fun TrainingProposalDetailScreen(
    id: String,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel, id) { viewModel.open(id) }
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
        { viewModel.open(id) },
        refinement = state.refinement,
        onRefinementChange = viewModel::setRefinement,
        onRefine = viewModel::refine,
        explanation = state.explanation,
        exerciseTypes = state.exerciseTypes,
        availableExerciseIds = state.availableExerciseIds,
        onSaveCopy = viewModel::saveCopy,
        scheduleConflict = state.scheduleConflict,
        copySaved = state.copySaved,
    )
  }
}
