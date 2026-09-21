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
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalWire
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.rememberDeviceTimeZone
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun proposalCalculationLabel(state: String): String? =
    when (state) {
      "WAITING" -> "Ожидаем отправки и синхронизации…"
      "QUEUED" -> "Расчёт в очереди…"
      "RUNNING" -> "Составляем тренировку…"
      "PAUSED_WAITING",
      "PAUSED_STATUS" -> "Расчёт приостановлен. Нажмите «Составить с ИИ», чтобы повторить."
      "FAILED" -> "Не удалось завершить расчёт. Нажмите «Составить с ИИ», чтобы повторить."
      "STALE",
      "EXPIRED" -> "Условия расчёта устарели. Составьте план ещё раз."
      "SUPERSEDED" -> "Расчёт заменён другим запросом. При необходимости составьте план ещё раз."
      else -> null
    }

@Composable
internal fun ProposalCalculationCard(preparation: PreparationEntity, label: String) {
  val intent =
      remember(preparation.intentJson) {
        runCatching { ProposalWire.json.decodeFromString<CalendarAiIntent>(preparation.intentJson) }
            .getOrNull()
      }
  val zone = rememberDeviceTimeZone()
  GymCard(
      modifier =
          Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Расчёт предложения"
            disabled()
            stateDescription = label
            liveRegion = LiveRegionMode.Polite
          },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          "Тренировка с ИИ",
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
    }
  }
}
