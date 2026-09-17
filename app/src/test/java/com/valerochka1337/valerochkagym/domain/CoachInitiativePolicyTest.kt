package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.*
import org.junit.Test

class CoachInitiativePolicyTest {
  private val ready = CoachInitiativeState(welcomed = true)

  @Test
  fun `enabling coach stays silent until a meaningful event`() {
    assertNull(CoachInitiativePolicy.next(CoachInitiativeState(), 1000, emptyList(), emptyList()))
  }

  @Test
  fun `disabled and pending states suppress every initiative`() {
    listOf(
            ready.copy(enabled = false),
            ready.copy(pendingInteraction = true),
        )
        .forEach {
          assertNull(CoachInitiativePolicy.next(it, 1_000_000, drops(), history(), 1_000_000))
        }
  }

  @Test
  fun `recent messages and persisted counts do not suppress a new initiative`() {
    val state = ready.copy(automaticCount = 9, lastAutomaticAtMillis = 1000)
    for (now in listOf(999L, 1000L, 1001L)) {
      val decision = CoachInitiativePolicy.next(state, now, drops(), history())!!
      assertEquals(CoachInitiativeKind.PERFORMANCE_QUESTION, decision.kind)
      assertEquals(10, decision.nextState.automaticCount)
      assertEquals(now, decision.nextState.lastAutomaticAtMillis)
    }
  }

  @Test
  fun `new calculation evidence permits more than three immediate interventions without repeats`() {
    var state = ready
    repeat(5) { index ->
      val assessment =
          com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationRecommendation(
              accountId = "user",
              workoutId = "workout",
              baseRevision = index.toLong(),
              kind =
                  com.valerochka1337.valerochkagym.domain.autoregulation.RecommendationKind.CLARIFY,
              observation = "Рабочий подход выполнен",
              reason = "Уточните RIR",
              expectedEffect = "",
              evidenceKey = "evidence-$index",
              options =
                  com.valerochka1337.valerochkagym.domain.autoregulation.AutoregulationOptions(),
          )
      val decision =
          CoachInitiativePolicy.next(
              state,
              1000L + index,
              emptyList(),
              emptyList(),
              assessment = assessment,
          )!!
      assertEquals(CoachInitiativeKind.AUTOREGULATION, decision.kind)
      assertEquals(index + 1, decision.nextState.automaticCount)
      assertNull(
          CoachInitiativePolicy.next(
              decision.nextState,
              1000L + index,
              emptyList(),
              emptyList(),
              assessment = assessment,
          )
      )
      state = decision.nextState.copy(pendingInteraction = false)
      assertNull(
          CoachInitiativePolicy.next(
              state,
              1000L + index,
              emptyList(),
              emptyList(),
              assessment = assessment,
          )
      )
    }
  }

  @Test
  fun `two matching historical declines ask once and wait for a reply`() {
    val decision = CoachInitiativePolicy.next(ready, 1_000_000, drops(), history())!!
    assertEquals(CoachInitiativeKind.PERFORMANCE_QUESTION, decision.kind)
    assertEquals("exercise", decision.exerciseId)
    assertTrue(decision.nextState.pendingInteraction)
    assertEquals(setOf("exercise"), decision.nextState.askedExerciseIds)
    assertNull(
        CoachInitiativePolicy.next(
            decision.nextState.copy(pendingInteraction = false),
            2_000_000,
            drops(),
            history(),
        )
    )
  }

  @Test
  fun `repeated history projections do not hide matching historical references`() {
    val repeated = history() + history()
    assertEquals(
        CoachInitiativeKind.PERFORMANCE_QUESTION,
        CoachInitiativePolicy.next(ready, 1_000_000, drops(), repeated)?.kind,
    )
  }

  @Test
  fun `one decline or a one rep reduction does not ask`() {
    assertNull(
        CoachInitiativePolicy.next(
            ready,
            1_000_000,
            listOf(current(0, 6), current(1, 8)),
            history(),
        )
    )
    assertNull(CoachInitiativePolicy.next(ready, 1_000_000, listOf(current(0, 6)), history()))
  }

