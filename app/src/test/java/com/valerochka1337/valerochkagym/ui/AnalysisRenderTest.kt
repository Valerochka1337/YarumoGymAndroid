package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.domain.ExerciseStatisticsCalculator
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisDateRange
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsEngine
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsInput
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisDateRangePickerDialog
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisPeriodSelector
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisSelectableDates
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisUiState
import com.valerochka1337.valerochkagym.ui.analysis.BalanceCard
import com.valerochka1337.valerochkagym.ui.analysis.ExerciseProgressCard
import com.valerochka1337.valerochkagym.ui.analysis.MuscleFrequencyCard
import com.valerochka1337.valerochkagym.ui.analysis.MuscleHeatmapCard
import com.valerochka1337.valerochkagym.ui.analysis.MuscleVolumeCard
import com.valerochka1337.valerochkagym.ui.analysis.ProgressExerciseSelector
import com.valerochka1337.valerochkagym.ui.analysis.RecordsCard
import com.valerochka1337.valerochkagym.ui.analysis.SummaryCard
import com.valerochka1337.valerochkagym.ui.analysis.WeeklyVolumeCard
import com.valerochka1337.valerochkagym.ui.analysis.WorkloadCard
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import com.valerochka1337.valerochkagym.ui.analysis.body.InBodySegmentMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.body.InBodySegmentMapMode
import com.valerochka1337.valerochkagym.ui.analysis.body.MuscleSelector
import com.valerochka1337.valerochkagym.ui.analysis.body.maxMember
import com.valerochka1337.valerochkagym.ui.exercise.ExerciseDetailContent
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Дымовой тест отрисовки вкладки «Анализы».
 *
 * Графики и карта тела нарисованы вручную в Compose Canvas, поэтому одной компиляции мало: ошибки
 * такого кода (бесконечные ограничения, деление на ноль в геометрии, вылет подписи за пределы)
 * проявляются только при реальной отрисовке. Тест собирает отчёт настоящим движком по искусственной
 * истории и рендерит все карточки, а результат сохраняет в PNG (`build/reports/analysis-render/`) —
 * снимки можно посмотреть глазами при правках вида.
 *
 * Золотых картинок нет намеренно: сравнение пикселей на разных машинах даёт ложные падения.
 * Проверяется то, что действительно должно держаться — отрисовка без исключений и непустое
 * изображение.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Высокий «экран»: карточки помещаются целиком, и снимок захватывает их без прокрутки.
@Config(application = Application::class, qualifiers = "w420dp-h5200dp-xhdpi")
class AnalysisRenderTest {

  @get:Rule val composeRule = createComposeRule()

  private val zone: ZoneId = ZoneId.of("UTC")
  private val now = 1_780_000_000_000L
  private val day = 86_400_000L

  @Test
  fun `analysis cards render`() {
    val state = buildState()

    composeRule.setContent {
      GymTheme {
        Column(
            modifier = Modifier.width(420.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          SummaryCard(state)
          MuscleHeatmapCard(state, onMuscleClicked = {})
          MuscleVolumeCard(state, onMuscleClicked = {})
          MuscleFrequencyCard(state)
          WeeklyVolumeCard(state, onMetricSelected = {}, onWeekSelected = {})
          WorkloadCard(state.report.workload, state.report.range.endInclusive)
          BalanceCard(state.report.balances)
          ExerciseProgressCard(
              state,
              onExerciseSelected = {},
              onSessionSelected = {},
              onExerciseClick = {},
          )
          RecordsCard(state, onExerciseClick = {})
        }
      }
    }
    composeRule.onNodeWithText("Объём по мышцам").performClick()
    composeRule.onNodeWithText("Частота и пауза").performClick()
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue("картинка должна иметь размер", bitmap.width > 0 && bitmap.height > 0)
    save(bitmap, "analysis-cards.png")
  }

  @Test
  fun `muscle selector renders wrapped full names at two times font scale`() {
    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        GymTheme {
          Column(modifier = Modifier.width(420.dp).padding(16.dp)) {
            MuscleSelector(
                selected = Muscle.TIBIALIS_ANTERIOR,
                onSelected = {},
            )
          }
        }
      }
    }
    composeRule.waitForIdle()

