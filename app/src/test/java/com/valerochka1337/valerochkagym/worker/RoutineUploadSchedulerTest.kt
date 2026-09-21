package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoutineUploadSchedulerTest {

  private lateinit var workManager: WorkManager

  @org.junit.Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    workManager = WorkManager.getInstance(context)
  }

  @Test
  fun `schedule all enqueues the current snapshot of every routine`() = runTest {
    val scheduler =
        WorkManagerRoutineUploadScheduler(
            workManager,
            FakeRoutineDao(listOf(routine("a"), routine("b"))),
        )

    val count = scheduler.scheduleAll()

    assertEquals(2, count)
    assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_routine_a").get().size)
    assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_routine_b").get().size)
  }

  @Test
  fun `a deletion replaces the snapshot work with the same routine key`() = runTest {
    val scheduler = WorkManagerRoutineUploadScheduler(workManager, FakeRoutineDao())

    scheduler.schedule("deleted")
    scheduler.scheduleDeletion("deleted", 123L)

    assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_routine_deleted").get().size)
  }

  private fun routine(syncId: String): RoutineWithExercises =
      RoutineWithExercises(
          routine =
              RoutineEntity(
                  id = syncId.hashCode().toLong(),
                  syncId = syncId,
                  updatedAt = 1,
                  name = syncId,
              ),
          exercises = emptyList(),
      )

  private class FakeRoutineDao(
      private val routines: List<RoutineWithExercises> = emptyList(),
  ) : RoutineDao {
    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = flowOf(emptyList())

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(routines)

    override suspend fun routinesFullOnce(): List<RoutineWithExercises> =
        observeRoutinesFull().first()

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? = null

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? = null

    override suspend fun getRoutineName(id: Long): String? = null

    override suspend fun upsertRoutine(routine: RoutineEntity): Long = routine.id

    override suspend fun deleteRoutine(id: Long) = Unit

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit

    override suspend fun replaceRoutineExercises(
        routineId: Long,
        list: List<RoutineExerciseEntity>,
    ) = Unit
  }
}
