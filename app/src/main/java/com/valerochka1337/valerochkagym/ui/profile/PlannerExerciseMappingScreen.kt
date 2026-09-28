package com.valerochka1337.valerochkagym.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.plannermapping.PlannerExerciseMapping
import com.valerochka1337.valerochkagym.data.plannermapping.PlannerExerciseMappingRepository
import com.valerochka1337.valerochkagym.data.plannermapping.PlannerExerciseRole
import com.valerochka1337.valerochkagym.data.plannermapping.PlannerMovementClass
import com.valerochka1337.valerochkagym.data.plannermapping.PlannerSupportedGoal
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlannerExerciseMappingUiState(
    val loading: Boolean = true,
    val exercises: List<ExerciseEntity> = emptyList(),
    val localEquipmentIds: Map<String, List<String>> = emptyMap(),
    val mappings: Map<String, PlannerExerciseMapping> = emptyMap(),
    val editingId: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class PlannerExerciseMappingViewModel
@Inject
constructor(
    private val repository: PlannerExerciseMappingRepository,
    exercises: ExerciseDao,
) : ViewModel() {
  private val mutableState = MutableStateFlow(PlannerExerciseMappingUiState())
  val uiState: StateFlow<PlannerExerciseMappingUiState> =
      combine(mutableState, exercises.getAll(), exercises.observeAllRequirements()) {
              state,
              rows,
              requirements ->
            state.copy(
                exercises = rows.filter { it.isCustom && !it.archived }.sortedBy { it.name },
                localEquipmentIds =
                    requirements
                        .groupBy { it.exerciseId }
                        .mapNotNull { (exerciseId, links) ->
                          rows
                              .firstOrNull { it.id == exerciseId }
                              ?.syncId
                              ?.let { syncId -> syncId to links.map { it.equipmentId }.sorted() }
                        }
                        .toMap(),
            )
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableState.value)

  init {
    viewModelScope.launch {
      try {
        val mappings = repository.list().associateBy { it.exerciseId }
        mutableState.update { it.copy(loading = false, mappings = mappings) }
      } catch (_: Exception) {
        mutableState.update {
          it.copy(loading = false, error = "Не удалось загрузить классификации")
        }
      }
    }
  }

  fun edit(exerciseId: String) =
      mutableState.update { it.copy(editingId = exerciseId, error = null) }

  fun closeEditor() = mutableState.update { it.copy(editingId = null) }

  fun save(mapping: PlannerExerciseMapping) {
    if (mutableState.value.saving) return
    mutableState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      try {
        val saved = repository.save(mapping)
        mutableState.update {
          it.copy(
              saving = false,
              editingId = null,
              mappings = it.mappings + (saved.exerciseId to saved),
          )
        }
      } catch (_: Exception) {
        mutableState.update {
          it.copy(saving = false, error = "Не удалось сохранить классификацию")
        }
      }
    }
  }

  fun delete(exerciseId: String) {
    if (mutableState.value.saving) return
    mutableState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      try {
        repository.delete(exerciseId)
        mutableState.update {
          it.copy(saving = false, editingId = null, mappings = it.mappings - exerciseId)
        }
      } catch (_: Exception) {
        mutableState.update { it.copy(saving = false, error = "Не удалось удалить классификацию") }
      }
    }
  }
}

