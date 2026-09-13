package com.valerochka1337.valerochkagym.ui.routine

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.DragHandle
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip
import com.valerochka1337.valerochkagym.ui.components.LoadingState
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.components.rememberGymReorderableLazyListState
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import sh.calvin.reorderable.ReorderableItem

/**
 * Полноэкранный редактор программы: имя, список упражнений с подходами и отдыхом. По событию
 * [RoutineEditorViewModel.saved] экран закрывается через [onBack]. [onAddExercise] открывает
 * библиотеку в режиме выбора упражнения.
 */
@Composable
fun RoutineEditorScreen(
    onBack: () -> Unit,
    onAddExercise: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineEditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  val lazyListState = rememberLazyListState()
  val reorderableLazyListState =
      rememberGymReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = state.exercises.indexOfReorderKey(from.key)
        val toIndex = state.exercises.indexOfReorderKey(to.key)
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
          // Reorderable expects the backing list to change before this callback returns.
          viewModel.moveExercise(fromIndex, toIndex)
          haptics.stepFrequent()
        }
      }
  BackHandler(enabled = state.isSaving) {}

  LaunchedEffect(Unit) { viewModel.saved.collect { onBack() } }

  GlowBackground(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      EditorHeader(
          isNew = state.isNew,
          isReadOnly = state.origin == "STANDARD",
          routineName = state.name,
          canSave = state.isValid,
          isSaving = state.isSaving,
          onBack = onBack,
          onSave = viewModel::save,
      )

      if (state.isLoading) {
        LoadingState(label = "Загружаем программу…", modifier = Modifier.weight(1f))
        return@Column
      }

      if (state.origin == "STANDARD") {
        RoutineDetailContent(
            routine = state.toDetailRoutine(),
            onExerciseClick = null,
            windowWidthClass =
                com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass.Compact,
            modifier = Modifier.weight(1f),
        )
        return@Column
      }

      com.valerochka1337.valerochkagym.ui.components.CatalogOriginRow(
          state.origin,
          "routine",
          state.syncId,
      )
      OutlinedTextField(
          value = state.name,
          onValueChange = viewModel::setName,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
          singleLine = true,
          enabled = !state.isSaving && state.origin != "STANDARD",
          label = { Text("Название программы") },
          shape = MaterialTheme.shapes.medium,
      )

      Spacer(Modifier.height(12.dp))

      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          state = lazyListState,
          contentPadding =
              PaddingValues(
                  start = 24.dp,
                  end = 24.dp,
                  top = 4.dp,
                  bottom = 96.dp,
              ),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          GymSelectionCard(
              state = state,
              onToggle = viewModel::toggleGym,
          )
        }

        itemsIndexed(
            state.exercises,
            // В программе одно и то же упражнение может встречаться несколько раз,
            // поэтому используем отдельный стабильный id строки, а не exerciseId.
            key = { _, exercise -> exercise.editorId },
        ) { index, exercise ->
          ReorderableItem(
              state = reorderableLazyListState,
              key = exercise.editorId,
          ) { isDragging ->
            val reorderableItemScope = this
            val moveActions = buildList {
              if (index > 0) {
                add(
                    CustomAccessibilityAction("Переместить выше") {
                      viewModel.moveExercise(index, index - 1)
                      true
                    },
                )
              }
              if (index < state.exercises.lastIndex) {
                add(
                    CustomAccessibilityAction("Переместить ниже") {
                      viewModel.moveExercise(index, index + 1)
                      true
                    },
                )
              }
            }
            ExerciseCard(
                modifier =
                    Modifier.animateItem(placementSpec = GymMotion.spatialDefault())
                        .then(
                            if (isDragging) {
                              Modifier.border(
                                  width = 1.dp,
                                  color = MaterialTheme.colorScheme.primary,
                                  shape = GymCardShape,
                              )
                            } else {
                              Modifier
                            },
                        )
                        .semantics { customActions = moveActions },
                exercise = exercise,
                dragHandle = {
                  DragHandle(
                      reorderableItemScope = reorderableItemScope,
                      onDragStarted = haptics::dragStart,
                      onDragStopped = haptics::dragEnd,
                  )
                },
                onRemove = { viewModel.removeExercise(index) },
                onRestChange = { viewModel.setRest(index, it) },
                onAddSet = { viewModel.addPlannedSet(index) },
                onRemoveSet = { setIndex -> viewModel.removePlannedSet(index, setIndex) },
                onSetChange = { setIndex, set -> viewModel.updatePlannedSet(index, setIndex, set) },
            )
          }
        }

        item {
          TextButton(
              onClick = onAddExercise,
              enabled = !state.isSaving && state.origin != "STANDARD",
              modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Упражнение")
          }
        }
      }
    }
  }
}

