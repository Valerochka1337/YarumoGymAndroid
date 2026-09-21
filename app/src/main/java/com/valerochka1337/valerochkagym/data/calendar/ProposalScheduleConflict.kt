package com.valerochka1337.valerochkagym.data.calendar

import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionEntity
import com.valerochka1337.valerochkagym.data.db.relation.CalendarPlanWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.CalendarRuleWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.trainingproposal.ApprovalDraft
import com.valerochka1337.valerochkagym.domain.PlannerDuration
import java.time.Instant
import java.time.ZoneId

/** Pure, bounded interval check shared by the preview and the copy transaction. */
object ProposalScheduleConflict {
  fun hasConflict(
      draft: ApprovalDraft,
      plans: List<CalendarPlanWithRoutine>,
      rules: List<CalendarRuleWithRoutine>,
      exceptions: List<CalendarExceptionEntity>,
      routines: List<RoutineWithExercises>,
      excludedPlanId: String? = null,
  ): Boolean {
    if (!com.valerochka1337.valerochkagym.data.trainingproposal.ProposalWire.validDraft(draft))
        return false
    val candidateDuration = duration(draft)
    val candidateStart = draft.startsAtMillis
    val candidateEnd = candidateStart + candidateDuration * 1_000L
    val durations = routines.associate { it.routine.id to duration(it) }
    val lookback = (durations.values.maxOrNull() ?: 0L) * 1_000L
    val from = candidateStart - lookback
    val zone = ZoneId.of(draft.timeZoneId)
    val range =
        LocalDateRange(
            Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(candidateEnd - 1).atZone(zone).toLocalDate(),
        )
    val instances = CalendarInstances.resolveIn(plans, rules, exceptions, range, zone)
    return instances.any { instance ->
      (excludedPlanId == null || instance.planId != excludedPlanId) &&
          (instance.startsAtMillis == candidateStart ||
              durations[instance.routineId]?.let { duration ->
                intervalsOverlap(
                    candidateStart,
                    candidateEnd,
                    instance.startsAtMillis,
                    instance.startsAtMillis + duration * 1_000L,
                )
              } == true)
    }
  }

  fun intervalsOverlap(
      firstStart: Long,
      firstEnd: Long,
      secondStart: Long,
      secondEnd: Long,
  ): Boolean = firstStart < secondEnd && secondStart < firstEnd

  fun duration(draft: ApprovalDraft): Long =
      PlannerDuration.seconds(
          draft.exercises.map {
            PlannerDuration.Exercise(it.plannedSets.map { set -> set.durationSec }, it.restSeconds)
          }
      )

  fun duration(routine: RoutineWithExercises): Long =
      PlannerDuration.seconds(
          routine.exercises.map {
            PlannerDuration.Exercise(
                it.routineExercise.plannedSets.map { set -> set.durationSec },
                it.routineExercise.restSeconds,
            )
          }
      )
}