@Composable
fun PlannerExerciseMappingScreen(
    onBack: () -> Unit,
    viewModel: PlannerExerciseMappingViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  GlowBackground {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Классификация личных упражнений", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Классифицированные упражнения доступны для автоматического плана. Остальные остаются доступны в ручном редактировании.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
      if (!state.loading && state.exercises.isEmpty()) {
        item { Text("Личных упражнений пока нет.") }
      }
      items(state.exercises, key = { it.syncId }) { exercise ->
        val mapping = state.mappings[exercise.syncId]
        GymCard(modifier = Modifier.fillMaxWidth()) {
          Text(exercise.name, style = MaterialTheme.typography.titleMedium)
          Text(
              mapping?.let { "Классифицировано: ${it.movementClass.label()}" }
                  ?: "Нужна классификация для автоматического плана",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (state.editingId == exercise.syncId)
              MappingEditor(
                  exercise = exercise,
                  current = mapping,
                  localEquipmentIds = state.localEquipmentIds[exercise.syncId].orEmpty(),
                  saving = state.saving,
                  onSave = viewModel::save,
                  onDelete = { viewModel.delete(exercise.syncId) },
                  onCancel = viewModel::closeEditor,
              )
          else
              OutlinedButton(
                  onClick = { viewModel.edit(exercise.syncId) },
                  modifier = Modifier.heightIn(min = 48.dp).padding(top = 8.dp),
              ) {
                Text(if (mapping == null) "Классифицировать" else "Изменить классификацию")
              }
        }
      }
    }
  }
}

@Composable
private fun MappingEditor(
    exercise: ExerciseEntity,
    current: PlannerExerciseMapping?,
    localEquipmentIds: List<String>,
    saving: Boolean,
    onSave: (PlannerExerciseMapping) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
  var movement by
      androidx.compose.runtime.remember(current) {
        androidx.compose.runtime.mutableStateOf(
            current?.movementClass ?: PlannerMovementClass.HORIZONTAL_PUSH
        )
      }
  var roles by
      androidx.compose.runtime.remember(current) {
        androidx.compose.runtime.mutableStateOf(
            current?.roles?.toSet() ?: setOf(PlannerExerciseRole.PRIMARY)
        )
      }
  var goals by
      androidx.compose.runtime.remember(current) {
        androidx.compose.runtime.mutableStateOf(
            current?.supportedGoals?.toSet() ?: setOf(PlannerSupportedGoal.GENERAL_FITNESS)
        )
      }
  var type by
      androidx.compose.runtime.remember(current, exercise.type) {
        androidx.compose.runtime.mutableStateOf(current?.exerciseType ?: exercise.type)
      }
  Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Движение", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PlannerMovementClass.entries.forEach { choice ->
        FilterChip(
            selected = movement == choice,
            onClick = { movement = choice },
            label = { Text(choice.label()) },
        )
      }
    }
    MappingChoices("Роль", PlannerExerciseRole.entries.toList(), roles) { roles = it }
    MappingChoices("Цели", PlannerSupportedGoal.entries.toList(), goals) { goals = it }
    Text("Тип упражнения", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ExerciseType.entries.forEach { choice ->
        FilterChip(
            selected = type == choice,
            onClick = { type = choice },
            label = { Text(choice.name) },
        )
      }
    }
    Text(
        if (localEquipmentIds.isEmpty()) "Оборудование: не требуется"
        else "Оборудование: ${localEquipmentIds.joinToString()}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PillButton(
        text = if (saving) "Сохраняем…" else "Сохранить классификацию",
        onClick = {
          onSave(
              PlannerExerciseMapping(
                  exercise.syncId,
                  movement,
                  roles.sorted(),
                  goals.sorted(),
                  type,
                  localEquipmentIds,
                  current?.revision ?: 0,
              )
          )
        },
        enabled = !saving && roles.isNotEmpty() && goals.isNotEmpty(),
        modifier =
            Modifier.fillMaxWidth().semantics {
              contentDescription = "Сохранить классификацию: ${exercise.name}"
            },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = onCancel, enabled = !saving) { Text("Отмена") }
      if (current != null)
          OutlinedButton(onClick = onDelete, enabled = !saving) { Text("Удалить классификацию") }
    }
  }
}

@Composable
private fun <T : Enum<T>> MappingChoices(
    title: String,
    choices: List<T>,
    selected: Set<T>,
    onSelected: (Set<T>) -> Unit,
) {
  Text(title, style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    choices.forEach { choice ->
      FilterChip(
          selected = choice in selected,
          onClick = {
            onSelected(if (choice in selected) selected - choice else selected + choice)
          },
          label = { Text(choice.name) },
      )
    }
  }
}

private fun PlannerMovementClass.label(): String = name.lowercase().replace('_', ' ')
