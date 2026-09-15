package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.analysis.ExerciseProgress
import com.valerochka1337.valerochkagym.domain.analysis.TrendVerdict
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.StatTile
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import java.time.ZoneId

/** Сводка периода плитками: одно число — это не график, а плитка. */
@Composable
internal fun SummaryCard(
    state: AnalysisUiState,
    modifier: Modifier = Modifier,
) {
  val report = state.report

  AnalysisCard(
      title = "Итоги периода",
      subtitle = "За ${state.report.range.displayName()}",
      icon = Icons.Rounded.FitnessCenter,
      modifier = modifier,
  ) {
    Row(modifier = Modifier.fillMaxWidth()) {
      StatTile(
          label = "Тренировок",
          value = report.sessions.toString(),
          caption = "${formatDecimal(report.sessionsPerWeek)} в неделю",
          modifier = Modifier.weight(1f),
      )
      StatTile(
          label = "Рабочих подходов",
          value = formatDecimal(report.totalHardSets, 0),
          caption = "${formatDecimal(report.totalHardSets / report.periodWeeks, 0)} в неделю",
          modifier = Modifier.weight(1f),
      )
    }
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
      StatTile(
          label = "Тоннаж",
          value = formatTonnage(report.totalTonnageKg),
          caption = "вес × повторы",
          modifier = Modifier.weight(1f),
      )
      StatTile(
          label = "Средняя тренировка",
          value = formatMinutes(report.avgSessionMinutes),
          caption =
              report.daysSinceLast?.let { formatLastSessionCaption(it, report.range.endInclusive) },
          modifier = Modifier.weight(1f),
      )
    }
    Spacer(Modifier.height(14.dp))
    ValueRow(
        label = "Недель подряд",
        value = report.streakWeeks.toString(),
        accent = true,
    )
    if (report.cardioMinutes > 0) {
      ValueRow(label = "Кардио", value = formatMinutes(report.cardioMinutes))
    }
  }
}

/**
 * Прогресс выбранного упражнения: тренд расчётного максимума.
 *
 * Сравнивать подходы напрямую нельзя — 100 кг × 5 и 110 кг × 3 это разная работа, поэтому по
 * вертикали отложена оценка одноповторного максимума (формула Эпли по лучшему подходу тренировки).
 * Подходы дальше 12 повторений в оценку не идут: там формула систематически врёт.
 */
@Composable
internal fun ExerciseProgressCard(
    state: AnalysisUiState,
    onExerciseSelected: (Long) -> Unit,
    onSessionSelected: (Int?) -> Unit,
    onExerciseClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
  val exercises = state.report.exercises
  val shown = state.shownExercise ?: return

  AnalysisCard(
      title = "Прогресс по упражнению",
      subtitle =
          "Оценка одноповторного максимума по лучшему подходу тренировки (формула Эпли, до 12 повторений)",
      icon = Icons.AutoMirrored.Rounded.ShowChart,
      modifier = modifier,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ProgressExerciseSelector(
          exercises = exercises,
          selected = shown,
          onSelect = onExerciseSelected,
          modifier = Modifier.weight(1f),
      )
      IconButton(onClick = { onExerciseClick(shown.exerciseId) }) {
        Icon(Icons.Rounded.Info, contentDescription = "Открыть карточку упражнения")
      }
    }
    Spacer(Modifier.height(14.dp))

    TrendHeadline(shown)
    Spacer(Modifier.height(8.dp))

    TrendLineChart(
        points =
            shown.points.map { point ->
              LinePoint(
                  xMillis = point.dateMillis,
                  y = point.bestE1rm.toFloat(),
                  xLabel = formatDate(point.dateMillis, state.zone),
              )
            },
        selectedIndex = state.selectedSessionIndex,
        onSelect = onSessionSelected,
        valueFormatter = { "${it.toInt()} кг" },
    )

    Spacer(Modifier.height(8.dp))
    SessionDetails(shown, state.selectedSessionIndex, state.zone)
  }
}

