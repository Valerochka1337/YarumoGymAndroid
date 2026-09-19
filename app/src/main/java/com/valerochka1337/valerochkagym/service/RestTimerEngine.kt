package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Источник «сейчас» в миллисекундах стенных часов. */
fun interface WallClock {
  fun nowMillis(): Long
}

/** Состояние активного отдыха: обычный таймер или ожидание снижения пульса. */
sealed interface RestTimerState {
  data class Timed(
      val totalSec: Int,
      val remainingSec: Int,
      val endsAtMillis: Long,
      val heartRateThresholdBpm: Int? = null,
      val heartRateHoldSeconds: Int = 0,
  ) : RestTimerState

  data class HeartRate(
      val thresholdBpm: Int,
      val holdSeconds: Int,
      val startedAtMillis: Long,
      val belowSinceMillis: Long? = null,
  ) : RestTimerState
}

/**
 * Чистый движок отдыха. Все переходы состояния и проверка identity таймера защищены одним lock:
 * команда, снимок которой относился к старому отдыху, не может остановить или продлить новый.
 */
@Singleton
class RestTimerEngine
@Inject
constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val clock: WallClock,
) {
  private val stateLock = Any()
  private val _state = MutableStateFlow<RestTimerState?>(null)
  val state: StateFlow<RestTimerState?> = _state.asStateFlow()

  private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val finished: SharedFlow<Unit> = _finished.asSharedFlow()

  private var tickerJob: Job? = null
  private var generation = 0
  private var activeStartId: String? = null

  /** Запускает (перезапускает) таймерный отдых на [sec] секунд. */
  fun start(sec: Int, heartRateThresholdBpm: Int? = null, heartRateHoldSeconds: Int = 0): String =
      synchronized(stateLock) {
        tickerJob?.cancel()
        val myGeneration = ++generation
        if (sec <= 0) {
          _state.value = null
          tickerJob = null
          activeStartId = null
          return@synchronized ""
        }
        val endsAt = clock.nowMillis() + sec * MILLIS_PER_SECOND
        val startId = "$myGeneration:$endsAt"
        activeStartId = startId
        _state.value =
            RestTimerState.Timed(
                totalSec = sec,
                remainingSec = sec,
                endsAtMillis = endsAt,
                heartRateThresholdBpm = heartRateThresholdBpm,
                heartRateHoldSeconds = heartRateHoldSeconds,
            )
        tickerJob = scope.launch { tick(myGeneration) }
        startId
      }

  private suspend fun tick(myGeneration: Int) {
    while (currentCoroutineContext().isActive) {
      delay(TICK_MS.milliseconds)
      val finishedNow =
          synchronized(stateLock) {
            if (myGeneration != generation) return
            val timed = _state.value as? RestTimerState.Timed ?: return
            val updated =
                timed.copy(remainingSec = remainingSeconds(timed.endsAtMillis, clock.nowMillis()))
            if (updated.remainingSec == 0 && updated.heartRateThresholdBpm != null) {
              _state.value =
                  RestTimerState.HeartRate(
                      updated.heartRateThresholdBpm,
                      updated.heartRateHoldSeconds,
                      clock.nowMillis(),
                  )
              return
            }
            _state.value = updated
            updated.remainingSec == 0
          }
      if (!finishedNow) continue
      _finished.emit(Unit)
      delay(FINAL_FRAME_MS.milliseconds)
      synchronized(stateLock) {
        if (
            myGeneration == generation && (_state.value as? RestTimerState.Timed)?.remainingSec == 0
        ) {
          _state.value = null
          activeStartId = null
        }
      }
      return
    }
  }

  /** Запускает отдых, который завершит только непрерывный интервал ниже порога. */
  fun startUntilHeartRateAtMost(thresholdBpm: Int, holdSeconds: Int): String =
      synchronized(stateLock) {
        tickerJob?.cancel()
        tickerJob = null
        val myGeneration = ++generation
        val startedAtMillis = clock.nowMillis()
        val startId = "$myGeneration:$startedAtMillis"
        activeStartId = startId
        _state.value = RestTimerState.HeartRate(thresholdBpm, holdSeconds, startedAtMillis)
        startId
      }

  /** Принимает уже проверенное на свежесть измерение. */
  fun onHeartRate(bpm: Int, updatedAtMillis: Long) {
    val didFinish =
        synchronized(stateLock) {
          val rest = _state.value as? RestTimerState.HeartRate ?: return@synchronized false
          when {
            updatedAtMillis <= rest.startedAtMillis -> false
            bpm > rest.thresholdBpm -> {
              _state.value = rest.copy(belowSinceMillis = null)
              false
            }
            else -> {
              val belowSince = rest.belowSinceMillis ?: updatedAtMillis
              if (updatedAtMillis - belowSince >= rest.holdSeconds * MILLIS_PER_SECOND) {
                _state.value = null
                activeStartId = null
                true
              } else {
                _state.value = rest.copy(belowSinceMillis = belowSince)
                false
              }
            }
          }
        }
    if (didFinish) _finished.tryEmit(Unit)
  }

  /** Потерянное измерение разрывает непрерывное удержание пульса ниже порога. */
  fun onHeartRateUnavailable() =
      synchronized(stateLock) {
        _state.update { current ->
          (current as? RestTimerState.HeartRate)?.copy(belowSinceMillis = null) ?: current
        }
      }

  /** Правит оставшееся время, не опуская дедлайн раньше «сейчас». */
  fun addSeconds(delta: Int) = synchronized(stateLock) { addSecondsLocked(delta) }

  /** Applies only to the exact rest observed by the caller; stale commands have no side effect. */
  fun addSeconds(expectedStartId: String, delta: Int): Boolean =
      synchronized(stateLock) {
        if (activeStartId != expectedStartId) return@synchronized false
        addSecondsLocked(delta)
        activeStartId == expectedStartId
      }

  private fun addSecondsLocked(delta: Int) {
    val now = clock.nowMillis()
    _state.update { current ->
      val timed = current as? RestTimerState.Timed
      if (timed == null || timed.remainingSec == 0) current
      else {
        val endsAt = (timed.endsAtMillis + delta * MILLIS_PER_SECOND).coerceAtLeast(now)
        val remaining = remainingSeconds(endsAt, now)
        timed.copy(
            totalSec = maxOf(timed.totalSec, remaining),
            remainingSec = remaining,
            endsAtMillis = endsAt,
        )
      }
    }
  }

  /** Останавливает отдых немедленно, без сигнала [finished]. */
  fun skip() = synchronized(stateLock) { skipLocked() }

  fun skip(expectedStartId: String): Boolean =
      synchronized(stateLock) {
        if (activeStartId != expectedStartId) return@synchronized false
        skipLocked()
        true
      }

  private fun skipLocked() {
    generation++
    activeStartId = null
    tickerJob?.cancel()
    tickerJob = null
    _state.value = null
  }

  fun currentStartId(): String? = synchronized(stateLock) { activeStartId }
}

private fun remainingSeconds(endsAtMillis: Long, nowMillis: Long): Int {
  val left = endsAtMillis - nowMillis
  if (left <= 0) return 0
  return ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
}

private const val MILLIS_PER_SECOND = 1000L
private const val TICK_MS = 1000L
private const val FINAL_FRAME_MS = 1000L
