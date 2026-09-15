package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.PlanningChoiceField
import com.valerochka1337.valerochkagym.ui.components.PlanningChoiceSheet
import com.valerochka1337.valerochkagym.ui.components.PlanningDateTimeFields
import com.valerochka1337.valerochkagym.ui.components.PlanningScreen
import com.valerochka1337.valerochkagym.ui.components.rememberDeviceTimeZone
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.profile.AiProfilePromptDialog

@Composable
fun CalendarAiScreen(
    onBack: () -> Unit,
    onOpenProposal: (String) -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: CalendarAiViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  val zone = rememberDeviceTimeZone()
  LaunchedEffect(viewModel, zone) { viewModel.synchronizeDeviceTimeZone() }
  LaunchedEffect(viewModel, onBack) { viewModel.saved.collect { onBack() } }
  LaunchedEffect(viewModel, onOpenProposal) { viewModel.openProposal.collect(onOpenProposal) }
  LaunchedEffect(viewModel, onOpenProfile) { viewModel.openProfile.collect { onOpenProfile() } }
  CalendarAiContent(
      state = state,
      onBack = onBack,
      onDate = viewModel::setDate,
      onTime = viewModel::setTime,
      onZone = viewModel::setTimeZone,
      onGym = viewModel::toggleGym,
      onExcludedExercise = viewModel::toggleExcludedExercise,
      onExcludedEquipment = viewModel::toggleExcludedEquipment,
      onPriorityMuscle = viewModel::togglePriorityMuscle,
      onIncludeNotes = viewModel::setIncludeNotes,
      onDuration = viewModel::setDuration,
      onCurrentState = viewModel::setCurrentState,
      onPreferences = viewModel::setPreferences,
      onGenerate = {
        haptics.confirm()
        viewModel.generate()
      },
      onPromptVisible = viewModel::acknowledgeProfilePrompt,
      onPromptFill = viewModel::fillProfileFromPrompt,
      onPromptContinue = { viewModel.continueAfterProfilePrompt(it) },
      onPromptDisable = { viewModel.continueAfterProfilePrompt(it, disableFuturePrompts = true) },
      onPromptDismiss = viewModel::dismissProfilePrompt,
  )
}

@Composable
internal fun CalendarAiContent(
    state: CalendarAiUiState,
    onBack: () -> Unit,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onZone: (String) -> Unit,
    onGym: (String) -> Unit,
    onExcludedExercise: (String) -> Unit,
    onExcludedEquipment: (String) -> Unit,
    onPriorityMuscle: (String) -> Unit,
    onIncludeNotes: (Boolean) -> Unit,
    onDuration: (String) -> Unit,
    onCurrentState: (String) -> Unit,
    onPreferences: (String) -> Unit,
    onGenerate: () -> Unit,
    onPromptVisible: (String) -> Unit,
    onPromptFill: (String) -> Unit,
    onPromptContinue: (String) -> Unit,
    onPromptDisable: (String) -> Unit,
    onPromptDismiss: (String) -> Unit,
) {
  var extras by rememberSaveable { mutableStateOf(false) }
  PlanningScreen(
      "Тренировка с ИИ",
      onBack,
      bottomBar = {
        PillButton(
            text = if (state.generating) "Сохраняем заявку…" else "Начать расчёт",
            onClick = onGenerate,
            enabled = !state.generating,
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics {
                  contentDescription = "Начать расчёт"
                },
        )
      },
  ) {
    Text(
        "Укажите, когда и где хотите заниматься. ИИ предложит план — вы сможете проверить и изменить его перед добавлением в календарь.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CalendarAiTimeCard(state.form, onDate, onTime, onZone, onDuration)
    ChoiceCard("Залы", state.gyms, state.form.gymIds, onGym, "Доступных залов нет.")
    if (state.form.gymIds.isEmpty()) Text("Без привязки к залу: доступны все упражнения")
    TextButton(onClick = { extras = !extras }) {
      Text(if (extras) "Скрыть пожелания" else "Настроить пожелания и исключения")
    }
    if (extras) {
      ExcludedExercises(
          "Исключить упражнения",
          state.exercises,
          state.form.excludedExerciseIds,
          onExcludedExercise,
      )
      ChoiceCard(
          "Исключить оборудование",
          state.equipment,
          state.form.excludedEquipmentIds,
          onExcludedEquipment,
          "Оборудование не найдено.",
      )
      ChoiceCard(
          "Приоритетные мышцы",
          state.muscles,
          state.form.priorityMuscles,
          onPriorityMuscle,
          null,
      )
      GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Использовать заметки", style = MaterialTheme.typography.titleMedium)
            Text(
                "Заметки завершённых тренировок и личные подсказки помогут составить запрос.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
              checked = state.form.includeNotes,
              onCheckedChange = onIncludeNotes,
              modifier = Modifier.semantics { contentDescription = "Использовать заметки" },
          )
        }
      }
      GymCard(modifier = Modifier.fillMaxWidth()) {
        Text("Дополнительно", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.form.currentState,
            onValueChange = onCurrentState,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Текущее состояние (необязательно)") },
            minLines = 2,
        )
        OutlinedTextField(
            value = state.form.preferences,
            onValueChange = onPreferences,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Предпочтения (необязательно)") },
            minLines = 2,
        )
      }
    }
    state.error?.let { message ->
      Text(
          message,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.semantics { contentDescription = "Ошибка: $message" },
      )
    }
  }
  state.profilePrompt?.let { prompt ->
    AiProfilePromptDialog(
        token = prompt.token,
        onVisible = onPromptVisible,
        onFillProfile = onPromptFill,
        onContinue = onPromptContinue,
        onDisable = onPromptDisable,
        onDismiss = onPromptDismiss,
    )
  }
}