/** Поиск только среди упражнений с точками прогресса за выбранный период. */
@Composable
internal fun ProgressExerciseSelector(
    exercises: List<ExerciseProgress>,
    selected: ExerciseProgress,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  val haptics = gymHaptics()
  val filtered =
      exercises.filter {
        it.points.isNotEmpty() && it.name.contains(query.trim(), ignoreCase = true)
      }
  BoxWithConstraints(modifier) {
    OutlinedButton(
        onClick = {
          haptics.tap()
          query = ""
          expanded = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(selected.name, modifier = Modifier.weight(1f))
      Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.width(maxWidth).heightIn(max = 400.dp),
    ) {
      OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text("Поиск упражнения") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      )
      if (filtered.isEmpty()) {
        Text(
            "Ничего не найдено",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      filtered.forEach { exercise ->
        DropdownMenuItem(
            text = { Text(exercise.name) },
            onClick = {
              haptics.tap()
              onSelect(exercise.exerciseId)
              expanded = false
            },
        )
      }
    }
  }
}

/** Крупное текущее значение, вердикт и скорость изменения — то, ради чего смотрят на график. */
@Composable
private fun TrendHeadline(progress: ExerciseProgress) {
  val last = progress.points.lastOrNull() ?: return
  val color =
      when (progress.verdict) {
        TrendVerdict.GROWING -> ChartPalette.Optimal
        TrendVerdict.STALLED -> ChartPalette.Maintenance
        TrendVerdict.REGRESSING -> ChartPalette.TooLittle
        TrendVerdict.NOT_ENOUGH_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
      }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
          text = "≈ ${formatKg(last.bestE1rm)}",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false,
      )
      Text(
          text = "по подходу ${formatKg(last.bestWeightKg)} × ${last.bestWeightReps}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      StatusPill(text = progress.verdict.displayName(), color = color)
      progress.trendKgPerMonth?.let { kgPerMonth ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatSigned(kgPerMonth, "кг/мес"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
      }
    }
  }
}

@Composable
private fun SessionDetails(
    progress: ExerciseProgress,
    selectedIndex: Int?,
    zone: ZoneId,
) {
  val index = selectedIndex?.coerceIn(progress.points.indices) ?: progress.points.lastIndex
  val point = progress.points[index]
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    ValueRow(
        label = "Тренировка ${formatDateWithYear(point.dateMillis, zone)}",
        value = "≈ ${formatKg(point.bestE1rm)}",
        accent = true,
    )
    ValueRow(
        label = "Лучший подход",
        value = "${formatKg(point.bestWeightKg)} × ${point.bestWeightReps}",
    )
    ValueRow(label = "Подходов", value = point.sets.toString())
    ValueRow(label = "Тоннаж", value = formatTonnage(point.tonnageKg))
  }
}

/**
 * Рекорды: лучший расчётный максимум по каждому упражнению за всю историю.
 *
 * Окно периода на карточку не влияет — рекорд не перестаёт быть рекордом из-за того, что поставлен
 * давно. Список ограничен, потому что дальше он перестаёт быть про достижения.
 */
@Composable
internal fun RecordsCard(
    state: AnalysisUiState,
    onExerciseClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
  val records = state.report.records.take(MAX_RECORDS)
  if (records.isEmpty()) return

  AnalysisCard(
      title = "Рекорды за всю историю",
      subtitle = "Лучшая оценка максимума — независимо от выбранного периода",
      icon = Icons.Rounded.EmojiEvents,
      modifier = modifier,
  ) {
    records.forEach { record ->
      Row(
          modifier =
              Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                onExerciseClick(record.exerciseId)
              },
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
              text = record.name,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
          )
          Text(
              text =
                  "${formatKg(record.weightKg)} × ${record.reps} · " +
                      formatDateWithYear(record.dateMillis, state.zone),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
          )
        }
        Text(
            text = "≈ ${formatKg(record.bestE1rm)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
      }
    }
  }
}

private const val MAX_RECORDS = 12
