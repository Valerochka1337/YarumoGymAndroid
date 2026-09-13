package com.valerochka1337.valerochkagym.ui.measurements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementChartGroup
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementMetricComparison
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementPeriod
import com.valerochka1337.valerochkagym.domain.measurements.inBodySegmentValues
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisCard
import com.valerochka1337.valerochkagym.ui.analysis.ChipRow
import com.valerochka1337.valerochkagym.ui.analysis.ValueRow
import com.valerochka1337.valerochkagym.ui.analysis.body.InBodySegmentMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.body.InBodySegmentMapMode
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.analysis.formatDecimal
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymSectionTabs
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.UploadStatusBadge
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

private enum class MeasurementsSection(val label: String) {
  OVERVIEW("Обзор"),
  INBODY("InBody"),
  CIRCUMFERENCES("Обхваты"),
}

/**
 * Pushed-раздел «Замеры»: единый период, независимые одновалюстные тренды и история. Каждая
 * карточка графика переключает только метрику своей группы, поэтому кг, см, проценты и уровень
 * никогда не оказываются на общей оси.
 */
@Composable
fun MeasurementsScreen(
    onBack: () -> Unit,
    onCreateMeasurement: () -> Unit,
    onEditMeasurement: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeasurementsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  var showAllMeasurements by remember { mutableStateOf(false) }
  var pendingDeletion by remember { mutableStateOf<BodyMeasurementEntity?>(null) }
  var section by rememberSaveable { mutableStateOf(MeasurementsSection.OVERVIEW) }

  GlowBackground(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      MeasurementsHeader(
          onBack = onBack,
          onShowAll =
              if (state.hasMeasurements) {
                {
                  haptics.tap()
                  showAllMeasurements = true
                }
              } else {
                null
              },
          onAdd = {
            haptics.tap()
            onCreateMeasurement()
          },
      )

      if (state.loading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
          CircularProgressIndicator()
          Spacer(Modifier.height(12.dp))
          Text(
              text = "Загружаем замеры…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        return@Column
      }
      if (!state.hasMeasurements) {
        EmptyMeasurementsState(
            onAdd = {
              haptics.tap()
              onCreateMeasurement()
            }
        )
        return@Column
      }

      val measurements = state.measurements.orEmpty()
      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          ChipRow(
              options = MeasurementPeriod.entries.toList(),
              selected = state.period,
              label = MeasurementPeriod::displayName,
              onSelect = {
                haptics.tap()
                viewModel.onPeriodSelected(it)
              },
          )
        }
        item {
          GymSectionTabs(
              options = MeasurementsSection.entries,
              selected = section,
              label = { it.label },
              onSelect = { item ->
                haptics.tap()
                section = item
              },
          )
        }

        if (measurements.isEmpty()) {
          item {
            EmptyPeriodState(
                onShowAll = {
                  haptics.tap()
                  showAllMeasurements = true
                },
            )
          }
        } else {
          when (section) {
            MeasurementsSection.OVERVIEW -> {
              item { LatestSummaryCard(state.summary) }
              item {
                MetricTrendCard(
                    title = "Главный тренд",
                    subtitle = "Выберите показатель состава тела для сравнения.",
                    icon = Icons.Rounded.MonitorWeight,
                    metrics = metricsFor(MeasurementChartGroup.COMPOSITION),
                    selectedMetric = state.compositionMetric,
                    measurements = measurements,
                    selectedMeasurementId = state.selectedMeasurementId,
                    zoneLabel = state.zone,
                    onMetricSelected = viewModel::onCompositionMetricSelected,
                    onMeasurementSelected = viewModel::onMeasurementSelected,
                )
              }
              item {
                MeasurementHistoryCard(
                    measurements = measurements.take(3),
                    zone = state.zone,
                    onEdit = { onEditMeasurement(it.id) },
                    onRetry = viewModel::retryUpload,
                )
              }
            }

            MeasurementsSection.INBODY -> {
              item {
                MetricTrendCard(
                    title = "Показатели InBody",
                    subtitle = "Сравнивайте замеры на одном аппарате и в похожих условиях.",
                    icon = Icons.Rounded.TableChart,
                    metrics = metricsFor(MeasurementChartGroup.INBODY),
                    selectedMetric = state.inBodyMetric,
                    measurements = measurements,
                    selectedMeasurementId = state.selectedMeasurementId,
                    zoneLabel = state.zone,
                    onMetricSelected = viewModel::onInBodyMetricSelected,
                    onMeasurementSelected = viewModel::onMeasurementSelected,
                )
              }
              item {
                MetricTrendCard(
                    title = "WHR / висцеральный жир",
                    subtitle = "Читайте эти показатели как тренд, а не диагноз.",
                    icon = Icons.AutoMirrored.Rounded.ShowChart,
                    metrics = metricsFor(MeasurementChartGroup.RISK),
                    selectedMetric = state.riskMetric,
                    measurements = measurements,
                    selectedMeasurementId = state.selectedMeasurementId,
                    zoneLabel = state.zone,
                    onMetricSelected = viewModel::onRiskMetricSelected,
                    onMeasurementSelected = viewModel::onMeasurementSelected,
                )
              }
              item { SelectedMeasurementCard(state.selectedMeasurement, state.zone) }
              item { InBodyReportCard(state.selectedMeasurement) }
              item { InBodySegmentalCard(state.selectedMeasurement) }
            }

            MeasurementsSection.CIRCUMFERENCES -> {
              item {
                MetricTrendCard(
                    title = "Обхваты",
                    subtitle = "Повторяйте один и тот же способ измерения.",
                    icon = Icons.Rounded.Straighten,
                    metrics = metricsFor(MeasurementChartGroup.CIRCUMFERENCES),
                    selectedMetric = state.circumferenceMetric,
                    measurements = measurements,
                    selectedMeasurementId = state.selectedMeasurementId,
                    zoneLabel = state.zone,
                    onMetricSelected = viewModel::onCircumferenceMetricSelected,
                    onMeasurementSelected = viewModel::onMeasurementSelected,
                )
              }
              item { SelectedMeasurementCard(state.selectedMeasurement, state.zone) }
            }
          }
        }
      }
    }
  }

  if (showAllMeasurements) {
    AllMeasurementsSheet(
        measurements = state.allMeasurements.orEmpty(),
        zone = state.zone,
        onEdit = { measurement ->
          haptics.tap()
          showAllMeasurements = false
          onEditMeasurement(measurement.id)
        },
        onDelete = { measurement ->
          haptics.tap()
          pendingDeletion = measurement
        },
        onDismiss = { showAllMeasurements = false },
    )
  }

  pendingDeletion?.let { measurement ->
    AlertDialog(
        onDismissRequest = { pendingDeletion = null },
        title = { Text("Удалить замер?") },
        text = {
          Text(
              "Замер от ${formatMeasurementDate(measurement.measuredAt, state.zone)} будет удалён только из приложения. " +
                  "Уже выгруженная строка Google Sheets останется в журнале.",
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                haptics.reject()
                pendingDeletion = null
                viewModel.deleteMeasurement(measurement.id)
              },
          ) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Отмена") } },
    )
  }
}