@Composable
private fun CalendarAiTimeCard(
    form: CalendarAiForm,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onZone: (String) -> Unit,
    onDuration: (String) -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text("Когда тренироваться", style = MaterialTheme.typography.titleMedium)
    PlanningDateTimeFields(form.date, form.time, form.timeZoneId, onDate, onTime, onZone)
    OutlinedTextField(
        value = form.availableDurationMinutes,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        onValueChange = onDuration,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.medium,
        label = { Text("Желаемая длительность, мин") },
        supportingText = {
          Text(
              "Постараемся приблизиться к этому времени. Оценка включает подходы, отдых и переходы; разминку — если она есть в плане."
          )
        },
        singleLine = true,
    )
  }
}

@Composable
private fun ChoiceCard(
    title: String,
    choices: List<CalendarAiChoice>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    emptyMessage: String?,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    if (choices.isNotEmpty() && (title != "Залы" || choices.size > 4)) {
      PlanningChoiceField(
          title,
          choices.map { it.id to it.label },
          selected,
          onToggle,
          emptyText = if (title.startsWith("Исключить")) "Без исключений" else "На усмотрение ИИ",
      )
      return@GymCard
    }
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (choices.isEmpty()) {
      if (emptyMessage != null)
          Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (title == "Залы" && choices.size <= 4) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
          FilterChip(
              selected = choice.id in selected,
              onClick = { onToggle(choice.id) },
              label = { Text(choice.label) },
              modifier = Modifier.heightIn(min = 48.dp),
          )
        }
      }
    }
  }
}

@Composable
internal fun ExcludedExercises(
    title: String,
    choices: List<CalendarAiChoice>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  val haptics = gymHaptics()
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (selected.isEmpty())
        Text("Без исключений", color = MaterialTheme.colorScheme.onSurfaceVariant)
    selected.forEach { id ->
      val name = choices.firstOrNull { it.id == id }?.label ?: "Недоступное упражнение"
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        IconButton(
            onClick = {
              haptics.toggle(false)
              onToggle(id)
            }
        ) {
          Icon(Icons.Rounded.Close, "Убрать из исключений: $name")
        }
      }
    }
    TextButton(
        onClick = {
          haptics.tap()
          open = true
        }
    ) {
      Text("Добавить исключение")
    }
  }
  if (open)
      PlanningChoiceSheet(
          title,
          choices.filterNot { it.archived || it.id in selected }.map { it.id to it.label },
          emptySet(),
          { id -> if (id !in selected) onToggle(id) },
          { open = false },
          singleChoice = true,
      )
}
