package com.valerochka1337.valerochkagym.ui.analysis

import com.valerochka1337.valerochkagym.domain.analysis.AnalysisDateRange
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.BalanceId
import com.valerochka1337.valerochkagym.domain.analysis.TrendVerdict
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Подписи и форматирование чисел вкладки «Анализы».
 *
 * Числа форматируются локаль-независимо через [BigDecimal] (как в итогах тренировки): результат не
 * зависит от языка устройства, а хвостовые нули не превращают «12» в «12.0».
 */
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
private val FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val DATE_YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")

/** Число с [digits] знаками после запятой, без хвостовых нулей. */
fun formatDecimal(value: Double, digits: Int = 1): String =
    BigDecimal.valueOf(value)
        .setScale(digits, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

/** Вес в килограммах, округлённый до целого — доли килограмма в аналитике не значимы. */
fun formatKg(value: Double): String = "${value.roundToInt()} кг"

/** Тоннаж: до тонны — в килограммах, дальше в тоннах, иначе число не читается. */
fun formatTonnage(kg: Double): String =
    if (kg < 1_000) "${kg.roundToInt()} кг" else "${formatDecimal(kg / 1_000)} т"

/** Длительность в минутах как «1 ч 05 мин» / «45 мин». */
fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} ч ${"%02d".format(minutes % 60)} мин" else "$minutes мин"

/** Дата последней тренировки относительно конца выбранного периода. */
fun formatLastSessionCaption(daysSinceLast: Int, periodEnd: LocalDate): String {
  val date = periodEnd.minusDays(daysSinceLast.coerceAtLeast(0).toLong())
  return "последняя ${FULL_DATE_FORMATTER.format(date)}"
}

fun formatDate(millis: Long, zone: ZoneId): String =
    DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatDateWithYear(millis: Long, zone: ZoneId): String =
    DATE_YEAR_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatDateRange(start: LocalDate, endInclusive: LocalDate): String =
    if (start == endInclusive) {
      DATE_YEAR_FORMATTER.format(start)
    } else {
      "${DATE_YEAR_FORMATTER.format(start)} – ${DATE_YEAR_FORMATTER.format(endInclusive)}"
    }

fun AnalysisDateRange.displayName(): String = formatDateRange(start, endInclusive)

/** Отношение как «1.2 : 1» — так виден перекос, в отличие от «1.2». */
fun formatRatio(ratio: Double): String = "${formatDecimal(ratio)} : 1"

/** Знаковое изменение, например «+2.5 кг/мес». */
fun formatSigned(value: Double, unit: String): String {
  val sign = if (value >= 0) "+" else "−"
  return "$sign${formatDecimal(abs(value))} $unit"
}

fun AnalysisPeriod.displayName(): String =
    when (this) {
      AnalysisPeriod.LAST_7_DAYS -> "Последние 7 дней"
      AnalysisPeriod.WEEKS_2 -> "2 недели"
      AnalysisPeriod.WEEKS_4 -> "4 недели"
      AnalysisPeriod.WEEKS_12 -> "12 недель"
      AnalysisPeriod.WEEKS_52 -> "52 недели"
      AnalysisPeriod.ALL_TIME -> "Всё время"
      is AnalysisPeriod.Custom -> formatDateRange(start, endInclusive)
    }

fun VolumeZone.displayName(): String =
    when (this) {
      VolumeZone.LOW -> "малый объём"
      VolumeZone.BASE -> "базовый объём"
      VolumeZone.WORKING -> "рабочий объём"
      VolumeZone.GROWTH_GUIDE -> "ориентир для роста"
    }

fun TrendVerdict.displayName(): String =
    when (this) {
      TrendVerdict.NOT_ENOUGH_DATA -> "мало данных"
      TrendVerdict.GROWING -> "растёт"
      TrendVerdict.STALLED -> "плато"
      TrendVerdict.REGRESSING -> "снижается"
    }

fun BalanceId.title(): String =
    when (this) {
      BalanceId.PUSH_PULL -> "Жим / тяга"
      BalanceId.ANTERIOR_POSTERIOR -> "Перёд / зад"
      BalanceId.UPPER_LOWER -> "Верх / низ"
      BalanceId.QUAD_HAMSTRING -> "Бицепс бедра / квадрицепс"
    }

/** Что означает перекос влево и вправо — без этого diverging-график не прочитать. */
fun BalanceId.sideLabels(): Pair<String, String> =
    when (this) {
      BalanceId.PUSH_PULL -> "больше жима" to "больше тяги"
      BalanceId.ANTERIOR_POSTERIOR -> "больше переда" to "больше зада"
      BalanceId.UPPER_LOWER -> "больше верха" to "больше низа"
      BalanceId.QUAD_HAMSTRING -> "больше бицепса бедра" to "больше квадрицепса"
    }
