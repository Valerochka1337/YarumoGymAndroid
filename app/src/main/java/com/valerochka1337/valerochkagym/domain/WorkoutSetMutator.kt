package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Отложенная правка одного подхода: применяется к его текущему (свежему из БД) состоянию. */
private sealed interface WorkoutSetMutationRequest

private data class SetMutation(
    val setId: Long,
    val transform: (WorkoutSetEntity) -> WorkoutSetEntity,
) : WorkoutSetMutationRequest

private data class CompletedSetNumberEdit(
    val setId: Long,
    val type: ExerciseType,
    val values: CompletedSetNumbers,
    val effort: SetEffort?,
    val reply: CompletableDeferred<CompletedSetEditResult>,
) : WorkoutSetMutationRequest

/** Type-scoped numeric values supplied by the completed-set editor. */
data class CompletedSetNumbers(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
)

/**
 * Единственный писатель значений подхода в процессе. Все правки сериализуются через канал и
 * применяются одним потребителем: он читает текущее значение из БД и пишет результат
 * [SetMutation.transform]. Так быстрые тапы по степперам и клавиатурный ввод не затирают друг друга
 * (lost update).
 *
 * Почему `@Singleton` на [ApplicationScope], а не поле ViewModel: править подход умеют два места —
 * экран активной тренировки и кнопки уведомления в шторке (см.
 * [com.valerochka1337.valerochkagym.service.WorkoutSessionService]). Два независимых канала свели
 * бы защиту на нет, поэтому потребитель ровно один и живёт столько же, сколько процесс.
 */
@Singleton
class WorkoutSetMutator
@Inject
constructor(
    private val repository: ActiveWorkoutRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

  private val mutations = Channel<WorkoutSetMutationRequest>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (mutation in mutations) {
        when (mutation) {
          is SetMutation -> {
            repository.mutateSet(mutation.setId, mutation.transform)
          }

          is CompletedSetNumberEdit -> {
            val result =
                try {
                  val current = repository.getSet(mutation.setId)
                  if (current == null) {
                    CompletedSetEditResult.MissingOrInactive
                  } else {
                    repository.updateCompletedSetNumbers(
                        current
                            .withCompletedNumbers(mutation.type, mutation.values)
                            .withCompletedEffort(mutation.type, mutation.effort),
                        mutation.type,
                    )
                  }
                } catch (_: Exception) {
                  CompletedSetEditResult.MissingOrInactive
                }
            mutation.reply.complete(result)
          }
        }
      }
    }
  }

  // --- Шаговые изменения значений подхода (кнопки ± на карточке подхода и в уведомлении). ---

  /** Вес: обычный тап ±2.5, долгое нажатие ±0.5. Не уходит ниже нуля. */
  fun stepWeight(setId: Long, delta: Double) =
      enqueue(setId) {
        it.copy(weightKg = ((it.weightKg ?: 0.0) + delta).coerceAtLeast(0.0).round2())
      }

  /** Повторы: ±1, не ниже нуля. */
  fun stepReps(setId: Long, delta: Int) =
      enqueue(setId) { it.copy(reps = ((it.reps ?: 0) + delta).coerceAtLeast(0)) }

  /** Длительность: ±15 сек, не ниже нуля. */
  fun stepDuration(setId: Long, delta: Int) =
      enqueue(setId) { it.copy(durationSec = ((it.durationSec ?: 0) + delta).coerceAtLeast(0)) }

  /** Скорость: ±0.5, не ниже нуля. */
  fun stepSpeed(setId: Long, delta: Double) =
      enqueue(setId) {
        it.copy(speedKmh = ((it.speedKmh ?: 0.0) + delta).coerceAtLeast(0.0).round2())
      }

  /** Наклон: ±0.5, не ниже нуля. */
  fun stepIncline(setId: Long, delta: Double) =
      enqueue(setId) {
        it.copy(inclinePct = ((it.inclinePct ?: 0.0) + delta).coerceAtLeast(0.0).round2())
      }

  // --- Клавиатурный ввод (NumberField): правит одно поле поверх свежего состояния подхода. ---

  fun setWeight(setId: Long, raw: String) =
      enqueue(setId) { it.copy(weightKg = raw.toDoubleOrNull()) }

  fun setReps(setId: Long, raw: String) = enqueue(setId) { it.copy(reps = raw.toIntOrNull()) }

  fun setDuration(setId: Long, raw: String) =
      enqueue(setId) { it.copy(durationSec = raw.toIntOrNull()) }

  fun setSpeed(setId: Long, raw: String) =
      enqueue(setId) { it.copy(speedKmh = raw.toDoubleOrNull()) }

  fun setIncline(setId: Long, raw: String) =
      enqueue(setId) { it.copy(inclinePct = raw.toDoubleOrNull()) }

  /** Применяет произвольную правку подхода в общей очереди (быстрая правка из уведомления). */
  fun edit(setId: Long, transform: (WorkoutSetEntity) -> WorkoutSetEntity) =
      enqueue(setId, transform)

  /** Queues a guarded completed-set numeric edit and always completes its reply. */
  suspend fun editCompletedNumbers(
      setId: Long,
      type: ExerciseType,
      values: CompletedSetNumbers,
      effort: SetEffort? = null,
  ): CompletedSetEditResult {
    val reply = CompletableDeferred<CompletedSetEditResult>()
    mutations.send(CompletedSetNumberEdit(setId, type, values, effort, reply))
    return reply.await()
  }

  private fun enqueue(setId: Long, transform: (WorkoutSetEntity) -> WorkoutSetEntity) {
    mutations.trySend(SetMutation(setId, transform))
  }
}

private fun WorkoutSetEntity.withCompletedNumbers(
    type: ExerciseType,
    values: CompletedSetNumbers,
): WorkoutSetEntity =
    when (type) {
      ExerciseType.STRENGTH -> copy(weightKg = values.weightKg, reps = values.reps)
      ExerciseType.TIMED -> copy(durationSec = values.durationSec)
      ExerciseType.CARDIO ->
          copy(
              durationSec = values.durationSec,
              speedKmh = values.speedKmh,
              inclinePct = values.inclinePct,
          )
    }

private fun WorkoutSetEntity.withCompletedEffort(
    type: ExerciseType,
    effort: SetEffort?,
): WorkoutSetEntity =
    if (type == ExerciseType.STRENGTH && effort != null) effort.applyTo(this) else this

/** Округление веса/скорости/наклона до сотых, чтобы шаги ±0.5/±2.5 не накапливали дрейф double. */
private fun Double.round2(): Double = (this * 100).roundToInt() / 100.0
