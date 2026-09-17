package com.valerochka1337.valerochkagym.ui.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import com.valerochka1337.valerochkagym.ui.theme.LocalCoachActionColors
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

data class CoachChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val status: String? = null,
    val quickReplies: List<String>? = null,
    val failed: Boolean = false,
    val streaming: Boolean = false,
    val unread: Boolean = false,
    val isNew: Boolean = false,
)

data class CoachChatProposal(
    val id: String,
    val before: String,
    val after: String,
    val preview: WorkoutApprovalPreview? = null,
)

data class CoachChatUiState(
    val workoutName: String = "Тренировка",
    val messages: List<CoachChatMessage> = emptyList(),
    val proposal: CoachChatProposal? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val readOnly: Boolean = false,
    val initiativeEnabled: Boolean = true,
    val canUndo: Boolean = false,
) {
  fun retryText(message: CoachChatMessage): String? {
    if (!message.failed || messages.lastOrNull()?.id != message.id || proposal != null || readOnly)
        return null
    return messages.dropLast(1).lastOrNull { it.role == "user" }?.text
  }

  val quickReplies: List<String>
    get() =
        when {
          readOnly || proposal != null -> emptyList()
          messages.lastOrNull()?.role == "assistant" && messages.last().quickReplies != null ->
              messages.last().quickReplies.orEmpty().take(4)
          messages.none { it.role == "user" || it.quickReplies != null } ->
              listOf("Тренажёр занят", "Слишком тяжело", "Добавь подход")
          else -> emptyList()
        }
}

