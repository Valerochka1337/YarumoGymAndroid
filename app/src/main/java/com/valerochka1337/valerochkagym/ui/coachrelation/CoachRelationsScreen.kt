package com.valerochka1337.valerochkagym.ui.coachrelation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.coachrelation.*
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.PlanningScreen
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.trainingproposal.ProposalDraftForm
import java.time.Instant
import java.time.ZoneId

@Composable
fun CoachRelationsScreen(
    onBack: () -> Unit,
    onOpen: (String, Boolean) -> Unit,
    viewModel: CoachRelationsViewModel = hiltViewModel(),
) {
  val s by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { viewModel.refresh() }
  var token by remember { mutableStateOf("") }
  var calendar by remember { mutableStateOf(false) }
  var completed by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  // Secrets and grants intentionally are not rememberSaveable; account changes clear input too.
  LaunchedEffect(s.error) {
    if (s.error?.startsWith("Аккаунт изменился") == true) {
      token = ""
      calendar = false
      completed = false
    }
  }
  PlanningScreen("Тренеры и подопечные", onBack) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
          selected = !s.clients,
          onClick = { viewModel.refresh(false) },
          enabled = !s.busy,
          label = { Text("Мои тренеры") },
      )
      FilterChip(
          selected = s.clients,
          onClick = { viewModel.refresh(true) },
          enabled = !s.busy,
          label = { Text("Подопечные") },
      )
    }
    Status(s)
    TextButton(onClick = { viewModel.refresh() }, enabled = !s.busy) { Text("Обновить список") }
    if (s.relations.isEmpty() && !s.busy) {
      GymCard(Modifier.fillMaxWidth()) {
        Text(
            if (s.clients) "Подопечных пока нет" else "Тренер ещё не подключён",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (s.clients)
                "Создайте приглашение и передайте код подопечному. После подключения здесь появятся доступные тренировки."
            else
                "Попросите у тренера код приглашения. Вы сами выбираете, какими тренировками делиться.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    s.relations.forEach { r ->
      GymCard(Modifier.fillMaxWidth(), onClick = { onOpen(r.relationId, s.clients) }) {
        Text(
            "${if (s.clients) "Подопечный" else "Тренер"} · ${r.counterpartyId}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (r.state == "ACTIVE") "Подключён" else "Доступ отозван",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOfNotNull(
                    if (r.calendar) "Календарь" else null,
                    if (r.completedWorkouts) "История тренировок" else null,
                )
                .joinToString(" · ")
                .ifEmpty { "Без доступа к тренировкам" },
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    if (s.clients) {
      GymCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Пригласить подопечного", style = MaterialTheme.typography.titleMedium)
          Text(
              "Одноразовый код подключает одного человека. Разрешения он выберет при подключении.",
              style = MaterialTheme.typography.bodyMedium,
          )
          if (s.invite == null)
              PillButton(
                  "Создать приглашение",
                  { viewModel.createInvite() },
                  enabled = !s.busy,
                  modifier = Modifier.fillMaxWidth(),
              )
          s.invite?.let { invite ->
            Text(invite.token, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Действует до ${relationDate(invite.expiresAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )
            RelationAction("Скопировать код", { clipboard.setText(AnnotatedString(invite.token)) })
            TextButton(onClick = { viewModel.clearInvite() }) { Text("Скрыть код") }
          }
        }
      }
    } else {
      GymCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Подключить тренера", style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
              token,
              { token = it.trim() },
              label = { Text("Код приглашения") },
              modifier = Modifier.fillMaxWidth(),
              enabled = !s.busy,
              singleLine = true,
              supportingText = { Text("Вставьте код, который прислал тренер") },
          )
          Text("Чем поделиться", style = MaterialTheme.typography.titleSmall)
          RelationGrant("Календарь тренировок", calendar, { calendar = it }, !s.busy)
          RelationGrant("Завершённые тренировки", completed, { completed = it }, !s.busy)
          Text(
              "Профиль, заметки и данные здоровья не передаются. Доступ можно отозвать в любой момент.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          PillButton(
              "Подключить тренера",
              {
                val value = token
                token = ""
                viewModel.accept(value, calendar, completed)
              },
              enabled = !s.busy && Regex("[A-Za-z0-9_-]{43}").matches(token),
              modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
    Pending(s, viewModel::retry)
  }
}

private fun relationDate(millis: Long, zone: String = ZoneId.systemDefault().id): String =
    Instant.ofEpochMilli(millis)
        .atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault()))
        .format(
            java.time.format.DateTimeFormatter.ofPattern(
                "d MMM yyyy, HH:mm",
                java.util.Locale.forLanguageTag("ru"),
            )
        )

@Composable
fun CoachRelationDetailScreen(
    id: String,
    clients: Boolean,
    onBack: () -> Unit,
    viewModel: CoachRelationsViewModel = hiltViewModel(),
) {
  val s by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(id, clients) { viewModel.refresh(clients, id) }
  var confirmRevoke by remember { mutableStateOf(false) }
  var confirmCloseDraft by remember { mutableStateOf(false) }
  GlowBackground {
    PlanningScreen(if (clients) "Подопечный" else "Тренер", onBack) {
      Status(s)
      RelationAction("Обновить доступ", { viewModel.refresh(clients, id) }, !s.busy)
      val r = s.relation
      if (r == null) {
        if (!s.busy) Text("Связь недоступна")
        return@PlanningScreen
      }
      GymCard(Modifier.fillMaxWidth()) {
        Text("Участник ${r.counterpartyId}", style = MaterialTheme.typography.titleMedium)
        Text(if (r.state == "ACTIVE") "Активная связь" else "Связь отозвана")
        Text("Календарь: ${if(r.calendar)"разрешён"else"закрыт"}")
        Text("Завершённые тренировки: ${if(r.completedWorkouts)"разрешены"else"закрыты"}")
      }
      if (r.state == "ACTIVE") {
        TextButton(
            onClick = { confirmRevoke = true },
            enabled = !s.busy,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text("Отозвать доступ")
        }
        if (confirmRevoke)
            AlertDialog(
                onDismissRequest = { confirmRevoke = false },
                title = { Text("Отозвать доступ?") },
                text = {
                  Text(
                      "Участник больше не сможет просматривать тренировки через эту связь. Для повторного подключения понадобится новое приглашение."
                  )
                },
                confirmButton = {
                  TextButton(
                      onClick = {
                        confirmRevoke = false
                        viewModel.revoke()
                      },
                      enabled = !s.busy,
                  ) {
                    Text("Отозвать")
                  }
                },
                dismissButton = {
                  TextButton(onClick = { confirmRevoke = false }) { Text("Отмена") }
                },
            )
        if (clients) {
          if (r.calendar) {
            RelationAction("Показать календарь", { viewModel.projection(true) }, !s.busy)
            s.calendar.forEach { item ->
              GymCard(Modifier.fillMaxWidth()) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    relationDate(item.startsAtMillis, item.timeZoneId),
                    style = MaterialTheme.typography.bodySmall,
                )
                item.exercises.forEach { Text("${it.name} · ${it.plannedSetCount} подходов") }
              }
            }
            if (s.calendarCursor != null)
                RelationAction("Ещё планы", { viewModel.projection(true, true) }, !s.busy)
          }
          if (r.completedWorkouts) {
            RelationAction(
                "Показать завершённые тренировки",
                { viewModel.projection(false) },
                !s.busy,
            )
            s.completed.forEach { item ->
              GymCard(Modifier.fillMaxWidth()) {
                Text(
                    relationDate(item.finishedAtMillis),
                    style = MaterialTheme.typography.titleMedium,
                )
                item.exercises.forEach { e ->
                  Text(e.name)
                  e.sets.forEachIndexed { index, set ->
                    Text("${index+1}. ${projectionSetText(set)}")
                  }
                }
              }
            }
            if (s.completedCursor != null)
                RelationAction("Ещё тренировки", { viewModel.projection(false, true) }, !s.busy)
          }
          if (s.recipientRevision != null && s.calendar.isEmpty() && s.completed.isEmpty())
              Text("В выбранном разделе пока нет тренировок")
          if (s.draft == null)
              PillButton(
                  "Подготовить предложение",
                  viewModel::newDraft,
                  enabled = !s.busy && (r.calendar || r.completedWorkouts),
                  modifier = Modifier.fillMaxWidth(),
              )
          if (!r.calendar && !r.completedWorkouts)
              Text("Для подготовки предложения участник должен открыть календарь или историю.")
          s.proposals.forEach { p ->
            GymCard(Modifier.fillMaxWidth()) {
              Text(p.snapshot.draft.name, style = MaterialTheme.typography.titleMedium)
              Text(
                  when (p.status) {
                    ProposalStatus.PENDING -> "Ожидает решения"
                    ProposalStatus.APPROVED -> "Принято"
                    ProposalStatus.REJECTED -> "Отклонено"
                    ProposalStatus.REVOKED -> "Отозвано"
                    ProposalStatus.STALE -> "Устарело"
                  },
                  style = MaterialTheme.typography.bodySmall,
              )
              if (p.status == ProposalStatus.PENDING) {
                RelationAction(
                    "Изменить предложение",
                    { viewModel.edit(p) },
                    !s.busy && s.draft == null,
                )
                RelationAction("Отозвать предложение", { viewModel.revokeProposal(p) }, !s.busy)
              }
            }
          }
          s.draft?.let { draft ->
            var valid by remember(s.editing?.proposalId) { mutableStateOf(false) }
            key(s.editing?.proposalId) {
              ProposalDraftForm(
                  draft,
                  s.exercises,
                  emptyList(),
                  viewModel::updateDraft,
                  { valid = it },
              )
            }
            PillButton(
                if (s.editing == null) "Отправить предложение" else "Сохранить новую версию",
                viewModel::submit,
                enabled = !s.busy && valid,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { confirmCloseDraft = true }, enabled = !s.busy) {
              Text("Закрыть черновик")
            }
            if (confirmCloseDraft)
                AlertDialog(
                    onDismissRequest = { confirmCloseDraft = false },
                    title = { Text("Закрыть черновик?") },
                    text = { Text("Несохранённые изменения предложения будут потеряны.") },
                    confirmButton = {
                      TextButton(
                          onClick = {
                            confirmCloseDraft = false
                            viewModel.closeDraft()
                          },
                          enabled = !s.busy,
                      ) {
                        Text("Закрыть")
                      }
                    },
                    dismissButton = {
                      TextButton(onClick = { confirmCloseDraft = false }) {
                        Text("Продолжить редактирование")
                      }
                    },
                )
          }
        }
      }
      Pending(s, viewModel::retry)
    }
  }
}

