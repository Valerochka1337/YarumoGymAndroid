package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.calendar.ManualPlanningForm
import com.valerochka1337.valerochkagym.ui.calendarai.WorkoutPreparationCard
import com.valerochka1337.valerochkagym.ui.components.GlowBackground

@Composable
fun TrainingProposalInboxScreen(
    onCreateAi: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.refresh() }
  var manual by rememberSaveable { mutableStateOf(false) }
  GlowBackground {
    TrainingProposalInboxContent(
        state.items,
        state.loading,
        state.error,
        state.nextCursor != null,
        { viewModel.refresh() },
        { viewModel.refresh(true) },
        onOpen,
        onBack,
        onCreateAi = onCreateAi,
        onManual = { manual = !manual },
        manualSelected = manual,
        manualContent = { if (manual) ManualPlanningForm(onClose = { manual = false }) },
        preparationContent = { WorkoutPreparationCard(onCreateAi, onOpen) },
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
        viewModel::approve,
        viewModel::reject,
        onBack,
        { viewModel.open(id) },
        explanation = state.explanation,
        exerciseTypes = state.exerciseTypes,
        availableExerciseIds = state.availableExerciseIds,
    )
  }
}
