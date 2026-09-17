package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt

/** Вход аналитики: сырые подходы, тренировки, карта мышц и «сейчас». */
data class AnalyticsInput(
    val sets: List<AnalyticsSetRow>,
    val workouts: List<WorkoutEntity>,
    val muscleMap: Map<Long, List<MuscleLoad>>,
    val nowMillis: Long,
    val zone: ZoneId,
)

/**
 * Считает всё, что показывает вкладка «Анализы». Чистая функция входа: ни Room, ни Android, ни
 * системного времени — поэтому каждая метрика проверяется обычным юнит-тестом.
 *
 * Общие соглашения, действующие во всех метриках:
 * * **Рабочий подход** — выполненный подход силового упражнения или упражнения на время, не похожий
 *   на разминочный. Кардио в подходах не считается: «подход» там не единица работы (для кардио есть
 *   минуты и МЕТ). Разминка отсекается эвристикой: подход легче 60% от самого тяжёлого подхода
 *   этого упражнения в этой же тренировке — разминочный. RIR приложение не записывает, а без него
 *   это лучший доступный признак.
 * * **Эффективный подход мышцы** — рабочий подход по общей шкале вовлечения ([setWeightFor]):
 *   прямая нагрузка ≥60% даёт 1.0, косвенная 25–59% — 0.5, стабилизация ниже 25% не учитывается.
 *   Это оценка стимула, а не измерение близости к отказу.
 * * **Тоннаж** — Σ вес × повторы по силовым подходам (как в итогах тренировки).
 * * Недельные величины — **средние за неделю окна**, иначе окна разной длины несравнимы.
 */
class AnalyticsEngine @Inject constructor() {

  fun analyze(input: AnalyticsInput, period: AnalysisPeriod): AnalyticsReport {
    val today = Instant.ofEpochMilli(input.nowMillis).atZone(input.zone).toLocalDate()
    val range = period.resolveRange(today, firstActivityDate(input))
    val sets = input.sets.filter { range.contains(it.date(input.zone)) }
    val workouts =
        input.workouts.filter { workout ->
          workout.finishedAt != null && range.contains(workout.startedDate(input.zone))
        }
    val allRecords = records(input.sets)
    if (sets.isEmpty() && workouts.isEmpty()) {
      return AnalyticsReport.empty(period = period, range = range, records = allRecords)
    }

    val hardSets = hardSets(sets)
    val weeks = range.weeks

    val muscleLoads = muscleLoads(hardSets, input.muscleMap, weeks, range.endInclusive, input.zone)
    val effectiveByMuscle = muscleLoads.associate { it.muscle to it.totalSets }

    return AnalyticsReport(
        hasData = hardSets.isNotEmpty() || workouts.isNotEmpty(),
        period = period,
        range = range,
        periodWeeks = weeks,
        sessions = workouts.size,
        sessionsPerWeek = workouts.size / weeks,
        totalTonnageKg = sets.sumOf { it.tonnage },
        totalHardSets = hardSets.size.toDouble(),
        avgSessionMinutes = averageSessionMinutes(workouts),
        cardioMinutes =
            sets.filter { it.exerciseType == ExerciseType.CARDIO }.sumOf { it.durationSec ?: 0 } /
                60,
        aerobicMetMinutesPerWeek = metMinutes(sets) / weeks,
        weeklyPoints = weeklyPoints(sets, hardSets, workouts, range, input.zone),
        muscleLoads = muscleLoads,
        exercises = exerciseProgress(sets),
        workload = workloadRatio(hardSets, range, input.zone),
        balances = balances(effectiveByMuscle, chestTotal(hardSets, input.muscleMap)),
        // Рекорд не перестаёт быть достижением после смены календарного периода.
        records = allRecords,
        streakWeeks = streakWeeks(workouts, range, input.zone),
        daysSinceLast =
            workouts
                .maxOfOrNull { it.startedAt }
                ?.let { last ->
                  ChronoUnit.DAYS.between(
                          Instant.ofEpochMilli(last).atZone(input.zone).toLocalDate(),
                          range.endInclusive,
                      )
                      .toInt()
                },
    )
  }

  // region shared helpers