/** Display-only contract: the service owns requests and the coordinator owns every mutation. */
@Composable
fun CoachChatContent(
    state: CoachChatUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onCancel: (String) -> Unit,
    onUndo: () -> Unit,
    onDisableInitiative: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (String) -> Unit = {},
    onCancelWithReason:
        ((String, com.valerochka1337.valerochkagym.domain.CoachRejectionReason) -> Unit)? =
        null,
    imeInsets: WindowInsets = WindowInsets.ime,
) {
  val last = state.messages.lastOrNull()
  val latestLast by rememberUpdatedState(last)
  var shownText by remember { mutableStateOf(last?.text.orEmpty()) }
  var shownId by remember { mutableStateOf(last?.id) }
  var smoother = remember { CoachTextSmoother(last?.text.orEmpty()) }
  LaunchedEffect(Unit) {
    snapshotFlow { latestLast }
        .collectLatest { message ->
          if (message == null) return@collectLatest
          if (shownId != message.id) {
            smoother =
                CoachTextSmoother(if (shownId != null && message.streaming) "" else message.text)
            shownText = smoother.visible
            shownId = message.id
          }
          val immediate =
              message.failed || currentCoroutineContext()[MotionDurationScale]?.scaleFactor == 0f
          var previousFrame = Long.MIN_VALUE
          do {
            val now = withFrameNanos { it / 1_000_000 }
            if (
                previousFrame == Long.MIN_VALUE || now - previousFrame >= GymMotion.CoachFrameMillis
            ) {
              shownText = smoother.update(message.text, now, immediate)
              previousFrame = now
            }
          } while (shownText != message.text)
        }
  }
  val accessibility = androidx.compose.ui.platform.LocalView.current
  var wasStreaming by remember { mutableStateOf(false) }
  LaunchedEffect(last?.streaming, last?.id, state.busy) {
    if (last?.streaming == true && !wasStreaming) {
      accessibility.announceForAccessibility("Тренер отвечает")
      wasStreaming = true
    }
    if (wasStreaming && !state.busy) {
      if (last?.role == "assistant" && last.failed != true && !state.readOnly) {
        accessibility.announceForAccessibility("Ответ тренера готов")
      }
      wasStreaming = false
    }
  }
  val waitingStatus = state.status?.takeIf { last?.streaming != true }
  val haptics = gymHaptics()
  val actionColors = LocalCoachActionColors.current
  val listState = rememberLazyListState()
  var conversationHeight by remember { mutableStateOf(0) }
  val imeBottom = imeInsets.getBottom(LocalDensity.current)
  var automaticScroll by remember { mutableStateOf(false) }
  var openedHistory by remember { mutableStateOf(false) }
  var followAnswer by remember { mutableStateOf(true) }
  LaunchedEffect(listState) {
    var previousIndex = listState.firstVisibleItemIndex
    var previousOffset = listState.firstVisibleItemScrollOffset
    snapshotFlow {
          Triple(
              listState.firstVisibleItemIndex,
              listState.firstVisibleItemScrollOffset,
              listState.canScrollForward,
          )
        }
        .collect { (index, offset, canScroll) ->
          val movedUp = index < previousIndex || (index == previousIndex && offset < previousOffset)
          if (!automaticScroll && movedUp) followAnswer = false
          if (!canScroll) followAnswer = true
          previousIndex = index
          previousOffset = offset
        }
  }
  val itemCount =
      state.messages.size.coerceAtLeast(1) +
          (if (waitingStatus != null) 1 else 0) +
          (if (state.error != null) 1 else 0) +
          (if (state.readOnly || state.proposal != null) 1 else 0)
  LaunchedEffect(
      state.messages.lastOrNull()?.id,
      shownText,
      state.proposal?.id,
      waitingStatus,
      state.readOnly,
  ) {
    if (!openedHistory || followAnswer) {
      withFrameNanos {}
      automaticScroll = true
      try {
        val firstNew = if (!openedHistory) state.messages.indexOfFirst { it.isNew } else -1
        if (firstNew >= 0) {
          listState.scrollToItem(firstNew)
          followAnswer = false
        } else listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
      } finally {
        automaticScroll = false
      }
      if (state.messages.isNotEmpty()) openedHistory = true
    }
  }
  // The composer reduces the viewport as the IME animates. Reveal the end after
  // each layout change, without moving the app bar or changing the window origin.
  LaunchedEffect(imeBottom, conversationHeight) {
    if (imeBottom > 0 && !state.readOnly && followAnswer) {
      withFrameNanos {}
      automaticScroll = true
      try {
        listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
      } finally {
        automaticScroll = false
      }
    }
  }
  Scaffold(
      modifier = modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
            title = {
              Column {
                Text("Live Coach", modifier = Modifier.semantics { heading() })
                Text(state.workoutName, style = MaterialTheme.typography.bodyMedium)
              }
            },
            navigationIcon = {
              IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            },
        )
      },
      bottomBar = {
        if (!state.readOnly) {
          CoachComposer(
              state = state,
              onDraftChange = onDraftChange,
              onSend = onSend,
              onUndo = onUndo,
              onDisableInitiative = onDisableInitiative,
              imeInsets = imeInsets,
          )
        }
      },
  ) { padding ->
    Box(
        Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
          state = listState,
          modifier =
              Modifier.widthIn(max = 960.dp)
                  .fillMaxSize()
                  .onSizeChanged { conversationHeight = it.height }
                  .testTag("coach-conversation"),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (state.messages.isEmpty())
            item(key = "empty") {
              Text(
                  if (state.readOnly) "В этой тренировке нет сообщений тренера."
                  else "Опишите, что нужно изменить, или выберите быструю фразу."
              )
            }
        items(state.messages, key = { "message:${it.id}" }) { message ->
          val actionResult = message.actionResult()
          if (actionResult != null) {
            AppliedActionMessage(message = message, result = actionResult)
            return@items
          }
          val isUser = message.role == "user"
          Box(
              Modifier.fillMaxWidth(),
              contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
          ) {
            Surface(
                color =
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor =
                    if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.large,
                border =
                    if (message.isNew && !message.streaming && !message.failed)
                        androidx.compose.foundation.BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                        )
                    else null,
                modifier =
                    Modifier.fillMaxWidth(0.86f).testTag("coach-message:${message.id}").semantics(
                        mergeDescendants = true
                    ) {
                      if (message.isNew) stateDescription = "Новое сообщение"
                    },
            ) {
              Column(Modifier.padding(16.dp)) {
                Text(
                    when (message.role) {
                      "user" -> "Вы"
                      "assistant" -> if (message.isNew) "Тренер · Новое" else "Тренер"
                      else -> "Действие"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                      if (message.id == shownId && !message.failed) shownText else message.text,
                      modifier =
                          Modifier.weight(1f)
                              .then(
                                  if (message.streaming)
                                      Modifier.clearAndSetSemantics {
                                        contentDescription = "Тренер отвечает"
                                      }
                                  else Modifier
                              ),
                      style = MaterialTheme.typography.bodyLarge,
                  )
                  state.retryText(message)?.let {
                    IconButton(
                        onClick = {
                          haptics.tap()
                          onRetry(message.id)
                        },
                        enabled = !state.busy,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                      Icon(Icons.Rounded.Refresh, "Повторить запрос")
                    }
                  }
                }
                message.status
                    ?.takeIf { it.isNotBlank() && !(isUser && it == "Обрабатывается") }
                    ?.let {
                      Spacer(Modifier.height(8.dp))
                      Text(
                          it,
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
              }
            }
          }
        }
        waitingStatus?.let { status ->
          item(key = "status") {
            GymCard(
                Modifier.fillMaxWidth().testTag("coach-status").semantics {
                  liveRegion = LiveRegionMode.Polite
                }
            ) {
              Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Column {
                  Text("Тренер отвечает", style = MaterialTheme.typography.titleSmall)
                  Text(
                      status,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
        state.error?.let { error ->
          item(key = "error") {
            Text(
                error,
                Modifier.testTag("coach-error").semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
        if (!state.readOnly) {
          state.proposal?.let { proposal ->
            item(key = "proposal:${proposal.id}") {
              GymCard(Modifier.fillMaxWidth().testTag("coach-proposal")) {
                Text(
                    "Предложение тренера",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                if (proposal.preview != null) {
                  proposal.preview.actions.forEachIndexed { index, action ->
                    if (index > 0) {
                      Spacer(Modifier.height(12.dp))
                      HorizontalDivider()
                      Spacer(Modifier.height(12.dp))
                    }
                    var expanded by remember(action.title, action.kind) { mutableStateOf(false) }
                    val revealAction = remember { BringIntoViewRequester() }
                    LaunchedEffect(expanded) {
                      if (expanded) {
                        withFrameNanos {}
                        revealAction.bringIntoView()
                      }
                    }
                    Column(Modifier.fillMaxWidth().bringIntoViewRequester(revealAction)) {
                      Row(
                          horizontalArrangement = Arrangement.spacedBy(12.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Icon(
                            when (action.kind) {
                              "replace",
                              "swap",
                              "move" -> Icons.Default.SwapVert
                              "delete" -> Icons.Default.RemoveCircleOutline
                              "add" -> Icons.Default.AddCircleOutline
                              "rest",
                              "time" -> Icons.Default.Timer
                              "undo" -> Icons.Default.History
                              else -> Icons.Default.Edit
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            action.kind.actionTypeLabel(),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.testTag("coach-proposal-action:$index"),
                        ) {
                          Icon(
                              if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                              contentDescription =
                                  if (expanded) "Скрыть подробности" else "Показать подробности",
                          )
                        }
                      }
                      if (expanded) {
                        Column(
                            modifier = Modifier.padding(start = 36.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                          Text(
                              action.title,
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                          action.details.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                        }
                      }
                    }
                  }
                } else {
                  Text("Было", style = MaterialTheme.typography.labelLarge)
                  Text(proposal.before)
                  Spacer(Modifier.height(12.dp))
                  Text("Станет", style = MaterialTheme.typography.labelLarge)
                  Text(proposal.after)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Button(
                      onClick = {
                        haptics.confirm()
                        onConfirm(proposal.id)
                      },
                      enabled = !state.busy && proposal.preview?.actions?.isEmpty() != true,
                      colors =
                          ButtonDefaults.buttonColors(
                              containerColor = actionColors.acceptedContainer,
                              contentColor = actionColors.onAcceptedContainer,
                          ),
                      modifier = Modifier.weight(1f).testTag("coach-apply"),
                  ) {
                    Text("Принять", maxLines = 1)
                  }
                  Button(
                      onClick = {
                        haptics.tap()
                        onCancel(proposal.id)
                      },
                      enabled = !state.busy,
                      colors =
                          ButtonDefaults.buttonColors(
                              containerColor = actionColors.rejectedContainer,
                              contentColor = actionColors.onRejectedContainer,
                          ),
                      modifier = Modifier.weight(1f).testTag("coach-cancel"),
                  ) {
                    Text("Отклонить")
                  }
                }
                if (onCancelWithReason != null) {
                  var showReasons by remember(proposal.id) { mutableStateOf(false) }
                  androidx.compose.foundation.layout.Box {
                    TextButton(
                        onClick = {
                          haptics.tap()
                          showReasons = true
                        },
                        enabled = !state.busy,
                    ) {
                      Text("Отклонить с причиной")
                    }
                    DropdownMenu(
                        expanded = showReasons && !state.busy,
                        onDismissRequest = { showReasons = false },
                    ) {
                      com.valerochka1337.valerochkagym.domain.CoachRejectionReason.entries
                          .forEach { reason ->
                            DropdownMenuItem(
                                text = { Text(reason.label) },
                                onClick = {
                                  haptics.tap()
                                  showReasons = false
                                  onCancelWithReason(proposal.id, reason)
                                },
                            )
                          }
                    }
                  }
                }
              }
            }
          }
        } else
            item(key = "read-only") {
              Text(
                  "Тренировка завершена. Диалог доступен для чтения.",
                  Modifier.testTag("coach-read-only"),
                  style = MaterialTheme.typography.bodyMedium,
              )
            }
      }
    }
  }
}

@Composable
private fun CoachComposer(
    state: CoachChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onUndo: () -> Unit,
    onDisableInitiative: () -> Unit,
    imeInsets: WindowInsets,
) {
  val haptics = gymHaptics()
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  fun submit(text: String) {
    haptics.tap()
    onSend(text)
    focusManager.clearFocus()
    keyboardController?.hide()
  }
  Surface(modifier = Modifier.windowInsetsPadding(imeInsets), tonalElevation = 3.dp) {
    Column(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.quickReplies.forEach { phrase ->
          item(key = phrase) {
            SuggestionChip(
                onClick = { submit(phrase) },
                label = { Text(phrase, maxLines = 1, softWrap = false) },
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("coach-quick-reply:$phrase"),
            )
          }
        }
      }
      Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { if (it.length <= 4000) onDraftChange(it) },
            label = { Text("Сообщение тренеру") },
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("coach-input"),
            minLines = 1,
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { submit(state.draft.trim()) },
            enabled = state.draft.isNotBlank() && !state.busy,
            modifier = Modifier.size(56.dp).testTag("coach-send"),
        ) {
          Icon(Icons.Default.Send, contentDescription = "Отправить сообщение")
        }
      }
    }
  }
}

private data class CoachActionResult(val accepted: Boolean, val kind: String, val details: String)

private fun CoachChatMessage.actionResult(): CoachActionResult? =
    when {
      role != "system" -> null
      text.startsWith("APPLIED|") -> text.actionResult(true)
      text.startsWith("REJECTED|") -> text.actionResult(false)
      else -> null
    }

@Composable
private fun AppliedActionMessage(message: CoachChatMessage, result: CoachActionResult) {
  var expanded by remember(message.id) { mutableStateOf(false) }
  val revealAction = remember { BringIntoViewRequester() }
  LaunchedEffect(expanded) {
    if (expanded) {
      withFrameNanos {}
      revealAction.bringIntoView()
    }
  }
  val actionColors = LocalCoachActionColors.current
  Surface(
      color =
          if (result.accepted) actionColors.acceptedContainer else actionColors.rejectedContainer,
      contentColor =
          if (result.accepted) actionColors.onAcceptedContainer
          else actionColors.onRejectedContainer,
      shape = MaterialTheme.shapes.medium,
      modifier =
          Modifier.fillMaxWidth(0.72f)
              .bringIntoViewRequester(revealAction)
              .testTag("coach-action-result:${message.id}"),
  ) {
    Column {
      Row(
          modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
            if (result.accepted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
        )
        Text(
            "${if (result.accepted) "Применено" else "Отклонено"} · ${result.kind.actionTypeLabel()}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Скрыть подробности" else "Показать подробности",
        )
      }
      if (expanded) {
        Text(
            result.details,
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private fun String.actionResult(accepted: Boolean): CoachActionResult {
  val fields = split('|', limit = 3)
  return if (fields.size == 3) CoachActionResult(accepted, fields[1], fields[2])
  else
      CoachActionResult(accepted, "change", removePrefix(if (accepted) "APPLIED|" else "REJECTED|"))
}

private fun String.actionTypeLabel(): String =
    when (this) {
      "replace",
      "swap" -> "Замена"
      "move" -> "Перестановка"
      "delete" -> "Удаление"
      "add" -> "Добавление"
      "rest",
      "time" -> "Отдых"
      "undo" -> "Отмена"
      else -> "Изменение"
    }
