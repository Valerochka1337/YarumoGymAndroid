package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.trainingproposal.ApprovalDraft
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalAuthor
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedExercise
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedSet
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalSource
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalStatus
import com.valerochka1337.valerochkagym.data.trainingproposal.TrainingProposal
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.PlannedSetFields
import com.valerochka1337.valerochkagym.ui.components.PlanningChoiceSheet
import com.valerochka1337.valerochkagym.ui.components.PlanningDateTimeFields
import com.valerochka1337.valerochkagym.ui.components.PlanningDateTimeResolution
import com.valerochka1337.valerochkagym.ui.components.PlanningScreen
import com.valerochka1337.valerochkagym.ui.components.displayPlanningDateTime
import com.valerochka1337.valerochkagym.ui.components.rememberDeviceTimeZone
import com.valerochka1337.valerochkagym.ui.components.resolvePlanningDateTime
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.proposalApproved
import com.valerochka1337.valerochkagym.ui.theme.proposalPending
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val localDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

@Composable
fun TrainingProposalInboxContent(
    items: List<TrainingProposal>,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onCreateAi: (() -> Unit)? = null,
    onManual: (() -> Unit)? = null,
    manualContent: @Composable () -> Unit = {},
    preparationContent: @Composable () -> Unit = {},
    manualSelected: Boolean = false,
) {
  val haptics = gymHaptics()
  PlanningScreen(
      "Планирование",
      onBack,
      actions = {
        IconButton(onClick = onRefresh, enabled = !loading) {
          Icon(Icons.Rounded.Refresh, "Обновить предложения")
        }
      },
  ) {
    if (onCreateAi != null) {
      Text("Новая тренировка", style = MaterialTheme.typography.titleLarge)
      Text(
          "Составьте план с ИИ или выберите готовую программу. Перед добавлением в календарь план можно проверить.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onCreateAi) {
          Icon(Icons.Rounded.AutoAwesome, null)
          Text("Составить с ИИ", Modifier.padding(start = 8.dp))
        }
        if (onManual != null)
            OutlinedButton(
                onClick = {
                  haptics.tap()
                  onManual()
                },
                modifier = Modifier.semantics { selected = manualSelected },
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor =
                            if (manualSelected) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Unspecified,
                        contentColor =
                            if (manualSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.primary,
                    ),
            ) {
              Icon(if (manualSelected) Icons.Rounded.Check else Icons.Rounded.FitnessCenter, null)
              Text("Из программы", Modifier.padding(start = 8.dp))
            }
      }
    }
    manualContent()
    preparationContent()
    Text("Предложения", style = MaterialTheme.typography.titleLarge)
    if (loading) {
      Text(
          "Загружаем предложения…",
          modifier = Modifier.semantics { contentDescription = "Загружаем предложения" },
      )
    }
    if (error != null) {
      Text(error, color = MaterialTheme.colorScheme.error)
      PillButton(
          "Повторить",
          {
            haptics.tap()
            onRefresh()
          },
          modifier = Modifier.fillMaxWidth(),
      )
    }
    if (!loading && error == null && items.isEmpty()) {
      GymCard(Modifier.fillMaxWidth()) {
        Text("Пока нет предложений", style = MaterialTheme.typography.titleMedium)
        Text(
            "Здесь появятся планы от ИИ и тренера. Готовый план можно изменить, принять или отклонить.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    items.forEach { proposal ->
      GymCard(
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            haptics.tap()
            onOpen(proposal.proposalId)
          },
      ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(proposal.snapshot.draft.name, style = MaterialTheme.typography.titleLarge)
            Text(
                inboxDescription(proposal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          ProposalStatusBadge(proposal)
        }
      }
    }
    if (hasMore) {
      PillButton(
          "Загрузить ещё",
          {
            haptics.tap()
            onMore()
          },
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
fun TrainingProposalDetailContent(
    proposal: TrainingProposal?,
    draft: ApprovalDraft?,
    saving: Boolean,
    applied: Boolean,
    error: String?,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
    onDraftChange: (ApprovalDraft) -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    exerciseTypes: Map<String, ExerciseType> = emptyMap(),
    availableExerciseIds: Set<String> = exerciseChoices.map { it.first }.toSet(),
) {
  val haptics = gymHaptics()
  val invalidInputs =
      remember(proposal?.proposalId, proposal?.currentVersion) {
        mutableStateMapOf<String, Boolean>()
      }
  var validationEpoch by
      remember(proposal?.proposalId, proposal?.currentVersion) { mutableStateOf(0) }
  val transientInputInvalid =
      invalidInputs.values.any { it } ||
          draft?.exercises?.any { exercise ->
            exercise.exerciseId !in availableExerciseIds ||
                exerciseTypes[exercise.exerciseId]?.let { type ->
                  exercise.plannedSets.any { it != it.forType(type) }
                } == true
          } == true
  var editing by
      rememberSaveable(proposal?.proposalId, proposal?.currentVersion) { mutableStateOf(false) }
  PlanningScreen(
      "Предложение",
      onBack,
      actions = { proposal?.let { ProposalStatusBadge(it) } },
  ) {
    if (error != null) {
      Text(error, color = MaterialTheme.colorScheme.error)
      PillButton(
          "Повторить",
          {
            haptics.tap()
            onRetry()
          },
          modifier = Modifier.fillMaxWidth(),
      )
    }
    if (proposal == null || draft == null) {
      Text(
          if (error == null) "Загружаем предложение…" else "Предложение пока недоступно.",
          modifier = Modifier.semantics { contentDescription = "Загружаем предложение" },
      )
      return@PlanningScreen
    }

    Text(
        inboxDescription(proposal),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (applied) {
      Text("Применено")
    }

    if (!editing)
        Text(
            "План тренировки",
            style = MaterialTheme.typography.titleMedium,
        )
    if (!editing)
        ProposalDraftSummary(
            draft,
            exerciseChoices,
            gymChoices,
        )

    val canEdit = proposal.status == ProposalStatus.PENDING && !applied && !isExpired(proposal)
    if (canEdit) {
      TextButton(
          onClick = { editing = !editing },
          enabled = !saving && (!editing || (!transientInputInvalid && isDraftValid(draft))),
      ) {
        Text(if (editing) "Завершить редактирование" else "Изменить план")
      }
    }
    if (canEdit && editing) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Изменённый план", style = MaterialTheme.typography.titleMedium)
          if (draft != proposal.snapshot.draft) {
            Text("Есть изменения относительно оригинала")
            DraftDifference(original = proposal.snapshot.draft, edited = draft)
          }
          key(proposal.proposalId, proposal.currentVersion) {
            DraftEditor(
                draft = draft,
                exerciseChoices = exerciseChoices,
                availableExerciseIds = availableExerciseIds,
                exerciseTypes = exerciseTypes,
                gymChoices = gymChoices,
                onDraftChange = onDraftChange,
                invalidInputs = invalidInputs,
                validationEpoch = validationEpoch,
                onExerciseInputsChanged = { validationEpoch++ },
            )
          }
        }
      }
    } else if (!canEdit) {
      Text("Этот вариант больше нельзя редактировать.")
    }

    if (canEdit && (!isDraftValid(draft) || transientInputInvalid)) {
      Text(
          "Проверьте план: нужны будущее время начала, уникальные упражнения и заполненные подходы. Исправьте значения через «Изменить план».",
          color = MaterialTheme.colorScheme.error,
      )
    }
    when {
      proposal.status == ProposalStatus.APPROVED && !applied -> {
        PillButton(
            "Загрузить результат",
            {
              haptics.confirm()
              onApply()
            },
            enabled = !saving,
            modifier =
                Modifier.fillMaxWidth().semantics { contentDescription = "Загрузить результат" },
        )
      }
      canEdit -> {
        PillButton(
            "Применить",
            {
              haptics.confirm()
              onApply()
            },
            enabled = !saving && !transientInputInvalid && isDraftValid(draft),
            modifier =
                Modifier.fillMaxWidth().semantics { contentDescription = "Применить предложение" },
        )
        OutlinedButton(
            onClick = {
              haptics.reject()
              onReject()
            },
            enabled = !saving,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                  contentDescription = "Отклонить предложение"
                },
        ) {
          Text("Отклонить")
        }
      }
    }
  }
}

@Composable
private fun ProposalDraftSummary(
    draft: ApprovalDraft,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
) {
  Text(draft.name.ifBlank { "Не указано" }, style = MaterialTheme.typography.headlineSmall)
  GymCard(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ProposalMetaRow(
          Icons.Rounded.CalendarMonth,
          "Начало: ${formatStart(draft.startsAtMillis, draft.timeZoneId)}",
      )
      val gymNames = draft.gymIds.mapNotNull { id -> gymChoices.nameFor(id) }
      ProposalMetaRow(
          Icons.Rounded.LocationOn,
          "Залы: ${gymNames.ifEmpty { listOf("Не выбраны") }.joinToString()}",
      )
      ProposalMetaRow(
          Icons.Rounded.FitnessCenter,
          "${draft.exercises.size} упр. · ${draft.exercises.sumOf { it.plannedSets.size }} подходов",
      )
    }
  }
  Text("Упражнения", style = MaterialTheme.typography.titleMedium)
  draft.exercises.forEach { exercise ->
    val exerciseName = exerciseChoices.nameFor(exercise.exerciseId) ?: "Упражнение недоступно"
    GymCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ExerciseAvatar(name = exerciseName)
        Text(
            exerciseName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
      }
      Spacer(Modifier.height(8.dp))
      exercise.plannedSets.forEachIndexed { setIndex, set ->
        Text(
            "${setIndex + 1} · ${setSummary(set)}",
            modifier = Modifier.padding(vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      exercise.restSeconds?.let {
        Spacer(Modifier.height(8.dp))
        ProposalMetaRow(Icons.Rounded.Timer, "Отдых · $it сек")
      }
    }
  }
}

@Composable
private fun ProposalMetaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun ProposalStatusBadge(proposal: TrainingProposal) {
  var showExplanation by rememberSaveable(proposal.proposalId) { mutableStateOf(false) }
  val haptics = gymHaptics()
  val expired = proposal.status == ProposalStatus.PENDING && isExpired(proposal)
  val label = if (expired) "Срок действия истёк" else statusLabel(proposal.status)
  val icon =
      when {
        expired -> Icons.Rounded.EventBusy
        proposal.status == ProposalStatus.PENDING -> Icons.Rounded.HourglassTop
        proposal.status == ProposalStatus.APPROVED -> Icons.Rounded.CheckCircle
        proposal.status == ProposalStatus.REJECTED -> Icons.Rounded.Cancel
        proposal.status == ProposalStatus.REVOKED -> Icons.AutoMirrored.Rounded.Undo
        else -> Icons.Rounded.Update
      }
  val color =
      when {
        expired -> MaterialTheme.colorScheme.onSurfaceVariant
        proposal.status == ProposalStatus.PENDING -> MaterialTheme.colorScheme.proposalPending
        proposal.status == ProposalStatus.APPROVED -> MaterialTheme.colorScheme.proposalApproved
        proposal.status == ProposalStatus.REJECTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      }
  IconButton(
      onClick = {
        haptics.tap()
        showExplanation = true
      }
  ) {
    Icon(icon, "Статус: $label", modifier = Modifier.size(28.dp), tint = color)
  }
  if (showExplanation) {
    AlertDialog(
        onDismissRequest = { showExplanation = false },
        icon = { Icon(icon, null, tint = color) },
        title = { Text(label) },
        text = {
          Text(
              when {
                expired -> "Время на решение истекло. Подготовьте новое предложение."
                proposal.status == ProposalStatus.PENDING ->
                    "Проверьте план, затем примите или отклоните его."
                proposal.status == ProposalStatus.APPROVED ->
                    "Предложение принято. План добавлен в календарь."
                proposal.status == ProposalStatus.REJECTED ->
                    "Предложение отклонено и не будет добавлено в календарь."
                proposal.status == ProposalStatus.REVOKED -> "Автор отозвал предложение."
                else -> "Данные изменились. Для актуального плана нужен новый расчёт."
              }
          )
        },
        confirmButton = { TextButton(onClick = { showExplanation = false }) { Text("Понятно") } },
    )
  }
}

@Composable
private fun DraftDifference(original: ApprovalDraft, edited: ApprovalDraft) {
  if (original.name != edited.name) Text("Изменено название")
  if (original.gymIds != edited.gymIds) Text("Изменён выбор залов")
  if (
      original.startsAtMillis != edited.startsAtMillis || original.timeZoneId != edited.timeZoneId
  ) {
    Text("Изменено время начала")
  }
  if (original.exercises != edited.exercises) {
    Text("Изменены упражнения или подходы")
  }
}

@Composable
private fun DraftEditor(
    draft: ApprovalDraft,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
    onDraftChange: (ApprovalDraft) -> Unit,
    invalidInputs: MutableMap<String, Boolean>,
    validationEpoch: Int,
    onExerciseInputsChanged: () -> Unit,
    exerciseTypes: Map<String, ExerciseType> = emptyMap(),
    availableExerciseIds: Set<String> = exerciseChoices.map { it.first }.toSet(),
) {
  fun setInvalid(key: String, invalid: Boolean) {
    invalidInputs[key] = invalid
  }

  OutlinedTextField(
      value = draft.name,
      onValueChange = { onDraftChange(draft.copy(name = it)) },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Название") },
      isError = draft.name.isBlank(),
      supportingText =
          if (draft.name.isBlank()) {
            { Text("Укажите название") }
          } else null,
  )

  Text("Залы")
  gymChoices.forEach { (id, name) ->
    FilterChip(
        selected = id in draft.gymIds,
        onClick = {
          val gyms = if (id in draft.gymIds) draft.gymIds - id else (draft.gymIds + id).distinct()
          onDraftChange(draft.copy(gymIds = gyms.sorted()))
        },
        label = { Text(name) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
  }
  if (gymChoices.isEmpty()) Text("Доступных залов нет.")

  DateTimeEditor(
      draft = draft,
      onDraftChange = onDraftChange,
      onInvalid = { setInvalid("starts", it) },
  )

  fun clearExerciseInputErrors() {
    invalidInputs.keys.filter { it != "starts" }.forEach(invalidInputs::remove)
    onExerciseInputsChanged()
  }

  draft.exercises.forEachIndexed { exerciseIndex, exercise ->
    key(exercise.exerciseId) {
      val fallbackType = rememberSaveable { exercise.inferredType().name }
      ExerciseEditor(
          exerciseType = exerciseTypes[exercise.exerciseId] ?: ExerciseType.valueOf(fallbackType),
          exerciseTypes = exerciseTypes,
          availableExerciseIds = availableExerciseIds,
          exerciseIndex = exerciseIndex,
          exercise = exercise,
          allExercises = draft.exercises,
          exerciseChoices = exerciseChoices,
          onChange = { updated -> onDraftChange(draft.copy(exercises = updated)) },
          onInvalid = { key, invalid -> setInvalid(key, invalid) },
          validationEpoch = validationEpoch,
          onSetRemoved = ::clearExerciseInputErrors,
          onExerciseRemoved = ::clearExerciseInputErrors,
      )
    }
  }

  var addExercise by rememberSaveable { mutableStateOf(false) }
  val available =
      exerciseChoices.filter { (id, _) ->
        id in availableExerciseIds && draft.exercises.none { it.exerciseId == id }
      }
  TextButton(
      onClick = { addExercise = true },
      enabled = available.isNotEmpty() && draft.exercises.size < 30,
  ) {
    Text("Добавить упражнение")
  }
  if (addExercise) {
    PlanningChoiceSheet(
        "Добавить упражнение",
        available,
        emptySet(),
        { id ->
          onDraftChange(
              draft.copy(
                  exercises =
                      draft.exercises +
                          ProposalPlannedExercise(
                              exerciseId = id,
                              restSeconds = null,
                              plannedSets = listOf(emptyPlannedSet()),
                          )
              )
          )
        },
        { addExercise = false },
        singleChoice = true,
    )
  }
}

@Composable
private fun DateTimeEditor(
    draft: ApprovalDraft,
    onDraftChange: (ApprovalDraft) -> Unit,
    onInvalid: (Boolean) -> Unit,
) {
  val zone = rememberDeviceTimeZone()
  val local = displayPlanningDateTime(draft.startsAtMillis, zone)
  var invalidTime by rememberSaveable(draft.startsAtMillis, zone.id) { mutableStateOf(false) }
  LaunchedEffect(invalidTime) { onInvalid(invalidTime) }
  fun update(date: String = local.date, time: String = local.time) {
    val currentZone = ZoneId.systemDefault()
    val result = resolvePlanningDateTime(date, time, currentZone, draft.startsAtMillis)
    invalidTime = result !is PlanningDateTimeResolution.Resolved
    if (result is PlanningDateTimeResolution.Resolved)
        onDraftChange(
            draft.copy(startsAtMillis = result.instantMillis, timeZoneId = currentZone.id)
        )
  }
  PlanningDateTimeFields(
      local.date,
      local.time,
      zone.id,
      { update(date = it) },
      { update(time = it) },
      {},
  )
  if (invalidTime)
      Text(
          "Это время недоступно из-за перевода часов. Выберите другое время.",
          color = MaterialTheme.colorScheme.error,
      )
}

@Composable
private fun ExerciseEditor(
    exerciseType: ExerciseType,
    exerciseTypes: Map<String, ExerciseType>,
    availableExerciseIds: Set<String>,
    exerciseIndex: Int,
    exercise: ProposalPlannedExercise,
    allExercises: List<ProposalPlannedExercise>,
    exerciseChoices: List<Pair<String, String>>,
    onChange: (List<ProposalPlannedExercise>) -> Unit,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onSetRemoved: () -> Unit,
    onExerciseRemoved: () -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ExerciseAvatar(
            name = exerciseChoices.nameFor(exercise.exerciseId) ?: "Недоступное упражнение",
            type = exerciseType,
        )
        Text(
            exerciseChoices.nameFor(exercise.exerciseId) ?: "Недоступное упражнение",
            Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
      }
      if (exercise.exerciseId !in availableExerciseIds)
          Text(
              "Упражнение недоступно в выбранных залах. Замените его или измените залы.",
              color = MaterialTheme.colorScheme.error,
          )
      var replacing by rememberSaveable { mutableStateOf(false) }
      TextButton(onClick = { replacing = true }) { Text("Заменить") }
      if (replacing)
          PlanningChoiceSheet(
              "Заменить упражнение",
              exerciseChoices.filter { (id, _) ->
                id in availableExerciseIds && allExercises.none { it.exerciseId == id }
              },
              emptySet(),
              { id ->
                val type = exerciseTypes[id] ?: exerciseType
                onSetRemoved()
                onChange(
                    allExercises.replaceAt(exerciseIndex, exercise.replacingExercise(id, type))
                )
              },
              { replacing = false },
              singleChoice = true,
          )

      com.valerochka1337.valerochkagym.ui.components.NumberField(
          value = exercise.restSeconds?.toString().orEmpty(),
          label = "Отдых, сек",
          modifier = Modifier.fillMaxWidth(),
          onValueChange = { value ->
            onChange(
                allExercises.replaceAt(
                    exerciseIndex,
                    exercise.copy(restSeconds = value.toIntOrNull()),
                )
            )
          },
      )
      exercise.plannedSets.forEachIndexed { setIndex, plannedSet ->
        SetEditor(
            exerciseType = exerciseType,
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
            plannedSet = plannedSet,
            onInvalid = onInvalid,
            validationEpoch = validationEpoch,
            onChange = { changedSet ->
              val sets = exercise.plannedSets.replaceAt(setIndex, changedSet)
              onChange(allExercises.replaceAt(exerciseIndex, exercise.copy(plannedSets = sets)))
            },
            onRemove = {
              onSetRemoved()
              onChange(
                  allExercises.replaceAt(
                      exerciseIndex,
                      exercise.copy(
                          plannedSets =
                              exercise.plannedSets.toMutableList().also { it.removeAt(setIndex) }
                      ),
                  )
              )
            },
        )
      }
      TextButton(
          enabled = exercise.plannedSets.size < 20,
          onClick = {
            onChange(
                allExercises.replaceAt(
                    exerciseIndex,
                    exercise.copy(
                        plannedSets =
                            exercise.plannedSets +
                                (exercise.plannedSets.lastOrNull() ?: emptyPlannedSet())
                    ),
                )
            )
          },
          modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Добавить подход")
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { onChange(allExercises.move(exerciseIndex, exerciseIndex - 1)) },
            enabled = exerciseIndex > 0,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Выше")
        }
        TextButton(
            onClick = { onChange(allExercises.move(exerciseIndex, exerciseIndex + 1)) },
            enabled = exerciseIndex < allExercises.lastIndex,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Ниже")
        }
        TextButton(
            onClick = {
              onExerciseRemoved()
              onChange(allExercises.toMutableList().also { it.removeAt(exerciseIndex) })
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Удалить упражнение")
        }
      }
    }
  }
}

@Composable
private fun SetEditor(
    exerciseType: ExerciseType,
    exerciseIndex: Int,
    setIndex: Int,
    plannedSet: ProposalPlannedSet,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (ProposalPlannedSet) -> Unit,
    onRemove: () -> Unit,
) {
  Text("Подход ${setIndex + 1}", style = MaterialTheme.typography.titleSmall)
  key(validationEpoch, setIndex) {
    PlannedSetFields(
        exerciseType,
        PlannedSet(
            plannedSet.weightKg,
            plannedSet.reps,
            plannedSet.durationSec,
            plannedSet.speedKmh,
            plannedSet.inclinePct,
        ),
        {
          onChange(
              ProposalPlannedSet(it.weightKg, it.reps, it.durationSec, it.speedKmh, it.inclinePct)
          )
        },
        Modifier.fillMaxWidth(),
        number = setIndex + 1,
    )
  }
  TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
    Text("Удалить подход")
  }
}

private fun emptyPlannedSet() = ProposalPlannedSet(null, null, null, null, null)

private fun isDraftValid(draft: ApprovalDraft): Boolean =
    draft.name == draft.name.trim() &&
        draft.name.isNotEmpty() &&
        draft.name.length <= 200 &&
        draft.gymIds.size <= 1_000 &&
        draft.gymIds == draft.gymIds.distinct().sorted() &&
        draft.exercises.size in 1..30 &&
        draft.exercises.map { it.exerciseId }.distinct().size == draft.exercises.size &&
        draft.startsAtMillis > System.currentTimeMillis() &&
        runCatching { ZoneId.of(draft.timeZoneId).id == draft.timeZoneId }.getOrDefault(false) &&
        draft.exercises.all { exercise ->
          exercise.exerciseId.isNotBlank() &&
              (exercise.restSeconds == null || exercise.restSeconds in 0..86_400) &&
              exercise.plannedSets.size in 1..20 &&
              exercise.plannedSets.all(::isPlannedSetValid)
        }

private fun isPlannedSetValid(plannedSet: ProposalPlannedSet): Boolean {
  fun Double?.within(minimum: Double = 0.0): Boolean =
      this == null || (isFinite() && this in minimum..1_000_000.0)

  val validShape =
      if (plannedSet.reps != null) {
        plannedSet.durationSec == null &&
            plannedSet.speedKmh == null &&
            plannedSet.inclinePct == null
      } else {
        plannedSet.durationSec != null && plannedSet.weightKg == null
      }
  return validShape &&
      plannedSet.weightKg.within() &&
      plannedSet.speedKmh.within() &&
      plannedSet.inclinePct.within(-100.0) &&
      (plannedSet.reps == null || plannedSet.reps in 1..1_000_000) &&
      (plannedSet.durationSec == null || plannedSet.durationSec in 1..1_000_000)
}

private fun inboxDescription(proposal: TrainingProposal): String =
    "Источник: ${sourceLabel(proposal.source)} · Автор: ${authorLabel(proposal.author)} · " +
        "версия ${proposal.currentVersion}"

private fun sourceLabel(source: ProposalSource): String =
    if (source == ProposalSource.AI) "ИИ" else "Тренер"

private fun authorLabel(author: ProposalAuthor): String =
    if (author.kind == ProposalSource.AI) "ИИ" else "Тренер"

private fun statusLabel(status: ProposalStatus): String =
    when (status) {
      ProposalStatus.PENDING -> "Ожидает решения"
      ProposalStatus.APPROVED -> "Принято"
      ProposalStatus.REJECTED -> "Отклонено"
      ProposalStatus.REVOKED -> "Отозвано"
      ProposalStatus.STALE -> "Устарело"
    }

private fun isExpired(proposal: TrainingProposal): Boolean =
    proposal.expiresAt <= System.currentTimeMillis()

@Composable
private fun formatStart(startsAtMillis: Long, timeZoneId: String): String {
  val zone = rememberDeviceTimeZone()
  return Instant.ofEpochMilli(startsAtMillis)
      .atZone(zone)
      .format(
          DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.forLanguageTag("ru"))
      )
}

private fun formatEditableStart(startsAtMillis: Long, timeZoneId: String): String {
  val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.of("UTC") }
  return Instant.ofEpochMilli(startsAtMillis).atZone(zone).format(localDateTimeFormatter)
}

