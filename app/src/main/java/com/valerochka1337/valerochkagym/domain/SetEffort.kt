package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity

/** User selection combines the purpose of the set and its reported proximity to failure. */
enum class SetEffort(val label: String, val rir: Int? = null) {
  FAILURE("Отказ", 0),
  ONE("1", 1),
  TWO("2", 2),
  THREE("3", 3),
  FOUR_PLUS("4+"),
  WARMUP("Разминка");

  fun applyTo(set: WorkoutSetEntity): WorkoutSetEntity =
      set.copy(
          setType = if (this == WARMUP) "WARMUP" else "WORK",
          actualRir = rir,
          actualRirAtLeastFour = this == FOUR_PLUS,
      )

  companion object {
    fun selectedFor(set: WorkoutSetEntity): SetEffort? =
        when {
          set.setType == "WARMUP" -> WARMUP
          set.actualRirAtLeastFour -> FOUR_PLUS
          set.actualRir == 0 -> FAILURE
          set.actualRir == 1 -> ONE
          set.actualRir == 2 -> TWO
          set.actualRir == 3 -> THREE
          else -> null
        }

    fun labelFor(set: WorkoutSetEntity): String = selectedFor(set)?.label ?: "Не указано"
  }
}
