package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.valerochka1337.valerochkagym.ui.components.PlanningChoiceField
import com.valerochka1337.valerochkagym.ui.components.PlanningChoiceSheet
import com.valerochka1337.valerochkagym.ui.components.PlanningDateTimeFields
import com.valerochka1337.valerochkagym.ui.components.PlanningScreen
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
) {
  val haptics = gymHaptics()
  val invalidInputs =
      remember(proposal?.proposalId, proposal?.currentVersion) {
        mutableStateMapOf<String, Boolean>()
      }
  var validationEpoch by
      remember(proposal?.proposalId, proposal?.currentVersion) { mutableStateOf(0) }
  val transientInputInvalid = invalidInputs.values.any { it }
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

    Text(
        if (editing) "Оригинал" else "План тренировки",
        style = MaterialTheme.typography.titleMedium,
    )
    ProposalDraftSummary(
        if (editing) proposal.snapshot.draft else draft,
        exerciseChoices,
        gymChoices,
    )

    val canEdit = proposal.status == ProposalStatus.PENDING && !applied && !isExpired(proposal)
    if (canEdit) {
      TextButton(
          onClick = { editing = !editing },
          enabled = !saving && (!editing || !transientInputInvalid),
      ) {
        Text(if (editing) "Завершить редактирование" else "Изменить план")
      }
    }
    if (canEdit && editing) {
      GymCard(modifier = Modifier.fillMaxWidth()) {
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
    ExerciseEditor(
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

  var addExercise by rememberSaveable { mutableStateOf(false) }
  val available = exerciseChoices.filter { (id, _) -> draft.exercises.none { it.exerciseId == id } }
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
  val zone = runCatching { ZoneId.of(draft.timeZoneId) }.getOrDefault(ZoneId.of("UTC"))
  val local = Instant.ofEpochMilli(draft.startsAtMillis).atZone(zone).toLocalDateTime()
  var invalidTime by
      rememberSaveable(draft.startsAtMillis, draft.timeZoneId) { mutableStateOf(false) }
  LaunchedEffect(invalidTime) { onInvalid(invalidTime) }
  fun update(value: LocalDateTime, newZone: ZoneId = zone) {
    invalidTime = newZone.rules.getValidOffsets(value).isEmpty()
    if (!invalidTime)
        onDraftChange(
            draft.copy(
                startsAtMillis = value.atZone(newZone).toInstant().toEpochMilli(),
                timeZoneId = newZone.id,
            )
        )
  }
  PlanningDateTimeFields(
      local.toLocalDate().toString(),
      local.toLocalTime().toString(),
      draft.timeZoneId,
      { update(java.time.LocalDate.parse(it).atTime(local.toLocalTime())) },
      { update(local.toLocalDate().atTime(java.time.LocalTime.parse(it))) },
      { update(local, ZoneId.of(it)) },
  )
  if (invalidTime)
      Text(
          "Это время недоступно из-за перевода часов. Выберите другое время.",
          color = MaterialTheme.colorScheme.error,
      )
}

@Composable
private fun ExerciseEditor(
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
      Text("Упражнение ${exerciseIndex + 1}", style = MaterialTheme.typography.titleSmall)
      PlanningChoiceField(
          title = exerciseChoices.nameFor(exercise.exerciseId) ?: "Выбрать упражнение",
          choices =
              exerciseChoices.filter { (id, _) ->
                id == exercise.exerciseId || allExercises.none { it.exerciseId == id }
              },
          selected = setOf(exercise.exerciseId),
          onToggle = { id ->
            onChange(allExercises.replaceAt(exerciseIndex, exercise.copy(exerciseId = id)))
          },
          singleChoice = true,
      )

      IntField(
          label = "Отдых, сек",
          value = exercise.restSeconds,
          key = "rest-$exerciseIndex",
          onInvalid = onInvalid,
          validationEpoch = validationEpoch,
          onChange = { value ->
            onChange(allExercises.replaceAt(exerciseIndex, exercise.copy(restSeconds = value)))
          },
      )
      exercise.plannedSets.forEachIndexed { setIndex, plannedSet ->
        SetEditor(
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
    exerciseIndex: Int,
    setIndex: Int,
    plannedSet: ProposalPlannedSet,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (ProposalPlannedSet) -> Unit,
    onRemove: () -> Unit,
) {
  Text("Подход ${setIndex + 1}", style = MaterialTheme.typography.titleSmall)
  var timed by
      rememberSaveable(exerciseIndex, setIndex, validationEpoch) {
        mutableStateOf(
            plannedSet.durationSec != null ||
                plannedSet.speedKmh != null ||
                plannedSet.inclinePct != null
        )
      }
  // Reordering/removing exercises may reuse a position with a different set type.
  // A temporarily empty duration must keep the current editor visible.
  LaunchedEffect(
      plannedSet.reps,
      plannedSet.weightKg,
      plannedSet.durationSec,
      plannedSet.speedKmh,
      plannedSet.inclinePct,
  ) {
    if (
        plannedSet.durationSec != null ||
            plannedSet.speedKmh != null ||
            plannedSet.inclinePct != null
    )
        timed = true
    else if (plannedSet.reps != null || plannedSet.weightKg != null) timed = false
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(false to "Повторения", true to "На время").forEach { (isTimed, label) ->
      FilterChip(
          selected = timed == isTimed,
          onClick = {
            if (timed != isTimed) {
              timed = isTimed
              listOf("weight", "reps", "duration", "speed", "incline").forEach {
                onInvalid("$it-$exerciseIndex-$setIndex", false)
              }
              onChange(
                  if (isTimed) ProposalPlannedSet(null, null, 60, null, null)
                  else ProposalPlannedSet(null, 10, null, null, null)
              )
            }
          },
          label = { Text(label) },
      )
    }
  }
  if (!timed) {
    DecimalField(
        label = "Вес подхода ${setIndex + 1}, кг",
        value = plannedSet.weightKg,
        key = "weight-$exerciseIndex-$setIndex",
        onInvalid = onInvalid,
        validationEpoch = validationEpoch,
        onChange = { onChange(plannedSet.copy(weightKg = it)) },
    )
    IntField(
        label = "Повторы подхода ${setIndex + 1}",
        value = plannedSet.reps,
        key = "reps-$exerciseIndex-$setIndex",
        onInvalid = onInvalid,
        validationEpoch = validationEpoch,
        onChange = { onChange(plannedSet.copy(reps = it)) },
    )
  } else {
    IntField(
        label = "Длительность подхода ${setIndex + 1}, сек",
        value = plannedSet.durationSec,
        key = "duration-$exerciseIndex-$setIndex",
        onInvalid = onInvalid,
        validationEpoch = validationEpoch,
        onChange = { onChange(plannedSet.copy(durationSec = it)) },
    )
    DecimalField(
        label = "Скорость подхода ${setIndex + 1}, км/ч",
        value = plannedSet.speedKmh,
        key = "speed-$exerciseIndex-$setIndex",
        onInvalid = onInvalid,
        validationEpoch = validationEpoch,
        onChange = { onChange(plannedSet.copy(speedKmh = it)) },
    )
    DecimalField(
        label = "Наклон подхода ${setIndex + 1}, %",
        value = plannedSet.inclinePct,
        key = "incline-$exerciseIndex-$setIndex",
        onInvalid = onInvalid,
        validationEpoch = validationEpoch,
        onChange = { onChange(plannedSet.copy(inclinePct = it)) },
    )
  }
  TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
    Text("Удалить подход")
  }
}

@Composable
private fun IntField(
    label: String,
    value: Int?,
    key: String,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (Int?) -> Unit,
) {
  var text by rememberSaveable(key, value) { mutableStateOf(value?.toString().orEmpty()) }
  val invalid = text.isNotBlank() && text.toIntOrNull() == null
  LaunchedEffect(key, text, validationEpoch) { onInvalid(key, invalid) }
  OutlinedTextField(
      value = text,
      onValueChange = {
        text = it
        val parsed = it.toIntOrNull()
        if (it.isBlank() || parsed != null) onChange(parsed)
      },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(label) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      isError = invalid,
      supportingText =
          if (invalid) {
            { Text("Введите целое число") }
          } else null,
  )
}

@Composable
private fun DecimalField(
    label: String,
    value: Double?,
    key: String,
    onInvalid: (String, Boolean) -> Unit,
    validationEpoch: Int,
    onChange: (Double?) -> Unit,
) {
  var text by rememberSaveable(key, value) { mutableStateOf(value?.toString().orEmpty()) }
  val invalid = text.isNotBlank() && text.replace(',', '.').toDoubleOrNull() == null
  LaunchedEffect(key, text, validationEpoch) { onInvalid(key, invalid) }
  OutlinedTextField(
      value = text,
      onValueChange = {
        text = it
        val parsed = it.replace(',', '.').toDoubleOrNull()
        if (it.isBlank() || parsed != null) onChange(parsed)
      },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(label) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      isError = invalid,
      supportingText =
          if (invalid) {
            { Text("Введите число") }
          } else null,
  )
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

private fun formatStart(startsAtMillis: Long, timeZoneId: String): String {
  val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.of("UTC") }
  return "${Instant.ofEpochMilli(startsAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.forLanguageTag("ru")))} ($timeZoneId)"
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
) {
  val invalid = remember { mutableStateMapOf<String, Boolean>() }
  var epoch by remember { mutableStateOf(0) }
  val valid = invalid.values.none { it } && isDraftValid(draft)
  LaunchedEffect(valid) { onValidity(valid) }
  DraftEditor(draft, exerciseChoices, gymChoices, onDraftChange, invalid, epoch, { epoch++ })
}