  /**
   * Рабочие подходы: силовые и на время, без разминочных. Порог разминки — 60% от лучшего веса
   * этого упражнения в этой тренировке; упражнения без веса (свой вес, планка) проходят целиком,
   * там сравнивать нечего.
   */
  private fun hardSets(sets: List<AnalyticsSetRow>): List<AnalyticsSetRow> {
    val topWeights =
        sets
            .filter { it.exerciseType == ExerciseType.STRENGTH && it.setType != "WARMUP" }
            .groupBy { it.workoutId to it.exerciseId }
            .mapValues { (_, group) -> group.maxOfOrNull { it.weightKg ?: 0.0 } ?: 0.0 }
    return sets.filter { set ->
      if (set.exerciseType == ExerciseType.CARDIO) return@filter false
      if (set.setType == "WARMUP") return@filter false
      if (set.reps != null && set.reps !in 1..30) return@filter false
      if (set.setType == "WORK") return@filter true
      val top = topWeights[set.workoutId to set.exerciseId] ?: 0.0
      top <= 0.0 || (set.weightKg ?: 0.0) >= 0.6 * top
    }
  }

  private val AnalyticsSetRow.tonnage: Double
    get() =
        if (exerciseType == ExerciseType.STRENGTH && weightKg != null && reps != null) {
          weightKg * reps
        } else {
          0.0
        }

  private fun AnalyticsSetRow.date(zone: ZoneId): LocalDate =
      Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()

  private fun WorkoutEntity.startedDate(zone: ZoneId): LocalDate =
      Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()

  /** Первая активность нужна только для начала пресета «всё время». */
  private fun firstActivityDate(input: AnalyticsInput): LocalDate? =
      minOf(
              input.sets.minOfOrNull { it.completedAt } ?: Long.MAX_VALUE,
              input.workouts.minOfOrNull { it.startedAt } ?: Long.MAX_VALUE,
          )
          .takeIf { it != Long.MAX_VALUE }
          ?.let { Instant.ofEpochMilli(it).atZone(input.zone).toLocalDate() }

  // endregion

  // region muscles

  private fun muscleLoads(
      hardSets: List<AnalyticsSetRow>,
      muscleMap: Map<Long, List<MuscleLoad>>,
      weeks: Double,
      today: LocalDate,
      zone: ZoneId,
  ): List<MuscleLoadSummary> {
    val totals = mutableMapOf<Muscle, Double>()
    val tonnage = mutableMapOf<Muscle, Double>()
    val perDay = mutableMapOf<Muscle, MutableMap<LocalDate, Double>>()
    val perExercise = mutableMapOf<Muscle, MutableMap<String, Double>>()

    for (set in hardSets) {
      val loads = muscleMap[set.exerciseId].orEmpty()
      val date = set.date(zone)
      for (load in loads) {
        val share = setWeightFor(load.contribution)
        if (share <= 0.0) continue
        totals[load.muscle] = (totals[load.muscle] ?: 0.0) + share
        // Тоннаж идёт по тем же прямым/косвенным правилам, что и эффективные подходы:
        // стабилизация не должна создавать ощущение отдельной мышечной работы.
        tonnage[load.muscle] = (tonnage[load.muscle] ?: 0.0) + set.tonnage * share
        val days = perDay.getOrPut(load.muscle) { mutableMapOf() }
        days[date] = (days[date] ?: 0.0) + share
        val exercises = perExercise.getOrPut(load.muscle) { mutableMapOf() }
        exercises[set.exerciseName] = (exercises[set.exerciseName] ?: 0.0) + share
      }
    }

    return Muscle.entries.map { muscle ->
      val total = totals[muscle] ?: 0.0
      val weekly = total / weeks
      // Днём тренировки мышцы считается день, где она набрала хотя бы один полный
      // эффективный подход: иначе «трицепс тренировался» после единственного жима.
      val trainedDays = perDay[muscle].orEmpty().filterValues { it >= 1.0 }.keys
      MuscleLoadSummary(
          muscle = muscle,
          weeklySets = weekly,
          totalSets = total,
          tonnageKg = tonnage[muscle] ?: 0.0,
          zone = muscle.zoneFor(weekly),
          sessionsPerWeek = trainedDays.size / weeks,
          daysSinceLast =
              trainedDays.maxOrNull()?.let { ChronoUnit.DAYS.between(it, today).toInt() },
          topExercises =
              perExercise[muscle]
                  .orEmpty()
                  .entries
                  .sortedByDescending { it.value }
                  .take(3)
                  .map { it.key },
      )
    }
  }

  // endregion

  // region weekly

