@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.valerochka1337.valerochkagym.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.KeyExercisePriority
import com.valerochka1337.valerochkagym.data.db.entity.PlannerExercisePreference
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.PlanningChoiceSheet
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  ProfileScreenContent(
      state = state,
      onBack = onBack,
      onGoal = viewModel::setGoal,
      onExperience = viewModel::setExperience,
      onSex = viewModel::setSex,
      onBirthDate = viewModel::setBirthDate,
      onSessions = viewModel::setSessions,
      onDuration = viewModel::setDuration,
      onConstraints = viewModel::setConstraints,
      onRepRange = viewModel::setRepRange,
      onEquipment = viewModel::toggleEquipment,
      accountContent = { com.valerochka1337.valerochkagym.ui.account.AccountCard() },
      onKeyExercise = {
        haptics.tap()
        viewModel.toggleKeyExercise(it)
      },
      onKeyPriority = { id, priority ->
        haptics.tap()
        viewModel.setKeyExercisePriority(id, priority)
      },
      onRemoveKeyExercise = {
        haptics.tap()
        viewModel.removeKeyExercise(it)
      },
      onKeySheet = {
        haptics.tap()
        viewModel.setKeyExerciseSheet(it)
      },
      onPlannerPreference = { id, preference ->
        haptics.tap()
        viewModel.setPlannerPreference(id, preference)
      },
      onPlannerPreferenceSheet = {
        haptics.tap()
        viewModel.setPlannerPreferenceSheet(it)
      },
      modifier = modifier,
  )
}

