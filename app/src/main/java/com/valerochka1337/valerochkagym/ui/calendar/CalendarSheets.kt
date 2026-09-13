package com.valerochka1337.valerochkagym.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton

/**
 * Нижняя шторка выбранного дня. Показывает все секции дня сразу: завершённые тренировки (тап —
 * детали), ad-hoc запланированные («Начать»/«Удалить»), ранее созданные повторяющиеся занятия
 * («Начать»/«Перенести»/«Отменить») и — для сегодня/будущего — кнопку «Запланировать».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayModalBottomSheet(
    day: DaySheetUi,
    onDismiss: () -> Unit,
    onWorkoutClick: (String) -> Unit,
    onStartAdHoc: (AdHocUi) -> Unit,
    onCancelAdHoc: (String) -> Unit,
    onMoveAdHoc: (AdHocUi) -> Unit,
    onStartRecurring: (Long) -> Unit,
    onCancelRecurring: (RecurringUi) -> Unit,
    onMoveRecurring: (RecurringUi) -> Unit,
    onPlan: () -> Unit,
    editingEnabled: Boolean,
) {
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState =
          rememberBottomSheetState(
              initialValue = SheetValue.Hidden,
              enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
          ),
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = day.title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )

      day.completed.forEach { workout ->
        SectionLabel("Проведено")
        GymCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onWorkoutClick(workout.id) },
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        ) {
          RowTitleTime(workout.name, workout.timeLabel)
        }
      }

      day.adHoc.forEach { item ->
        SectionLabel("Запланировано")
        GymCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
          RowTitleTime(item.routineName, item.timeLabel)
          Spacer(Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.canStart) {
              PillButton(
                  text = "Начать",
                  onClick = { onStartAdHoc(item) },
                  modifier = Modifier.weight(1f),
              )
            }
            TextButton(
                onClick = { onMoveAdHoc(item) },
                enabled = editingEnabled,
            ) {
              Text("Перенести")
            }
            TextButton(onClick = { onCancelAdHoc(item.planId) }, enabled = editingEnabled) {
              Text("Отменить")
            }
          }
        }
      }

      day.planMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

      day.recurring.forEach { rule ->
        SectionLabel("Из расписания")
        GymCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
          RowTitleTime(rule.routineName, rule.timeLabel)
          Spacer(Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rule.canStart) {
              PillButton(
                  text = "Начать",
                  onClick = { onStartRecurring(rule.routineId) },
                  modifier = Modifier.weight(1f),
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onMoveRecurring(rule) }, enabled = editingEnabled) {
              Text("Перенести")
            }
            TextButton(onClick = { onCancelRecurring(rule) }, enabled = editingEnabled) {
              Text("Отменить")
            }
          }
        }
      }

      if (day.completed.isEmpty() && day.adHoc.isEmpty() && day.recurring.isEmpty()) {
        Text(
            text = "В этот день ничего нет",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (day.allowPlan) {
        Spacer(Modifier.height(4.dp))
        PillButton(
            text = "Запланировать",
            onClick = onPlan,
            enabled = editingEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Пикер программы для планирования ad-hoc тренировки. Пустой список — подсказка создать программу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutinePickerSheet(
    routines: List<RoutinePickUi>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState =
          rememberBottomSheetState(
              initialValue = SheetValue.Hidden,
              enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
          ),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = "Выберите программу",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      if (routines.isEmpty()) {
        Text(
            text = "Нет программ — сначала создайте программу на вкладке «Тренировки».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(routines, key = { it.id }) { routine ->
            GymCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPick(routine.id) },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            ) {
              Text(
                  text = routine.name,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary,
  )
}

@Composable
private fun RowTitleTime(title: String, time: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
    )
    Text(
        text = time,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
