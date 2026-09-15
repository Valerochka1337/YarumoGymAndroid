package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.analysis.HypertrophyVolumeGuide
import com.valerochka1337.valerochkagym.domain.analysis.MuscleLoadSummary
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.body.MuscleSector
import com.valerochka1337.valerochkagym.ui.analysis.body.MuscleSelector
import com.valerochka1337.valerochkagym.ui.analysis.body.maxMember
import com.valerochka1337.valerochkagym.ui.analysis.charts.ChartSpec
import com.valerochka1337.valerochkagym.ui.analysis.charts.rememberChartColors
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * Тепловая карта тела: интерактивный человек, закрашенный по недельному объёму каждой мышцы.
 *
 * Категория объёма и числа выбранной мышцы выводятся текстом справа — цвет здесь нигде не остаётся
 * единственным носителем смысла. Тёмно-зелёный — ориентир для роста, а не верхний лимит или оценка
 * восстановления.
 */
@Composable
internal fun MuscleHeatmapCard(
    state: AnalysisUiState,
    onMuscleClicked: (Muscle?) -> Unit,
    modifier: Modifier = Modifier,
    onSelectorSelected: (Muscle) -> Unit = { onMuscleClicked(it) },
) {
  // Производные от отчёта, а не от выбора: пересобирать их на каждый тап по карте незачем.
  val loads = remember(state.report) { state.report.muscleLoads.associateBy { it.muscle } }
  val summaryWeight = if (LocalDensity.current.fontScale >= 1.5f) 1.6f else 1f

  AnalysisCard(
      title = "Карта нагрузки",
      icon = Icons.Rounded.Accessibility,
      modifier = modifier,
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
      BodyMapFlip(
          fillFor = heatmapSectorFillFor(loads),
          selectedMuscle = state.selectedMuscle,
          onMuscleClick = onMuscleClicked,
          modifier = Modifier.weight(1.3f),
          figureWidthFraction = 1f,
          showViewLabel = false,
      )
      SelectedMuscleDetails(
          state.selectedMuscleLoad,
          modifier = Modifier.weight(summaryWeight).animateContentSize(GymMotion.spatialDefault()),
      )
    }

    MuscleSelector(
        selected = state.selectedMuscle,
        onSelected = onSelectorSelected,
    )

    Spacer(Modifier.height(14.dp))
    TopMuscleExercises(state.selectedMuscleLoad)
  }
}

/** A shared SVG sector follows its hottest logical muscle, never an aggregate. */
internal fun heatmapSectorFillFor(
    loads: Map<Muscle, MuscleLoadSummary>,
): (MuscleSector) -> androidx.compose.ui.graphics.Color = { sector ->
  ChartPalette.zoneColor(sector.maxMember(loads) { it.weeklySets }?.zone ?: VolumeZone.LOW)
}

/** Числа выбранной мышцы: без них карта — только «красиво», но не ответ на вопрос. */
@Composable
private fun SelectedMuscleDetails(
    load: MuscleLoadSummary?,
    modifier: Modifier = Modifier,
) {
  if (load == null) {
    Text(
        text = "Нажмите на мышцу, чтобы увидеть подробности",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
    return
  }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
        text = load.muscle.displayName(),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          "Объём",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(10.dp).background(ChartPalette.zoneColor(load.zone), CircleShape))
        Text(
            text =
                when (load.zone) {
                  VolumeZone.LOW -> "Малый"
                  VolumeZone.BASE -> "Базовый"
                  VolumeZone.WORKING -> "Рабочий"
                  VolumeZone.GROWTH_GUIDE -> "Эталонный"
                },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
      }
    }
    MuscleSummaryMetric("Подходы", formatDecimal(load.totalSets))
    MuscleSummaryMetric("Эфф. подходы", "${formatDecimal(load.weeklySets)} / нед.")
    MuscleSummaryMetric("Пауза", load.daysSinceLast?.let { "$it дн." } ?: "—")
  }
}

