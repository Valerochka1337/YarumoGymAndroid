package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.BalanceId
import com.valerochka1337.valerochkagym.domain.analysis.TrendVerdict
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.ui.analysis.displayName
import com.valerochka1337.valerochkagym.ui.analysis.formatDate
import com.valerochka1337.valerochkagym.ui.analysis.formatDateWithYear
import com.valerochka1337.valerochkagym.ui.analysis.formatDecimal
import com.valerochka1337.valerochkagym.ui.analysis.formatKg
import com.valerochka1337.valerochkagym.ui.analysis.formatLastSessionCaption
import com.valerochka1337.valerochkagym.ui.analysis.formatMinutes
import com.valerochka1337.valerochkagym.ui.analysis.formatRatio
import com.valerochka1337.valerochkagym.ui.analysis.formatSigned
import com.valerochka1337.valerochkagym.ui.analysis.formatTonnage
import com.valerochka1337.valerochkagym.ui.analysis.sideLabels
import com.valerochka1337.valerochkagym.ui.analysis.title
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Форматтеры вкладки «Анализы»: локаль-независимые числа и подписи enum'ов. */
class AnalysisFormatTest {

  private val zone: ZoneId = ZoneId.of("Europe/Moscow")

  @Test
  fun `decimal drops trailing zeros and rounds half up`() {
    assertEquals("12", formatDecimal(12.0))
    assertEquals("12.3", formatDecimal(12.34))
    assertEquals("12.4", formatDecimal(12.35))
    assertEquals("0.5", formatDecimal(0.5))
    assertEquals("3.25", formatDecimal(3.246, digits = 2))
  }

  @Test
  fun `kilograms are rounded to whole units`() {
    assertEquals("100 кг", formatKg(100.4))
    assertEquals("101 кг", formatKg(100.5))
  }

  @Test
  fun `tonnage switches to tonnes from one thousand kilograms`() {
    assertEquals("999 кг", formatTonnage(999.0))
    assertEquals("1 т", formatTonnage(1_000.0))
    assertEquals("2.3 т", formatTonnage(2_340.0))
  }

  @Test
  fun `minutes grow into hours with a padded remainder`() {
    assertEquals("45 мин", formatMinutes(45))
    assertEquals("1 ч 05 мин", formatMinutes(65))
    assertEquals("2 ч 00 мин", formatMinutes(120))
  }

  @Test
  fun `last session caption refers to the end of the selected period`() {
    val end = LocalDate.of(2026, 1, 2)
    assertEquals("последняя 02.01.2026", formatLastSessionCaption(0, end))
    assertEquals("последняя 01.01.2026", formatLastSessionCaption(1, end))
    assertEquals("последняя 31.12.2025", formatLastSessionCaption(2, end))
  }

  @Test
  fun `dates format as day-month with an optional two-digit year`() {
    val millis = ZonedDateTime.of(2026, 8, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    assertEquals("02.08", formatDate(millis, zone))
    assertEquals("02.08.26", formatDateWithYear(millis, zone))
  }

  @Test
  fun `ratio reads as value to one`() {
    assertEquals("1.2 : 1", formatRatio(1.2))
    assertEquals("1 : 1", formatRatio(1.0))
  }

  @Test
  fun `signed change carries an explicit sign and unit`() {
    assertEquals("+2.5 кг/мес", formatSigned(2.5, "кг/мес"))
    assertEquals("+0 кг", formatSigned(0.0, "кг"))
    assertEquals("−1.5 кг", formatSigned(-1.5, "кг"))
  }

  @Test
  fun `every analysis period has a display name`() {
    assertEquals(
        listOf("Последние 7 дней", "2 недели", "4 недели", "12 недель", "52 недели", "Всё время"),
        AnalysisPeriod.presets.map { it.displayName() },
    )
    assertEquals(
        "01.06.26 – 07.06.26",
        AnalysisPeriod.Custom(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7)).displayName(),
    )
  }

  @Test
  fun `every volume zone has a display name`() {
    assertEquals(
        listOf("малый объём", "базовый объём", "рабочий объём", "ориентир для роста"),
        VolumeZone.entries.map { it.displayName() },
    )
  }

  @Test
  fun `every trend verdict has a display name`() {
    assertEquals(
        listOf("мало данных", "растёт", "плато", "снижается"),
        TrendVerdict.entries.map { it.displayName() },
    )
  }

  @Test
  fun `balance labels follow the same numerator-to-denominator order as their titles`() {
    assertEquals("больше жима" to "больше тяги", BalanceId.PUSH_PULL.sideLabels())
    assertEquals("больше переда" to "больше зада", BalanceId.ANTERIOR_POSTERIOR.sideLabels())
    assertEquals("больше верха" to "больше низа", BalanceId.UPPER_LOWER.sideLabels())
    assertEquals(
        "больше бицепса бедра" to "больше квадрицепса",
        BalanceId.QUAD_HAMSTRING.sideLabels(),
    )
    BalanceId.entries.forEach { balance -> assert(balance.title().isNotBlank()) }
  }
}
