package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.ai.CalendarAiIntent
import com.valerochka1337.valerochkagym.data.ai.PreparationEntity
import com.valerochka1337.valerochkagym.data.ai.WorkoutPreparationRepository
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalWire
import com.valerochka1337.valerochkagym.ui.calendarai.calendarAiErrorMessage
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.rememberDeviceTimeZone
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun proposalCalculationLabel(state: String, errorCode: String? = null): String? =
    when (state) {
      "WAITING" -> "Ожидаем отправки и синхронизации…"
      "QUEUED" -> "Расчёт в очереди…"
      "RUNNING" -> "Составляем тренировку…"
      "PAUSED_WAITING",
      "PAUSED_STATUS" -> "Расчёт приостановлен."
      "FAILED" ->
          errorCode?.let { calendarAiErrorMessage(BackendException(400, it, "")) }
              ?: "Не удалось завершить расчёт."
      "STALE",
      "EXPIRED" -> "Условия расчёта устарели. Составьте новый план."
      "SUPERSEDED" -> "Расчёт заменён другим запросом."
      "IMPOSSIBLE" ->
          "С этими условиями готовый план не найден. Измените условия или отредактируйте тренировку вручную."
      "UPDATE_REQUIRED" -> "Для планирования нужна более новая версия приложения."
      else -> null
    }

private fun PreparationEntity.canRetryCalculation(): Boolean =
    state in setOf("PAUSED_WAITING", "PAUSED_STATUS") ||
        (state in setOf("FAILED", "STALE", "SUPERSEDED") &&
            !(protocolVersion == 2 && proposalId != null))

@Composable
internal fun ProposalCalculationCard(
    preparation: PreparationEntity,
    label: String,
    onRetry: () -> Unit = {},
    retrying: Boolean = false,
    retryEnabled: Boolean = true,
    retryError: String? = null,
) {
  val haptics = gymHaptics()
  val intent =
      remember(preparation.intentJson) {
        runCatching { ProposalWire.json.decodeFromString<CalendarAiIntent>(preparation.intentJson) }
            .getOrNull()
      }
  val zone = rememberDeviceTimeZone()
  val retryLabel =
      when (preparation.state) {
        "PAUSED_WAITING",
        "PAUSED_STATUS",
        "FAILED" -> if (preparation.canRetryCalculation()) "Повторить" else null
        "STALE",
        "SUPERSEDED" -> if (preparation.canRetryCalculation()) "Повторить расчёт" else null
        else -> null
      }
  GymCard(
      modifier =
          Modifier.fillMaxWidth().semantics {
            contentDescription = "Расчёт предложения"
            if (preparation.state in WorkoutPreparationRepository.activeStates) disabled()
            stateDescription = if (retrying && retryLabel != null) "Повторяем расчёт" else label
            liveRegion = LiveRegionMode.Polite
          },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          "План тренировки",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      intent?.let {
        Text(
            "Начало: " +
                Instant.ofEpochMilli(it.startsAtMillis)
                    .atZone(zone)
                    .format(
                        DateTimeFormatter.ofPattern(
                            "d MMM yyyy, HH:mm",
                            Locale.forLanguageTag("ru"),
                        )
                    ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (preparation.state in WorkoutPreparationRepository.activeStates) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      Text(
          label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      retryError?.let { message ->
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
      retryLabel?.let { actionLabel ->
        PillButton(
            text = if (retrying) "Повторяем…" else actionLabel,
            onClick = {
              haptics.tap()
              onRetry()
            },
            enabled = retryEnabled && !retrying,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