@Composable
private fun GymSelectionCard(
    state: RoutineEditorUiState,
    onToggle: (String) -> Unit,
) {
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(18.dp),
  ) {
    Text(
        text = "Залы",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text =
            if (state.selectedGymIds.isEmpty()) {
              "Без ограничений — доступен весь каталог"
            } else {
              "Доступны упражнения, которые есть сразу во всех выбранных залах"
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.gyms.isNotEmpty()) {
      Spacer(Modifier.height(10.dp))
      FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        state.gyms.forEach { gym ->
          GymFilterChip(
              selected = gym.id in state.selectedGymIds,
              onClick = { onToggle(gym.id) },
              label = gym.name,
          )
        }
      }
    } else {
      Spacer(Modifier.height(8.dp))
      Text(
          text = "Залы можно создать в настройках",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (state.conflictingExercises.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      Text(
          text = "Недоступно: " + state.conflictingExercises.joinToString { it.exerciseName },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
      )
      Text(
          text = "Уберите эти упражнения или измените выбранные залы.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    state.saveError?.let { error ->
      Spacer(Modifier.height(8.dp))
      Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun EditorHeader(
    isNew: Boolean,
    isReadOnly: Boolean,
    routineName: String,
    canSave: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack, enabled = !isSaving) {
      Icon(
          Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Назад",
          tint = MaterialTheme.colorScheme.onBackground,
      )
    }
    Spacer(Modifier.width(4.dp))
    Text(
        text = if (isNew) "Новая программа" else if (isReadOnly) routineName else "Редактирование",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.weight(1f),
    )
    if (!isReadOnly) {
      TextButton(onClick = onSave, enabled = canSave) {
        Text(if (isSaving) "Сохраняем…" else "Сохранить")
      }
    }
  }
}

@Composable
internal fun ExerciseCard(
    exercise: EditorExercise,
    dragHandle: @Composable () -> Unit,
    onRemove: () -> Unit,
    onRestChange: (Int?) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (Int) -> Unit,
    onSetChange: (Int, PlannedSet) -> Unit,
    modifier: Modifier = Modifier,
) {
  val haptics = gymHaptics()
  var expanded by rememberSaveable(exercise.editorId) { mutableStateOf(false) }
  GymCard(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Row(
          modifier =
              Modifier.weight(1f)
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
        ExerciseAvatar(name = exercise.exerciseName, type = exercise.exerciseType)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
              text = exercise.exerciseName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
              text = exercise.exerciseType.displayName(),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
              text = exercise.compactSummary(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onRemove) {
        Icon(
            Icons.Default.Delete,
            contentDescription = "Удалить упражнение",
            tint = MaterialTheme.colorScheme.error,
        )
      }
      dragHandle()
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(GymMotion.spatialDefault()) + fadeIn(GymMotion.effectsFast()),
        exit = shrinkVertically(GymMotion.spatialDefault()) + fadeOut(GymMotion.effectsFast()),
    ) {
      Column {
        Spacer(Modifier.height(8.dp))

        NumberField(
            value = exercise.restSeconds.toField(),
            onValueChange = { onRestChange(it.toIntOrNull()) },
            modifier = Modifier.fillMaxWidth(),
            label = "Отдых, сек",
        )

        Spacer(Modifier.height(12.dp))

        exercise.plannedSets.forEachIndexed { setIndex, set ->
          PlannedSetRow(
              type = exercise.exerciseType,
              number = setIndex + 1,
              set = set,
              canRemove = exercise.plannedSets.size > 1,
              onChange = { onSetChange(setIndex, it) },
              onRemove = { onRemoveSet(setIndex) },
          )
          Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = onAddSet) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Подход")
        }
      }
    }
  }
}

private fun EditorExercise.compactSummary(): String {
  val sets = plannedSets.size
  val rest = restSeconds?.let { "отдых $it сек" } ?: "отдых по умолчанию"
  return "$sets ${setsWord(sets)} · $rest"
}

/** Сопоставляет стабильный key карточки с индексом упражнения, игнорируя служебные item списка. */
internal fun List<EditorExercise>.indexOfReorderKey(key: Any): Int = indexOfFirst { exercise ->
  exercise.editorId == key
}

internal fun setsWord(count: Int): String {
  val mod100 = count % 100
  val mod10 = count % 10
  return when {
    mod100 in 11..14 -> "подходов"
    mod10 == 1 -> "подход"
    mod10 in 2..4 -> "подхода"
    else -> "подходов"
  }
}

@Composable
private fun PlannedSetRow(
    type: ExerciseType,
    number: Int,
    set: PlannedSet,
    canRemove: Boolean,
    onChange: (PlannedSet) -> Unit,
    onRemove: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    SetNumberBadge(number)
    Spacer(Modifier.width(10.dp))
    com.valerochka1337.valerochkagym.ui.components.PlannedSetFields(
        type,
        set,
        onChange,
        Modifier.weight(1f),
    )
    IconButton(onClick = onRemove, enabled = canRemove) {
      Icon(
          Icons.Default.Close,
          contentDescription = "Удалить подход",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Номер подхода в виде компактного кружка вместо надписи «Подход N». */
@Composable
private fun SetNumberBadge(number: Int) {
  Box(
      modifier =
          Modifier.size(30.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = number.toString(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

private fun Int?.toField(): String = this?.toString() ?: ""

private fun Double?.toField(): String =
    when {
      this == null -> ""
      this % 1.0 == 0.0 -> toInt().toString()
      else -> toString()
    }

/** Секунды длительности как строка минут (для CARDIO-поля «мин»). */
private fun Int?.minutesField(): String = this?.let { (it / 60).toString() } ?: ""