  private fun weeklyPoints(
      sets: List<AnalyticsSetRow>,
      hardSets: List<AnalyticsSetRow>,
      workouts: List<WorkoutEntity>,
      range: AnalysisDateRange,
      zone: ZoneId,
  ): List<WeeklyPoint> {
    // Блоки всегда начинаются с первой даты выбранного диапазона, а не с понедельника:
    // пользователь сравнивает именно выбранные семь дней, без добавочных дней вне периода.
    val points = mutableListOf<WeeklyPoint>()
    var weekStart = range.start
    while (!weekStart.isAfter(range.endInclusive)) {
      val weekEnd = minOf(weekStart.plusDays(6), range.endInclusive)
      points +=
          WeeklyPoint(
              weekStart = weekStart,
              weekEndInclusive = weekEnd,
              label = "%02d.%02d".format(weekStart.dayOfMonth, weekStart.monthValue),
              hardSets =
                  hardSets
                      .count { set ->
                        val date = set.date(zone)
                        !date.isBefore(weekStart) && !date.isAfter(weekEnd)
                      }
                      .toDouble(),
              tonnageKg =
                  sets.sumOf { set ->
                    val date = set.date(zone)
                    if (!date.isBefore(weekStart) && !date.isAfter(weekEnd)) set.tonnage else 0.0
                  },
              sessions =
                  workouts.count { workout ->
                    val date = workout.startedDate(zone)
                    !date.isBefore(weekStart) && !date.isAfter(weekEnd)
                  },
              partial = ChronoUnit.DAYS.between(weekStart, weekEnd) + 1 < 7,
          )
      weekStart = weekStart.plusDays(7)
    }
    return points
  }

  /** МЕТ-минуты аэробной работы за окно: только кардио — полоса ВОЗ определена именно для него. */
  private fun metMinutes(sets: List<AnalyticsSetRow>): Double =
      sets
          .filter { it.exerciseType == ExerciseType.CARDIO }
          .sumOf { set ->
            val minutes = (set.durationSec ?: 0) / 60.0
            if (minutes <= 0.0) 0.0
            else minutes * CardioMet.forSet(set.exerciseName, set.speedKmh, set.inclinePct)
          }

  private fun averageSessionMinutes(workouts: List<WorkoutEntity>): Int {
    val durations =
        workouts.mapNotNull { workout ->
          workout.finishedAt?.let { (it - workout.startedAt).coerceAtLeast(0L) }
        }
    if (durations.isEmpty()) return 0
    return (durations.average() / 60_000.0).roundToInt()
  }

  /**
   * Серия опирается на конец выбранного диапазона и его же семидневные блоки. Данные до начала
   * периода не «достраивают» серию и не меняют историю, которую выбрал пользователь.
   */
  private fun streakWeeks(
      workouts: List<WorkoutEntity>,
      range: AnalysisDateRange,
      zone: ZoneId,
  ): Int {
    if (workouts.isEmpty()) return 0
    val blocks = weeklyPoints(emptyList(), emptyList(), workouts, range, zone)
    var streak = 0
    for (block in blocks.asReversed()) {
      if (block.sessions == 0) break
      streak += 1
    }
    return streak
  }

  // endregion

  // region exercises

  private fun exerciseProgress(sets: List<AnalyticsSetRow>): List<ExerciseProgress> =
      sets
          .filter { it.exerciseType == ExerciseType.STRENGTH }
          .groupBy { it.exerciseId }
          .map { (exerciseId, exerciseSets) -> progressFor(exerciseId, exerciseSets) }
          .filter { it.points.isNotEmpty() }
          .sortedWith(compareByDescending<ExerciseProgress> { it.points.size }.thenBy { it.name })

  private fun progressFor(exerciseId: Long, sets: List<AnalyticsSetRow>): ExerciseProgress {
    val points =
        sets
            .groupBy { it.workoutId }
            .mapNotNull { (workoutId, sessionSets) ->
              val bestE1rm =
                  sessionSets.mapNotNull { OneRepMax.epley(it.weightKg, it.reps) }.maxOrNull()
                      ?: return@mapNotNull null
              val heaviest =
                  sessionSets
                      .filter { it.weightKg != null && it.reps != null }
                      .maxByOrNull { it.weightKg!! }
              ExerciseSessionPoint(
                  workoutId = workoutId,
                  dateMillis = sessionSets.minOf { it.completedAt },
                  bestE1rm = bestE1rm,
                  bestWeightKg = heaviest?.weightKg ?: 0.0,
                  bestWeightReps = heaviest?.reps ?: 0,
                  tonnageKg = sessionSets.sumOf { it.tonnage },
                  sets = sessionSets.size,
              )
            }
            .sortedBy { it.dateMillis }

    val slopePerDay = e1rmSlopePerDay(points)
    val kgPerMonth = slopePerDay?.times(30.0)
    val meanE1rm = points.map { it.bestE1rm }.average().takeIf { points.isNotEmpty() }
    return ExerciseProgress(
        exerciseId = exerciseId,
        name = sets.first().exerciseName,
        points = points,
        repMaxes = repMaxes(sets),
        bestE1rm = points.maxOfOrNull { it.bestE1rm },
        trendPercentPerMonth =
            if (kgPerMonth != null && meanE1rm != null && meanE1rm > 0.0) {
              kgPerMonth / meanE1rm * 100.0
            } else {
              null
            },
        trendKgPerMonth = kgPerMonth,
        verdict = verdict(points, kgPerMonth),
    )
  }

