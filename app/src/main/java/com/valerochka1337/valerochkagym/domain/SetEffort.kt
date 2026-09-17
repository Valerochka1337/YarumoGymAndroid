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
    fun labelFor(set: WorkoutSetEntity): String =
        when {
          set.setType == "WARMUP" -> WARMUP.label
          set.actualRirAtLeastFour -> FOUR_PLUS.label
          set.actualRir == 0 -> FAILURE.label
          else -> set.actualRir?.toString() ?: "Не указано"
        }
  }
}
