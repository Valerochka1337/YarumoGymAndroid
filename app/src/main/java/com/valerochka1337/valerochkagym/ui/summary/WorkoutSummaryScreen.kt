package com.valerochka1337.valerochkagym.ui.summary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffort
import com.valerochka1337.valerochkagym.domain.PrResult
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.LoadingState
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.SaveWorkoutAsProgramDialog
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import java.math.BigDecimal

/**
 * Итоги завершённой тренировки: длительность, объём, новые рекорды и сводка упражнений. Если факт
 * разошёлся с программой — один раз предлагает обновить программу. [onDone] возвращает на главную.
 */
@Composable
fun WorkoutSummaryScreen(
    onDone: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutSummaryViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  // Один success-отклик на появление рекордов — вместе с их «впрыгивающими» карточками.
  val haptics = gymHaptics()
  LaunchedEffect(state.prs.isNotEmpty()) { if (state.prs.isNotEmpty()) haptics.success() }
  LaunchedEffect(Unit) {
    viewModel.saveEvents.collect {
      haptics.success()
      snackbarHostState.showSnackbar("Программа сохранена")
    }
  }
  LaunchedEffect(Unit) { viewModel.doneEvents.collect { onDone() } }

  GlowBackground(modifier = modifier) {
    if (state.loading) {
      LoadingState(label = "Подводим итоги…")
      return@GlowBackground
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Тренировка завершена",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(
            text = state.workoutName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item {
            StatsCard(
                durationSeconds = state.durationSeconds,
                volumeKg = state.volumeKg,
            )
          }

          if (state.prs.isNotEmpty()) {
            item {
              Text(
                  text = "Новые рекорды",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onBackground,
              )
            }
            items(state.prs, key = { it.exerciseId }) { pr ->
              PrCard(
                  pr = pr,
                  onClick = {
                    haptics.tap()
                    onExerciseClick(pr.exerciseId)
                  },
              )
            }
          }

          if (state.exercises.isNotEmpty()) {
            item {
              Text(
                  text = "Упражнения",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onBackground,
              )
            }
            items(state.exercises, key = { it.id }) { exercise ->
              GymCard(
                  modifier = Modifier.fillMaxWidth().animateItem(),
                  onClick = {
                    haptics.tap()
                    onExerciseClick(exercise.exerciseId)
                  },
                  contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  ExerciseAvatar(name = exercise.name)
                  Spacer(Modifier.width(12.dp))
                  Text(
                      text = exercise.name,
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                      color = MaterialTheme.colorScheme.onSurface,
                  )
                }
                if (exercise.setsSummary.isNotEmpty()) {
                  Spacer(Modifier.height(2.dp))
                  Text(
                      text = exercise.setsSummary,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
          if (state.canEditEffort) {
            item {
              WorkoutEffortCard(
                  selected = state.effortDraft,
                  isSaving = state.isSavingEffort,
                  error = state.effortError,
                  onSelect = {
                    haptics.tap()
                    viewModel.setEffort(it)
                  },
              )
            }
          }
        }

        WorkoutSummaryActions(onDone = viewModel::onDone, enabled = !state.isSavingEffort)
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }

  if (state.showSaveChoice) {
    SaveRoutineChoiceDialog(
        canReplace = state.canReplaceRoutine,
        isPreparing = state.isPreparingReplacement,
        error = state.saveAsProgramError,
        onCreate = viewModel::chooseCreateRoutine,
        onReplace = viewModel::chooseReplaceRoutine,
        onSkip = viewModel::skipSavingAndFinish,
        onDismiss = viewModel::dismissSaveChoice,
    )
  }
  if (state.showReplaceRoutineDialog) {
    AlertDialog(
        onDismissRequest = viewModel::dismissReplaceRoutine,
        title = { Text("Перезаписать программу?") },
        text = {
          Column {
            Text("Программа будет заменена только выполненными подходами этой тренировки.")
            state.saveAsProgramError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          }
        },
        confirmButton = {
          TextButton(
              onClick = viewModel::confirmRoutineReplace,
              enabled = !state.isSavingAsProgram,
              modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
          ) {
            Text(if (state.isSavingAsProgram) "Сохраняем…" else "Перезаписать")
          }
        },
        dismissButton = {
          TextButton(
              onClick = viewModel::dismissReplaceRoutine,
              enabled = !state.isSavingAsProgram,
              modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
          ) {
            Text("Отмена")
          }
        },
    )
  }
  if (state.showSaveAsProgramDialog) {
    SaveWorkoutAsProgramDialog(
        name = state.saveAsProgramName,
        isSaving = state.isSavingAsProgram,
        error = state.saveAsProgramError,
        onNameChange = viewModel::changeSaveAsProgramName,
        onConfirm = {
          haptics.tap()
          viewModel.confirmSaveAsProgram()
        },
        onDismiss = viewModel::dismissSaveAsProgram,
    )
  }
}

@Composable
internal fun WorkoutSummaryActions(onDone: () -> Unit, enabled: Boolean = true) {
  PillButton(
      text = "Готово",
      onClick = onDone,
      enabled = enabled,
      modifier =
          Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
  )
}

@Composable
internal fun WorkoutEffortCard(
    selected: WorkoutEffort?,
    isSaving: Boolean,
    error: String?,
    onSelect: (WorkoutEffort?) -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text("Насколько тяжёлой была тренировка?", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      WorkoutEffort.entries.forEach { effort ->
        FilterChip(
            selected = selected == effort,
            onClick = { onSelect(if (selected == effort) null else effort) },
            enabled = !isSaving,
            label = { Text(effortLabel(effort)) },
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        )
      }
    }
    if (selected != null) {
      TextButton(
          onClick = { onSelect(null) },
          enabled = !isSaving,
          modifier = Modifier.sizeIn(minHeight = 48.dp),
      ) {
        Text("Очистить")
      }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}

private fun effortLabel(effort: WorkoutEffort): String =
    when (effort) {
      WorkoutEffort.EASY -> "Легко"
      WorkoutEffort.MODERATE -> "Умеренно"
      WorkoutEffort.HARD -> "Тяжело"
    }

@Composable
internal fun SaveRoutineChoiceDialog(
    canReplace: Boolean,
    isPreparing: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onReplace: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Сохранить программу?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              if (canReplace) {
                "Создайте новую программу или перезапишите текущую личную программу выполненными подходами."
              } else {
                "Создайте новую программу из выполненных подходов."
              }
          )
          error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        TextButton(
            onClick = onCreate,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
          Text("Новая")
        }
      },
      dismissButton = {
        Column(horizontalAlignment = Alignment.End) {
          if (canReplace) {
            TextButton(
                onClick = onReplace,
                enabled = !isPreparing,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
              Text(if (isPreparing) "Проверяем…" else "Перезаписать")
            }
          }
          TextButton(
              onClick = onSkip,
              modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
          ) {
            Text("Не сохранять")
          }
        }
      },
  )
}

@Composable
private fun StatsCard(
    durationSeconds: Long,
    volumeKg: Double,
) {
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(20.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth()) {
      StatTile(
          icon = Icons.Rounded.Timer,
          contentDescription = "Длительность",
          value = formatDuration(durationSeconds),
          modifier = Modifier.weight(1f),
      )
      StatTile(
          icon = Icons.Rounded.FitnessCenter,
          contentDescription = "Объём",
          value = "${formatNumber(volumeKg)} кг",
          modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    contentDescription: String,
    value: String,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = value,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun PrCard(pr: PrResult, onClick: () -> Unit) {
  // Каждая строка рекорда «впрыгивает» отдельно (expressive bouncy scale + затухание) —
  // акцент на достижении. Анимация запускается один раз при первом появлении карточки.
  val appear = remember { MutableTransitionState(false).apply { targetState = true } }
  AnimatedVisibility(
      visibleState = appear,
      enter =
          scaleIn(animationSpec = GymMotion.spatialDefault(), initialScale = 0.8f) +
              fadeIn(animationSpec = GymMotion.effectsDefault()),
  ) {
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.EmojiEvents,
            contentDescription = "Личный рекорд",
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = pr.exerciseName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatNumber(pr.weightKg)} кг",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

private fun formatDuration(totalSeconds: Long): String {
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}

/** Число без хвостовых нулей, локаль-независимо. */
private fun formatNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
