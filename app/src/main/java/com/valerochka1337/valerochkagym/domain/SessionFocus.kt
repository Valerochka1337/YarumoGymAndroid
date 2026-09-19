package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull

/**
 * Один подход «в фокусе» вместе с контекстом, нужным для подписи снаружи экрана тренировки:
 * уведомление показывает «Жим лёжа · подход 3 из 4», плашка в приложении — то же самое.
 *
 * [setNumber] считается от единицы внутри своего упражнения, а не по всей тренировке: именно так
 * подходы пронумерованы на экране.
 */
data class SessionFocus(
    val exerciseName: String,
    val type: ExerciseType,
    val set: WorkoutSetEntity,
    val setNumber: Int,
    val setsInExercise: Int,
)

/**
 * Подход, который пользователь делает прямо сейчас: первый невыполненный подход первого упражнения,
 * где такой есть. null — когда вся тренировка закрыта (или пуста).
 *
 * Порядок берётся как есть: [com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository]
 * отдаёт [WorkoutFull] уже отсортированным по position/setIndex.
 */
fun WorkoutFull.currentFocus(): SessionFocus? {
  for (exercise in exercises) {
    val index = exercise.sets.indexOfFirst { !it.isCompleted }
    if (index >= 0) {
      return SessionFocus(
          exerciseName = exercise.exercise.name,
          type = exercise.exercise.type,
          set = exercise.sets[index],
          setNumber = index + 1,
          setsInExercise = exercise.sets.size,
      )
    }
  }
  return null
}

/**
 * Последний закрытый подход по [WorkoutSetEntity.completedAt] — его правит быстрая правка из
 * уведомления во время отдыха («сделал 8, а не 10»).
 *
 * Сравнение нестрогое (`>=`), поэтому при равных отметках времени — а старые подходы могут не иметь
 * [WorkoutSetEntity.completedAt] вовсе — побеждает более поздний в доменном порядке.
 */
fun WorkoutFull.lastCompletedFocus(): SessionFocus? {
  var best: SessionFocus? = null
  var bestCompletedAt = Long.MIN_VALUE
  for (exercise in exercises) {
    for ((index, set) in exercise.sets.withIndex()) {
      if (!set.isCompleted) continue
      val completedAt = set.completedAt ?: Long.MIN_VALUE
      if (best != null && completedAt < bestCompletedAt) continue
      best =
          SessionFocus(
              exerciseName = exercise.exercise.name,
              type = exercise.exercise.type,
              set = set,
              setNumber = index + 1,
              setsInExercise = exercise.sets.size,
          )
      bestCompletedAt = completedAt
    }
  }
  return best
}

/** Сколько подходов тренировки уже закрыто — для сводки «7/12». */
fun WorkoutFull.completedSetCount(): Int =
    exercises.sumOf { exercise -> exercise.sets.count { it.isCompleted } }

/** Сколько всего подходов в тренировке. */
fun WorkoutFull.totalSetCount(): Int = exercises.sumOf { it.sets.size }

/** Дополнительный подход не возвращает к упражнению после начала следующего. */
fun WorkoutFull.canAddSet(workoutExerciseId: Long): Boolean {
  val index = exercises.indexOfFirst { it.workoutExercise.id == workoutExerciseId }
  if (index < 0 || workout.finishedAt != null) return false
  val focusIndex = exercises.indexOfFirst { exercise -> exercise.sets.any { !it.isCompleted } }
  if (index == focusIndex) return true
  val previousIndex = if (focusIndex < 0) exercises.lastIndex else focusIndex - 1
  return index == previousIndex &&
      exercises[index].sets.isNotEmpty() &&
      exercises[index].sets.all { it.isCompleted } &&
      exercises.drop(index + 1).all { exercise -> exercise.sets.none { it.isCompleted } }
}
