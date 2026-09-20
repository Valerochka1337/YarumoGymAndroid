package com.valerochka1337.valerochkagym.ui.coach

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.CoachBehaviorEntity
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

@Composable
internal fun CoachBehaviorCard(
    entry: CoachBehaviorEntity,
    busy: Boolean,
    answer: (String, String) -> Unit,
    resolve: (String) -> Unit,
) {
  val data =
      remember(entry.payload) {
        runCatching { Json.parseToJsonElement(entry.payload).jsonObject }.getOrNull()
      } ?: return
  val concern = entry.kind == "concern"
  val question = data["question"] as? JsonObject
  var expired by remember(entry.id) { mutableStateOf(false) }
  LaunchedEffect(entry.id) {
    val expires =
        question?.get("expiresAtMillis")?.jsonPrimitive?.longOrNull ?: return@LaunchedEffect
    delay((expires - System.currentTimeMillis()).coerceAtLeast(0))
    expired = true
  }
  val haptics = gymHaptics()
  GymCard(
      modifier =
          Modifier.fillMaxWidth().semantics {
            liveRegion = if (concern) LiveRegionMode.Assertive else LiveRegionMode.Polite
          }
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          if (concern) "Сообщение о самочувствии" else "Уточнение тренера",
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          (data["text"] as? JsonPrimitive)?.contentOrNull ?: "Уточните, что произошло",
          style = MaterialTheme.typography.bodyLarge,
      )
      when {
        concern ->
            OutlinedButton(
                onClick = {
                  haptics.tap()
                  resolve(entry.id)
                },
                enabled = !busy,
            ) {
              Text("Жалоба разрешена")
            }
        entry.status == "PENDING" -> Text("Ответ сохранён. Отправим при подключении.")
        expired -> Text("Вопрос больше не актуален. План сохранён.")
        else -> {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (question?.get("options") as? JsonArray)?.forEach { value ->
              val option = value.jsonObject
              val id = option["id"]?.jsonPrimitive?.content ?: return@forEach
              OutlinedButton(
                  onClick = {
                    haptics.tap()
                    answer(entry.id, id)
                  },
                  enabled = !busy,
              ) {
                Text(option["text"]?.jsonPrimitive?.content ?: id)
              }
            }
          }
          Text(
              "Ответ уточняет причину. Изменения плана потребуют отдельного подтверждения.",
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}
