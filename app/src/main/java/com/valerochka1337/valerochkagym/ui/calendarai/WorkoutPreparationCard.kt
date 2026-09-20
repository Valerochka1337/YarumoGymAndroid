package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class WorkoutPreparationViewModel
@Inject
constructor(private val repository: WorkoutPreparationRepository) : ViewModel() {
  val current =
      repository.current.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  private val _retrying = MutableStateFlow(false)
  val retrying = _retrying.asStateFlow()
  private val _error = MutableStateFlow<String?>(null)
  val error = _error.asStateFlow()

  fun retry(id: String) {
    if (_retrying.value) return
    _retrying.value = true
    _error.value = null
    viewModelScope.launch {
      try {
        repository.retryCurrent(id)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _error.value = calendarAiErrorMessage(e)
      } finally {
        _retrying.value = false
      }
    }
  }

  suspend fun refreshWhileVisible(requestId: String) =
      pollWorkoutPreparation(requestId) { repository.step(it) }
}

private const val PREPARATION_POLL_INTERVAL_MILLIS = 10_000L

internal suspend fun pollWorkoutPreparation(
    requestId: String,
    step: suspend (String) -> Boolean,
) {
  while (step(requestId)) delay(PREPARATION_POLL_INTERVAL_MILLIS)
}

internal suspend fun pollWorkoutPreparationWhileResumed(
    lifecycle: Lifecycle,
    requestId: String,
    refreshWhileVisible: suspend (String) -> Unit,
) {
  lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refreshWhileVisible(requestId) }
}

@Composable
fun WorkoutPreparationCard(
    onPrepare: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: WorkoutPreparationViewModel = hiltViewModel(),
) {
  val row by viewModel.current.collectAsStateWithLifecycle()
  val retrying by viewModel.retrying.collectAsStateWithLifecycle()
  val error by viewModel.error.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val requestId = row?.requestId
  LaunchedEffect(lifecycle, requestId, row?.state in WorkoutPreparationRepository.activeStates) {
    if (requestId != null && row?.state in WorkoutPreparationRepository.activeStates) {
      pollWorkoutPreparationWhileResumed(lifecycle, requestId, viewModel::refreshWhileVisible)
    }
  }
  if (row != null)
      WorkoutPreparationCardContent(
          row,
          onPrepare,
          onOpen,
          onRetry = { row?.let { viewModel.retry(it.requestId) } },
          retrying = retrying,
          retryError = error,
      )
}

@Composable
internal fun WorkoutPreparationCardContent(
    row: PreparationEntity?,
    onPrepare: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: (() -> Unit)? = null,
    retrying: Boolean = false,
    retryError: String? = null,
) {
  val haptics = gymHaptics()
  val intent =
      row?.let {
        runCatching { ProposalWire.json.decodeFromString<CalendarAiIntent>(it.intentJson) }
            .getOrNull()
      }
  val proposal =
      row?.proposalJson?.let {
        runCatching { ProposalWire.json.decodeFromString<TrainingProposal>(it) }.getOrNull()
      }
  val date =
      intent
          ?.let {
            Instant.ofEpochMilli(it.startsAtMillis)
                .atZone(ZoneId.of(it.timeZoneId))
                .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
          }
          .orEmpty()
  val displayState =
      if (intent != null && intent.startsAtMillis <= System.currentTimeMillis()) "EXPIRED"
      else row?.state
  val status =
      when (displayState) {
        null -> "Выберите дату и условия следующей тренировки"
        "WAITING" -> "Ожидает отправки и синхронизации. При подключении расчёт начнётся в фоне"
        "QUEUED",
        "RUNNING" -> "Готовим предложение на $date"
        "PAUSED_WAITING",
        "PAUSED_STATUS" -> "Не удалось завершить обмен с сервером. Повторите попытку"
        "READY" ->
            "На $date · ${proposal?.snapshot?.draft?.exercises?.size ?: 0} упр. · желаемое время ${intent?.availableDurationMinutes} мин"
        "STALE" ->
            "Данные тренировок изменились. Повторите расчёт с актуальными данными — выбранные условия сохранятся."
        "SUPERSEDED" ->
            "Этот расчёт заменён другим. Можно подготовить новый план с теми же условиями."
        "EXPIRED" -> "Выбранная дата прошла. Выберите новую дату"
        else ->
            calendarAiErrorMessage(BackendException(400, row?.errorCode ?: "ai_unknown_error", ""))
      }
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text("Следующая тренировка", style = MaterialTheme.typography.titleLarge)
    if (row?.state in WorkoutPreparationRepository.activeStates) {
      LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
    }
    Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (proposal != null && row?.state != "READY") {
      Text(
          if (row?.state in WorkoutPreparationRepository.activeStates)
              "Готовим новый вариант. Предыдущий доступен для просмотра"
          else "Предыдущий вариант требует повторной проверки"
      )
    }
    retryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val canRetry =
        displayState in setOf("STALE", "SUPERSEDED", "FAILED", "PAUSED_WAITING", "PAUSED_STATUS")
    val actionLabel =
        when (displayState) {
          null -> "Подготовить тренировку"
          "WAITING",
          "QUEUED",
          "RUNNING" -> "Изменить условия"
          "PAUSED_WAITING",
          "PAUSED_STATUS",
          "FAILED" -> if (retrying) "Повторяем…" else "Повторить"
          "STALE",
          "SUPERSEDED" -> if (retrying) "Повторяем…" else "Повторить расчёт"
          "EXPIRED" -> "Выбрать новую дату"
          else -> "Пересчитать"
        }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (proposal != null) {
        PillButton(
            text = "Посмотреть",
            onClick = {
              haptics.tap()
              onOpen(proposal.proposalId)
            },
        )
      }
      OutlinedButton(
          modifier = Modifier.heightIn(min = 56.dp),
          contentPadding = PaddingValues(horizontal = 16.dp),
          enabled = !retrying,
          onClick = {
            haptics.tap()
            if (canRetry && onRetry != null) onRetry() else onPrepare()
          },
      ) {
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (canRetry) TextButton(onClick = onPrepare, enabled = !retrying) { Text("Изменить условия") }
  }
}
