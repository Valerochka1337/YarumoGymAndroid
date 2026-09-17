package com.valerochka1337.valerochkagym.domain.autoregulation

import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.domain.analysis.OneRepMax
import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
enum class TrainingGoal {
  PRESERVE_PLAN,
  STRENGTH,
  HYPERTROPHY,
}

/** Explicit context only. Empty equipment data means unknown, not unrestricted weights. */
@Serializable
data class AutoregulationOptions(
    val goal: TrainingGoal = TrainingGoal.PRESERVE_PLAN,
    val availableWeightsKg: Map<String, List<Double>> = emptyMap(),
    val observedRestSeconds: Int? = null,
)

@Serializable
enum class RecommendationKind {
  NO_CHANGE,
  ADVISE,
  CLARIFY,
  ADJUST,
}

@Serializable
enum class MissingData {
  SET_TYPE,
  ACTUAL_RIR,
  RESULT,
  EQUIPMENT,
  REST,
  INTENT,
  TIME_ESTIMATE,
}

@Serializable
data class AutoregulationRecommendation(
    val accountId: String,
    val workoutId: String,
    val baseRevision: Long,
    val kind: RecommendationKind,
    val observation: String,
    val reason: String,
    val expectedEffect: String,
    val missingData: Set<MissingData> = emptySet(),
    val operations: List<WorkoutChangeSet.Operation> = emptyList(),
    val rulesVersion: String = AutoregulationEngine.RULES_VERSION,
    val evidenceKey: String,
    val options: AutoregulationOptions,
    val interventionKey: String = evidenceKey,
) {
  fun packet(): WorkoutChangeSet.Packet? =
      operations
          .takeIf { it.isNotEmpty() }
          ?.let {
            WorkoutChangeSet.Packet(
                it,
                AutoregulationProof(rulesVersion, evidenceKey, options, interventionKey),
            )
          }

  fun explanation(): String =
      listOf(observation, reason, expectedEffect).filter { it.isNotBlank() }.joinToString(" ")
}

@Serializable
data class AutoregulationProof(
    val rulesVersion: String,
    val evidenceKey: String,
    val options: AutoregulationOptions,
    val interventionKey: String = evidenceKey,
)

/**
 * Deterministic local calculation; thresholds and limits are documented in docs/autoregulation.md.
 */
object AutoregulationEngine {
  const val RULES_VERSION = "1.3.0"

