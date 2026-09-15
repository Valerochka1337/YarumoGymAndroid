package com.valerochka1337.valerochkagym.domain

/** planner-duration-v1. Listed warmup counts once; no implicit warmup allowance. */
object PlannerDuration {
  data class Exercise(val durations: List<Int?>, val restSeconds: Int?)

  fun seconds(exercises: List<Exercise>, defaultRestSeconds: Int = 90): Long =
      exercises.sumOf { exercise ->
        exercise.durations.sumOf { (it ?: 45).toLong() } +
            (exercise.durations.size - 1).coerceAtLeast(0).toLong() *
                (exercise.restSeconds ?: defaultRestSeconds)
      } + (exercises.size - 1).coerceAtLeast(0) * 90L

  fun minimumSeconds(minutes: Int): Long = minutes * 60L - maxOf(300L, minutes * 12L)

  fun minutes(seconds: Long): Long = (seconds + 30) / 60
}