@Composable
internal fun ProfileScreenContent(
    state: ProfileEditorUiState,
    onBack: () -> Unit,
    onGoal: (TrainingGoal?) -> Unit,
    onExperience: (ExperienceLevel?) -> Unit,
    onSex: (ProfileSex?) -> Unit,
    onBirthDate: (String) -> Unit,
    onSessions: (String) -> Unit,
    onDuration: (String) -> Unit,
    onConstraints: (String) -> Unit,
    onEquipment: (String) -> Unit,
    accountContent: @Composable () -> Unit = {},
    onRepRange: (String, String) -> Unit = { _, _ -> },
    onKeyExercise: (Long) -> Unit = {},
    onKeyPriority: (Long, KeyExercisePriority) -> Unit = { _, _ -> },
    onRemoveKeyExercise: (String) -> Unit = {},
    onKeySheet: (Boolean) -> Unit = {},
    onPlannerPreference: (Long, PlannerExercisePreference?) -> Unit = { _, _ -> },
    onPlannerPreferenceSheet: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
  GlowBackground(modifier = modifier) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
      Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
          Text("Профиль и аккаунт", style = MaterialTheme.typography.headlineLarge)
        }
        if (state.isLoading) {
          Text(
              "Загружаем профиль…",
              modifier =
                  Modifier.padding(horizontal = horizontalPadding).semantics {
                    contentDescription = "Загружаем профиль"
                  },
          )
          return@Column
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          ProfileChoiceCard(
              "Цель тренировок",
              TrainingGoal.entries,
              state.trainingGoal,
              ::goalLabel,
              onGoal,
          )
          GymCard(modifier = Modifier.fillMaxWidth()) {
            Text("Диапазон повторений", style = MaterialTheme.typography.titleMedium)
            Text(
                "Необязательно. Тренер учитывает этот ориентир вместе с историей упражнения и самочувствием.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf(3 to 6, 6 to 12, 8 to 14, 12 to 20).forEach { (min, max) ->
                FilterChip(
                    selected =
                        state.preferredRepMin == min.toString() &&
                            state.preferredRepMax == max.toString(),
                    onClick = { onRepRange(min.toString(), max.toString()) },
                    label = { Text("$min–$max") },
                )
              }
              FilterChip(
                  selected = state.preferredRepMin.isBlank() && state.preferredRepMax.isBlank(),
                  onClick = { onRepRange("", "") },
                  label = { Text("Не задан") },
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  state.preferredRepMin,
                  { onRepRange(it, state.preferredRepMax) },
                  modifier = Modifier.weight(1f),
                  label = { Text("От") },
                  singleLine = true,
                  keyboardOptions =
                      androidx.compose.foundation.text.KeyboardOptions(
                          keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                      ),
              )
              OutlinedTextField(
                  state.preferredRepMax,
                  { onRepRange(state.preferredRepMin, it) },
                  modifier = Modifier.weight(1f),
                  label = { Text("До") },
                  singleLine = true,
                  keyboardOptions =
                      androidx.compose.foundation.text.KeyboardOptions(
                          keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                      ),
              )
            }
          }
          if (state.trainingGoal == TrainingGoal.STRENGTH) {
            GymCard(modifier = Modifier.fillMaxWidth()) {
              Text(
                  "Ключевые упражнения",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
              )
              Text(
                  "Необязательно. Выберите до пяти упражнений для планирования.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              PillButton(
                  text = "Выбрать упражнения (${state.keyExercises.size}/5)",
                  onClick = { onKeySheet(true) },
                  compact = true,
              )
              state.keyExercises.forEach { choice ->
                val name =
                    state.strengthExercises.firstOrNull { it.id == choice.exerciseId }?.name
                        ?: "Недоступное упражнение"
                Column(modifier = Modifier.fillMaxWidth()) {
                  Text(name)
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choice.exerciseId != null) {
                      FilterChip(
                          selected = choice.priority == KeyExercisePriority.HIGH,
                          onClick = {
                            onKeyPriority(
                                choice.exerciseId,
                                if (choice.priority == KeyExercisePriority.HIGH)
                                    KeyExercisePriority.NORMAL
                                else KeyExercisePriority.HIGH,
                            )
                          },
                          label = {
                            Text(
                                if (choice.priority == KeyExercisePriority.HIGH) "Высокий"
                                else "Обычный"
                            )
                          },
                      )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { onRemoveKeyExercise(choice.exerciseSyncId) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                      Text("Удалить")
                    }
                  }
                }
              }
            }
          }
          GymCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Предпочтения упражнений",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Выберите чаще, реже или никогда для любого доступного упражнения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PillButton(
                text = "Настроить (${state.plannerPreferences.size})",
                onClick = { onPlannerPreferenceSheet(true) },
                compact = true,
            )
          }
          ProfileChoiceCard(
              "Опыт",
              ExperienceLevel.entries,
              state.experienceLevel,
              ::experienceLabel,
              onExperience,
          )
          ProfileChoiceCard("Пол", ProfileSex.entries, state.sex, ::sexLabel, onSex)
          GymCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "О себе",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            BirthDateField(state.birthDate, onBirthDate)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.plannedSessionsPerWeek,
                onSessions,
                Modifier.fillMaxWidth(),
                label = { Text("Тренировок в неделю") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.preferredSessionDurationMinutes,
                onDuration,
                Modifier.fillMaxWidth(),
                label = { Text("Длительность тренировки, мин") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.manualConstraints,
                onConstraints,
                Modifier.fillMaxWidth(),
                label = { Text("Ограничения и предпочтения") },
                minLines = 3,
            )
          }
          EquipmentDropdown(state.equipmentIds, onEquipment)
          accountContent()
          state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "Ошибка: $it" },
            )
          }
          Text(
              if (state.isSaving) "Сохраняем…" else "Изменения сохраняются автоматически",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
  if (state.showKeyExercises && state.trainingGoal == TrainingGoal.STRENGTH) {
    PlanningChoiceSheet(
        title = "Ключевые упражнения",
        choices = state.strengthExercises.map { it.id.toString() to it.name },
        selected = state.keyExercises.mapNotNull { it.exerciseId?.toString() }.toSet(),
        onToggle = { id -> onKeyExercise(id.toLong()) },
        onDismiss = { onKeySheet(false) },
    )
  }
  if (state.showPlannerPreferences) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onPlannerPreferenceSheet(false) },
        title = { Text("Предпочтения упражнений") },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            state.plannerExercises.forEach { exercise ->
              val selected =
                  state.plannerPreferences.firstOrNull { it.exerciseId == exercise.id }?.preference
              Text(exercise.name, style = MaterialTheme.typography.titleSmall)
              FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                PlannerExercisePreference.entries.forEach { preference ->
                  FilterChip(
                      selected = selected == preference,
                      onClick = {
                        onPlannerPreference(
                            exercise.id,
                            if (selected == preference) null else preference,
                        )
                      },
                      label = { Text(plannerPreferenceLabel(preference)) },
                      modifier =
                          Modifier.semantics {
                            contentDescription =
                                "${exercise.name}: ${plannerPreferenceLabel(preference)}"
                          },
                  )
                }
              }
              Spacer(Modifier.height(12.dp))
            }
          }
        },
        confirmButton = {
          PillButton(
              text = "Готово",
              onClick = { onPlannerPreferenceSheet(false) },
              compact = true,
          )
        },
    )
  }
}

private fun plannerPreferenceLabel(value: PlannerExercisePreference) =
    when (value) {
      PlannerExercisePreference.MORE -> "Чаще"
      PlannerExercisePreference.LESS -> "Реже"
      PlannerExercisePreference.NEVER -> "Никогда"
    }

