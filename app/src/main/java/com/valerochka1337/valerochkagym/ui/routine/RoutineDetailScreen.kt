package com.valerochka1337.valerochkagym.ui.routine

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneDialog
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneTarget
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneViewModel
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

@Composable
fun RoutineDetailScreen(
    onBack: () -> Unit,
    onEditRoutine: (Long) -> Unit,
    onExerciseClick: (Long) -> Unit,
    windowWidthClass: GymWindowWidthClass = GymWindowWidthClass.Compact,
    modifier: Modifier = Modifier,
    viewModel: RoutineDetailViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val cloneViewModel: ConfigurationCloneViewModel = hiltViewModel()
  val cloneState by cloneViewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val haptics = gymHaptics()
  LaunchedEffect(Unit) { cloneViewModel.messages.collect(snackbarHostState::showSnackbar) }
  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        RoutineDetailHeader(
            routine = state.routine,
            onBack = onBack,
            onEdit = {
              state.routine?.let {
                haptics.tap()
                onEditRoutine(it.id)
              }
            },
            onClone = {
              state.routine?.let {
                haptics.tap()
                cloneViewModel.open(ConfigurationCloneTarget.Routine(it.id, it.name))
              }
            },
        )
        RoutineDetailBody(
            state = state,
            onBack = onBack,
            onRetry = {
              haptics.tap()
              viewModel.retry()
            },
            onExerciseClick = {
              haptics.tap()
              onExerciseClick(it)
            },
            windowWidthClass = windowWidthClass,
        )
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
      )
    }
  }
  ConfigurationCloneDialog(
      state = cloneState,
      onNameChange = cloneViewModel::setName,
      onSave = cloneViewModel::save,
      onDismiss = cloneViewModel::dismiss,
  )
}

@Composable
internal fun RoutineDetailHeader(
    routine: RoutineDetailRoutine?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onClone: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  TopAppBar(
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
      },
      title = {
        Text(routine?.name ?: "Программа", maxLines = 1, overflow = TextOverflow.Ellipsis)
      },
      actions = {
        if (routine?.origin != "STANDARD" && routine != null) {
          CircleIconButton(
              icon = Icons.Rounded.Edit,
              contentDescription = "Редактировать программу",
              onClick = onEdit,
          )
        }
        if (routine != null) {
          Box {
            IconButton(onClick = { menuExpanded = true }) {
              Icon(Icons.Rounded.MoreVert, contentDescription = "Меню программы")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
              DropdownMenuItem(
                  text = { Text("Клонировать") },
                  onClick = {
                    menuExpanded = false
                    onClone()
                  },
              )
            }
          }
        }
      },
      colors =
          TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
  )
}

@Composable
internal fun RoutineDetailBody(
    state: RoutineDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    windowWidthClass: GymWindowWidthClass,
    modifier: Modifier = Modifier,
) {
  when {
    state.loading ->
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
    state.loadError ->
        RoutineDetailMessage(
            title = "Не удалось загрузить программу",
            description = "Проверьте подключение и попробуйте снова.",
            action = "Повторить",
            onAction = onRetry,
        )
    state.routine == null ->
        RoutineDetailMessage(
            title = "Программа не найдена",
            description = "Возможно, она была удалена или больше недоступна.",
            action = "Вернуться",
            onAction = onBack,
        )
    else ->
        RoutineDetailContent(
            routine = state.routine,
            onExerciseClick = onExerciseClick,
            windowWidthClass = windowWidthClass,
            modifier = modifier,
        )
  }
}

/** Pure display renderer used by detail and by the defensive standard editor. */
@Composable
internal fun RoutineDetailContent(
    routine: RoutineDetailRoutine,
    onExerciseClick: ((Long) -> Unit)?,
    windowWidthClass: GymWindowWidthClass,
    modifier: Modifier = Modifier,
    originContent: (@Composable () -> Unit)? = null,
    showGyms: Boolean = true,
    actionContent: (@Composable () -> Unit)? = null,
) {
  val horizontalPadding = if (windowWidthClass == GymWindowWidthClass.Compact) 16.dp else 24.dp
  LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { if (originContent != null) originContent() else RoutineOriginLabel(routine.origin) }
    if (showGyms) {
      item {
        GymCard(modifier = Modifier.fillMaxWidth()) {
          Text(
              "Залы",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(4.dp))
          Text(
              text =
                  routine.gymNames.takeIf { it.isNotEmpty() }?.joinToString()
                      ?: "Без ограничений по залу",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    item {
      Text(
          text = "Упражнения · ${routine.exercises.size}",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
      )
    }
    if (routine.exercises.isEmpty()) {
      item {
        GymCard(modifier = Modifier.fillMaxWidth()) {
          Text(
              "В программе пока нет упражнений.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      itemsIndexed(routine.exercises, key = { index, exercise -> "$index-${exercise.id}" }) {
          _,
          exercise ->
        RoutineDetailExerciseCard(exercise = exercise, onClick = onExerciseClick)
      }
    }
    if (actionContent != null) item { actionContent() }
  }
}

@Composable
private fun RoutineDetailExerciseCard(
    exercise: RoutineDetailExercise,
    onClick: ((Long) -> Unit)?,
) {
  val haptics = gymHaptics()
  var expanded by rememberSaveable(exercise.id, exercise.name) { mutableStateOf(false) }
  GymCard(
      modifier =
          Modifier.fillMaxWidth().animateContentSize(GymMotion.spatialDefault()).semantics {
            contentDescription = "Упражнение ${exercise.name}"
          },
      contentPadding = PaddingValues(16.dp),
  ) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                  stateDescription = if (expanded) "Подходы раскрыты" else "Подходы свернуты"
                }
                .clickable {
                  haptics.tap()
                  expanded = !expanded
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
      ExerciseAvatar(name = exercise.name, type = exercise.type)
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
            exercise.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            exercise.type.displayName(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            exercise.detailSummary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
          imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (onClick != null) {
        IconButton(
            onClick = {
              haptics.tap()
              onClick(exercise.id)
            }
        ) {
          Icon(Icons.Rounded.Info, contentDescription = "Открыть карточку упражнения")
        }
      }
    }
    if (expanded) {
      Spacer(Modifier.height(10.dp))
      Text(
          text = exercise.restSeconds?.let { "Отдых: $it сек" } ?: "Отдых: по умолчанию",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      exercise.plannedSets.forEachIndexed { index, set ->
        Text(
            text = "${index + 1}. ${set.display(exercise.type)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun RoutineOriginLabel(origin: String) {
  Text(
      text = if (origin == "STANDARD") "Стандартное" else "Личное",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun RoutineDetailMessage(
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
          Icons.Rounded.FitnessCenter,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Text(
          title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 16.dp),
      )
      Text(
          description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
      )
      TextButton(onClick = onAction) { Text(action) }
    }
  }
}

private fun RoutineDetailExercise.detailSummary(): String =
    "${plannedSets.size} ${setsWord(plannedSets.size)} · " +
        (restSeconds?.let { "отдых $it сек" } ?: "отдых по умолчанию")

private fun PlannedSet.display(type: ExerciseType): String =
    when (type) {
      ExerciseType.STRENGTH -> "${weightKg.display()} кг × ${reps ?: "—"}"
      ExerciseType.TIMED -> "${durationSec ?: "—"} сек"
      ExerciseType.CARDIO ->
          "${speedKmh.display()} км/ч · ${inclinePct.display()}% · ${durationSec?.let { "$it сек" } ?: "—"}"
    }

private fun Double?.display(): String =
    when (this) {
      null -> "—"
      else -> if (this % 1.0 == 0.0) toInt().toString() else toString()
    }
