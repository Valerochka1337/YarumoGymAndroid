package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEffort
import com.valerochka1337.valerochkagym.domain.WorkoutEffortSaveResult
import com.valerochka1337.valerochkagym.service.WallClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

class WorkoutEffortRepositoryTest : RoomDaoTest() {
  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    override var sessionEpoch = 0L
    override fun save(tokens: BackendTokens?) { sessionEpoch++; session.value = tokens }
  }
  private object Server : BackendTransport {
    override val json = Json
    override suspend fun public(method: String, path: String, body: JsonElement?) = error("unused")
    override suspend fun authorized(method: String, path: String, body: JsonElement?) = error("unused")
  }
  private fun repository(store: Store, sync: BackendSync, clock: WallClock = WallClock { 2000L }) =
      WorkoutEffortRepositoryImpl(db, db.workoutEffortDao(), db.workoutDao(), sync, store, clock)

  @Test fun `only completed workouts accept effort and clearing retains an explicit record`() = runTest {
    val store = Store()
    val repo = repository(store, BackendSync(db, Server, store))
    insertWorkout("active")
    insertWorkout("finished", finishedAt = 1500L)
    assertEquals(WorkoutEffortSaveResult.Invalid, repo.save(requireNotNull(repo.captureTarget("active")), WorkoutEffort.HARD))
    val target = requireNotNull(repo.captureTarget("finished"))
    assertEquals(WorkoutEffortSaveResult.Saved, repo.save(target, WorkoutEffort.HARD))
    assertEquals(WorkoutEffortSaveResult.Saved, repo.save(target, null))
    val row = requireNotNull(db.workoutEffortDao().get("finished", "GUEST"))
    assertNull(row.effort)
    assertEquals(2001L, row.updatedAt)
    assertEquals(1, tableCount("workout_efforts"))
  }

  @Test fun `same owner login invalidates an already open effort editor`() = runTest {
    val store = Store()
    val sync = BackendSync(db, Server, store)
    sync.claim("owner")
    val tokens = BackendTokens("owner", "owner@example.com", "access", "refresh")
    store.save(tokens)
    insertWorkout("finished", finishedAt = 1500L)
    val repo = repository(store, sync)
    val target = requireNotNull(repo.captureTarget("finished"))
    store.save(tokens)
    assertEquals(WorkoutEffortSaveResult.StaleOwner, repo.save(target, WorkoutEffort.HARD))
    assertEquals(0, tableCount("workout_efforts"))
  }

  @Test fun `session changes during a write roll back effort`() = runTest {
    val store = Store()
    val sync = BackendSync(db, Server, store)
    sync.claim("owner")
    val tokens = BackendTokens("owner", "owner@example.com", "access", "refresh")
    store.save(tokens)
    insertWorkout("finished", finishedAt = 1500L)
    val repo = repository(store, sync, WallClock { store.save(tokens); 2000L })
    val target = requireNotNull(repo.captureTarget("finished"))
    assertEquals(WorkoutEffortSaveResult.StaleOwner, repo.save(target, WorkoutEffort.HARD))
    assertEquals(0, tableCount("workout_efforts"))
  }
}