private fun parseLocalDateTime(value: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(value.trim(), localDateTimeFormatter) }.getOrNull()

private fun setSummary(plannedSet: ProposalPlannedSet): String =
    buildList {
          plannedSet.weightKg?.let { add("$it кг") }
          plannedSet.reps?.let { add("$it повторов") }
          plannedSet.durationSec?.let { add("$it сек") }
          plannedSet.speedKmh?.let { add("$it км/ч") }
          plannedSet.inclinePct?.let { add("наклон $it%") }
        }
        .ifEmpty { listOf("значения не указаны") }
        .joinToString()

private fun List<Pair<String, String>>.nameFor(id: String): String? =
    firstOrNull { it.first == id }?.second

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun <T> List<T>.move(from: Int, to: Int): List<T> =
    toMutableList().also { values ->
      if (to in values.indices) values.add(to, values.removeAt(from))
    }

/** Shared typed editor for a coach-authored proposal; no recipient apply side effects. */
@Composable
fun ProposalDraftForm(
    draft: ApprovalDraft,
    exerciseChoices: List<Pair<String, String>>,
    gymChoices: List<Pair<String, String>>,
    onDraftChange: (ApprovalDraft) -> Unit,
    onValidity: (Boolean) -> Unit,
    exerciseTypes: Map<String, ExerciseType> = emptyMap(),
) {
  val invalid = remember { mutableStateMapOf<String, Boolean>() }
  var epoch by remember { mutableStateOf(0) }
  val valid = invalid.values.none { it } && isDraftValid(draft)
  LaunchedEffect(valid) { onValidity(valid) }
  DraftEditor(
      draft,
      exerciseChoices,
      gymChoices,
      onDraftChange,
      invalid,
      epoch,
      { epoch++ },
      exerciseTypes,
  )
}
