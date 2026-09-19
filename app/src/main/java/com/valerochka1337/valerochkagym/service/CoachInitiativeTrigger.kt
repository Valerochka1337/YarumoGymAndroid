package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull

/** Selects business changes from the notification stream; countdown/HR progress is presentation. */
internal class CoachInitiativeTrigger {
  private var previousWorkout: WorkoutFull? = null
  private var previousRest: RestTimerState? = null

  fun changed(workout: WorkoutFull?, rest: RestTimerState?): Boolean {
    val restPlan =
        when (rest) {
          is RestTimerState.Timed -> rest.copy(remainingSec = rest.totalSec)
          is RestTimerState.HeartRate -> rest.copy(belowSinceMillis = null)
          null -> null
        }
    val changed = workout != previousWorkout || restPlan != previousRest
    previousWorkout = workout
    previousRest = restPlan
    return changed && workout != null && workout.workout.finishedAt == null
  }
}
