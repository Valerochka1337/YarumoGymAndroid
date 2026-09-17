package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics

/** Durable values belong to the workout, not the process. Update only with the saved message. */
data class CoachInitiativeState(
    val enabled: Boolean = true,
    val welcomed: Boolean = false,
    /** Persisted diagnostics only; count and timestamp do not gate initiative. */
    val automaticCount: Int = 0,
    val lastAutomaticAtMillis: Long? = null,
    val askedExerciseIds: Set<String> = emptySet(),
    val endReminderSent: Boolean = false,
    val pendingInteraction: Boolean = false,
)

/** Host supplies completed strength sets only; known modes/interruptions remain explicit. */
data class CoachPerformanceSet(
    val setId: String,
    val workoutId: String,
    val exerciseId: String,
    val exerciseName: String,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val completedAtMillis: Long,
    val workoutFinishedAtMillis: Long? = null,
    val setType: String = "UNKNOWN",
    val interrupted: Boolean = false,
) {
  internal val comparable: Boolean
    get() =
        !interrupted &&
            setType in setOf("WORK", "UNKNOWN") &&
            weightKg?.let { it.isFinite() && it >= 0 } == true &&
            reps != null &&
            reps >= 0
}

enum class CoachInitiativeKind {
  AUTOREGULATION,
  WELCOME,
  PERFORMANCE_QUESTION,
  END_REMINDER,
}

data class CoachInitiativeDecision(
    val kind: CoachInitiativeKind,
    val text: String,
    val nextState: CoachInitiativeState,
    val exerciseId: String? = null,
)

/** A product heuristic for asking a question; it never changes a load or assesses health. */
object CoachInitiativePolicy {
  fun next(
      state: CoachInitiativeState,
      nowMillis: Long,
      completedSets: List<CoachPerformanceSet>,
      history: List<CoachPerformanceSet>,
      endsAtMillis: Long? = null,
      assessment:
          com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationRecommendation? =
          null,
  ): CoachInitiativeDecision? {
    fun skip(reason: String): CoachInitiativeDecision? {
      CoachDiagnostics.event(
          "initiative.skipped",
          "reason" to reason,
          "automatic_count" to state.automaticCount,
      )
      return null
    }
    if (!state.enabled) return skip("disabled")
    if (state.pendingInteraction) return skip("pending_interaction")
    fun decision(
        kind: CoachInitiativeKind,
        text: String,
        exerciseId: String? = null,
    ): CoachInitiativeDecision =
        CoachInitiativeDecision(
                kind,
                text,
                state.copy(
                    welcomed = state.welcomed || kind == CoachInitiativeKind.WELCOME,
                    automaticCount = state.automaticCount + 1,
                    lastAutomaticAtMillis = nowMillis,
                    askedExerciseIds = state.askedExerciseIds + listOfNotNull(exerciseId),
                    endReminderSent =
                        state.endReminderSent || kind == CoachInitiativeKind.END_REMINDER,
                    pendingInteraction =
                        kind == CoachInitiativeKind.PERFORMANCE_QUESTION ||
                            (kind == CoachInitiativeKind.AUTOREGULATION &&
                                assessment?.kind !=
                                    com.valerochka1337.valerochkagym.domain.autoregulation
                                        .RecommendationKind
                                        .ADVISE),
                ),
                exerciseId,
            )
            .also { CoachDiagnostics.event("initiative.selected", "kind" to kind) }
    if (assessment != null) {
      if (
          assessment.missingData.any {
            it in
                setOf(
                    com.valerochka1337.valerochkagym.domain.autoregulation.MissingData.SET_TYPE,
                    com.valerochka1337.valerochkagym.domain.autoregulation.MissingData.ACTUAL_RIR,
                )
          }
      )
          return skip("optional_set_effort")

      val key = "autoregulation:${assessment.interventionKey}"
      if (
          assessment.kind ==
              com.valerochka1337.valerochkagym.domain.autoregulation.RecommendationKind.NO_CHANGE
      )
          return skip("no_change")
      if (key in state.askedExerciseIds) return skip("already_asked")
      return decision(CoachInitiativeKind.AUTOREGULATION, assessment.explanation(), key)
    }
    if (!state.endReminderSent && endsAtMillis != null && nowMillis >= endsAtMillis - 5 * 60_000L) {
      return decision(
          CoachInitiativeKind.END_REMINDER,
          if (nowMillis >= endsAtMillis)
              "Заданное время тренировки закончилось. Можно завершить занятие или уточнить оставшееся время."
          else
              "До заданного окончания осталось не больше пяти минут. Можно завершить главное или скорректировать остаток.",
      )
    }
    val current =
        completedSets
            .distinctBy { it.setId }
            .filter { it.workoutFinishedAtMillis == null }
            .sortedBy { it.completedAtMillis }
    val latest = current.lastOrNull() ?: return null
    if (!latest.comparable || latest.exerciseId in state.askedExerciseIds) return null
    val exerciseSets = current.filter { it.exerciseId == latest.exerciseId && it.comparable }
    val lastTwo = exerciseSets.takeLast(2)
    if (lastTwo.size < 2 || lastTwo.any { it.weightKg != latest.weightKg }) return null
    val uniqueHistory = history.distinctBy { it.setId }
    val recentHistoryIds =
        uniqueHistory
            .filter { it.exerciseId == latest.exerciseId && it.workoutFinishedAtMillis != null }
            .groupBy { it.workoutId }
            .entries
            .sortedByDescending { (_, sets) -> sets.maxOf { it.workoutFinishedAtMillis!! } }
            .take(3)
            .map { it.key }
            .toSet()
    fun reference(target: CoachPerformanceSet): Double? {
      val historical =
          uniqueHistory
              .filter {
                it.workoutId in recentHistoryIds &&
                    it.exerciseId == target.exerciseId &&
                    it.comparable &&
                    it.setIndex == target.setIndex &&
                    it.weightKg == target.weightKg
              }
              .groupBy { it.workoutId }
              .values
              .mapNotNull { sets ->
                // Multiple sections with this exercise/index in one session are ambiguous: exclude
                // it.
                sets.singleOrNull()?.reps?.toDouble()
              }
              .sorted()
      if (historical.isNotEmpty()) {
        val middle = historical.size / 2
        return if (historical.size % 2 == 1) historical[middle]
        else (historical[middle - 1] + historical[middle]) / 2.0
      }
      // Without matching history use the first earlier comparable set at this weight.
      return exerciseSets
          .firstOrNull {
            it.weightKg == target.weightKg && it.completedAtMillis < target.completedAtMillis
          }
          ?.reps
          ?.toDouble()
    }
    if (lastTwo.any { set -> reference(set)?.let { it - set.reps!! >= 2.0 } != true }) return null
    return decision(
        CoachInitiativeKind.PERFORMANCE_QUESTION,
        "В двух сопоставимых подходах «${latest.exerciseName}» получилось меньше повторений. Это было запланировано или что-то мешает?",
        latest.exerciseId,
    )
  }
}
