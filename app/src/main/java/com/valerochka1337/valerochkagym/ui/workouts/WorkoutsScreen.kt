package com.valerochka1337.valerochkagym.ui.workouts

import android.Manifest
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.service.WorkoutSessionService
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneDialog
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneTarget
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneViewModel
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.GymTopBar
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.TemplatesSectionHeader
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.permissions.AndroidPermissionPlatform
import com.valerochka1337.valerochkagym.ui.permissions.rememberPermissionRecoveryHost
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

private const val NOTIFICATION_PENDING_KIND = "notification"

/**
 * Вкладка «Тренировки»: тап по программе выбирает её, а закреплённый снизу блок запускает выбранную
 * или пустую тренировку. Меню карточки отвечает за вторичные действия.
 *
 * Старт тренировки создаётся в [WorkoutsViewModel] (single-flight); по событию
 * [WorkoutsViewModel.startEvents] экран навигирует на активную тренировку через [onStartWorkout].
 */
@Composable
fun WorkoutsScreen(
    onCreateRoutine: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
    onEditRoutine: (Long) -> Unit,
    onShareRoutine: (Long) -> Unit = {},
    onStartWorkout: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val cloneViewModel: ConfigurationCloneViewModel = hiltViewModel()
  val cloneState by cloneViewModel.uiState.collectAsStateWithLifecycle()
  var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
  var templatesExpanded by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }

  val context = LocalContext.current
  val permissionPlatform = remember(context) { AndroidPermissionPlatform(context) }
  val permissionHost =
      rememberPermissionRecoveryHost(
          kind = NOTIFICATION_PENDING_KIND,
          permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
          platform = permissionPlatform,
          decide = viewModel::decidePermissions,
          markRequestLaunched = viewModel::markPermissionRequestLaunched,
          onAction = { token, workoutId, _ -> viewModel.continueStartedWorkout(token, workoutId) },
      )
  val continueToWorkout = {
    WorkoutSessionService.start(context)
    onStartWorkout()
  }

  LaunchedEffect(Unit) {
    viewModel.startEvents.collect { started ->
      permissionHost.begin(started.workoutId, started.actionToken)
    }
  }
  LaunchedEffect(Unit) { viewModel.startReadyEvents.collect { continueToWorkout() } }
  LaunchedEffect(Unit) { viewModel.messages.collect(snackbarHostState::showSnackbar) }
  LaunchedEffect(Unit) { cloneViewModel.messages.collect(snackbarHostState::showSnackbar) }

  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        GymTopBar(
            title = "Тренировки",
            onOpenSettings = onOpenSettings,
            actions = {
              CircleIconButton(
                  icon = Icons.Default.Add,
                  contentDescription = "Новая программа",
                  onClick = onCreateRoutine,
                  tint = MaterialTheme.colorScheme.primary,
              )
            },
        )

        val routines = state.routines
        when {
          routines == null ->
              Box(
                  modifier = Modifier.fillMaxSize().weight(1f),
                  contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator()
              }
          routines.isEmpty() ->
              FadeInContent(modifier = Modifier.weight(1f)) {
                EmptyWorkoutsState(
                    onStartEmpty = viewModel::startEmpty,
                    onCreateRoutine = onCreateRoutine,
                    modifier = Modifier.fillMaxSize(),
                )
              }
          else ->
              FadeInContent(modifier = Modifier.weight(1f)) {
                WorkoutRoutinesList(
                    routines = routines,
                    selectedRoutineId = state.selectedRoutineId,
                    templatesExpanded = templatesExpanded,
                    onTemplatesExpandedChange = { templatesExpanded = it },
                    onRoutineSelected = viewModel::onRoutineSelected,
                    onOpenRoutine = onOpenRoutine,
                    onEditRoutine = onEditRoutine,
                    onShareRoutine = onShareRoutine,
                    onDuplicateRoutine = { id ->
                      state.routines
                          ?.firstOrNull { it.id == id }
                          ?.let { routine ->
                            cloneViewModel.open(
                                ConfigurationCloneTarget.Routine(routine.id, routine.name)
                            )
                          }
                    },
                    onDeleteRoutine = { pendingDeleteId = it },
                )
              }
        }

        if (routines != null && routines.isNotEmpty()) {
          StartBar(
              startEnabled = state.selectedRoutineId != null,
              onStart = { state.selectedRoutineId?.let(viewModel::startFromRoutine) },
              onEmpty = viewModel::startEmpty,
          )
        }
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
      )
    }
  }

  val deleteId = pendingDeleteId
  if (deleteId != null) {
    val name = state.routines?.firstOrNull { it.id == deleteId }?.name.orEmpty()
    DeleteRoutineDialog(
        routineName = name,
        onConfirm = {
          viewModel.delete(deleteId)
          pendingDeleteId = null
        },
        onDismiss = { pendingDeleteId = null },
    )
  }

  if (permissionHost.showDialog) {
    AlertDialog(
        onDismissRequest = permissionHost::cancel,
        title = { Text("Показывать таймер отдыха в уведомлении?") },
        text = {
          Text(
              "Разрешение нужно, чтобы видеть таймер и вернуться к тренировке после " +
                  "сворачивания. Без него тренировка и таймер на экране продолжат работать.",
          )
        },
        confirmButton = {
          TextButton(onClick = { permissionHost.confirm() }) {
            Text(if (permissionHost.offersSettings) "Открыть настройки" else "Разрешить")
          }
        },
        dismissButton = { TextButton(onClick = permissionHost::cancel) { Text("Не сейчас") } },
    )
  }

  ConfigurationCloneDialog(
      state = cloneState,
      onNameChange = cloneViewModel::setName,
      onSave = cloneViewModel::save,
      onDismiss = cloneViewModel::dismiss,
  )
}