    val bitmap =
        composeRule.onNodeWithTag("muscle_selector_viewport").captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    save(bitmap, "muscle-selector.png")
  }

  @Test
  fun `exercise search includes all data backed exercises and selects a filtered result`() {
    val example = buildState().report.exercises.first()
    val choices =
        (1L..12L).map { example.copy(exerciseId = it, name = "Упражнение $it") } +
            example.copy(exerciseId = 13L, name = "Без данных", points = emptyList())
    var selected: Long? = null
    composeRule.setContent {
      GymTheme {
        ProgressExerciseSelector(
            choices,
            example.copy(name = "Выбрать упражнение"),
            { selected = it },
        )
      }
    }
    composeRule.onNodeWithText("Выбрать упражнение").performClick()
    composeRule.onNodeWithText("Без данных").assertDoesNotExist()
    composeRule.onNodeWithText("Поиск упражнения").performTextInput("НЕСУЩЕСТВУЮЩЕЕ")
    composeRule.onNodeWithText("Ничего не найдено").assertIsDisplayed()
    composeRule.onNodeWithText("Поиск упражнения").performTextClearance()
    composeRule.onNodeWithText("Поиск упражнения").performTextInput("упражнение 12")
    composeRule.onNodeWithText("Упражнение 1").assertDoesNotExist()
    composeRule.onNodeWithText("Упражнение 12").performClick()
    assertEquals(12L, selected)
    composeRule.onNodeWithText("Поиск упражнения").assertDoesNotExist()
  }

  @Test
  fun `period selector opens one menu with presets and custom dates`() {
    val today = LocalDate.of(2026, 6, 10)
    val range = AnalysisDateRange(today.minusDays(6), today)
    var selected: AnalysisPeriod? = null

    composeRule.setContent {
      GymTheme {
        AnalysisPeriodSelector(
            period = AnalysisPeriod.LAST_7_DAYS,
            range = range,
            onPeriodSelected = { selected = it },
            onCustomRangeSelected = { _, _ -> },
        )
      }
    }

    composeRule.onNodeWithContentDescription("Выбрать период анализа").performClick()
    composeRule.onNodeWithText("2 недели").assertIsDisplayed().performClick()
    assertEquals(AnalysisPeriod.WEEKS_2, selected)

    composeRule.onNodeWithContentDescription("Выбрать период анализа").performClick()
    composeRule.onNodeWithText("Выбрать даты…").assertIsDisplayed()
  }

  @Test
  fun `calendar disables confirmation for a range shorter than seven days and future dates`() {
    val today = LocalDate.of(2026, 6, 10)
    val oneDayRange = AnalysisDateRange(today.minusDays(1), today.minusDays(1))
    val selectableDates = AnalysisSelectableDates(today)
    val tomorrowMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    assertFalse(selectableDates.isSelectableDate(tomorrowMillis))

    composeRule.setContent {
      GymTheme {
        AnalysisDateRangePickerDialog(
            initialRange = oneDayRange,
            today = today,
            onConfirm = { _, _ -> },
            onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithText("Готово").assertIsNotEnabled()
    composeRule.onNodeWithText("Выберите не меньше 7 дней включительно").assertIsDisplayed()
  }

  @Test
  fun `exercise detail with statistics renders`() {
    val exercise =
        ExerciseEntity(
            id = BENCH,
            name = "Жим штанги лёжа",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
        )
    val statistics =
        ExerciseStatisticsCalculator()
            .calculate(
                type = exercise.type,
                rows =
                    buildState().report.exercises.first().points.flatMapIndexed { index, point ->
                      listOf(
                          AnalyticsSetRow(
                              workoutId = "detail-$index",
                              exerciseId = BENCH,
                              exerciseName = exercise.name,
                              exerciseType = exercise.type,
                              weightKg = point.bestWeightKg,
                              reps = point.bestWeightReps,
                              durationSec = null,
                              speedKmh = null,
                              inclinePct = null,
                              completedAt = point.dateMillis,
                          ),
                      )
                    },
            )

    composeRule.setContent {
      GymTheme {
        ExerciseDetailContent(
            exercise = exercise,
            loads =
                listOf(
                    MuscleLoad(Muscle.UPPER_CHEST, 50),
                    MuscleLoad(Muscle.LOWER_CHEST, 100),
                    MuscleLoad(Muscle.TRICEPS, 70),
                    MuscleLoad(Muscle.FRONT_DELTS, 45),
                ),
            statistics = statistics,
        )
      }
    }
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue("карточка упражнения должна отрисоваться", bitmap.width > 0 && bitmap.height > 0)
    save(bitmap, "exercise-detail.png")
  }

  @Test
  fun `heatmap renders a muscle selected on the back side`() {
    // Задняя фигура и её сдвиг координат нигде больше не видны на снимках — рендерим её явно
    // с выбранной широчайшей, чтобы поймать и разбор задних путей, и обводку выбора.
    val loads = buildState().report.muscleLoads.associateBy { it.muscle }

    composeRule.setContent {
      GymTheme {
        Column(modifier = Modifier.width(420.dp).padding(16.dp)) {
          BodyMapFlip(
              fillFor = { sector ->
                ChartPalette.zoneColor(
                    sector.maxMember(loads) { it.weeklySets }?.zone ?: VolumeZone.LOW
                )
              },
              selectedMuscle = Muscle.LATS,
              initialView = BodyView.BACK,
          )
        }
      }
    }
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    save(bitmap, "analysis-body-back.png")
  }

  @Test
  fun `heatmap renders lower chest selection in its shared sector`() {
    val loads = buildState().report.muscleLoads.associateBy { it.muscle }
    composeRule.setContent {
      GymTheme {
        BodyMapFlip(
            fillFor = { sector ->
              ChartPalette.zoneColor(
                  sector.maxMember(loads) { it.weeklySets }?.zone ?: VolumeZone.LOW
              )
            },
            selectedMuscle = Muscle.LOWER_CHEST,
            initialView = BodyView.FRONT,
            modifier = Modifier.width(420.dp).padding(16.dp),
        )
      }
    }
    composeRule.waitForIdle()
    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    save(bitmap, "analysis-body-lower-chest.png")
  }

  @Test
  fun `InBody segment maps render for muscles and fat`() {
    val values =
        mapOf(
            InBodySegment.LEFT_ARM to
                InBodySegmentValues(
                    leanMassKg = 1.99,
                    leanPercentage = 85.0,
                    fatMassKg = 1.0,
                    fatPercentage = 88.8,
                ),
            InBodySegment.RIGHT_ARM to
                InBodySegmentValues(
                    leanMassKg = 2.07,
                    leanPercentage = 95.1,
                    fatMassKg = 1.0,
                    fatPercentage = 130.0,
                ),
            InBodySegment.TRUNK to
                InBodySegmentValues(
                    leanMassKg = 19.1,
                    leanPercentage = 100.0,
                    fatMassKg = 7.1,
                    fatPercentage = 180.0,
                ),
            InBodySegment.LEFT_LEG to
                InBodySegmentValues(
                    leanMassKg = 7.43,
                    leanPercentage = 108.0,
                    fatMassKg = 2.4,
                    fatPercentage = 100.0,
                ),
            InBodySegment.RIGHT_LEG to
                InBodySegmentValues(
                    leanMassKg = 7.39,
                    leanPercentage = 107.5,
                    fatMassKg = 2.4,
                    fatPercentage = 160.0,
                ),
        )
    composeRule.setContent {
      GymTheme {
        Column(modifier = Modifier.width(420.dp).padding(16.dp)) {
          InBodySegmentMapFlip(values = values, mode = InBodySegmentMapMode.LEAN)
          InBodySegmentMapFlip(values = values, mode = InBodySegmentMapMode.FAT)
        }
      }
    }
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    assertTrue(
        "карта должна содержать красную ступень",
        bitmap.containsColor(ChartPalette.HeatRed.toArgb()),
    )
    assertTrue(
        "карта должна содержать янтарную ступень",
        bitmap.containsColor(ChartPalette.HeatAmber.toArgb()),
    )
    assertTrue(
        "карта должна содержать зелёную ступень",
        bitmap.containsColor(ChartPalette.HeatGreen.toArgb()),
    )
    save(bitmap, "inbody-segment-maps.png")
  }

  @Test
  fun `muscle picker shading renders`() {
    // Разметка мышц в редакторе упражнения красится одной зелёной шкалой по доле вовлечения:
    // проверяем, что ступени различимы и фигура не ломается на частичных значениях.
    val loads =
        mapOf(
            Muscle.UPPER_CHEST to 50,
            Muscle.LOWER_CHEST to 100,
            Muscle.TRICEPS to 65,
            Muscle.FRONT_DELTS to 40,
            Muscle.ABS to 15,
        )
    composeRule.setContent {
      GymTheme {
        val base = MaterialTheme.colorScheme.surfaceContainerHighest
        val accent = MaterialTheme.colorScheme.primary
        Column(modifier = Modifier.width(420.dp).padding(16.dp)) {
          BodyMapFlip(
              fillFor = { sector ->
                val load = sector.maxMember(loads) { it.toDouble() }
                if (load == null) ChartPalette.Empty else lerp(base, accent, load / 100f)
              },
              selectedMuscle = Muscle.LOWER_CHEST,
          )
        }
      }
    }
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    save(bitmap, "exercise-muscle-picker.png")
  }

  /** История на 10 недель: жим, тяга, приседания и кардио — хватает всем карточкам. */
  private fun buildState(): AnalysisUiState {
    val muscleMap =
        mapOf(
            BENCH to
                listOf(
                    MuscleLoad(Muscle.UPPER_CHEST, 100),
                    MuscleLoad(Muscle.TRICEPS, 65),
                    MuscleLoad(Muscle.FRONT_DELTS, 60),
                ),
            ROW to
                listOf(
                    MuscleLoad(Muscle.LATS, 100),
                    MuscleLoad(Muscle.UPPER_BACK, 85),
                    MuscleLoad(Muscle.BICEPS, 60),
                    MuscleLoad(Muscle.REAR_DELTS, 55),
                ),
            SQUAT to
                listOf(
                    MuscleLoad(Muscle.QUADS, 100),
                    MuscleLoad(Muscle.GLUTES, 85),
                    MuscleLoad(Muscle.ADDUCTORS, 60),
                    MuscleLoad(Muscle.HAMSTRINGS, 30),
                ),
            TREADMILL to listOf(MuscleLoad(Muscle.QUADS, 20), MuscleLoad(Muscle.CALVES, 20)),
        )

    val sets = mutableListOf<AnalyticsSetRow>()
    val workouts = mutableListOf<WorkoutEntity>()
    var counter = 0
    for (week in 0..9) {
      listOf(BENCH to 0L, ROW to 2L, SQUAT to 4L).forEach { (exerciseId, dayOffset) ->
        val startedAt = now - (week * 7 + dayOffset) * day
        val workoutId = "w-$week-$exerciseId"
        workouts +=
            WorkoutEntity(
                id = workoutId,
                name = "Тренировка",
                startedAt = startedAt,
                finishedAt = startedAt + 70 * 60_000L,
                uploadStatus = UploadStatus.UPLOADED,
            )
        repeat(4) { _ ->
          counter++
          sets +=
              AnalyticsSetRow(
                  workoutId = workoutId,
                  exerciseId = exerciseId,
                  exerciseName = nameOf(exerciseId),
                  exerciseType = ExerciseType.STRENGTH,
                  // Вес растёт от старых недель к свежим — тренд должен читаться.
                  weightKg = 80.0 + (9 - week) * 2.5 + exerciseId * 10,
                  reps = 5,
                  durationSec = null,
                  speedKmh = null,
                  inclinePct = null,
                  completedAt = startedAt + counter * 1_000L,
              )
        }
        if (exerciseId == SQUAT) {
          sets +=
              AnalyticsSetRow(
                  workoutId = workoutId,
                  exerciseId = TREADMILL,
                  exerciseName = nameOf(TREADMILL),
                  exerciseType = ExerciseType.CARDIO,
                  weightKg = null,
                  reps = null,
                  durationSec = 1_500,
                  speedKmh = 9.0,
                  inclinePct = 1.0,
                  completedAt = startedAt + 60 * 60_000L,
              )
        }
      }
    }

    val report =
        AnalyticsEngine()
            .analyze(
                AnalyticsInput(
                    sets = sets.sortedBy { it.completedAt },
                    workouts = workouts,
                    muscleMap = muscleMap,
                    nowMillis = now,
                    zone = zone,
                ),
                AnalysisPeriod.WEEKS_12,
            )
    return AnalysisUiState(
        loading = false,
        report = report,
        period = AnalysisPeriod.WEEKS_12,
        selectedMuscle = Muscle.UPPER_CHEST,
        zone = zone,
    )
  }

  private fun nameOf(exerciseId: Long): String =
      when (exerciseId) {
        BENCH -> "Жим штанги лёжа"
        ROW -> "Тяга штанги в наклоне"
        SQUAT -> "Приседания со штангой"
        else -> "Беговая дорожка"
      }

  private fun save(bitmap: android.graphics.Bitmap, name: String) {
    val dir = File("build/reports/analysis-render").apply { mkdirs() }
    FileOutputStream(File(dir, name)).use { stream ->
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
    }
  }

  private fun android.graphics.Bitmap.containsColor(expected: Int): Boolean {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return pixels.any { it == expected }
  }

  private companion object {
    const val BENCH = 1L
    const val ROW = 2L
    const val SQUAT = 3L
    const val TREADMILL = 4L
  }
}
