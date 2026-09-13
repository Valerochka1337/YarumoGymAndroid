package com.valerochka1337.valerochkagym.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymTopBar
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Порог горизонтального свайпа для смены месяца, px. */
private const val SWIPE_THRESHOLD = 80f

private val WEEKDAY_LABELS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Вкладка «Календарь»: месячная сетка (что сделано + что запланировано), навигация по месяцам
 * стрелками и свайпом, нижняя шторка выбранного дня и переход к планированию тренировок.
 */
@Composable
fun CalendarScreen(
    onWorkoutClick: (String) -> Unit,
    onStartWorkout: () -> Unit,
    onOpenPlanning: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
  val haptics = com.valerochka1337.valerochkagym.ui.haptics.gymHaptics()
  val month by viewModel.monthUi.collectAsStateWithLifecycle()
  val sheet by viewModel.daySheet.collectAsStateWithLifecycle()
  val routines by viewModel.routines.collectAsStateWithLifecycle()
  val calendarStatus by viewModel.calendarStatus.collectAsStateWithLifecycle()

  val snackbarHostState = remember { SnackbarHostState() }

  // Двухфазное планирование ad-hoc: выбранный день → выбор программы → выбор времени.
  var planningDate by remember { mutableStateOf<LocalDate?>(null) }
  var pickedRoutineId by remember { mutableStateOf<Long?>(null) }
  var movingAdHoc by remember { mutableStateOf<AdHocUi?>(null) }
  var movingRecurring by remember { mutableStateOf<RecurringUi?>(null) }

  LaunchedEffect(Unit) {
    viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
  }
  LaunchedEffect(Unit) { viewModel.startEvents.collect { onStartWorkout() } }

  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BoxWithConstraints {
          val separatePlanningRow = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
          Column {
            GymTopBar(
                title = "Календарь",
                onOpenSettings = onOpenSettings,
                actions = {
                  if (!separatePlanningRow) {
                    PillButton(
                        "AI-план",
                        {
                          haptics.tap()
                          onOpenPlanning()
                        },
                        leadingIcon = Icons.Rounded.AutoAwesome,
                        compact = true,
                    )
                  }
                },
            )
            if (separatePlanningRow) {
              PillButton(
                  "AI-план",
                  {
                    haptics.tap()
                    onOpenPlanning()
                  },
                  leadingIcon = Icons.Rounded.AutoAwesome,
                  compact = true,
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
              )
            }
          }
        }

        CalendarStatusBanner(status = calendarStatus, onRetry = viewModel::retryMigration)
        month.planMessage?.let { message ->
          androidx.compose.material3.TextButton(onClick = viewModel::retryMigration) {
            androidx.compose.material3.Text("$message. Повторить")
          }
        }

        MonthHeader(
            title = month.title,
            onPrev = viewModel::prevMonth,
            onNext = viewModel::nextMonth,
        )

        WeekdayHeader()

        // Смена месяца — направленный слайд: вперёд сетка уезжает влево, назад — вправо,
        // в ту же сторону, что и свайп. Высота (5 или 6 недель) переезжает той же пружиной.
        val slideSpec = GymMotion.spatialDefault<IntOffset>()
        val fadeSpec = GymMotion.effectsDefault<Float>()
        val sizeSpec = GymMotion.spatialDefault<IntSize>()
        AnimatedContent(
            targetState = month,
            transitionSpec = {
              val direction =
                  if (targetState.yearMonth >= initialState.yearMonth) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                  } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                  }
              (slideIntoContainer(direction, slideSpec) + fadeIn(fadeSpec))
                  .togetherWith(slideOutOfContainer(direction, slideSpec) + fadeOut(fadeSpec))
                  .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
            },
            label = "month-grid",
        ) { shownMonth ->
          MonthGrid(
              cells = shownMonth.cells,
              onDayClick = viewModel::onDaySelected,
              onSwipeNext = viewModel::nextMonth,
              onSwipePrev = viewModel::prevMonth,
          )
        }
      }

      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
      )
    }
  }

  sheet?.let { day ->
    DayModalBottomSheet(
        day = day,
        onDismiss = viewModel::onSheetDismissed,
        onWorkoutClick = { id ->
          viewModel.onSheetDismissed()
          onWorkoutClick(id)
        },
        onStartAdHoc = viewModel::startAdHoc,
        onCancelAdHoc = viewModel::cancelAdHoc,
        onMoveAdHoc = { item ->
          movingAdHoc = item
          viewModel.onSheetDismissed()
        },
        onStartRecurring = viewModel::startRecurring,
        onCancelRecurring = { item -> viewModel.cancelRecurring(item.ruleId, item.instanceDate) },
        onMoveRecurring = { item ->
          movingRecurring = item
          viewModel.onSheetDismissed()
        },
        onPlan = {
          planningDate = day.date
          viewModel.onSheetDismissed()
        },
        editingEnabled = calendarStatus.editingEnabled,
    )
  }

  // Шаг 1 планирования: выбор программы.
  planningDate?.let { date ->
    if (pickedRoutineId == null) {
      RoutinePickerSheet(
          routines = routines,
          onPick = { pickedRoutineId = it },
          onDismiss = { planningDate = null },
      )
    }
  }

  // Шаг 2 планирования: выбор времени, затем schedule.
  val date = planningDate
  val routineId = pickedRoutineId
  if (date != null && routineId != null) {
    com.valerochka1337.valerochkagym.ui.common.ScheduleTimePickerDialog(
        onConfirm = { hour, minute ->
          viewModel.schedule(
              routineId,
              date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
          )
          planningDate = null
          pickedRoutineId = null
        },
        onDismiss = {
          planningDate = null
          pickedRoutineId = null
        },
    )
  }

  movingAdHoc?.let { item ->
    com.valerochka1337.valerochkagym.ui.common.ScheduleTimePickerDialog(
        initialHour = Instant.ofEpochMilli(item.startsAtMillis).atZone(ZoneId.systemDefault()).hour,
        initialMinute =
            Instant.ofEpochMilli(item.startsAtMillis).atZone(ZoneId.systemDefault()).minute,
        onConfirm = { hour, minute ->
          val date =
              Instant.ofEpochMilli(item.startsAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
          viewModel.moveAdHoc(
              item.planId,
              date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
          )
          movingAdHoc = null
        },
        onDismiss = { movingAdHoc = null },
    )
  }

  movingRecurring?.let { item ->
    com.valerochka1337.valerochkagym.ui.common.ScheduleTimePickerDialog(
        onConfirm = { hour, minute ->
          viewModel.moveRecurring(
              item.ruleId,
              item.instanceDate,
              item.instanceDate
                  .atTime(hour, minute)
                  .atZone(ZoneId.systemDefault())
                  .toInstant()
                  .toEpochMilli(),
          )
          movingRecurring = null
        },
        onDismiss = { movingRecurring = null },
    )
  }
}