  fun calculate(
      snapshot: WorkoutSnapshot,
      options: AutoregulationOptions = snapshot.autoregulationOptions,
  ): AutoregulationRecommendation {
    val trace =
        CoachDiagnostics.span(
            "autoregulation.calculate",
            "revision" to snapshot.revision,
            "goal" to options.goal,
            "rules" to RULES_VERSION,
        )
    try {
      val sets = snapshot.exercises.flatMap { it.sets }
      CoachDiagnostics.event(
          "autoregulation.inputs",
          "trace" to trace.id,
          "sets" to sets.size,
          "completed" to sets.count { it.completed },
          "remaining_minutes" to snapshot.availableTimeMinutes,
          "observed_rest_seconds" to options.observedRestSeconds,
      )
      // Wall time and pulse are deliberately absent. Only the remaining-time minute bucket matters.
      val evidence =
          listOf(
                  snapshot.accountId,
                  snapshot.workoutId,
                  snapshot.exercises,
                  snapshot.availableTimeMinutes,
                  snapshot.futureRestSeconds,
                  snapshot.rest?.plannedSeconds,
                  snapshot.rest?.startId,
                  snapshot.excludedExerciseIds,
                  options,
              )
              .joinToString("|")
      fun digest(value: String) =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.toByteArray(Charsets.UTF_8))
              .joinToString("") { "%02x".format(it) }
      val key = digest(evidence)
      val performanceKey =
          digest(
              "${snapshot.accountId}|${snapshot.workoutId}|${sets.filter { it.completed }}|${options.copy(observedRestSeconds = null)}"
          )
      fun result(
          rule: String,
          kind: RecommendationKind,
          observation: String,
          reason: String = "",
          effect: String = "",
          missing: Set<MissingData> = emptySet(),
          operations: List<WorkoutChangeSet.Operation> = emptyList(),
          interventionKey: String = performanceKey,
      ) =
          AutoregulationRecommendation(
                  snapshot.accountId,
                  snapshot.workoutId,
                  snapshot.revision,
                  kind,
                  observation,
                  reason,
                  effect,
                  missing,
                  operations,
                  evidenceKey = key,
                  options = options,
                  interventionKey = interventionKey,
              )
              .also {
                operations.forEach { operation ->
                  when (operation) {
                    is WorkoutChangeSet.Operation.EditSet ->
                        CoachDiagnostics.event(
                            "autoregulation.change",
                            "trace" to trace.id,
                            "operation" to "edit_set",
                            "weight_kg" to operation.weightKg,
                            "reps" to operation.reps,
                        )
                    is WorkoutChangeSet.Operation.Rest ->
                        CoachDiagnostics.event(
                            "autoregulation.change",
                            "trace" to trace.id,
                            "operation" to "rest",
                            "action" to operation.action,
                            "seconds" to operation.seconds,
                        )
                    else ->
                        CoachDiagnostics.event(
                            "autoregulation.change",
                            "trace" to trace.id,
                            "operation" to operation.javaClass.simpleName,
                        )
                  }
                }
                trace.finish(
                    "decision" to kind,
                    "rule" to rule,
                    "missing" to missing.joinToString(",") { it.name },
                    "operations" to operations.joinToString(",") { it.javaClass.simpleName },
                )
              }
      fun clarify(rule: String, observation: String, reason: String, vararg missing: MissingData) =
          result(rule, RecommendationKind.CLARIFY, observation, reason, missing = missing.toSet())
      if (
          sets.map { it.syncId }.distinct().size != sets.size ||
              options.availableWeightsKg.values.flatten().any { !it.isFinite() || it <= 0 } ||
              options.observedRestSeconds?.let { it < 0 } == true
      )
          return clarify(
              "invalid_input",
              "Данные расчёта некорректны.",
              "Уточните результаты и оборудование.",
              MissingData.RESULT,
          )
      val remaining = sets.filter { !it.completed }
      if (remaining.isEmpty())
          return result(
              "no_remaining_sets",
              RecommendationKind.NO_CHANGE,
              "Оставшихся подходов нет.",
          )
      snapshot.exercises
          .firstOrNull {
            it.exerciseId in snapshot.excludedExerciseIds && it.sets.any { set -> !set.completed }
          }
          ?.let { unavailable ->
            return clarify(
                "excluded_exercise",
                "«${unavailable.name}» исключено, но в нём остались подходы.",
                "Уточните доступное оборудование: можно подобрать замену и сохранить выполненные результаты.",
                MissingData.EQUIPMENT,
            )
          }
      val latest =
          sets
              .filter { it.completed }
              .maxWithOrNull(
                  compareBy<SnapshotSet> { it.completedAt ?: Long.MIN_VALUE }
                      .thenBy { sets.indexOf(it) }
              )
      val rest = snapshot.futureRestSeconds ?: snapshot.rest?.plannedSeconds

      // Time budgeting removes only a suffix, preserving order and every recorded result.
      snapshot.availableTimeMinutes?.let { minutes ->
        if (rest != null && rest > 0 && remaining.all { it.setType == "WORK" && it.reps != null }) {
          val secondsPerSet =
              60L + rest // product estimate: execution + transition + prescribed rest
          val capacity = ((minutes.coerceAtLeast(0) * 60L + rest) / secondsPerSet).toInt()
          if (capacity < remaining.size) {
            val removed = remaining.drop(capacity)
            return result(
                "time_capacity",
                RecommendationKind.ADJUST,
                "Осталось $minutes мин и ${remaining.size} рабочих подходов при отдыхе $rest с.",
                "По приблизительной оценке времени помещается $capacity подходов в текущем порядке.",
                "Убрать последние ${removed.size} подходов, сохранив выполненные результаты и длительность отдыха.",
                operations = removed.map { WorkoutChangeSet.Operation.DeleteSet(it.syncId) },
                interventionKey =
                    digest("time|${snapshot.workoutId}|$capacity|${remaining.map { it.syncId }}"),
            )
          }
        } else if (minutes <= 5) {
          return clarify(
              "missing_time_context",
              "Осталось $minutes мин и ${remaining.size} подходов.",
              "Уточните длительность отдыха и приоритетные упражнения перед сокращением занятия.",
              MissingData.TIME_ESTIMATE,
              MissingData.REST,
          )
        }
      }
      if (latest == null)
          return result(
              "no_completed_sets",
              RecommendationKind.NO_CHANGE,
              "Нет выполненных подходов для оценки.",
          )
      if (latest.reportedFeelings.any { it in setOf("PAIN", "TECHNIQUE_BREAKDOWN") })
          return clarify(
              "reported_safety_issue",
              "Вы сообщили о боли или нарушении техники.",
              "Уточните, что произошло, перед планированием продолжения.",
              MissingData.INTENT,
          )
      if ("PLANNED_EFFORT" in latest.reportedFeelings)
          return result(
              "planned_effort",
              RecommendationKind.NO_CHANGE,
              "Вы подтвердили, что усилие было запланировано.",
              "Сохраняем оставшийся план.",
          )
      val harderConfirmed = "HARDER_THAN_EXPECTED" in latest.reportedFeelings
      if ("INTERRUPTED" in latest.reportedFeelings)
          return clarify(
              "interrupted_set",
              "Последний подход прерван.",
              "Это было запланировано или стало тяжелее?",
              MissingData.INTENT,
          )
      if (latest.setType == "WARMUP")
          return result(
              "warmup",
              RecommendationKind.NO_CHANGE,
              "Разминка не используется для изменения рабочей нагрузки.",
          )
      val section = snapshot.exercises.single { latest in it.sets }
      if (section.type != com.valerochka1337.valerochkagym.data.db.entity.ExerciseType.STRENGTH)
          return result(
              "non_strength",
              RecommendationKind.NO_CHANGE,
              "Правила RIR применяются к силовым подходам.",
          )
      if (latest.setType != "WORK") {
        return clarify(
            "unknown_set_type",
            "Тип выполненного подхода не определён как рабочий.",
            "Уточните: рабочий подход, разминка или специальный режим?",
            MissingData.SET_TYPE,
        )
      }
      val actual = latest.actualRir
      val currentWeight = latest.actualWeightKg ?: latest.weightKg
      val currentReps = latest.actualReps ?: latest.reps
      // Adjacent completed working sets in this exercise, never historical sessions.
      // Do not bridge an interrupted or special set to an older result.
      val completed =
          section.sets
              .filter { it.completed }
              .sortedWith(
                  compareBy<SnapshotSet> { it.completedAt ?: Long.MIN_VALUE }.thenBy { it.setIndex }
              )
      val previous =
          completed.getOrNull(completed.indexOf(latest) - 1)?.takeIf {
            it.setType == "WORK" &&
                it.reportedFeelings.none { feeling ->
                  feeling in setOf("INTERRUPTED", "PAIN", "TECHNIQUE_BREAKDOWN")
                }
          }
      val previousWeight = previous?.let { it.actualWeightKg ?: it.weightKg }
      val previousReps = previous?.let { it.actualReps ?: it.reps }
      val sameWeight = previousWeight != null && previousWeight == currentWeight
      val drop =
          if (sameWeight && previousReps != null && currentReps != null) previousReps - currentReps
          else null
      CoachDiagnostics.event(
          "autoregulation.adjacent",
          "trace" to trace.id,
          "previous_weight" to previousWeight,
          "current_weight" to currentWeight,
          "previous_reps" to previousReps,
          "current_reps" to currentReps,
          "rep_drop" to drop,
          "actual_rir" to actual,
          "rir_four_plus" to latest.actualRirAtLeastFour,
      )
      if (
          currentWeight != null &&
              currentWeight.isFinite() &&
              currentWeight > 0 &&
              currentReps != null &&
              currentReps > 0
      ) {
        val lowReps = currentReps < 5
        val highReps = currentReps > 15
        val restDrop = drop != null && drop > 2
        if (lowReps || highReps || restDrop) {
          val nextSet = section.sets.sortedBy { it.setIndex }.firstOrNull { !it.completed }
          val observation =
              if (previousWeight != null && previousReps != null)
                  "«${section.name}»: $previousWeight кг × $previousReps → $currentWeight кг × $currentReps."
              else "«${section.name}»: $currentWeight кг × $currentReps."
          val operations = mutableListOf<WorkoutChangeSet.Operation>()
          val notes = mutableListOf<String>()
          var rule = "adjacent_rep_drop"
          val canEditNext =
              nextSet != null &&
                  nextSet.setType in setOf("WORK", "UNKNOWN") &&
                  (nextSet.targetWeightKg ?: nextSet.weightKg) == currentWeight
          if (lowReps || highReps) {
            rule = if (lowReps) "below_five_reps" else "above_fifteen_reps"
            val candidate =
                options.availableWeightsKg[section.exerciseSyncId]
                    .orEmpty()
                    .filter {
                      if (lowReps) it < currentWeight && it >= currentWeight * .95
                      else it > currentWeight && it <= currentWeight * 1.05
                    }
                    .minByOrNull { abs(it - currentWeight) }
            if (candidate != null && canEditNext) {
              operations +=
                  WorkoutChangeSet.Operation.EditSet(nextSet!!.syncId, weightKg = candidate)
              notes +=
                  if (lowReps) "Предлагаю снизить вес следующего подхода до $candidate кг."
                  else "Предлагаю повысить вес следующего подхода до $candidate кг."
            } else {
              notes +=
                  if (lowReps)
                      "Получилось меньше 5 повторений: рекомендую снизить вес на небольшой доступный шаг; одного этого результата недостаточно для прекращения упражнения."
                  else
                      "Получилось больше 15 повторений: рекомендую повысить вес на небольшой доступный шаг."
              if (nextSet != null && !canEditNext)
                  notes += "Следующий подход уже отличается по весу или режиму; его план не меняю."
              else if (candidate == null)
                  notes +=
                      "Подходящий доступный шаг веса не задан, поэтому конкретный вес не подставляю."
            }
          }
          if (restDrop) {
            notes +=
                "При том же весе потеряно больше 2 повторений: предлагаю увеличить отдых между подходами на 30 секунд."
            if (rest != null && rest in 1..299) {
              operations +=
                  WorkoutChangeSet.Operation.Rest(
                      RestAction.FUTURE_DURATION,
                      null,
                      (rest + 30).coerceAtMost(300),
                  )
              snapshot.rest
                  ?.takeIf {
                    it.plannedSeconds != null &&
                        it.plannedSeconds in 1..299 &&
                        (it.remainingSeconds ?: 0) > 0
                  }
                  ?.let {
                    operations +=
                        WorkoutChangeSet.Operation.Rest(
                            RestAction.EXTEND,
                            it.startId,
                            minOf(30, 300 - it.plannedSeconds!!),
                        )
                  }
            } else if (rest == null)
                notes += "Текущая длительность отдыха неизвестна; таймер автоматически не задаю."
            else notes += "Заданный отдых уже не меньше 5 минут; автоматически его не удлиняю."
          }
          return result(
              rule,
              if (operations.isEmpty()) RecommendationKind.ADVISE else RecommendationKind.ADJUST,
              observation,
              notes.joinToString(" "),
              "Диапазон 5–15 — ориентир приложения. Если такой режим был запланирован, сохраните план.",
              operations = operations,
          )
        }
      }
      if (latest.actualRirAtLeastFour)
          return result(
              "rir_range",
              RecommendationKind.NO_CHANGE,
              "Запас составляет не меньше четырёх повторений; точное значение неизвестно.",
              "Диапазон 4+ не подставляется в расчёт как точный RIR 4.",
          )
      if (actual != null && actual !in 0..10)
          return clarify(
              "invalid_rir",
              "RIR вне диапазона 0–10.",
              "Уточните запас повторений.",
              MissingData.ACTUAL_RIR,
          )
      val observation = "«${section.name}»: фактический запас ${actual?.toString() ?: "не указан"}."
      if (!harderConfirmed)
          return result(
              "recorded_effort",
              RecommendationKind.NO_CHANGE,
              observation,
              "Фактический RIR записан; сам по себе он не является основанием менять нагрузку.",
          )
      val weight = latest.actualWeightKg ?: latest.weightKg
      val reps = latest.actualReps ?: latest.reps
      if (weight == null || !weight.isFinite() || weight <= 0 || reps == null || reps < 1)
          return clarify(
              "missing_result",
              observation,
              "Для корректировки нужен записанный вес и число повторений.",
              MissingData.RESULT,
          )
      val next =
          section.sets.firstOrNull { !it.completed && it.setType == "WORK" }
              ?: return result(
                  "exercise_finished",
                  RecommendationKind.NO_CHANGE,
                  observation,
                  "Рабочие подходы этого упражнения завершены; нагрузку другого упражнения не выводим из этого результата.",
              )
      val nextWeight = next.targetWeightKg ?: next.weightKg
      val nextReps = next.targetReps ?: next.reps
      // Different prescribed load/effort can be a deliberate back-off, ramp or drop set.
      if (nextWeight != weight || nextReps != latest.targetReps)
          return clarify(
              "different_next_target",
              observation,
              "Следующий подход имеет другую цель. Это было запланировано или стало тяжелее?",
              MissingData.INTENT,
          )
      val plannedReps = latest.targetReps
      if (plannedReps == null || nextReps == null || (reps != plannedReps && !harderConfirmed))
          return clarify(
              "unexplained_reps",
              observation,
              "Повторы отличаются от цели или цель неизвестна. Это было запланировано или стало тяжелее?",
              MissingData.INTENT,
          )
      if (options.observedRestSeconds != null && rest != null && options.observedRestSeconds < rest)
          return result(
              "short_rest",
              RecommendationKind.NO_CHANGE,
              observation,
              "Отдых был короче запланированного ($rest с). Сначала выдержите предусмотренный отдых; причина изменения результата пока не ясна.",
          )
      val operations = mutableListOf<WorkoutChangeSet.Operation>()
      val weights = options.availableWeightsKg[section.exerciseSyncId].orEmpty().distinct().sorted()
      val candidate =
          weights.filter { it < weight && it >= weight * .95 }.minByOrNull { abs(it - weight) }
      CoachDiagnostics.event(
          "autoregulation.adjustment",
          "trace" to trace.id,
          "direction" to -1,
          "available_weights" to weights.size,
          "weight_step_found" to (candidate != null),
          "strategy" to if (candidate != null) "weight" else "repetitions",
      )
      if (candidate != null) {
        operations += WorkoutChangeSet.Operation.EditSet(next.syncId, weightKg = candidate)
      } else {
        if (options.goal == TrainingGoal.STRENGTH)
            return clarify(
                "missing_weight_step",
                observation,
                "Для сохранения плановых повторов нужен доступный шаг веса в пределах 5%.",
                MissingData.EQUIPMENT,
            )
        val adjustedReps = (minOf(reps, nextReps) - 1).coerceIn(1, OneRepMax.MAX_TRUSTED_REPS)
        if (nextReps !in 1..OneRepMax.MAX_TRUSTED_REPS || adjustedReps == nextReps)
            return clarify(
                "rep_limit",
                observation,
                "Текущий диапазон повторений выходит за границы правил; уточните желаемое продолжение.",
                MissingData.INTENT,
            )
        operations += WorkoutChangeSet.Operation.EditSet(next.syncId, reps = adjustedReps)
      }
      if (rest != null && rest in 1..299)
          operations +=
              WorkoutChangeSet.Operation.Rest(
                  RestAction.FUTURE_DURATION,
                  null,
                  (rest + 30).coerceAtMost(300),
              )
      val e1rm = OneRepMax.epley(weight, reps)?.takeIf { it.isFinite() }
      val comparableHistory =
          section.history
              .filter {
                it.setType == "WORK" &&
                    !it.interrupted &&
                    it.setIndex == latest.setIndex &&
                    it.weightKg == weight
              }
              .distinctBy { it.setSyncId.ifBlank { it.toString() } }
              .groupBy { it.workoutId }
              .values
              .mapNotNull { it.singleOrNull() }
      val historyNote =
          if (comparableHistory.isEmpty())
              "Сопоставимой истории нет; снижение опирается на сообщение, что стало тяжелее ожидаемого."
          else {
            val ordered = comparableHistory.mapNotNull { it.reps }.sorted()
            val reference = ordered.getOrNull(ordered.size / 2)
            "В сопоставимой истории ${reference ?: "неизвестно"} повторений при этом весе и номере подхода; сейчас $reps. История не заменяет фактический RIR."
          }
      return result(
          "confirmed_harder_adjustment",
          RecommendationKind.ADJUST,
          observation,
          "Вы сообщили, что стало тяжелее ожидаемого. $historyNote" +
              (if (e1rm != null)
                  " Оценка e1RM по записанному результату: ${kotlin.math.round(e1rm)} кг; она не задаёт новый вес."
              else ""),
          "Один небольшой шаг снижает нагрузку следующего рабочего подхода; после него оценим результат.",
          operations = operations,
      )
    } catch (error: Exception) {
      trace.failed(error)
      throw error
    }
  }
}