internal fun projectionSetText(s: ProjectionSet): String =
    listOfNotNull(
            s.weightKg?.let { "$it кг" },
            s.reps?.let { "$it повторений" },
            s.durationSec?.let { "$it с" },
            s.speedKmh?.let { "$it км/ч" },
            s.inclinePct?.let { "наклон $it %" },
        )
        .joinToString(" · ")
        .ifEmpty { "Значения не указаны" }

@Composable
private fun Pending(s: CoachRelationsState, retry: (String) -> Unit) {
  s.operations
      .filter { it.state == "PENDING" && it.firstSendBytes != null }
      .forEach { op ->
        Text("Операция ожидает подтверждения")
        RelationAction(
            "Повторить: ${when(op.action) { "REVOKE_RELATION" -> "отзыв связи"
 "CREATE_COACH_PROPOSAL" -> "предложение"
 "REVISE_COACH_PROPOSAL" -> "изменение предложения"
 else -> "отзыв предложения" }}",
            { retry(op.operationId) },
            !s.busy,
        )
      }
}

@Composable
private fun Status(s: CoachRelationsState) {
  if (s.busy) Text("Загружаем…")
  s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun RelationLayout(content: @Composable ColumnScope.() -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 840.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(if (maxWidth < 600.dp) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
  }
}

@Composable
internal fun RelationAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
  val h = gymHaptics()
  OutlinedButton(
      onClick = {
        h.tap()
        onClick()
      },
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
  ) {
    Text(text)
  }
}

@Composable
internal fun RelationGrant(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
  val h = gymHaptics()
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 48.dp)
          .toggleable(
              value = checked,
              enabled = enabled,
              role = Role.Checkbox,
              onValueChange = {
                h.tap()
                onChange(it)
              },
          ),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
        checked,
        null,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    )
    Text(text, Modifier.weight(1f))
  }
}