@Composable
private fun MuscleSummaryMetric(label: String, value: String) {
  Column {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        value,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun TopMuscleExercises(load: MuscleLoadSummary?) {
  if (load == null || load.topExercises.isEmpty()) return
  var expanded by rememberSaveable(load.muscle) { mutableStateOf(false) }
  val haptics = gymHaptics()
  Column(
      modifier = Modifier.animateContentSize(GymMotion.spatialDefault()),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    TextButton(
        onClick = {
          haptics.tap()
          expanded = !expanded
        },
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
              stateDescription = if (expanded) "Развернуто" else "Свернуто"
            },
    ) {
      Text(
          "Основной вклад",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
      )
      Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
    }
    if (expanded) {
      load.topExercises.forEachIndexed { index, exercise ->
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
          Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                exercise,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

/**
 * Объём по мышцам в виде bullet-графика: фактическое значение поверх общей шкалы 2 / 5 / 10.
 *
 * Строки отсортированы от большего объёма к меньшему: это снимок распределения, а не рецепт
 * «добрать до потолка». Больший объём в среднем помогает гипертрофии с убывающей отдачей, но
 * единого оптимума или максимального восстанавливаемого объёма не существует.
 */
@Composable
internal fun MuscleVolumeCard(
    state: AnalysisUiState,
    onMuscleClicked: (Muscle?) -> Unit,
    modifier: Modifier = Modifier,
) {
  val rows =
      state.report.muscleLoads.filter { it.totalSets > 0.0 }.sortedByDescending { it.weeklySets }

  AnalysisCard(
      title = "Объём по мышцам",
      collapsible = true,
      subtitle = "Эффективные подходы в неделю — ориентир для гипертрофии, не диагноз тренировки",
      icon = Icons.Rounded.BarChart,
      modifier = modifier,
  ) {
    rows.forEach { load ->
      MuscleBulletRow(
          load = load,
          selected = load.muscle == state.selectedMuscle,
          onClick = { onMuscleClicked(load.muscle) },
      )
    }
  }
}

@Composable
private fun MuscleBulletRow(
    load: MuscleLoadSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
  val colors = rememberChartColors()
  val haptics = gymHaptics()
  val fill = ChartPalette.zoneColor(load.zone)
  val maxValue = maxOf(HypertrophyVolumeGuide.WORKING_MAX, load.weeklySets) * 1.05

  Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = load.muscle.displayName(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color =
            if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(MUSCLE_LABEL_WIDTH),
    )
    Box(
        modifier =
            Modifier.weight(1f).heightIn(min = 48.dp).clickable {
              haptics.tap()
              onClick()
            },
    ) {
      Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
        val trackHeight = 10.dp.toPx()
        val top = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        fun x(value: Double) = (value / maxValue).coerceIn(0.0, 1.0).toFloat() * size.width

        drawRoundRect(
            color = colors.track,
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )
        // Фон показывает четыре честные ступени; метки 2 / 5 / 10 остаются читаемыми
        // и при значениях выше ориентира — это не верхний лимит.
        listOf(
                Triple(0.0, HypertrophyVolumeGuide.LOW_MAX, ChartPalette.LowVolume),
                Triple(
                    HypertrophyVolumeGuide.LOW_MAX,
                    HypertrophyVolumeGuide.BASE_MAX,
                    ChartPalette.BaseVolume,
                ),
                Triple(
                    HypertrophyVolumeGuide.BASE_MAX,
                    HypertrophyVolumeGuide.WORKING_MAX,
                    ChartPalette.WorkingVolume,
                ),
                Triple(
                    HypertrophyVolumeGuide.WORKING_MAX,
                    maxValue,
                    ChartPalette.GrowthGuideVolume,
                ),
            )
            .forEach { (start, end, color) ->
              if (end > start) {
                drawRect(
                    color = color.copy(alpha = 0.18f),
                    topLeft = Offset(x(start), top),
                    size = Size(x(end) - x(start), trackHeight),
                )
              }
            }
        listOf(
                HypertrophyVolumeGuide.LOW_MAX,
                HypertrophyVolumeGuide.BASE_MAX,
                HypertrophyVolumeGuide.WORKING_MAX,
            )
            .forEach { landmark ->
              drawLine(
                  color = colors.grid,
                  start = Offset(x(landmark), top - 3.dp.toPx()),
                  end = Offset(x(landmark), top + trackHeight + 3.dp.toPx()),
                  strokeWidth = ChartSpec.GridWidth.toPx(),
              )
            }
        if (load.weeklySets > 0.0) {
          val barHeight = 14.dp.toPx()
          val barTop = (size.height - barHeight) / 2f
          drawRoundRect(
              color = fill,
              topLeft = Offset(0f, barTop),
              size = Size(x(load.weeklySets).coerceAtLeast(3.dp.toPx()), barHeight),
              cornerRadius = CornerRadius(ChartSpec.BarCorner.toPx(), ChartSpec.BarCorner.toPx()),
          )
        }
      }
    }
    Spacer(Modifier.width(8.dp))
    Text(
        text = formatDecimal(load.weeklySets),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(34.dp),
    )
  }
}

/**
 * Частота и пауза: сколько дней прошло с последней работы по каждой мышце.
 *
 * Отсортировано по паузе вниз, поэтому первые строки — это мягкое напоминание, что давно не было
 * работы. При равном объёме частота сама по себе не даёт значимого преимущества для гипертрофии;
 * раз в неделю может быть достаточно при умеренном объёме.
 *
 * Основание: https://pubmed.ncbi.nlm.nih.gov/30558493/ и position stand IUSCA:
 * https://journal.iusca.org/index.php/Journal/article/download/81/140/5323
 */
@Composable
internal fun MuscleFrequencyCard(
    state: AnalysisUiState,
    modifier: Modifier = Modifier,
) {
  val rows =
      state.report.muscleLoads
          .filter { it.sessionsPerWeek > 0.0 || it.daysSinceLast != null }
          .sortedByDescending { it.daysSinceLast ?: Int.MAX_VALUE }
  if (rows.isEmpty()) return

  AnalysisCard(
      title = "Частота и пауза",
      collapsible = true,
      subtitle =
          "До недели — обычный ритм; дальше — мягкое напоминание. Раз в неделю может " +
              "быть достаточно при умеренном объёме: при равном объёме частота сама по себе не определяет рост",
      icon = Icons.Rounded.Schedule,
      modifier = modifier,
  ) {
    rows.forEach { load -> FrequencyRow(load) }
  }
}

@Composable
private fun FrequencyRow(load: MuscleLoadSummary) {
  val colors = rememberChartColors()
  val maxDays = 14f
  val days = load.daysSinceLast?.toFloat()

  Row(
      modifier = Modifier.fillMaxWidth().height(30.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        text = load.muscle.displayName(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(MUSCLE_LABEL_WIDTH),
    )
    Canvas(modifier = Modifier.weight(1f).height(22.dp)) {
      val centerY = size.height / 2f
      fun x(value: Float) = (value / maxDays).coerceIn(0f, 1f) * size.width

      drawLine(
          color = colors.track,
          start = Offset(0f, centerY),
          end = Offset(size.width, centerY),
          strokeWidth = 6.dp.toPx(),
          cap = androidx.compose.ui.graphics.StrokeCap.Round,
      )
      listOf(7f).forEach { landmark ->
        drawLine(
            color = colors.grid,
            start = Offset(x(landmark), centerY - 7.dp.toPx()),
            end = Offset(x(landmark), centerY + 7.dp.toPx()),
            strokeWidth = ChartSpec.GridWidth.toPx(),
        )
      }
      if (days != null) {
        val dotX = x(days)
        drawLine(
            color = colors.mark.copy(alpha = 0.5f),
            start = Offset(0f, centerY),
            end = Offset(dotX, centerY),
            strokeWidth = 6.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(
            color = colors.surface,
            radius = ChartSpec.MarkerRadius.toPx() + ChartSpec.MarkerRing.toPx(),
            center = Offset(dotX, centerY),
        )
        drawCircle(
            color =
                when {
                  days > 7f -> ChartPalette.Maintenance
                  else -> ChartPalette.Optimal
                },
            radius = ChartSpec.MarkerRadius.toPx(),
            center = Offset(dotX, centerY),
        )
      }
    }
    Spacer(Modifier.width(8.dp))
    Text(
        text = days?.let { "${it.toInt()} д" } ?: "—",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(34.dp),
    )
  }
}

/**
 * Ширина колонки названий мышц в строках-графиках. Подобрана под самое длинное название
 * («Разгибатели спины»); что не влезло, укорачивается многоточием, а не обрезается по букве.
 */
private val MUSCLE_LABEL_WIDTH = 130.dp