  /**
   * Вердикт по тренду. Порог заметности — `max(1.25 кг, 1% от текущего e1RM)` за месяц: 1.25 кг это
   * типичный минимальный шаг блинов, ниже него «изменение» неотличимо от дискретности снаряда.
   * Вердикт выносится только при ≥ 5 тренировках.
   */
  private fun verdict(points: List<ExerciseSessionPoint>, kgPerMonth: Double?): TrendVerdict {
    if (points.size < 5 || kgPerMonth == null) return TrendVerdict.NOT_ENOUGH_DATA
    val current = points.last().bestE1rm
    val threshold = maxOf(1.25, current * 0.01)
    return when {
      kgPerMonth > threshold -> TrendVerdict.GROWING
      kgPerMonth < -threshold -> TrendVerdict.REGRESSING
      else -> TrendVerdict.STALLED
    }
  }

  /**
   * Силовая кривая: для каждого числа повторений — самый тяжёлый вес, поднятый **хотя бы** на
   * столько раз. Условие «хотя бы» делает кривую монотонной: подход 100 кг × 8 закрывает и «100 кг
   * на 5», которое иначе выглядело бы как пробел в данных.
   */
  private fun repMaxes(sets: List<AnalyticsSetRow>): List<RepMaxPoint> {
    val usable = sets.filter { it.weightKg != null && it.reps != null && it.weightKg > 0.0 }
    if (usable.isEmpty()) return emptyList()
    return (1..OneRepMax.MAX_TRUSTED_REPS).mapNotNull { reps ->
      val best =
          usable.filter { it.reps!! >= reps }.maxByOrNull { it.weightKg!! }
              ?: return@mapNotNull null
      RepMaxPoint(reps = reps, weightKg = best.weightKg!!, dateMillis = best.completedAt)
    }
  }

  /**
   * Наклон e1RM в килограммах за день — обычный МНК по (день, e1RM). При < 3 точках или нулевом
   * разбросе по времени тренда нет: по двум тренировкам «рост 40%/мес» это шум.
   */
  private fun e1rmSlopePerDay(points: List<ExerciseSessionPoint>): Double? {
    if (points.size < 3) return null
    val days = points.map { it.dateMillis / 86_400_000.0 }
    val values = points.map { it.bestE1rm }
    val meanX = days.average()
    val meanY = values.average()
    var numerator = 0.0
    var denominator = 0.0
    for (i in points.indices) {
      val dx = days[i] - meanX
      numerator += dx * (values[i] - meanY)
      denominator += dx * dx
    }
    if (denominator <= 0.0) return null
    return numerator / denominator
  }

  private fun records(sets: List<AnalyticsSetRow>): List<ExerciseRecord> =
      sets
          .filter { it.exerciseType == ExerciseType.STRENGTH }
          .groupBy { it.exerciseId }
          .mapNotNull { (exerciseId, exerciseSets) ->
            val best =
                exerciseSets
                    .mapNotNull { set ->
                      OneRepMax.epley(set.weightKg, set.reps)?.let { set to it }
                    }
                    .maxByOrNull { it.second } ?: return@mapNotNull null
            ExerciseRecord(
                exerciseId = exerciseId,
                name = best.first.exerciseName,
                bestE1rm = best.second,
                weightKg = best.first.weightKg ?: 0.0,
                reps = best.first.reps ?: 0,
                dateMillis = best.first.completedAt,
            )
          }
          .sortedByDescending { it.bestE1rm }

  // endregion

  // region load and balance