@Composable
private fun <T> ProfileChoiceCard(
    title: String,
    entries: Iterable<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(expanded, { expanded = it }) {
    OutlinedTextField(
        value = selected?.let(label) ?: "Не задано",
        onValueChange = {},
        readOnly = true,
        label = { Text(title) },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        modifier =
            Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded, { expanded = false }) {
      DropdownMenuItem(
          text = { Text("Не задано") },
          onClick = {
            onSelect(null)
            expanded = false
          },
      )
      entries.forEach { entry ->
        DropdownMenuItem(
            text = { Text(label(entry)) },
            onClick = {
              onSelect(entry)
              expanded = false
            },
        )
      }
    }
  }
}

@Composable
internal fun EquipmentDropdown(selected: Set<String>, onToggle: (String) -> Unit) {
  val catalog by LocalEquipmentCatalog.state.collectAsStateWithLifecycle()
  var expanded by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  ExposedDropdownMenuBox(
      expanded,
      {
        expanded = it
        query = ""
      },
  ) {
    OutlinedTextField(
        value = if (expanded) query else "Выбрано: ${selected.size}",
        onValueChange = { query = it },
        readOnly = !expanded,
        label = { Text(if (expanded) "Поиск оборудования" else "Оборудование") },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        modifier =
            Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
    )
    ExposedDropdownMenu(
        expanded,
        { expanded = false },
        modifier = Modifier.heightIn(max = 320.dp),
    ) {
      val search = com.valerochka1337.valerochkagym.domain.TextSearch(query)
      val choices =
          catalog
              .filter { !it.archived || it.equipment.id in selected }
              .map { it.equipment }
              .filter { search.matches(listOf(it.name, it.group) + it.synonyms) }
              .sortedBy { it.name }
      if (choices.isEmpty())
          DropdownMenuItem(text = { Text("Ничего не найдено") }, onClick = {}, enabled = false)
      choices.forEach { equipment ->
        DropdownMenuItem(
            text = { Text(equipment.name) },
            onClick = { onToggle(equipment.id) },
            leadingIcon = { Checkbox(equipment.id in selected, onCheckedChange = null) },
        )
      }
    }
  }
}

@Composable
internal fun BirthDateField(value: String, onChange: (String) -> Unit) {
  var showPicker by rememberSaveable { mutableStateOf(false) }
  val date = remember(value) { runCatching { LocalDate.parse(value) }.getOrNull() }
  OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
    Text(
        "Дата рождения: " +
            (date?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                ?: "Не задана")
    )
  }
  if (showPicker) {
    val today = LocalDate.now()
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis =
                date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = 1900..today.year,
            selectableDates =
                object : SelectableDates {
                  override fun isSelectableDate(utcTimeMillis: Long) =
                      Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <=
                          today
                },
        )
    DatePickerDialog(
        onDismissRequest = { showPicker = false },
        confirmButton = {
          TextButton(
              enabled = state.selectedDateMillis != null,
              onClick = {
                state.selectedDateMillis?.let {
                  onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                }
                showPicker = false
              },
          ) {
            Text("Выбрать")
          }
        },
        dismissButton = {
          Row {
            TextButton(
                onClick = {
                  onChange("")
                  showPicker = false
                }
            ) {
              Text("Очистить")
            }
            TextButton(onClick = { showPicker = false }) { Text("Отмена") }
          }
        },
    ) {
      DatePicker(state)
    }
  }
}

private fun goalLabel(value: TrainingGoal) =
    when (value) {
      TrainingGoal.STRENGTH -> "Сила"
      TrainingGoal.MUSCLE_GAIN -> "Мышцы"
      TrainingGoal.FAT_LOSS -> "Снижение веса"
      TrainingGoal.GENERAL_FITNESS -> "Общая форма"
      TrainingGoal.ENDURANCE -> "Выносливость"
      TrainingGoal.OTHER -> "Другое"
    }

private fun experienceLabel(value: ExperienceLevel) =
    when (value) {
      ExperienceLevel.BEGINNER -> "Начинающий"
      ExperienceLevel.INTERMEDIATE -> "Средний"
      ExperienceLevel.ADVANCED -> "Продвинутый"
    }

private fun sexLabel(value: ProfileSex) =
    when (value) {
      ProfileSex.FEMALE -> "Женский"
      ProfileSex.MALE -> "Мужской"
      ProfileSex.PREFER_NOT_TO_SAY -> "Не указывать"
    }
