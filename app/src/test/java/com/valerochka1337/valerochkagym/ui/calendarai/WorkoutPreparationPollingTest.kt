package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutPreparationPollingTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `polling continues past twelve ticks with the captured request id`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val requestIds = mutableListOf<String>()
        val job =
            backgroundScope.launch {
              pollWorkoutPreparation("request-1") {
                requestIds += it
                true
              }
            }

        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(13, requestIds.size)
        assertEquals(List(13) { "request-1" }, requestIds)
        job.cancelAndJoin()
      }

  @Test
  fun `polling stops without another call after terminal result`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val requestIds = mutableListOf<String>()

        pollWorkoutPreparation("request-1") {
          requestIds += it
          requestIds.size < 14
        }
        advanceUntilIdle()

        assertEquals(List(14) { "request-1" }, requestIds)
      }

  @Test
  fun `cancelling polling prevents later calls`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val requestIds = mutableListOf<String>()
        val job =
            backgroundScope.launch {
              pollWorkoutPreparation("request-1") {
                requestIds += it
                true
              }
            }

        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("request-1"), requestIds)
      }

  @Test
  fun `resuming polling immediately uses the current request id`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val requestIds = mutableListOf<String>()
        val first =
            backgroundScope.launch {
              pollWorkoutPreparationWhileResumed(lifecycle.lifecycle, "request-1") { requestId ->
                pollWorkoutPreparation(requestId) {
                  requestIds += it
                  true
                }
              }
            }

        runCurrent()
        lifecycle.pause()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        lifecycle.resume()
        runCurrent()

        assertEquals(listOf("request-1", "request-1"), requestIds)
        first.cancelAndJoin()

        val replacement =
            backgroundScope.launch {
              pollWorkoutPreparationWhileResumed(lifecycle.lifecycle, "request-2") { requestId ->
                pollWorkoutPreparation(requestId) {
                  requestIds += it
                  true
                }
              }
            }
        runCurrent()

        assertEquals(listOf("request-1", "request-1", "request-2"), requestIds)
        replacement.cancelAndJoin()
      }
}

private class TestLifecycleOwner(initial: Lifecycle.State) : LifecycleOwner {
  private val registry = LifecycleRegistry.createUnsafe(this)

  override val lifecycle: Lifecycle = registry

  init {
    registry.currentState = initial
  }

  fun pause() {
    registry.currentState = Lifecycle.State.STARTED
  }

  fun resume() {
    registry.currentState = Lifecycle.State.RESUMED
  }
}
