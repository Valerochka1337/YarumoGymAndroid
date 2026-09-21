package com.valerochka1337.valerochkagym.data.trainingproposal

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.calendar.CalendarTimeResolver
import com.valerochka1337.valerochkagym.data.calendar.ProposalScheduleConflict
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock

/** A personal copy is independent of the proposal's expired approval and server receipt. */
@Singleton
class ProposalCopyRepository
@Inject
constructor(
    private val database: GymDatabase,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val routines: GymRepository,
    private val calendarMigration: CalendarMigrationGate,
    private val uploads: RoutineUploadScheduler,
    private val clock: WallClock,
    @param:ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) {
  fun observeScheduleConflict(draft: ApprovalDraft): Flow<Boolean> =
      combine(
              database.calendarPlanDao().observePlansWithRoutines(),
              database.calendarPlanDao().observeRulesWithRoutines(),
              database.calendarPlanDao().observeExceptions(),
              database.routineDao().observeRoutinesFull(),
          ) { plans, rules, exceptions, routines ->
            ProposalScheduleConflict.hasConflict(draft, plans, rules, exceptions, routines)
          }
          .flowOn(computeDispatcher)

  suspend fun save(
      editor: ProposalEditor,
      operationId: String,
      startsAtMillis: Long? = null,
      timeZoneId: String = editor.draft.timeZoneId,
  ): RoutineEntity {
    require(ProposalWire.uuid(operationId) && ProposalWire.validDraft(editor.draft))
    fun guard() {
      if (
          sessions.snapshot()?.let {
            it.tokens.userId == editor.session.tokens.userId && it.epoch == editor.session.epoch
          } != true ||
              sync.owner() != editor.session.tokens.userId ||
              editor.proposal.recipientId != editor.session.tokens.userId
      )
          throw BackendException(401, "owner_changed", "Аккаунт изменился")
    }
    guard()
    // Migration has its own mutex; finish it before taking the Room transaction.
    if (startsAtMillis != null && !calendarMigration.ensureReady())
        throw BackendException(
            409,
            "proposal_copy_calendar",
            "Подготовка календаря ещё не завершена",
        )
    val saved =
        sync.mutex.withLock {
          database.withTransaction {
            guard()
            val draft = editor.draft
            val exercises =
                database
                    .exerciseDao()
                    .getAllOnce()
                    .filterNot { it.archived }
                    .associateBy { it.syncId }
            val gyms =
                database.gymDao().getGyms().filterNot { it.archived }.associateBy { it.syncId }
            val gymIds = draft.gymIds.map { gyms[it]?.syncId ?: unavailable() }.toSet()
            val rows =
                draft.exercises.mapIndexed { index, item ->
                  val exercise = exercises[item.exerciseId] ?: unavailable()
                  if (item.plannedSets.any { !it.matches(exercise.type) }) unavailable()
                  RoutineExerciseEntity(
                      routineId = 0,
                      exerciseId = exercise.id,
                      position = index,
                      restSeconds = item.restSeconds,
                      plannedSets =
                          item.plannedSets.map {
                            PlannedSet(
                                it.weightKg,
                                it.reps,
                                it.durationSec,
                                it.speedKmh,
                                it.inclinePct,
                            )
                          },
                  )
                }
            val planId =
                startsAtMillis?.let {
                  if (it <= clock.nowMillis())
                      throw BackendException(
                          400,
                          "proposal_copy_date",
                          "Выберите будущее время тренировки",
                      )
                  CalendarTimeResolver.requirePlanInstant(it, ZoneId.of(timeZoneId))
                  UUID.nameUUIDFromBytes("proposal-copy-plan:$operationId".toByteArray()).toString()
                }
            if (
                startsAtMillis != null &&
                    ProposalScheduleConflict.hasConflict(
                        draft.copy(startsAtMillis = startsAtMillis, timeZoneId = timeZoneId),
                        database.calendarPlanDao().plansWithRoutines(),
                        database.calendarPlanDao().rulesWithRoutines(),
                        database.calendarPlanDao().allExceptions(),
                        database.routineDao().routinesFullOnce(),
                        excludedPlanId = planId,
                    )
            )
                throw BackendException(
                    409,
                    "proposal_copy_conflict",
                    "В это время уже запланирована тренировка",
                )
            val result =
                routines.saveRoutineConfiguration(
                    RoutineConfigurationDraft(
                        RoutineEntity(
                            syncId = operationId,
                            name = draft.name,
                            updatedAt = clock.nowMillis(),
                        ),
                        rows,
                        gymIds,
                    )
                )
            val routine =
                when (result) {
                  is SaveRoutineConfigurationResult.Saved -> result.routine
                  is SaveRoutineConfigurationResult.Conflict,
                  SaveRoutineConfigurationResult.GymNotFound -> unavailable()
                  SaveRoutineConfigurationResult.Failure ->
                      throw BackendException(
                          409,
                          "proposal_copy_failed",
                          "Не удалось сохранить тренировку",
                      )
                }
            if (startsAtMillis != null) {
              val nonNullPlanId = requireNotNull(planId)
              val plan = CalendarPlanEntity(nonNullPlanId, routine.id, startsAtMillis, timeZoneId)
              val existing = database.calendarPlanDao().plan(nonNullPlanId)
              if (existing != null && existing != plan)
                  throw BackendException(
                      409,
                      "proposal_copy_calendar",
                      "Этот план уже добавлен в календарь",
                  )
              if (existing == null) database.calendarPlanDao().insertPlan(plan)
            }
            guard()
            routine
          }
        }
    uploads.schedule(saved.syncId)
    return saved
  }

  private fun unavailable(): Nothing =
      throw BackendException(
          409,
          "proposal_copy_unavailable",
          "Упражнения или залы плана больше недоступны",
      )

  private fun ProposalPlannedSet.matches(type: ExerciseType): Boolean =
      when (type) {
        ExerciseType.STRENGTH ->
            reps != null && durationSec == null && speedKmh == null && inclinePct == null
        ExerciseType.TIMED ->
            durationSec != null &&
                weightKg == null &&
                reps == null &&
                speedKmh == null &&
                inclinePct == null
        ExerciseType.CARDIO -> durationSec != null && weightKg == null && reps == null
      }
}