@Composable
internal fun WorkoutRoutinesList(
    routines: List<RoutineCardUi>,
    selectedRoutineId: Long?,
    templatesExpanded: Boolean,
    onTemplatesExpandedChange: (Boolean) -> Unit,
    onRoutineSelected: (Long) -> Unit,
    onOpenRoutine: (Long) -> Unit,
    onEditRoutine: (Long) -> Unit = {},
    onShareRoutine: (Long) -> Unit = {},
    onDuplicateRoutine: (Long) -> Unit,
    onDeleteRoutine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
  val standard = routines.filter { it.origin == "STANDARD" }
  val personal = routines.filterNot { it.origin == "STANDARD" }
  val haptics = gymHaptics()
  LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding =
          PaddingValues(
              start = 24.dp,
              end = 24.dp,
              top = 4.dp,
              bottom = 16.dp,
          ),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (standard.isNotEmpty()) {
      item(key = "routine_templates_header") {
        TemplatesSectionHeader(
            count = standard.size,
            expanded = templatesExpanded,
            onClick = {
              haptics.tap()
              onTemplatesExpandedChange(!templatesExpanded)
            },
        )
      }
      if (templatesExpanded) {
        items(standard, key = { it.id }) { routine ->
          RoutineCard(
              routine = routine,
              selected = routine.id == selectedRoutineId,
              onClick = {
                haptics.tap()
                onRoutineSelected(routine.id)
              },
              onOpen = { onOpenRoutine(routine.id) },
              onEdit = { onEditRoutine(routine.id) },
              onShare = { onShareRoutine(routine.id) },
              onDuplicate = { onDuplicateRoutine(routine.id) },
              onDelete = { onDeleteRoutine(routine.id) },
              modifier = Modifier.animateItem(),
          )
        }
      }
    }
    items(personal, key = { it.id }) { routine ->
      RoutineCard(
          routine = routine,
          selected = routine.id == selectedRoutineId,
          onClick = {
            haptics.tap()
            onRoutineSelected(routine.id)
          },
          onOpen = { onOpenRoutine(routine.id) },
          onEdit = { onEditRoutine(routine.id) },
          onShare = { onShareRoutine(routine.id) },
          onDuplicate = { onDuplicateRoutine(routine.id) },
          onDelete = { onDeleteRoutine(routine.id) },
          modifier = Modifier.animateItem(),
      )
    }
  }
}

@Composable
private fun RoutineCard(
    routine: RoutineCardUi,
    selected: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val borderColor by
      animateColorAsState(
          targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
          animationSpec = GymMotion.effectsFast(),
          label = "routine-border",
      )
  GymCard(
      modifier =
          modifier.fillMaxWidth().border(2.dp, borderColor, GymCardShape).semantics {
            this.selected = selected
            stateDescription = if (selected) "Выбрано" else "Не выбрано"
          },
      onClick = onClick,
      contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text =
                "${routine.exerciseCount} ${exercisesWord(routine.exerciseCount)} · около ${routine.estimatedMinutes} мин",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text =
                if (routine.gymNames.isEmpty()) {
                  "Без ограничений по залу"
                } else {
                  routine.gymNames.joinToString()
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      RoutineCardMenu(
          standard = routine.origin == "STANDARD",
          onOpen = onOpen,
          onEdit = onEdit,
          onShare = onShare,
          onDuplicate = onDuplicate,
          onDelete = onDelete,
      )
    }
  }
}

@Composable
private fun StartBar(
    startEnabled: Boolean,
    onStart: () -> Unit,
    onEmpty: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    PillButton(
        text = "Начать тренировку",
        onClick = onStart,
        enabled = startEnabled,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onEmpty) { Text("Пустая тренировка") }
  }
}

@Composable
private fun RoutineCardMenu(
    standard: Boolean = false,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(
          Icons.Default.MoreVert,
          contentDescription = "Меню программы",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text("Открыть") },
          onClick = {
            expanded = false
            onOpen()
          },
      )
      if (!standard)
          DropdownMenuItem(
              text = { Text("Редактировать") },
              onClick = {
                expanded = false
                onEdit()
              },
          )
      if (!standard)
          DropdownMenuItem(
              text = { Text("Поделиться") },
              onClick = {
                expanded = false
                onShare()
              },
          )
      DropdownMenuItem(
          text = { Text("Клонировать") },
          onClick = {
            expanded = false
            onDuplicate()
          },
      )
      if (!standard)
          DropdownMenuItem(
              text = { Text("Удалить") },
              onClick = {
                expanded = false
                onDelete()
              },
          )
    }
  }
}

@Composable
internal fun EmptyWorkoutsState(
    onStartEmpty: () -> Unit,
    onCreateRoutine: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = "Начните первую тренировку",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Добавляйте упражнения во время тренировки или заранее создайте программу.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    PillButton(
        text = "Начать без программы",
        onClick = onStartEmpty,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onCreateRoutine) { Text("Создать программу") }
  }
}

@Composable
private fun DeleteRoutineDialog(
    routineName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Удалить программу?") },
      text = {
        Text(
            "Программа «$routineName» и запланированные записи в календаре будут удалены. " +
                "Завершённые тренировки сохранятся в истории.",
        )
      },
      confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

/** Русское склонение слова «упражнение» по количеству. */
private fun exercisesWord(count: Int): String {
  val mod100 = count % 100
  val mod10 = count % 10
  return when {
    mod100 in 11..14 -> "упражнений"
    mod10 == 1 -> "упражнение"
    mod10 in 2..4 -> "упражнения"
    else -> "упражнений"
  }
}
