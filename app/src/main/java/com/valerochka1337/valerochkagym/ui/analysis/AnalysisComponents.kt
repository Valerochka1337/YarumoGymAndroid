package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisDateRange
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.MIN_ANALYSIS_RANGE_DAYS
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Карточка блока аналитики: заголовок, пояснение и содержимое.
 *
 * Пояснение под заголовком обязательное по смыслу, а не декоративное: почти каждый график здесь
 * показывает величину с оговоркой («в среднем за неделю», «оценка по формуле»), и без подписи его
 * легко прочитать неверно.
 */
@Composable
internal fun AnalysisCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    collapsible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val haptics = gymHaptics()
  GymCard(
      modifier =
          modifier
              .fillMaxWidth()
              .then(
                  if (collapsible) Modifier.animateContentSize(GymMotion.spatialDefault())
                  else Modifier
              ),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
  ) {
    Row(
        modifier =
            if (collapsible) {
              Modifier.fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .semantics { stateDescription = if (expanded) "Развернуто" else "Свернуто" }
                  .clickable(
                      role = Role.Button,
                      onClickLabel = if (expanded) "Свернуть" else "Развернуть",
                  ) {
                    haptics.tap()
                    expanded = !expanded
                  }
            } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
      }
      Text(
          text = title,
          modifier = if (collapsible) Modifier.weight(1f) else Modifier,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      if (collapsible) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
      }
    }
    if (!collapsible || expanded) {
      if (subtitle != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.height(14.dp))
      content()
    }
  }
}

/** Ряд взаимоисключающих чипов — переключатель вида/метрики/периода. */
@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
  FlowRow(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { option ->
      GymFilterChip(
          selected = option == selected,
          onClick = { onSelect(option) },
          label = label(option),
      )
    }
  }
}

/**
 * Единственный выбор периода над всеми карточками. Пресеты и ручной календарный диапазон сходятся в
 * один [AnalysisPeriod], поэтому соседние графики всегда показывают один срез.
 */
@Composable
internal fun AnalysisPeriodSelector(
    period: AnalysisPeriod,
    range: AnalysisDateRange,
    onPeriodSelected: (AnalysisPeriod) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  var showDatePicker by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
    OutlinedButton(
        onClick = { expanded = true },
        modifier = Modifier.semantics { contentDescription = "Выбрать период анализа" },
    ) {
      Icon(
          imageVector = Icons.Rounded.CalendarMonth,
          contentDescription = null,
      )
      Spacer(Modifier.width(8.dp))
      Text(period.displayName())
      Spacer(Modifier.width(4.dp))
      Icon(
          imageVector = Icons.Rounded.ArrowDropDown,
          contentDescription = null,
      )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
      AnalysisPeriod.presets.forEach { option ->
        DropdownMenuItem(
            text = { Text(option.displayName()) },
            onClick = {
              expanded = false
              onPeriodSelected(option)
            },
        )
      }
      DropdownMenuItem(
          text = { Text("Выбрать даты…") },
          onClick = {
            expanded = false
            showDatePicker = true
          },
      )
    }
  }

  if (showDatePicker) {
    AnalysisDateRangePickerDialog(
        initialRange = range,
        today = LocalDate.now(),
        onConfirm = { start, endInclusive ->
          showDatePicker = false
          onCustomRangeSelected(start, endInclusive)
        },
        onDismiss = { showDatePicker = false },
    )
  }
}

/** Будущие даты не выбираются в M3-календаре. */
@OptIn(ExperimentalMaterial3Api::class)
internal class AnalysisSelectableDates(
    private val latestDate: LocalDate,
) : SelectableDates {
  override fun isSelectableDate(utcTimeMillis: Long): Boolean =
      !utcTimeMillis.toUtcLocalDate().isAfter(latestDate)

  override fun isSelectableYear(year: Int): Boolean = year <= latestDate.year
}

