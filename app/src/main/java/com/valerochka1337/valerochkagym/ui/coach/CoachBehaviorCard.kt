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
import kotlinx.serialization.json.*

@Composable
internal fun CoachBehaviorCard(
    entry: CoachBehaviorEntity,
    busy: Boolean,
    answer: (String, String) -> Unit,
) {
  val data =
      remember(entry.payload) {
        runCatching { Json.parseToJsonElement(entry.payload).jsonObject }.getOrNull()
      } ?: return
  val question = data["question"] as? JsonObject
  val haptics = gymHaptics()
  GymCard(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          "Тренер",
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          (data["text"] as? JsonPrimitive)?.contentOrNull ?: "Уточните, что произошло",
          style = MaterialTheme.typography.bodyLarge,
      )
      when {
        entry.status == "PENDING" -> Text("Ответ сохранён. Отправим при подключении.")
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
              "Можно ответить своими словами в сообщении.",
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}
