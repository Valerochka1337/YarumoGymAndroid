package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Минимум для ручного периода: семь календарных дней включительно. */
const val MIN_ANALYSIS_RANGE_DAYS = 7L

/**
 * Выбор периода на вкладке «Анализы».
 *
 * Пресеты намеренно не хранят вычисленные даты: они всегда заканчиваются «сегодня» в зоне
 * [AnalyticsInput]. Ручной диапазон уже содержит обе даты. Выбор живёт только в ViewModel.
 */
sealed interface AnalysisPeriod {
  /** Последние семь календарных дней, включая сегодня. Значение по умолчанию. */
  data object LAST_7_DAYS : AnalysisPeriod

  data object WEEKS_2 : AnalysisPeriod

  data object WEEKS_4 : AnalysisPeriod

  data object WEEKS_12 : AnalysisPeriod

  data object WEEKS_52 : AnalysisPeriod

  /** Вся известная история, от первой активности до сегодня. */
  data object ALL_TIME : AnalysisPeriod

  /** Ручной диапазон с обеими включительными границами. */
  data class Custom(
      val start: LocalDate,
      val endInclusive: LocalDate,
  ) : AnalysisPeriod {
    init {
      require(!endInclusive.isBefore(start)) { "Конец периода не может быть раньше начала" }
      require(ChronoUnit.DAYS.between(start, endInclusive) + 1 >= MIN_ANALYSIS_RANGE_DAYS) {
        "Период аналитики должен занимать не меньше семи дней"
      }
    }
  }

  companion object {
    /** Порядок пунктов одного выпадающего меню. */
    val presets: List<AnalysisPeriod> =
        listOf(
            LAST_7_DAYS,
            WEEKS_2,
            WEEKS_4,
            WEEKS_12,
            WEEKS_52,
            ALL_TIME,
        )
  }
}

/** Фактический диапазон отчёта: обе даты входят в него. */
data class AnalysisDateRange(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
  init {
    require(!endInclusive.isBefore(start)) { "Конец периода не может быть раньше начала" }
  }

  val days: Long
    get() = ChronoUnit.DAYS.between(start, endInclusive) + 1

  /** Не раздуваем среднее за неделю для короткой начальной истории. */
  val weeks: Double
    get() = maxOf(1.0, days / 7.0)

  fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)
}

/** Разворачивает пресет в календарные даты для конкретного дня и истории. */
fun AnalysisPeriod.resolveRange(
    today: LocalDate,
    firstActivity: LocalDate?,
): AnalysisDateRange =
    when (this) {
      AnalysisPeriod.LAST_7_DAYS -> recentRange(today, 7)
      AnalysisPeriod.WEEKS_2 -> recentRange(today, 2 * 7)
      AnalysisPeriod.WEEKS_4 -> recentRange(today, 4 * 7)
      AnalysisPeriod.WEEKS_12 -> recentRange(today, 12 * 7)
      AnalysisPeriod.WEEKS_52 -> recentRange(today, 52 * 7)
      AnalysisPeriod.ALL_TIME ->
          AnalysisDateRange(
              start = firstActivity ?: today.minusDays(MIN_ANALYSIS_RANGE_DAYS - 1),
              endInclusive = today,
          )
      is AnalysisPeriod.Custom -> AnalysisDateRange(start, endInclusive)
    }

private fun recentRange(today: LocalDate, days: Long): AnalysisDateRange =
    AnalysisDateRange(
        start = today.minusDays(days - 1),
        endInclusive = today,
    )

/** Точка недельного графика. [partial] — последний блок короче семи дней. */
data class WeeklyPoint(
    val weekStart: LocalDate,
    val weekEndInclusive: LocalDate,
    val label: String,
    val hardSets: Double,
    val tonnageKg: Double,
    val sessions: Int,
    val partial: Boolean,
)

/**
 * Недельная нагрузка на одну мышцу.
 *
 * [weeklySets] — среднее число эффективных подходов в неделю за выбранный диапазон (а не сумма).
 * Шкала зон — общий UX-ориентир для гипертрофии, а не индивидуальный диагноз.
 */
data class MuscleLoadSummary(
    val muscle: Muscle,
    val weeklySets: Double,
    val totalSets: Double,
    val tonnageKg: Double,
    val zone: VolumeZone,
    val sessionsPerWeek: Double,
    val daysSinceLast: Int?,
    val topExercises: List<String>,
)

/** Одна тренировка на графике прогресса упражнения. */
data class ExerciseSessionPoint(
    val workoutId: String,
    val dateMillis: Long,
    val bestE1rm: Double,
    val bestWeightKg: Double,
    val bestWeightReps: Int,
    val tonnageKg: Double,
    val sets: Int,
)