@Composable
internal fun CalendarStatusBanner(
    status: CalendarStatusUi,
    onRetry: () -> Unit,
) {
  when (val migration = status.migration) {
    CalendarMigrationUiState.Ready ->
        when (status.cloud) {
          com.valerochka1337.valerochkagym.data.backend.CalendarCloudState.Pending ->
              Text(
                  text = "Изменения сохранены на устройстве. Синхронизация с сервером ожидается.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
              )
          com.valerochka1337.valerochkagym.data.backend.CalendarCloudState.Unsupported ->
              Text(
                  text =
                      "Изменения сохранены на устройстве. Сервер пока не поддерживает календарь.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
              )
          com.valerochka1337.valerochkagym.data.backend.CalendarCloudState.Available -> Unit
        }
    CalendarMigrationUiState.Preparing ->
        Text(
            text =
                "Подготавливаем календарь. История тренировок доступна, редактирование появится после подготовки.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
    is CalendarMigrationUiState.Error ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              text = migration.message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onRetry) { Text("Повторить") }
        }
  }
}

@Composable
private fun MonthHeader(
    title: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CircleIconButton(
        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
        contentDescription = "Предыдущий месяц",
        onClick = onPrev,
    )
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f),
    )
    CircleIconButton(
        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = "Следующий месяц",
        onClick = onNext,
    )
  }
}

@Composable
private fun WeekdayHeader() {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
    WEEKDAY_LABELS.forEach { label ->
      Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun MonthGrid(
    cells: List<DayCellUi>,
    onDayClick: (LocalDate) -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth().padding(horizontal = 12.dp).pointerInput(Unit) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onHorizontalDrag = { _, delta -> drag += delta },
                onDragEnd = {
                  if (drag <= -SWIPE_THRESHOLD) onSwipeNext()
                  else if (drag >= SWIPE_THRESHOLD) onSwipePrev()
                },
            )
          },
      verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    cells.chunked(7).forEach { week ->
      Row(modifier = Modifier.fillMaxWidth()) {
        week.forEach { cell -> DayCell(cell = cell, onClick = { onDayClick(cell.date) }) }
      }
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DayCell(
    cell: DayCellUi,
    onClick: () -> Unit,
) {
  val spokenDate =
      remember(cell.date) {
        cell.date.format(
            DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale.forLanguageTag("ru")),
        )
      }
  val spokenState =
      buildList {
            if (cell.isToday) add("сегодня")
            when (cell.dot) {
              DotStyle.Completed -> add("тренировка проведена")
              DotStyle.Planned -> add("тренировка запланирована")
              DotStyle.None -> Unit
            }
          }
          .joinToString(", ")
          .ifEmpty { "нет тренировок" }
  val todayBorder =
      if (cell.isToday) {
        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
      } else {
        Modifier
      }
  Column(
      modifier =
          Modifier.weight(1f)
              .aspectRatio(1f)
              .clip(CircleShape)
              .then(todayBorder)
              .semantics(mergeDescendants = true) {
                contentDescription = spokenDate
                stateDescription = spokenState
              }
              .clickable(enabled = cell.inMonth, onClick = onClick),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = cell.date.dayOfMonth.toString(),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
        color =
            when {
              !cell.inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
              cell.isToday -> MaterialTheme.colorScheme.primary
              else -> MaterialTheme.colorScheme.onSurface
            },
    )
    Spacer(Modifier.height(3.dp))
    Dot(cell.dot)
  }
}

/**
 * Точка под числом: залитая (сделано), контурная (запланировано) или невидимая (для выравнивания).
 */
@Composable
private fun Dot(style: DotStyle) {
  val primary = MaterialTheme.colorScheme.primary
  when (style) {
    DotStyle.None -> Spacer(Modifier.size(6.dp))
    DotStyle.Completed ->
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).background(primary),
        )
    DotStyle.Planned ->
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).border(1.5.dp, primary, CircleShape),
        )
  }
}
