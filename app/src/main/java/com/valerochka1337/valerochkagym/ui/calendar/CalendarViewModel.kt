package com.valerochka1337.valerochkagym.ui.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backend.CalendarCloudState
import com.valerochka1337.valerochkagym.data.backend.CalendarCloudStatus
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanReadState
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanRepository
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanResult
import com.valerochka1337.valerochkagym.data.calendar.LocalDateRange
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Стиль точки в ячейке дня: без точки, залитая (тренировка была) или контурная (запланировано). */
enum class DotStyle {
  None,
  Completed,
  Planned,
}

/** Одна ячейка сетки месяца. [inMonth] == false — день соседнего месяца (заглушка). */
data class DayCellUi(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val dot: DotStyle,
)

/** Состояние отображаемого месяца: заголовок «Август 2026» и 42 ячейки (Пн-первый). */
data class MonthUi(
    val yearMonth: YearMonth,
    val title: String,
    val cells: List<DayCellUi>,
    val planMessage: String? = null,
)

/** Завершённая тренировка в шторке дня; тап открывает детали по [id]. */
data class CompletedWorkoutUi(val id: String, val name: String, val timeLabel: String)

/** Ad-hoc запланированная тренировка в шторке; [canStart] == true, когда время уже наступило. */
data class AdHocUi(
    val planId: String,
    val routineId: Long,
    val routineName: String,
    val timeLabel: String,
    val startsAtMillis: Long,
    val canStart: Boolean,
)

/** Правило недельного расписания на выбранный день; [canStart] == true только сегодня. */
data class RecurringUi(
    val ruleId: String,
    val instanceDate: LocalDate,
    val routineId: Long,
    val routineName: String,
    val timeLabel: String,
    val canStart: Boolean,
)

/** Содержимое нижней шторки выбранного дня — агрегат всех секций (может быть несколько сразу). */
data class DaySheetUi(
    val date: LocalDate,
    val title: String,
    val completed: List<CompletedWorkoutUi>,
    val adHoc: List<AdHocUi>,
    val recurring: List<RecurringUi>,
    val planMessage: String? = null,
    val allowPlan: Boolean,
)

/** Программа для пикера при планировании ad-hoc и в редакторе расписания. */
data class RoutinePickUi(val id: Long, val name: String)

sealed interface CalendarMigrationUiState {
  data object Preparing : CalendarMigrationUiState

  data object Ready : CalendarMigrationUiState

  data class Error(val message: String) : CalendarMigrationUiState
}

data class CalendarStatusUi(
    val migration: CalendarMigrationUiState,
    val cloud: CalendarCloudState,
) {
  val editingEnabled: Boolean
    get() = migration == CalendarMigrationUiState.Ready
}

/** Снимок реактивных данных календаря на один тик — из него строятся сетка и шторка. */
private data class CalendarData(
    val finished: List<WorkoutEntity>,
    val plans: CalendarPlanReadState,
    val routineNames: Map<Long, String>,
    val nowMillis: Long,
)