@Composable
private fun MeasurementsHeader(
    onBack: () -> Unit,
    onShowAll: (() -> Unit)?,
    onAdd: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CircleIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = "Назад",
        onClick = onBack,
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text = "Замеры",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.weight(1f),
    )
    onShowAll?.let {
      CircleIconButton(
          icon = Icons.AutoMirrored.Rounded.List,
          contentDescription = "Все замеры",
          onClick = it,
      )
      Spacer(Modifier.width(8.dp))
    }
    CircleIconButton(
        icon = Icons.Rounded.Add,
        contentDescription = "Добавить замер",
        onClick = onAdd,
    )
  }
}

@Composable
private fun EmptyMeasurementsState(onAdd: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(
        imageVector = Icons.Rounded.MonitorWeight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Добавьте первый замер",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text =
            "Вес, состав тела и обхваты появятся здесь как честные тренды — без подмены пропусков нулями.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    PillButton(text = "Добавить замер", onClick = onAdd, leadingIcon = Icons.Rounded.Add)
  }
}

@Composable
private fun EmptyPeriodState(onShowAll: () -> Unit) {
  AnalysisCard(
      title = "Нет замеров за этот период",
      subtitle =
          "Замеры могут быть вне выбранного периода — откройте полный список или измените фильтр.",
      icon = Icons.Rounded.MonitorWeight,
  ) {
    TextButton(onClick = onShowAll) { Text("Все замеры") }
  }
}