/** Лучший вес, поднятый хотя бы на [reps] повторений — точка «силовой кривой». */
data class RepMaxPoint(
    val reps: Int,
    val weightKg: Double,
    val dateMillis: Long,
)

/** Вердикт по тренду силы — то, ради чего человек и смотрит на график. */
enum class TrendVerdict {
  /** Меньше 5 тренировок: наклон по такой выборке — шум. */
  NOT_ENOUGH_DATA,

  /** Рост за месяц больше порога заметности. */
  GROWING,

  /** Изменение меньше порога: плато. */
  STALLED,

  /** Устойчивое снижение. */
  REGRESSING,
}

/**
 * Прогресс одного упражнения. [trendPercentPerMonth] — наклон линейной регрессии e1RM по времени,
 * приведённый к процентам от среднего за месяц: «+2%/мес» читается, а «+0.7 кг/день» нет. Считается
 * только при ≥ 3 тренировках, вердикт — при ≥ 5.
 */
data class ExerciseProgress(
    val exerciseId: Long,
    val name: String,
    val points: List<ExerciseSessionPoint>,
    val repMaxes: List<RepMaxPoint>,
    val bestE1rm: Double?,
    val trendPercentPerMonth: Double?,
    val trendKgPerMonth: Double?,
    val verdict: TrendVerdict,
)

/**
 * Соотношение острой (7 дней) и хронической (среднее за 28 дней) нагрузки в эффективных подходах.
 * Оба окна заканчиваются последней датой выбранного диапазона и берут данные только из него. При
 * диапазоне короче 28 дней показатель не показываем.
 */
data class WorkloadRatio(
    val acuteSets: Double,
    val chronicWeeklySets: Double,
    val ratio: Double?,
    val hasEnoughData: Boolean,
)

/**
 * Баланс двух наборов мышц по эффективным подходам за диапазон. [ratio] = [leftSets] / [rightSets];
 * `null`, когда правая часть пуста (делить не на что).
 */
data class BalanceRatio(
    val id: BalanceId,
    val leftSets: Double,
    val rightSets: Double,
    val ratio: Double?,
    val targetLow: Double,
    val targetHigh: Double,
) {
  val inTarget: Boolean
    get() = ratio != null && ratio in targetLow..targetHigh
}

/** Какие именно балансы считаем; подписи живут в UI. */
enum class BalanceId {
  PUSH_PULL,
  ANTERIOR_POSTERIOR,
  UPPER_LOWER,
  QUAD_HAMSTRING,
}

/** Личный рекорд по упражнению за всю историю (выбранный диапазон на него не влияет). */
data class ExerciseRecord(
    val exerciseId: Long,
    val name: String,
    val bestE1rm: Double,
    val weightKg: Double,
    val reps: Int,
    val dateMillis: Long,
)

/**
 * Полный отчёт вкладки «Анализы» за один диапазон. Пустой диапазон — [hasData] = false, все его
 * списки пустые: экран показывает объяснение вместо графиков из нулей. [records] остаются за всю
 * историю и потому могут быть непустыми даже для пустого диапазона.
 */
data class AnalyticsReport(
    val hasData: Boolean,
    val period: AnalysisPeriod,
    val range: AnalysisDateRange,
    val periodWeeks: Double,
    val sessions: Int,
    val sessionsPerWeek: Double,
    val totalTonnageKg: Double,
    val totalHardSets: Double,
    val avgSessionMinutes: Int,
    val cardioMinutes: Int,
    val aerobicMetMinutesPerWeek: Double,
    val weeklyPoints: List<WeeklyPoint>,
    val muscleLoads: List<MuscleLoadSummary>,
    val exercises: List<ExerciseProgress>,
    val workload: WorkloadRatio,
    val balances: List<BalanceRatio>,
    val records: List<ExerciseRecord>,
    val streakWeeks: Int,
    val daysSinceLast: Int?,
) {
  companion object {
    fun empty(
        period: AnalysisPeriod,
        range: AnalysisDateRange = period.resolveRange(LocalDate.now(), firstActivity = null),
        records: List<ExerciseRecord> = emptyList(),
    ): AnalyticsReport =
        AnalyticsReport(
            hasData = false,
            period = period,
            range = range,
            periodWeeks = range.weeks,
            sessions = 0,
            sessionsPerWeek = 0.0,
            totalTonnageKg = 0.0,
            totalHardSets = 0.0,
            avgSessionMinutes = 0,
            cardioMinutes = 0,
            aerobicMetMinutesPerWeek = 0.0,
            weeklyPoints = emptyList(),
            muscleLoads = emptyList(),
            exercises = emptyList(),
            workload = WorkloadRatio(0.0, 0.0, null, hasEnoughData = false),
            balances = emptyList(),
            records = records,
            streakWeeks = 0,
            daysSinceLast = null,
        )
  }
}