  /**
   * ACWR заканчивается последней датой выбранного периода. Подходы уже ограничены диапазоном,
   * поэтому более ранняя история не подмешивается в «хроническую» нагрузку.
   */
  private fun workloadRatio(
      hardSets: List<AnalyticsSetRow>,
      range: AnalysisDateRange,
      zone: ZoneId,
  ): WorkloadRatio {
    if (hardSets.isEmpty()) return WorkloadRatio(0.0, 0.0, null, hasEnoughData = false)
    val acuteStart = range.endInclusive.minusDays(6)
    val chronicStart = range.endInclusive.minusDays(27)
    val acute =
        hardSets
            .count { set ->
              val date = set.date(zone)
              !date.isBefore(acuteStart) && !date.isAfter(range.endInclusive)
            }
            .toDouble()
    val chronicTotal =
        hardSets
            .count { set ->
              val date = set.date(zone)
              !date.isBefore(chronicStart) && !date.isAfter(range.endInclusive)
            }
            .toDouble()
    val chronicWeekly = chronicTotal / 4.0
    // Выбранный период должен вмещать 4 недели, а журнал — действительно содержать
    // нагрузку в начале этого окна. Сам широкий выбранный диапазон не делает ACWR
    // информативным, если в нём записана только недавняя тренировка.
    val enough =
        !range.start.isAfter(chronicStart) &&
            hardSets.any { set -> !set.date(zone).isAfter(chronicStart) } &&
            chronicWeekly > 0.0
    return WorkloadRatio(
        acuteSets = acute,
        chronicWeeklySets = chronicWeekly,
        ratio = if (chronicWeekly > 0.0) acute / chronicWeekly else null,
        hasEnoughData = enough,
    )
  }

  /** Upper/lower chest are separate regions, but never double-count one completed set. */
  private fun chestTotal(
      hardSets: List<AnalyticsSetRow>,
      muscleMap: Map<Long, List<MuscleLoad>>,
  ): Double =
      hardSets.sumOf { set ->
        muscleMap[set.exerciseId]
            .orEmpty()
            .filter { it.muscle == Muscle.UPPER_CHEST || it.muscle == Muscle.LOWER_CHEST }
            .maxOfOrNull { setWeightFor(it.contribution) } ?: 0.0
      }

  private fun balances(effectiveByMuscle: Map<Muscle, Double>, chest: Double): List<BalanceRatio> {
    fun sum(vararg muscles: Muscle) = muscles.sumOf { effectiveByMuscle[it] ?: 0.0 }

    val push = chest + sum(Muscle.FRONT_DELTS, Muscle.TRICEPS)
    val pull = sum(Muscle.LATS, Muscle.UPPER_BACK, Muscle.REAR_DELTS, Muscle.BICEPS, Muscle.TRAPS)
    val upper =
        chest +
            sum(
                Muscle.FRONT_DELTS,
                Muscle.SIDE_DELTS,
                Muscle.REAR_DELTS,
                Muscle.TRAPS,
                Muscle.LATS,
                Muscle.UPPER_BACK,
                Muscle.BICEPS,
                Muscle.TRICEPS,
                Muscle.FOREARMS,
            )
    val lower =
        sum(
            Muscle.GLUTES,
            Muscle.QUADS,
            Muscle.HAMSTRINGS,
            Muscle.ADDUCTORS,
            Muscle.CALVES,
        )
    val quads = sum(Muscle.QUADS)
    val hamstrings = sum(Muscle.HAMSTRINGS)
    val anterior =
        chest +
            sum(
                Muscle.FRONT_DELTS,
                Muscle.BICEPS,
                Muscle.ABS,
                Muscle.QUADS,
            )
    val posterior =
        sum(
            Muscle.LATS,
            Muscle.UPPER_BACK,
            Muscle.LOWER_BACK,
            Muscle.REAR_DELTS,
            Muscle.TRAPS,
            Muscle.GLUTES,
            Muscle.HAMSTRINGS,
        )

    return listOf(
        // Жим к тяге ≈ 1:1 — общая рекомендация против передне-доминантного перекоса
        // (грудь и передняя дельта тянут плечо вперёд, спина возвращает баланс).
        BalanceRatio(BalanceId.PUSH_PULL, push, pull, ratio(push, pull), 0.8, 1.25),
        // Перёд к заду — та же логика на уровне всего тела.
        BalanceRatio(
            BalanceId.ANTERIOR_POSTERIOR,
            anterior,
            posterior,
            ratio(anterior, posterior),
            0.8,
            1.25,
        ),
        // У верха и низа «правильного» отношения нет — оно зависит от целей. Флажок
        // ставится только на крайности за пределами 1:2..2:1.
        BalanceRatio(BalanceId.UPPER_LOWER, upper, lower, ratio(upper, lower), 0.5, 2.0),
        // Задняя поверхность бедра — не меньше половины объёма квадрицепса: с этим
        // соотношением связывают устойчивость колена и профилактику травм задней группы.
        BalanceRatio(
            BalanceId.QUAD_HAMSTRING,
            hamstrings,
            quads,
            ratio(hamstrings, quads),
            0.5,
            1.5,
        ),
    )
  }

  private fun ratio(left: Double, right: Double): Double? = if (right > 0.0) left / right else null

  // endregion
}