/** Проверяем минимум на подтверждении: DateRangePicker не умеет задавать длину диапазона сам. */
internal fun isValidAnalysisDateRange(
    start: LocalDate?,
    endInclusive: LocalDate?,
    latestDate: LocalDate,
): Boolean =
    start != null &&
        endInclusive != null &&
        !endInclusive.isAfter(latestDate) &&
        ChronoUnit.DAYS.between(start, endInclusive) + 1 >= MIN_ANALYSIS_RANGE_DAYS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisDateRangePickerDialog(
    initialRange: AnalysisDateRange,
    today: LocalDate,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
  val selectableDates = remember(today) { AnalysisSelectableDates(today) }
  val pickerState =
      rememberDateRangePickerState(
          initialSelectedStartDateMillis = initialRange.start.toUtcDatePickerMillis(),
          initialSelectedEndDateMillis = initialRange.endInclusive.toUtcDatePickerMillis(),
          selectableDates = selectableDates,
      )
  val selectedStart = pickerState.selectedStartDateMillis?.toUtcLocalDate()
  val selectedEnd = pickerState.selectedEndDateMillis?.toUtcLocalDate()
  val isValid = isValidAnalysisDateRange(selectedStart, selectedEnd, today)

  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
            enabled = isValid,
            onClick = {
              if (selectedStart != null && selectedEnd != null)
                  onConfirm(selectedStart, selectedEnd)
            },
        ) {
          Text("Готово")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  ) {
    DateRangePicker(state = pickerState)
    if (!isValid) {
      Text(
          text = "Выберите не меньше 7 дней включительно",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
    }
  }
}

private fun LocalDate.toUtcDatePickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * Ряд чипов с горизонтальной прокруткой — для длинных подписей вроде названий упражнений. Перенос
 * по строкам там даёт по одному чипу на строку и съедает пол-экрана.
 */
@Composable
internal fun <T> ScrollableChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
  LazyRow(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(options) { option ->
      GymFilterChip(
          selected = option == selected,
          onClick = { onSelect(option) },
          label = label(option),
      )
    }
  }
}

/**
 * Легенда шкалы: цветной образец плюс подпись. Образец с обводкой ([outlined]) показывает
 * состояния, которые кодируются не заливкой, а обводкой в других графиках.
 */
@Composable
internal fun LegendSwatch(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    Box(
        modifier =
            Modifier.size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (outlined) MaterialTheme.colorScheme.surfaceContainerHighest else color
                )
                .then(
                    if (outlined) Modifier.border(2.dp, color, RoundedCornerShape(3.dp))
                    else Modifier,
                ),
    )
    Spacer(Modifier.width(6.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
  }
}

/**
 * Строка «подпись — значение» для таблиц-двойников под графиками.
 *
 * Оба текста получают долю ширины через `weight`. Это не украшательство, а единственный рабочий
 * вариант: в `Row` дети без веса меряются первыми и по всей ширине, поэтому значение без веса
 * забирает строку целиком, а подписи достаётся ноль — и она рассыпается в столбик по букве.
 * Значение всё равно должно быть коротким; длинному тексту место в [ValueBlock].
 */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
  Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f).padding(end = 12.dp),
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
        color =
            if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        modifier = Modifier.weight(VALUE_WEIGHT),
    )
  }
}

/**
 * Доля ширины под значение в [ValueRow]. Числа и короткие фразы («8 · 12–20 · 22», «40 мин»)
 * укладываются примерно в сорок процентов строки, а остальное нужнее подписи: она длиннее.
 */
private const val VALUE_WEIGHT = 0.72f

/**
 * Подпись и значение в две строки — для значений, которые в строку не помещаются в принципе
 * (перечисления, названия упражнений). Переносится по словам, а не обрезается: список из трёх
 * упражнений с многоточием после первого не отвечает ни на один вопрос.
 */
@Composable
internal fun ValueBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Компактный бейдж-пилюля: короткий статус рядом с заголовком. */
@Composable
internal fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = color,
      maxLines = 1,
      softWrap = false,
      modifier =
          modifier
              .clip(CircleShape)
              .background(color.copy(alpha = 0.16f))
              .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}