  @Test
  fun `different weights never form a decline pair`() {
    assertNull(
        CoachInitiativePolicy.next(
            ready,
            1_000_000,
            listOf(current(0, 6), current(1, 7).copy(weightKg = 85.0)),
            history(),
        )
    )
  }

  @Test
  fun `historical references match weight and set position`() {
    val wrong = history().map { it.copy(weightKg = 100.0) }
    assertNull(CoachInitiativePolicy.next(ready, 1_000_000, drops(), wrong))
    assertNull(
        CoachInitiativePolicy.next(
            ready,
            1_000_000,
            drops(),
            history().map { it.copy(setIndex = it.setIndex + 3) },
        )
    )
  }

  @Test
  fun `earlier same weight set provides fallback when history is absent`() {
    val sets = listOf(current(0, 10), current(1, 8), current(2, 8))
    assertNotNull(CoachInitiativePolicy.next(ready, 1_000_000, sets, emptyList()))
  }

  @Test
  fun `warmups interrupted and incompatible modes never provide fallback`() {
    listOf("WARMUP", "TIMED", "CARDIO", "COUNTERWEIGHT", "BAND", "INTERRUPTED").forEach { mode ->
      assertNull(
          CoachInitiativePolicy.next(
              ready,
              1_000_000,
              listOf(current(0, 10).copy(setType = mode), current(1, 8), current(2, 8)),
              emptyList(),
          )
      )
    }
    assertNull(
        CoachInitiativePolicy.next(
            ready,
            1_000_000,
            listOf(current(0, 10).copy(interrupted = true), current(1, 8), current(2, 8)),
            emptyList(),
        )
    )
  }

  @Test
  fun `only last three exercise sessions contribute history`() {
    val old =
        (0..3).flatMap { session ->
          (0..1).map { index ->
            current(index, if (session == 0) 30 else 6)
                .copy(
                    setId = "$session-$index",
                    workoutId = "history-$session",
                    workoutFinishedAtMillis = (session + 1) * 1000L,
                )
          }
        }
    assertNull(CoachInitiativePolicy.next(ready, 1_000_000, drops(), old))
  }

  @Test
  fun `median reference avoids one exceptional historical result`() {
    val old =
        listOf(8, 10, 100).flatMapIndexed { session, reps ->
          (0..1).map { index ->
            current(index, reps)
                .copy(
                    setId = "$session-$index",
                    workoutId = "history-$session",
                    workoutFinishedAtMillis = (session + 1) * 1000L,
                )
          }
        }
    assertNull(
        CoachInitiativePolicy.next(ready, 1_000_000, listOf(current(0, 9), current(1, 9)), old)
    )
  }

  @Test
  fun `five minute reminder is sent once and counts toward total`() {
    assertNull(CoachInitiativePolicy.next(ready, 1000, emptyList(), emptyList(), 301_001))
    val decision = CoachInitiativePolicy.next(ready, 1000, emptyList(), emptyList(), 301_000)!!
    assertEquals(CoachInitiativeKind.END_REMINDER, decision.kind)
    assertTrue(decision.nextState.endReminderSent)
    assertEquals(1, decision.nextState.automaticCount)
    assertNull(
        CoachInitiativePolicy.next(decision.nextState, 1_000_000, emptyList(), emptyList(), 301_000)
    )
  }

  private fun current(index: Int, reps: Int) =
      CoachPerformanceSet(
          "set-$index",
          "active",
          "exercise",
          "Жим",
          index,
          80.0,
          reps,
          100_000L + index * 1000,
          setType = "WORK",
      )

  private fun drops() = listOf(current(0, 6), current(1, 7))

  private fun history() =
      listOf(current(0, 8), current(1, 9)).map {
        it.copy(setId = "old-${it.setId}", workoutId = "old", workoutFinishedAtMillis = 50_000)
      }
}
