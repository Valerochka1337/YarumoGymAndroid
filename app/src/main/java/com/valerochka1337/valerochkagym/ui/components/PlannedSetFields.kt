package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType

/** Typed planned values shared by the routine and proposal editors. */
@Composable
internal fun PlannedSetFields(
    type: ExerciseType,
    set: PlannedSet,
    onChange: (PlannedSet) -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
) {
  @Composable
  fun Fields(fieldModifier: Modifier) {
    when (type) {
      ExerciseType.STRENGTH -> {
        NumberField(
            set.weightKg.field(),
            { onChange(set.copy(weightKg = it.toDoubleOrNull())) },
            fieldModifier,
            number?.let { "Вес подхода $it, кг" } ?: "кг",
            decimal = true,
        )
        NumberField(
            set.reps?.toString().orEmpty(),
            { onChange(set.copy(reps = it.toIntOrNull())) },
            fieldModifier,
            number?.let { "Повторы подхода $it" } ?: "повт",
        )
      }
      ExerciseType.TIMED ->
          NumberField(
              set.durationSec?.toString().orEmpty(),
              { onChange(set.copy(durationSec = it.toIntOrNull())) },
              fieldModifier,
              number?.let { "Длительность подхода $it, сек" } ?: "сек",
          )
      ExerciseType.CARDIO -> {
        NumberField(
            set.speedKmh.field(),
            { onChange(set.copy(speedKmh = it.toDoubleOrNull())) },
            fieldModifier,
            number?.let { "Скорость подхода $it, км/ч" } ?: "км/ч",
            decimal = true,
        )
        NumberField(
            set.inclinePct.field(),
            { onChange(set.copy(inclinePct = it.toDoubleOrNull())) },
            fieldModifier,
            number?.let { "Наклон подхода $it, %" } ?: "накл",
            decimal = true,
            signed = true,
        )
        val seconds = number != null
        NumberField(
            set.durationSec?.let { if (seconds) it else it / 60 }?.toString().orEmpty(),
            { value ->
              onChange(
                  set.copy(
                      durationSec =
                          value.toIntOrNull()?.let {
                            if (seconds) it
                            else
                                (it.toLong() * 60)
                                    .takeIf { total -> total <= Int.MAX_VALUE }
                                    ?.toInt()
                          }
                  )
              )
            },
            fieldModifier,
            number?.let { "Длительность подхода $it, сек" } ?: "мин",
        )
      }
    }
  }
  if (number != null)
      Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Fields(Modifier.fillMaxWidth())
      }
  else
      Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Fields(Modifier.weight(1f))
      }
}

private fun Double?.field(): String =
    this?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
