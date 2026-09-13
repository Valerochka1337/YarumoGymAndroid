package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.backend.CalendarCloudState
import com.valerochka1337.valerochkagym.data.backend.CalendarCloudStatus
import com.valerochka1337.valerochkagym.data.calendar.*
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanRepository
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanResult
import com.valerochka1337.valerochkagym.data.calendar.ResolvedCalendarInstance
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.CalendarPlanWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.CalendarRuleWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.ui.calendar.CalendarMigrationUiState
import com.valerochka1337.valerochkagym.ui.calendar.CalendarViewModel
import com.valerochka1337.valerochkagym.ui.calendar.DotStyle
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [CalendarViewModel]. All Room/Google boundaries are hand-written fakes backed by
 * [MutableStateFlow], so state flows emit synchronously under an [UnconfinedTestDispatcher] with no
 * Android framework. `today` is the real system date; date-dependent tests are anchored to `today`
 * (planned) or to a fixed far-past month (completed, which is today-independent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("SameParameterValue")
class CalendarViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val zone: ZoneId = ZoneId.systemDefault()

  private fun noon(date: LocalDate): Long =
      date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

  // region grid

  @Test
  fun `grid marks a completed day with a filled dot`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val completedDay = LocalDate.of(2000, 1, 10)
        val vm = viewModel(finished = listOf(finishedWorkout("w1", "Ноги", noon(completedDay))))
        collect(vm)
        vm.showMonth(YearMonth.of(2000, 1))

        val cell = vm.monthUi.value.cells.first { it.date == completedDay }
        assertEquals(DotStyle.Completed, cell.dot)
        assertTrue(cell.inMonth)
      }

  @Test
  fun `grid marks a future ad-hoc day planned and flags today`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val today = LocalDate.now(zone)
        val vm =
            viewModel(
                adHoc = listOf(scheduled(1L, routineId = 5L, "Грудь", noon(today))),
            )
        collect(vm)
        vm.showMonth(YearMonth.from(today))

        val cell = vm.monthUi.value.cells.first { it.date == today }
        assertTrue(cell.isToday)
        assertEquals(DotStyle.Planned, cell.dot)
      }

  // endregion

  // region day sheet

  @Test
  fun `day sheet lists completed workouts of the selected day`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val day = LocalDate.of(2000, 1, 10)
        val vm = viewModel(finished = listOf(finishedWorkout("w1", "Ноги", noon(day))))
        collect(vm)
        vm.onDaySelected(day)

        val sheet = vm.daySheet.value!!
        assertEquals(1, sheet.completed.size)
        assertEquals("Ноги", sheet.completed.single().name)
        assertEquals("w1", sheet.completed.single().id)
      }

  @Test
  fun `day sheet surfaces a recurring rule with the routine name`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val today = LocalDate.now(zone)
        val vm =
            viewModel(
                routines = listOf(routineWithCount(7L, "Спина")),
                weekly =
                    WeeklySchedule(
                        listOf(
                            DayRule(
                                isoDay = today.dayOfWeek.value,
                                routineId = 7L,
                                hour = 8,
                                minute = 30,
                            )
                        )
                    ),
            )
        collect(vm)
        vm.onDaySelected(today)

        val recurring = vm.daySheet.value!!.recurring.single()
        assertEquals("Спина", recurring.routineName)
        assertEquals("08:30", recurring.timeLabel)
        assertTrue(recurring.canStart) // today
      }

  // endregion

  // region month navigation

  @Test
  fun `next and prev shift the displayed month`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val vm = viewModel()
        collect(vm)
        vm.showMonth(YearMonth.of(2026, 8))

        vm.nextMonth()
        assertEquals(YearMonth.of(2026, 9), vm.monthUi.value.yearMonth)
        vm.prevMonth()
        vm.prevMonth()
        assertEquals(YearMonth.of(2026, 7), vm.monthUi.value.yearMonth)
      }

  // endregion

  // region scheduling

  @Test
  fun `pending migration keeps Room history visible and blocks editing`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar = FakeCalendarPlanRepository()
        val today = LocalDate.now(zone)
        val vm =
            viewModel(
                finished = listOf(finishedWorkout("done", "Ноги", noon(today))),
                calendarPlanRepository = calendar,
                migrationGate = MutableGate(false),
            )
        collect(vm)
        runCurrent()

        assertEquals(CalendarMigrationUiState.Preparing, vm.calendarStatus.value.migration)
        assertEquals(DotStyle.Completed, vm.monthUi.value.cells.first { it.date == today }.dot)
        vm.schedule(1L, System.currentTimeMillis() + 60_000)

        assertEquals("Подготовка календаря ещё не завершена", vm.events.first())
        assertEquals(0, calendar.scheduleCalls)
      }

  @Test
  fun `migration failure exposes a recoverable retry state`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val gate = MutableGate(error = IOException("disk"))
        val vm = viewModel(migrationGate = gate)
        collect(vm)
        runCurrent()

        assertEquals(
            CalendarMigrationUiState.Error("Не удалось подготовить календарь"),
            vm.calendarStatus.value.migration,
        )
        gate.ready = true
        gate.error = null
        vm.retryMigration()
        runCurrent()

        assertEquals(CalendarMigrationUiState.Ready, vm.calendarStatus.value.migration)
      }

  @Test
  fun `unsupported cloud status does not turn a local plan success into failure`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar = FakeCalendarPlanRepository()
        val vm =
            viewModel(
                calendarPlanRepository = calendar,
                cloudStatus = FakeCalendarCloudStatus(CalendarCloudState.Unsupported),
            )
        collect(vm)
        runCurrent()

        vm.schedule(1L, System.currentTimeMillis() + 60_000)

        assertEquals(CalendarCloudState.Unsupported, vm.calendarStatus.value.cloud)
        assertEquals(1, calendar.scheduleCalls)
        assertEquals("Запланировано", vm.events.first())
      }

  @Test
  fun `schedule in the past emits the guard message and never calls the repository`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar = FakeCalendarPlanRepository()
        val vm = viewModel(calendarPlanRepository = calendar)
        collect(vm)

        vm.schedule(routineId = 1L, dateTimeMillis = 1_000L)

        assertEquals("Время уже прошло — выберите будущий момент", vm.events.first())
        assertEquals(0, calendar.scheduleCalls)
      }

  @Test
  fun `repeated planning taps share one in flight operation and release busy state`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar = FakeCalendarPlanRepository(suspendCreate = true)
        val vm = viewModel(calendarPlanRepository = calendar)
        collect(vm)
        val at = System.currentTimeMillis() + 60_000
        vm.schedule(1L, at)
        vm.schedule(1L, at)
        assertTrue(vm.isPlanning.value)
        assertEquals(1, calendar.scheduleCalls)
        calendar.releaseCreate.complete(Unit)
        runCurrent()
        assertFalse(vm.isPlanning.value)
        assertEquals(1, calendar.scheduleCalls)
      }

  @Test
  fun `recreated scheduling command reuses its saved UUID until Room accepts it`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar =
            FakeCalendarPlanRepository().apply {
              createResult = CalendarPlanResult.Failure("Повторите")
            }
        val handle = SavedStateHandle()
        val at = System.currentTimeMillis() + 60_000
        val first = viewModel(calendarPlanRepository = calendar, savedStateHandle = handle)
        collect(first)
        first.schedule(1L, at)
        val id = calendar.planCommandIds.single()

        calendar.createResult = CalendarPlanResult.Success
        val recreated = viewModel(calendarPlanRepository = calendar, savedStateHandle = handle)
        collect(recreated)
        recreated.schedule(1L, at)

        assertEquals(listOf(id, id), calendar.planCommandIds)
      }

  @Test
  fun `startAdHoc starts the routine then cancels its event`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val active = FakeActiveWorkoutRepository()
        val calendar = FakeCalendarPlanRepository()
        val vm = viewModel(active = active, calendarPlanRepository = calendar)
        collect(vm)

        vm.startAdHoc(
            com.valerochka1337.valerochkagym.ui.calendar.AdHocUi(
                planId = "3",
                routineId = 5L,
                routineName = "Ноги",
                timeLabel = "18:00",
                startsAtMillis = System.currentTimeMillis(),
                canStart = true,
            ),
        )

        assertEquals(1, active.startFromRoutineCalls)
        assertEquals(listOf("3"), calendar.cancelledIds)
      }

  @Test
  fun `startRecurring starts the routine without cancelling anything`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val active = FakeActiveWorkoutRepository()
        val calendar = FakeCalendarPlanRepository()
        val vm = viewModel(active = active, calendarPlanRepository = calendar)
        collect(vm)

        vm.startRecurring(routineId = 5L)

        assertEquals(1, active.startFromRoutineCalls)
        assertTrue(calendar.cancelledIds.isEmpty())
      }

  @Test
  fun `recurring cancellation and move keep the selected rule and original date`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar = FakeCalendarPlanRepository()
        val vm = viewModel(calendarPlanRepository = calendar)
        collect(vm)
        val date = LocalDate.now(zone).plusDays(7)
        val movedAt = noon(date.plusDays(1))

        vm.cancelRecurring("rule-1", date)
        vm.moveRecurring("rule-1", date, movedAt)

        assertEquals(
            listOf(
                ExceptionCommand(
                    "rule-1",
                    date,
                    com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind.CANCELLED,
                    null,
                ),
                ExceptionCommand(
                    "rule-1",
                    date,
                    com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind.MOVED,
                    movedAt,
                ),
            ),
            calendar.exceptions,
        )
      }

  @Test
  fun `moved recurring commands retain the rule-local date when displayed on another date`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val original = LocalDate.of(2026, 9, 7)
        val movedAt = java.time.Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()
        val calendar =
            FakeCalendarPlanRepository(
                instances =
                    listOf(
                        ResolvedCalendarInstance(
                            id = "exception",
                            planId = null,
                            ruleId = "rule-1",
                            instanceKey = "2026-09-07T08:30[UTC]",
                            routineId = 1,
                            routineName = "Ноги",
                            startsAtMillis = movedAt,
                            moved = true,
                        )
                    )
            )
        val vm = viewModel(calendarPlanRepository = calendar)
        collect(vm)
        vm.onDaySelected(java.time.Instant.ofEpochMilli(movedAt).atZone(zone).toLocalDate())
        val recurring = vm.daySheet.value!!.recurring.single()

        assertEquals(original, recurring.instanceDate)
        vm.cancelRecurring(recurring.ruleId, recurring.instanceDate)
        vm.moveRecurring(recurring.ruleId, recurring.instanceDate, movedAt + 60_000)

        assertEquals(listOf(original, original), calendar.exceptions.map { it.instanceDate })
      }

  @Test
  fun `one-off move delegates its plan id and new instant`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val calendar = FakeCalendarPlanRepository()
        val vm = viewModel(calendarPlanRepository = calendar)
        collect(vm)
        val movedAt = System.currentTimeMillis() + 60_000

        vm.moveAdHoc("plan-1", movedAt)

        assertEquals(listOf("plan-1" to movedAt), calendar.movedPlans)
      }

  @Test
  fun `saveSchedule delegates and surfaces the success message`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val weekly = FakeCalendarPlanRepository()
        val vm = viewModel(calendarPlanRepository = weekly)
        collect(vm)

        val schedule =
            WeeklySchedule(listOf(DayRule(isoDay = 1, routineId = 2L, hour = 18, minute = 0)))
        vm.saveSchedule(schedule)

        assertEquals("Расписание сохранено", vm.events.first())
        assertEquals(schedule, weekly.saved)
      }

  @Test
  fun `clearSchedule surfaces a NeedsConsent message`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val weekly =
            FakeCalendarPlanRepository(
                clearResult = CalendarPlanResult.Failure("Нет доступа к календарю")
            )
        val vm = viewModel(calendarPlanRepository = weekly)
        collect(vm)

        vm.clearSchedule()

        assertEquals("Нет доступа к календарю", vm.events.first())
        assertTrue(weekly.cleared)
      }

  @Test
  fun `weeklySchedule exposes the persisted template`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val schedule =
            WeeklySchedule(listOf(DayRule(isoDay = 4, routineId = 9L, hour = 7, minute = 15)))
        val vm =
            viewModel(calendarPlanRepository = FakeCalendarPlanRepository(initialWeekly = schedule))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          vm.weeklySchedule.collect {}
        }

        assertEquals(schedule, vm.weeklySchedule.value)
      }

  @Test
  fun `rapid save and clear share one busy gate`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val weekly = FakeCalendarPlanRepository(suspendSave = true)
        val vm = viewModel(calendarPlanRepository = weekly)

        vm.saveSchedule(WeeklySchedule(listOf(DayRule(1, 2, 18, 0))))
        runCurrent()
        assertTrue(vm.isScheduleBusy.value)
        vm.clearSchedule()
        runCurrent()
        assertEquals(1, weekly.saveCalls)
        assertEquals(0, weekly.clearCalls)

        weekly.releaseSave.complete(Unit)
        runCurrent()
        assertTrue(!vm.isScheduleBusy.value)
      }

  @Test
  fun `rapid save and save invoke repository once`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val weekly = FakeCalendarPlanRepository(suspendSave = true)
        val vm = viewModel(calendarPlanRepository = weekly)
        val schedule = WeeklySchedule(listOf(DayRule(1, 2, 18, 0)))

        vm.saveSchedule(schedule)
        vm.saveSchedule(schedule)
        runCurrent()

        assertEquals(1, weekly.saveCalls)
        weekly.releaseSave.complete(Unit)
        runCurrent()
        assertFalse(vm.isScheduleBusy.value)
      }

  @Test
  fun `rapid clear and clear invoke repository once`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val weekly = FakeCalendarPlanRepository(suspendClear = true)
        val vm = viewModel(calendarPlanRepository = weekly)

        vm.clearSchedule()
        vm.clearSchedule()
        runCurrent()

        assertEquals(1, weekly.clearCalls)
        weekly.releaseClear.complete(Unit)
        runCurrent()
        assertFalse(vm.isScheduleBusy.value)
      }

  @Test
  fun `schedule busy resets after repository failure and cancellation`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val failureRepo =
            FakeCalendarPlanRepository(
                saveResult = CalendarPlanResult.Failure("Старое расписание сохранено"),
            )
        val failureVm = viewModel(calendarPlanRepository = failureRepo)
        failureVm.saveSchedule(WeeklySchedule(listOf(DayRule(1, 2, 18, 0))))
        assertEquals("Старое расписание сохранено", failureVm.events.first())
        assertFalse(failureVm.isScheduleBusy.value)

        val cancelledRepo =
            FakeCalendarPlanRepository(
                saveThrowable = CancellationException("cancelled"),
            )
        val cancelledVm = viewModel(calendarPlanRepository = cancelledRepo)
        cancelledVm.saveSchedule(WeeklySchedule(listOf(DayRule(1, 2, 18, 0))))
        runCurrent()
        assertFalse(cancelledVm.isScheduleBusy.value)
      }

  // endregion

  // region harness

  private fun viewModel(
      finished: List<WorkoutEntity> = emptyList(),
      adHoc: List<CalendarPlanWithRoutine> = emptyList(),
      routines: List<RoutineWithCount> = emptyList(),
      weekly: WeeklySchedule = WeeklySchedule(),
      calendarPlanRepository: FakeCalendarPlanRepository? = null,
      migrationGate: CalendarMigrationGate = ReadyGate,
      cloudStatus: CalendarCloudStatus = FakeCalendarCloudStatus(CalendarCloudState.Available),
      active: FakeActiveWorkoutRepository = FakeActiveWorkoutRepository(),
      savedStateHandle: SavedStateHandle = SavedStateHandle(),
  ): CalendarViewModel =
      CalendarViewModel(
          workoutDao = FakeWorkoutDao(finished),
          routineDao = FakeRoutineDao(routines),
          calendarPlanRepository =
              calendarPlanRepository
                  ?: FakeCalendarPlanRepository(plans = adHoc, initialWeekly = weekly),
          migrationGate = migrationGate,
          calendarCloudStatus = cloudStatus,
          activeWorkoutRepository = active,
          savedStateHandle = savedStateHandle,
      )

  /** Keep every `WhileSubscribed` state flow hot so `.value` reflects the latest emission. */
  private fun TestScope.collect(vm: CalendarViewModel) {
    val d = UnconfinedTestDispatcher(testScheduler)
    backgroundScope.launch(d) { vm.monthUi.collect {} }
    backgroundScope.launch(d) { vm.daySheet.collect {} }
    backgroundScope.launch(d) { vm.routines.collect {} }
    backgroundScope.launch(d) { vm.calendarStatus.collect {} }
  }

  private object ReadyGate : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean = true
  }

  private class MutableGate(
      var ready: Boolean = false,
      var error: Throwable? = null,
  ) : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean {
      error?.let { throw it }
      return ready
    }
  }

  private class FakeCalendarCloudStatus(initial: CalendarCloudState) : CalendarCloudStatus {
    override val calendarCloudState = MutableStateFlow(initial)
  }

  private fun finishedWorkout(id: String, name: String, startedAt: Long) =
      WorkoutEntity(id = id, name = name, startedAt = startedAt, finishedAt = startedAt + 3_600_000)

  private fun scheduled(id: Long, routineId: Long, name: String, millis: Long) =
      CalendarPlanWithRoutine(
          plan = CalendarPlanEntity("$id", routineId, millis, zone.id),
          routineName = name,
      )

  private fun routineWithCount(id: Long, name: String) =
      RoutineWithCount(routine = RoutineEntity(id = id, name = name), exerciseCount = 0)

  private class FakeWorkoutDao(private val finished: List<WorkoutEntity>) : WorkoutDao {
    override suspend fun latestComparableCompletedWeight(
        exerciseId: Long,
        excludeWorkoutId: String,
    ): Double? = null

    override suspend fun coachCompletedSetsForExercise(exerciseId: Long) =
        emptyList<com.valerochka1337.valerochkagym.data.db.relation.CoachCompletedSetRow>()

    override fun observeFinishedExerciseHistory() =
        flowOf(
            emptyList<com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow>()
        )

    override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = MutableStateFlow(finished)

    override suspend fun insertWorkout(workout: WorkoutEntity) = Unit

    override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0

    override suspend fun insertSet(set: WorkoutSetEntity): Long = 0

    override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()

    override suspend fun updateSet(set: WorkoutSetEntity) = Unit

    override suspend fun updateActiveSetNote(workoutId: String, setId: Long, note: String) = 0

    override suspend fun updateActiveWorkoutNote(workoutId: String, note: String) = 0

    override suspend fun updateCompletedStrengthNumbers(
        setId: Long,
        weightKg: Double?,
        reps: Int?,
    ) = 0

    override suspend fun updateCompletedTimedNumbers(setId: Long, durationSec: Int?) = 0

    override suspend fun updateCompletedCardioNumbers(
        setId: Long,
        durationSec: Int?,
        speedKmh: Double?,
        inclinePct: Double?,
    ) = 0

    override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit

    override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = null

    override suspend fun getSetsForWorkoutExercise(
        workoutExerciseId: Long
    ): List<WorkoutSetEntity> = emptyList()

    override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> =
        emptyList()

    override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit

    override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)

    override suspend fun getActiveWorkoutId(): String? = null

    override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())

    override suspend fun getWorkoutFull(id: String): WorkoutFull? = null

    override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> =
        emptyList()

    override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? =
        null

    override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) =
        Unit

    override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)

    override suspend fun getFinishedNotUploaded(): List<String> = emptyList()

    override suspend fun getExistingWorkoutIds(): List<String> = emptyList()

    override suspend fun deleteWorkout(id: String) = Unit

    override suspend fun deleteSet(id: Long) = Unit

    override suspend fun deleteWorkoutExercise(id: Long) = Unit
  }

  private class FakeRoutineDao(private val list: List<RoutineWithCount>) : RoutineDao {
    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = MutableStateFlow(list)

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(emptyList())

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? = null

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? = null

    override suspend fun getRoutineName(id: Long): String? =
        list.find { it.routine.id == id }?.routine?.name

    override suspend fun upsertRoutine(routine: RoutineEntity): Long = 0

    override suspend fun deleteRoutine(id: Long) = Unit

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }

  private class FakeCalendarPlanRepository(
      private val plans: List<CalendarPlanWithRoutine> = emptyList(),
      private val instances: List<ResolvedCalendarInstance> = emptyList(),
      initialWeekly: WeeklySchedule = WeeklySchedule(),
      private val saveResult: CalendarPlanResult = CalendarPlanResult.Success,
      private val clearResult: CalendarPlanResult = CalendarPlanResult.Success,
      private val suspendCreate: Boolean = false,
      private val suspendSave: Boolean = false,
      private val suspendClear: Boolean = false,
      private val saveThrowable: Throwable? = null,
  ) : CalendarPlanRepository {
    var scheduleCalls = 0
      private set

    var createResult: CalendarPlanResult = CalendarPlanResult.Success
    val planCommandIds = mutableListOf<String>()

    val cancelledIds = mutableListOf<String>()
    val movedPlans = mutableListOf<Pair<String, Long>>()
    val exceptions = mutableListOf<ExceptionCommand>()
    val weeklySchedule = MutableStateFlow(initialWeekly)
    var saved: WeeklySchedule? = null
      private set

    var cleared = false
      private set

    var saveCalls = 0
      private set

    var clearCalls = 0
      private set

    val releaseCreate = CompletableDeferred<Unit>()
    val releaseSave = CompletableDeferred<Unit>()
    val releaseClear = CompletableDeferred<Unit>()

    override fun observeInstancesIn(
        range: LocalDateRange,
        displayZoneSnapshot: ZoneId,
    ): Flow<CalendarPlanReadState> =
        if (instances.isNotEmpty()) flowOf(CalendarPlanReadState.Ready(instances))
        else
            weeklySchedule.map { weekly ->
              CalendarPlanReadState.Ready(
                  CalendarInstances.resolveIn(
                      plans,
                      weekly.rules.map { rule ->
                        CalendarRuleWithRoutine(
                            CalendarRuleEntity(
                                "rule-${rule.isoDay}",
                                rule.routineId,
                                rule.isoDay,
                                "%02d:%02d".format(rule.hour, rule.minute),
                                displayZoneSnapshot.id,
                                LocalDate.now(displayZoneSnapshot).toString(),
                            ),
                            "Программа",
                        )
                      },
                      emptyList(),
                      range,
                      displayZoneSnapshot,
                  )
              )
            }

    override fun observePlans(): Flow<List<CalendarPlanWithRoutine>> = MutableStateFlow(plans)

    override fun observeRules(): Flow<List<CalendarRuleWithRoutine>> = flowOf(emptyList())

    override fun observeWeeklySchedule(): Flow<WeeklySchedule> = weeklySchedule

    override suspend fun createPlan(
        commandId: String,
        routineId: Long,
        startsAtMillis: Long,
        zone: ZoneId,
    ): CalendarPlanResult {
      scheduleCalls++
      planCommandIds += commandId
      if (suspendCreate) releaseCreate.await()
      return createResult
    }

    override suspend fun cancelPlan(planId: String): CalendarPlanResult {
      cancelledIds += planId
      return CalendarPlanResult.Success
    }

    override suspend fun movePlan(
        planId: String,
        startsAtMillis: Long,
        zone: ZoneId,
    ): CalendarPlanResult {
      movedPlans += planId to startsAtMillis
      return CalendarPlanResult.Success
    }

    override suspend fun replaceWeeklySchedule(
        schedule: WeeklySchedule,
        zone: ZoneId,
    ): CalendarPlanResult {
      saveCalls++
      saved = schedule
      if (suspendSave) releaseSave.await()
      saveThrowable?.let { throw it }
      if (saveResult is CalendarPlanResult.Success) weeklySchedule.value = schedule
      return saveResult
    }

    override suspend fun clearWeeklySchedule(): CalendarPlanResult {
      clearCalls++
      cleared = true
      if (suspendClear) releaseClear.await()
      if (clearResult is CalendarPlanResult.Success) weeklySchedule.value = WeeklySchedule()
      return clearResult
    }

    override suspend fun setException(
        ruleId: String,
        instanceDate: LocalDate,
        kind: com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind,
        movedAtMillis: Long?,
    ): CalendarPlanResult {
      exceptions += ExceptionCommand(ruleId, instanceDate, kind, movedAtMillis)
      return CalendarPlanResult.Success
    }
  }

  private data class ExceptionCommand(
      val ruleId: String,
      val instanceDate: LocalDate,
      val kind: com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind,
      val movedAtMillis: Long?,
  )

  private class FakeActiveWorkoutRepository : ActiveWorkoutRepository {
    var startFromRoutineCalls = 0
      private set

    override suspend fun startFromRoutine(routineId: Long): String {
      startFromRoutineCalls++
      return "workout"
    }

    override suspend fun startEmpty(): String = "workout"

    override fun observeActive(): Flow<WorkoutFull?> = flowOf(null)

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = null

    override suspend fun updateSet(set: WorkoutSetEntity) = Unit

    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) = Unit

    override suspend fun addSet(workoutExerciseId: Long) = Unit

    override suspend fun deleteSet(setId: Long) = Unit

    override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = 0

    override suspend fun deleteExercise(workoutExerciseId: Long) = Unit

    override suspend fun reorderExercises(
        workoutId: String,
        orderedWorkoutExerciseIds: List<Long>,
    ) = Unit

    override suspend fun finish(workoutId: String) = Unit

    override suspend fun discard(workoutId: String) = Unit
  }

  // endregion
}