/**
 * Бэкенд вкладки «Календарь»: месячная сетка (что сделано + что запланировано), нижняя шторка дня и
 * планирование — через Room-authoritative [CalendarPlanRepository]. Завершённые дни группируются в
 * памяти из [WorkoutDao.observeFinishedWorkouts] (как это делала «История»); планы и правила — из
 * Room.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel
@Inject
constructor(
    workoutDao: WorkoutDao,
    routineDao: RoutineDao,
    private val calendarPlanRepository: CalendarPlanRepository,
    private val migrationGate: CalendarMigrationGate,
    calendarCloudStatus: CalendarCloudStatus,
    private val activeWorkoutRepository: ActiveWorkoutRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val zone: ZoneId = ZoneId.systemDefault()

  private val displayedMonth = MutableStateFlow(YearMonth.now(zone))
  private val selectedDate = MutableStateFlow<LocalDate?>(null)

  private val _isPlanning = MutableStateFlow(false)
  val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()

  private var startInFlight = false
  private var scheduleActionInFlight = false
  private val _isScheduleBusy = MutableStateFlow(false)
  val isScheduleBusy: StateFlow<Boolean> = _isScheduleBusy.asStateFlow()

  private val _startEvents = Channel<Unit>(Channel.BUFFERED)

  /** «Тренировка создана» — экран навигирует на активную тренировку. */
  val startEvents = _startEvents.receiveAsFlow()

  private val _events = Channel<String>(Channel.BUFFERED)

  /** Текст результата планирования/расписания — экран показывает в снекбаре. */
  val events = _events.receiveAsFlow()

  private val migrationState =
      MutableStateFlow<CalendarMigrationUiState>(CalendarMigrationUiState.Preparing)

  /** Migration controls editing only; Room history remains observable while it is preparing. */
  val calendarStatus: StateFlow<CalendarStatusUi> =
      combine(migrationState, calendarCloudStatus.calendarCloudState) { migration, cloud ->
            CalendarStatusUi(migration, cloud)
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              CalendarStatusUi(CalendarMigrationUiState.Preparing, CalendarCloudState.Pending),
          )

  private val readRetry = MutableStateFlow(0)

  init {
    retryMigration()
  }

  fun retryMigration() {
    readRetry.value += 1
    viewModelScope.launch {
      migrationState.value = CalendarMigrationUiState.Preparing
      try {
        migrationState.value =
            if (migrationGate.ensureReady()) CalendarMigrationUiState.Ready
            else CalendarMigrationUiState.Preparing
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        migrationState.value = CalendarMigrationUiState.Error("Не удалось подготовить календарь")
      }
    }
  }

  private val monthPlans =
      combine(displayedMonth, readRetry) { month, _ -> month }
          .flatMapLatest { month ->
            calendarPlanRepository.observeInstancesIn(
                LocalDateRange(month.atDay(1), month.atEndOfMonth()),
                zone,
            )
          }
  private val selectedPlans =
      combine(selectedDate, readRetry) { date, _ -> date }
          .flatMapLatest { date ->
            if (date == null) flowOf(CalendarPlanReadState.Ready(emptyList()))
            else calendarPlanRepository.observeInstancesIn(LocalDateRange(date, date), zone)
          }
  private val calendarData: StateFlow<CalendarData> =
      combine(
              workoutDao.observeFinishedWorkouts(),
              monthPlans,
              routineDao.observeRoutinesWithCount().map { list ->
                list.associate { it.routine.id to it.routine.name }
              },
              minuteTick(),
          ) { finished, plans, names, now ->
            CalendarData(finished, plans, names, now)
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              CalendarData(emptyList(), CalendarPlanReadState.Migrating, emptyMap(), 0L),
          )

  /** Сетка отображаемого месяца. */
  val monthUi: StateFlow<MonthUi> =
      combine(displayedMonth, calendarData) { month, data -> buildMonth(month, data) }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              buildMonth(displayedMonth.value, calendarData.value),
          )

  /** Содержимое шторки выбранного дня; null — шторка закрыта. */
  val daySheet: StateFlow<DaySheetUi?> =
      combine(selectedDate, calendarData, selectedPlans) { date, data, plans ->
            date?.let { buildSheet(it, data, plans) }
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** Список программ для пикеров (планирование + редактор расписания). */
  val routines: StateFlow<List<RoutinePickUi>> =
      routineDao
          .observeRoutinesWithCount()
          .map { list -> list.map { RoutinePickUi(it.routine.id, it.routine.name) } }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /** Текущий сохранённый шаблон недельного расписания — начальное состояние редактора. */
  val weeklySchedule: StateFlow<WeeklySchedule> =
      calendarPlanRepository
          .observeWeeklySchedule()
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklySchedule())

  fun nextMonth() {
    displayedMonth.value = displayedMonth.value.plusMonths(1)
  }

  fun prevMonth() {
    displayedMonth.value = displayedMonth.value.minusMonths(1)
  }

  fun showMonth(yearMonth: YearMonth) {
    displayedMonth.value = yearMonth
  }

  fun onDaySelected(date: LocalDate) {
    selectedDate.value = date
  }

  fun onSheetDismissed() {
    selectedDate.value = null
  }

  /** Планирует ad-hoc тренировку; прошедшее время отклоняется без обращения к календарю. */
  fun schedule(routineId: Long, dateTimeMillis: Long) {
    if (_isPlanning.value) return
    _isPlanning.value = true
    viewModelScope.launch {
      try {
        if (migrationState.value != CalendarMigrationUiState.Ready) {
          _events.send(PREPARING_CALENDAR_MESSAGE)
          return@launch
        }
        if (dateTimeMillis < System.currentTimeMillis()) {
          _events.send(PAST_TIME_MESSAGE)
          return@launch
        }
        val command = pendingPlanCommand(routineId, dateTimeMillis)
        val result = calendarPlanRepository.createPlan(command.id, routineId, dateTimeMillis, zone)
        if (result is CalendarPlanResult.Success) clearPendingPlanCommand()
        _events.send(calendarPlanResultMessage(result, "Запланировано"))
      } finally {
        _isPlanning.value = false
      }
    }
  }

  /** Отмена ad-hoc тренировки: успех тихий (сетка сама обновится), ошибку — в снекбар. */
  fun cancelAdHoc(planId: String) {
    viewModelScope.launch {
      if (migrationState.value != CalendarMigrationUiState.Ready) {
        _events.send(PREPARING_CALENDAR_MESSAGE)
        return@launch
      }
      (calendarPlanRepository.cancelPlan(planId) as? CalendarPlanResult.Failure)?.let {
        _events.send(it.message)
      }
    }
  }

  /** Changes only this one-off plan; a completed workout remains independent of the plan. */
  fun moveAdHoc(planId: String, startsAtMillis: Long) {
    viewModelScope.launch {
      if (migrationState.value != CalendarMigrationUiState.Ready) {
        _events.send(PREPARING_CALENDAR_MESSAGE)
        return@launch
      }
      _events.send(
          calendarPlanResultMessage(
              calendarPlanRepository.movePlan(planId, startsAtMillis, zone),
              "Тренировка перенесена",
          )
      )
    }
  }

  /** Cancels exactly the selected recurring instance and never changes its weekly rule. */
  fun cancelRecurring(ruleId: String, instanceDate: LocalDate) =
      setRecurringException(
          ruleId,
          instanceDate,
          com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind.CANCELLED,
          null,
          "Тренировка отменена",
      )

  /** Moves exactly the selected recurring instance while retaining its original instance key. */
  fun moveRecurring(ruleId: String, instanceDate: LocalDate, movedAtMillis: Long) =
      setRecurringException(
          ruleId,
          instanceDate,
          com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind.MOVED,
          movedAtMillis,
          "Тренировка перенесена",
      )

  private fun setRecurringException(
      ruleId: String,
      instanceDate: LocalDate,
      kind: com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind,
      movedAtMillis: Long?,
      success: String,
  ) {
    viewModelScope.launch {
      if (migrationState.value != CalendarMigrationUiState.Ready) {
        _events.send(PREPARING_CALENDAR_MESSAGE)
        return@launch
      }
      _events.send(
          calendarPlanResultMessage(
              calendarPlanRepository.setException(ruleId, instanceDate, kind, movedAtMillis),
              success,
          )
      )
    }
  }

  /** Старт наступившей ad-hoc тренировки: создаёт активную и удаляет запись+событие. */
  fun startAdHoc(item: AdHocUi) = launchStart {
    try {
      activeWorkoutRepository.startFromRoutine(item.routineId)
      _startEvents.send(Unit)
      calendarPlanRepository.cancelPlan(item.planId)
    } catch (conflict: RoutineGymConflictException) {
      _events.send(
          "Нельзя начать: недоступно во всех залах — ${conflict.exerciseNames.joinToString()}"
      )
    }
  }

  /** Старт по правилу расписания: только создаёт активную тренировку (серию НЕ трогаем). */
  fun startRecurring(routineId: Long) = launchStart {
    try {
      activeWorkoutRepository.startFromRoutine(routineId)
      _startEvents.send(Unit)
    } catch (conflict: RoutineGymConflictException) {
      _events.send(
          "Нельзя начать: недоступно во всех залах — ${conflict.exerciseNames.joinToString()}"
      )
    }
  }

  /** Сохраняет недельное расписание (замена серии). */
  fun saveSchedule(schedule: WeeklySchedule) {
    launchScheduleAction {
      if (migrationState.value != CalendarMigrationUiState.Ready) {
        _events.send(PREPARING_CALENDAR_MESSAGE)
      } else {
        _events.send(
            calendarPlanResultMessage(
                calendarPlanRepository.replaceWeeklySchedule(schedule, zone),
                success = "Расписание сохранено",
            )
        )
      }
    }
  }

  /** Очищает недельное расписание (удаляет серию). */
  fun clearSchedule() {
    launchScheduleAction {
      if (migrationState.value != CalendarMigrationUiState.Ready) {
        _events.send(PREPARING_CALENDAR_MESSAGE)
      } else {
        _events.send(
            calendarPlanResultMessage(
                calendarPlanRepository.clearWeeklySchedule(),
                success = "Расписание очищено",
            )
        )
      }
    }
  }

  private inline fun launchScheduleAction(crossinline block: suspend () -> Unit) {
    if (scheduleActionInFlight) return
    scheduleActionInFlight = true
    _isScheduleBusy.value = true
    viewModelScope.launch {
      try {
        block()
      } finally {
        scheduleActionInFlight = false
        _isScheduleBusy.value = false
      }
    }
  }

  private fun calendarPlanResultMessage(result: CalendarPlanResult, success: String): String =
      when (result) {
        CalendarPlanResult.Success -> success
        is CalendarPlanResult.Failure -> result.message
      }

  private fun pendingPlanCommand(routineId: Long, startsAtMillis: Long): PendingPlanCommand {
    val previousId = savedStateHandle.get<String>(PENDING_PLAN_ID)
    if (
        previousId != null &&
            savedStateHandle.get<Long>(PENDING_PLAN_ROUTINE) == routineId &&
            savedStateHandle.get<Long>(PENDING_PLAN_START) == startsAtMillis &&
            savedStateHandle.get<String>(PENDING_PLAN_ZONE) == zone.id
    )
        return PendingPlanCommand(previousId, routineId, startsAtMillis, zone.id)
    return PendingPlanCommand(UUID.randomUUID().toString(), routineId, startsAtMillis, zone.id)
        .also {
          savedStateHandle[PENDING_PLAN_ID] = it.id
          savedStateHandle[PENDING_PLAN_ROUTINE] = it.routineId
          savedStateHandle[PENDING_PLAN_START] = it.startsAtMillis
          savedStateHandle[PENDING_PLAN_ZONE] = it.zoneId
        }
  }

  private fun clearPendingPlanCommand() {
    savedStateHandle.remove<String>(PENDING_PLAN_ID)
    savedStateHandle.remove<Long>(PENDING_PLAN_ROUTINE)
    savedStateHandle.remove<Long>(PENDING_PLAN_START)
    savedStateHandle.remove<String>(PENDING_PLAN_ZONE)
  }

  private inline fun launchStart(crossinline block: suspend () -> Unit) {
    if (startInFlight) return
    startInFlight = true
    viewModelScope.launch {
      try {
        block()
      } finally {
        startInFlight = false
      }
    }
  }

  private fun buildMonth(month: YearMonth, data: CalendarData): MonthUi {
    val today = LocalDate.now(zone)
    val completedDays = data.finished.mapTo(mutableSetOf()) { it.startedAt.toLocalDate() }
    val plannedDays =
        (data.plans as? CalendarPlanReadState.Ready)?.instances.orEmpty().mapTo(mutableSetOf()) {
          it.startsAtMillis.toLocalDate()
        }
    return MonthUi(
        yearMonth = month,
        title = monthTitle(month),
        cells = buildMonthCells(month, today, completedDays, plannedDays, emptySet()),
        planMessage = (data.plans as? CalendarPlanReadState.Error)?.message,
    )
  }

  private fun buildSheet(
      date: LocalDate,
      data: CalendarData,
      plans: CalendarPlanReadState,
  ): DaySheetUi {
    val today = LocalDate.now(zone)
    val completed =
        data.finished
            .filter { it.startedAt.toLocalDate() == date }
            .map { CompletedWorkoutUi(it.id, it.name, timeLabel(it.startedAt, zone)) }
    val instances = (plans as? CalendarPlanReadState.Ready)?.instances.orEmpty()
    val adHoc =
        instances
            .filter { it.planId != null }
            .map {
              AdHocUi(
                  requireNotNull(it.planId),
                  it.routineId,
                  it.routineName,
                  timeLabel(it.startsAtMillis, zone),
                  it.startsAtMillis,
                  it.startsAtMillis <= data.nowMillis,
              )
            }
    val recurring =
        instances
            .filter { it.ruleId != null }
            .map {
              RecurringUi(
                  requireNotNull(it.ruleId),
                  LocalDate.parse(requireNotNull(it.instanceKey).substringBefore('T')),
                  it.routineId,
                  data.routineNames[it.routineId] ?: it.routineName,
                  timeLabel(it.startsAtMillis, zone),
                  date == today,
              )
            }
    return DaySheetUi(
        date = date,
        title = dayTitle(date),
        completed = completed,
        adHoc = adHoc,
        recurring = recurring,
        planMessage = (plans as? CalendarPlanReadState.Error)?.message,
        allowPlan = !date.isBefore(today),
    )
  }

  private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

  private fun minuteTick() = flow {
    while (true) {
      emit(System.currentTimeMillis())
      delay(60_000.milliseconds)
    }
  }

  private companion object {
    const val PAST_TIME_MESSAGE = "Время уже прошло — выберите будущий момент"
    const val PENDING_PLAN_ID = "calendar_pending_plan_id"
    const val PENDING_PLAN_ROUTINE = "calendar_pending_plan_routine"
    const val PENDING_PLAN_START = "calendar_pending_plan_start"
    const val PENDING_PLAN_ZONE = "calendar_pending_plan_zone"
    const val PREPARING_CALENDAR_MESSAGE = "Подготовка календаря ещё не завершена"
  }
}

private data class PendingPlanCommand(
    val id: String,
    val routineId: Long,
    val startsAtMillis: Long,
    val zoneId: String,
)
