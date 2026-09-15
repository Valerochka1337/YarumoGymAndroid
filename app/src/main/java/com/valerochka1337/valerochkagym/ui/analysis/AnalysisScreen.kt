package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymSectionTabs
import com.valerochka1337.valerochkagym.ui.components.GymTopBar
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.LocalDate

private enum class AnalysisSection(val label: String) {
  OVERVIEW("Обзор"),
  LOAD("Нагрузка"),
  PROGRESS("Прогресс"),
}

/**
 * Вкладка «Анализы»: тепловая карта нагрузки по мышцам, недельный объём, прогресс силы по
 * упражнениям и производные показатели (скачок нагрузки, баланс, частота, рекорды).
 *
 * Порядок карточек — от «что делать сейчас» к «как идут дела»: сводка, карта тела и объём по мышцам
 * отвечают на вопрос «чего не хватает», прогресс и рекорды — «работает ли план».
 *
 * Переключатель периода стоит **один раз над всем** содержимым: у каждой карточки свой фильтр
 * означал бы, что графики на экране показывают разные срезы и их нельзя сравнивать.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnalysisScreen(
    onOpenSettings: () -> Unit,
    onOpenMeasurements: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var upgradeMessage by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(viewModel) {
    viewModel.messages.collect {
      upgradeMessage = it
      viewModel.acknowledgeUpgradeNotice()
    }
  }

  AnalysisScreenContent(
      state,
      onOpenSettings,
      onOpenMeasurements,
      onExerciseClick,
      modifier = modifier,
      upgradeMessage = upgradeMessage,
      onPeriodSelected = viewModel::onPeriodSelected,
      onCustomRangeSelected = viewModel::onCustomRangeSelected,
      onMuscleClicked = viewModel::onMuscleClicked,
      onSelectorMuscleSelected = viewModel::onSelectorMuscleSelected,
      onExerciseSelected = viewModel::onExerciseSelected,
      onWeeklyMetricSelected = viewModel::onWeeklyMetricSelected,
      onWeekSelected = viewModel::onWeekSelected,
      onSessionSelected = viewModel::onSessionSelected,
  )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AnalysisScreenContent(
    state: AnalysisUiState,
    onOpenSettings: () -> Unit,
    onOpenMeasurements: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    upgradeMessage: String? = null,
    onPeriodSelected: (AnalysisPeriod) -> Unit = {},
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    onMuscleClicked: (Muscle?) -> Unit = {},
    onSelectorMuscleSelected: (Muscle) -> Unit = {},
    onExerciseSelected: (Long) -> Unit = {},
    onWeeklyMetricSelected: (WeeklyMetric) -> Unit = {},
    onWeekSelected: (Int?) -> Unit = {},
    onSessionSelected: (Int?) -> Unit = {},
) {
  val haptics = gymHaptics()
  var section by rememberSaveable { mutableStateOf(AnalysisSection.OVERVIEW.name) }
  val selectedSection =
      AnalysisSection.entries.firstOrNull { it.name == section } ?: AnalysisSection.OVERVIEW
  GlowBackground(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      GymTopBar(
          title = "Анализ",
          onOpenSettings = onOpenSettings,
          actions = {
            PillButton(
                text = "Замеры",
                leadingIcon = Icons.Rounded.MonitorWeight,
                compact = true,
                onClick = {
                  haptics.tap()
                  onOpenMeasurements()
                },
            )
          },
      )

      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        upgradeMessage?.let { message ->
          item {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
          }
        }
        item {
          GymSectionTabs(
              options = AnalysisSection.entries,
              selected = selectedSection,
              label = { it.label },
              onSelect = { item ->
                haptics.tap()
                section = item.name
              },
          )
        }
        item {
          AnalysisPeriodSelector(
              period = state.period,
              range = state.report.range,
              onPeriodSelected = {
                haptics.tap()
                onPeriodSelected(it)
              },
              onCustomRangeSelected = { start, endInclusive ->
                haptics.tap()
                onCustomRangeSelected(start, endInclusive)
              },
          )
        }

        if (state.loading && !state.report.hasData) {
          item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator()
              Spacer(Modifier.height(12.dp))
              Text(
                  text = "Собираем данные…",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else if (!state.report.hasData) {
          item { EmptyState(hasHistory = state.report.records.isNotEmpty()) }
          if (state.report.records.isNotEmpty()) {
            item {
              RecordsCard(
                  state = state,
                  onExerciseClick = {
                    haptics.tap()
                    onExerciseClick(it)
                  },
              )
            }
          }
        } else {
          when (selectedSection) {
            AnalysisSection.OVERVIEW -> {
              item { SummaryCard(state) }
              item {
                WorkloadCard(
                    state.report.workload,
                    state.report.range.endInclusive,
                )
              }
              item {
                RecordsCard(
                    state = state,
                    onExerciseClick = {
                      haptics.tap()
                      onExerciseClick(it)
                    },
                )
              }
            }

            AnalysisSection.LOAD -> {
              item {
                MuscleHeatmapCard(
                    state = state,
                    onMuscleClicked = onMuscleClicked,
                    onSelectorSelected = onSelectorMuscleSelected,
                )
              }
              item {
                MuscleVolumeCard(
                    state = state,
                    onMuscleClicked = onMuscleClicked,
                )
              }
              item { MuscleFrequencyCard(state) }
              item { BalanceCard(state.report.balances) }
            }

            AnalysisSection.PROGRESS -> {
              item {
                ExerciseProgressCard(
                    state = state,
                    onExerciseSelected = onExerciseSelected,
                    onSessionSelected = onSessionSelected,
                    onExerciseClick = {
                      haptics.tap()
                      onExerciseClick(it)
                    },
                )
              }
              item {
                WeeklyVolumeCard(
                    state = state,
                    onMetricSelected = onWeeklyMetricSelected,
                    onWeekSelected = onWeekSelected,
                )
              }
            }
          }
        }
      }
    }
  }
}

/** Пустая история или пустой выбранный период: объясняем, что появится, вместо нулевых графиков. */
@Composable
private fun EmptyState(
    hasHistory: Boolean,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.ic_tab_analysis),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = if (hasHistory) "В этом периоде нет тренировок" else "Пока нечего анализировать",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text =
            if (hasHistory) {
              "Выберите другой диапазон — рекорды ниже остаются за всю историю."
            } else {
              "Завершите первую тренировку — появятся карта нагрузки по мышцам, недельный объём " +
                  "и графики прогресса по каждому упражнению."
            },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
  }
}