/** Полный локальный журнал доступен независимо от фильтра графиков, чтобы записи не терялись. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllMeasurementsSheet(
    measurements: List<BodyMeasurementEntity>,
    zone: java.time.ZoneId,
    onEdit: (BodyMeasurementEntity) -> Unit,
    onDelete: (BodyMeasurementEntity) -> Unit,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = "Все замеры",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
          text =
              "Локальный список не зависит от периода графиков. Нажмите карточку, чтобы исправить замер.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(measurements, key = BodyMeasurementEntity::id) { measurement ->
          AllMeasurementsSheetRow(
              measurement = measurement,
              zone = zone,
              onEdit = { onEdit(measurement) },
              onDelete = { onDelete(measurement) },
          )
        }
      }
      TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
    }
  }
}

@Composable
private fun AllMeasurementsSheetRow(
    measurement: BodyMeasurementEntity,
    zone: java.time.ZoneId,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      onClick = onEdit,
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = formatMeasurementDate(measurement.measuredAt, zone),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Показателей: ${measurement.filledValueCount()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      UploadStatusBadge(measurement.uploadStatus)
    }
    Row(
        modifier = Modifier.align(Alignment.End),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      TextButton(onClick = onEdit) { Text("Открыть") }
      TextButton(onClick = onDelete) {
        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("Удалить", color = MaterialTheme.colorScheme.error)
      }
    }
  }
}

@Composable
private fun LatestSummaryCard(summary: List<MeasurementMetricComparison>) {
  AnalysisCard(
      title = "Последний замер",
      subtitle = "Изменение сравнивается с предыдущим заполненным значением той же метрики.",
      icon = Icons.Rounded.TableChart,
  ) {
    summary.forEachIndexed { index, item ->
      ValueRow(
          label = item.metric.title,
          value =
              buildString {
                append(formatMeasurementValue(item.metric, item.value))
                item.delta?.let { delta ->
                  append(" · ${formatMeasurementDelta(item.metric, delta)}")
                }
              },
          accent = item.delta != null,
      )
      if (index != summary.lastIndex) Spacer(Modifier.height(6.dp))
    }
  }
}

@Composable
private fun MetricTrendCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    metrics: List<BodyMeasurementMetric>,
    selectedMetric: BodyMeasurementMetric,
    measurements: List<BodyMeasurementEntity>,
    selectedMeasurementId: String?,
    zoneLabel: java.time.ZoneId,
    onMetricSelected: (BodyMeasurementMetric) -> Unit,
    onMeasurementSelected: (String) -> Unit,
) {
  val chartPoints =
      measurements
          .asSequence()
          .mapNotNull { measurement ->
            selectedMetric.value(measurement)?.let { value ->
              MeasurementChartPoint(
                  id = measurement.id,
                  point =
                      LinePoint(
                          xMillis = measurement.measuredAt,
                          y = value.toFloat(),
                          xLabel = formatMeasurementChartDate(measurement.measuredAt, zoneLabel),
                      ),
              )
            }
          }
          .sortedBy { it.point.xMillis }
          .toList()

  AnalysisCard(title = title, subtitle = subtitle, icon = icon) {
    ChipRow(
        options = metrics,
        selected = selectedMetric,
        label = BodyMeasurementMetric::title,
        onSelect = onMetricSelected,
    )
    Spacer(Modifier.height(12.dp))
    if (chartPoints.isEmpty()) {
      Text(
          text = "Для «${selectedMetric.title}» пока нет значений.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      val selectedIndex =
          chartPoints.indexOfFirst { it.id == selectedMeasurementId }.takeIf { it >= 0 }
      TrendLineChart(
          points = chartPoints.map(MeasurementChartPoint::point),
          selectedIndex = selectedIndex,
          onSelect = { index -> index?.let { onMeasurementSelected(chartPoints[it].id) } },
          valueFormatter = { value -> formatMeasurementValue(selectedMetric, value.toDouble()) },
      )
    }
  }
}

private data class MeasurementChartPoint(val id: String, val point: LinePoint)

@Composable
private fun SelectedMeasurementCard(measurement: BodyMeasurementEntity?, zone: java.time.ZoneId) {
  if (measurement == null) return
  val values =
      BodyMeasurementMetric.entries.mapNotNull { metric ->
        metric.value(measurement)?.let { value -> metric to value }
      }
  AnalysisCard(
      title = "Точка на графике",
      subtitle =
          "${formatMeasurementDate(measurement.measuredAt, zone)} · все заполненные значения",
      icon = Icons.Rounded.TableChart,
  ) {
    values.forEachIndexed { index, (metric, value) ->
      ValueRow(label = metric.title, value = formatMeasurementValue(metric, value))
      if (index != values.lastIndex) Spacer(Modifier.height(6.dp))
    }
  }
}

@Composable
private fun MeasurementHistoryCard(
    measurements: List<BodyMeasurementEntity>,
    zone: java.time.ZoneId,
    onEdit: (BodyMeasurementEntity) -> Unit,
    onRetry: (String) -> Unit,
) {
  AnalysisCard(
      title = "История",
      subtitle = "Правки и удаление не меняют уже добавленную строку в Google Sheets.",
      icon = Icons.Rounded.MonitorWeight,
  ) {
    measurements.forEachIndexed { index, measurement ->
      MeasurementHistoryRow(measurement, zone, onEdit, onRetry)
      if (index != measurements.lastIndex) {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        Spacer(Modifier.height(8.dp))
      }
    }
  }
}

@Composable
private fun MeasurementHistoryRow(
    measurement: BodyMeasurementEntity,
    zone: java.time.ZoneId,
    onEdit: (BodyMeasurementEntity) -> Unit,
    onRetry: (String) -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .clickable { onEdit(measurement) }
              .padding(vertical = 2.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = formatMeasurementDate(measurement.measuredAt, zone),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Заполнено показателей: ${measurement.filledValueCount()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      UploadStatusBadge(measurement.uploadStatus)
    }
    if (measurement.uploadStatus == UploadStatus.FAILED) {
      measurement.uploadError
          ?.takeIf { it.isNotBlank() }
          ?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
          }
      TextButton(onClick = { onRetry(measurement.id) }) { Text("Повторить выгрузку") }
    }
  }
}

private fun BodyMeasurementEntity.filledValueCount(): Int =
    BodyMeasurementMetric.entries.count { it.value(this) != null } +
        InBodySegment.entries.sumOf { segment ->
          val values = inBodySegmentValues(segment)
          listOf(
                  values.leanMassKg,
                  values.leanPercentage,
                  values.fatMassKg,
                  values.fatPercentage,
              )
              .count { it != null }
        }

private fun metricsFor(group: MeasurementChartGroup): List<BodyMeasurementMetric> =
    BodyMeasurementMetric.entries.filter { it.group == group }

@Composable
private fun InBodyReportCard(measurement: BodyMeasurementEntity?) {
  measurement ?: return
  val rows = buildList {
    measurement.bodyFatMassKg?.let { value ->
      add(
          "Масса жира по аппарату" to
              formatMeasurementValue(BodyMeasurementMetric.BODY_FAT_MASS, value)
      )
    }
    listOf(
            BodyMeasurementMetric.INBODY_SCORE,
            BodyMeasurementMetric.TOTAL_BODY_WATER,
            BodyMeasurementMetric.PROTEIN,
            BodyMeasurementMetric.MINERALS,
            BodyMeasurementMetric.BODY_MASS_INDEX,
            BodyMeasurementMetric.FAT_FREE_MASS,
            BodyMeasurementMetric.BASAL_METABOLIC_RATE,
            BodyMeasurementMetric.RECOMMENDED_CALORIE_INTAKE,
        )
        .forEach { metric ->
          metric.value(measurement)?.let { value ->
            add(metric.title to formatMeasurementValue(metric, value))
          }
        }
  }
  if (rows.isEmpty()) return
  AnalysisCard(
      title = "Отчёт InBody",
      subtitle = "Фактические показатели с листа; это не рекомендации и не медицинский диагноз.",
      icon = Icons.Rounded.MonitorWeight,
  ) {
    rows.forEachIndexed { index, (label, value) ->
      ValueRow(label = label, value = value)
      if (index != rows.lastIndex) Spacer(Modifier.height(6.dp))
    }
  }
}

@Composable
private fun InBodySegmentalCard(measurement: BodyMeasurementEntity?) {
  measurement ?: return
  val values = measurement.inBodySegmentValues()
  if (values.values.none(InBodySegmentValues::hasAnyValue)) return
  var mode by remember(measurement.id) { mutableStateOf(InBodySegmentMapMode.LEAN) }
  AnalysisCard(
      title = "Сегментный анализ InBody",
      subtitle =
          "Цвет показывает процент от эталона InBody, а не сравнение частей тела между собой.",
      icon = Icons.Rounded.MonitorWeight,
  ) {
    ChipRow(
        options = InBodySegmentMapMode.entries.toList(),
        selected = mode,
        label = InBodySegmentMapMode::displayName,
        onSelect = { mode = it },
    )
    Spacer(Modifier.height(12.dp))
    InBodySegmentMapFlip(values = values, mode = mode)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Точные значения",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    InBodySegment.entries.forEachIndexed { index, segment ->
      val value = values[segment] ?: InBodySegmentValues()
      ValueRow(
          label = segment.displayName,
          value = value.formatFor(mode),
      )
      if (index != InBodySegment.entries.lastIndex) Spacer(Modifier.height(6.dp))
    }
  }
}

private fun InBodySegmentValues.formatFor(mode: InBodySegmentMapMode): String {
  val mass = if (mode == InBodySegmentMapMode.LEAN) leanMassKg else fatMassKg
  val percentage = if (mode == InBodySegmentMapMode.LEAN) leanPercentage else fatPercentage
  return listOfNotNull(
          mass?.let { "${formatDecimal(it)} кг" },
          percentage?.let { "${formatDecimal(it)} %" },
      )
      .joinToString(" · ")
      .ifBlank { "Нет данных" }
}

private val InBodySegment.displayName: String
  get() =
      when (this) {
        InBodySegment.LEFT_ARM -> "Левая рука"
        InBodySegment.RIGHT_ARM -> "Правая рука"
        InBodySegment.TRUNK -> "Корпус"
        InBodySegment.LEFT_LEG -> "Левая нога"
        InBodySegment.RIGHT_LEG -> "Правая нога"
      }

private val InBodySegmentMapMode.displayName: String
  get() = if (this == InBodySegmentMapMode.LEAN) "Мышцы" else "Жир"
