package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class CoachDeliverySchedulerTest {
  @Test
  fun `new outbox writes queue behind pending delivery without cancelling it`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    val workManager = WorkManager.getInstance(context)
    val scheduler = CoachDeliveryScheduler(workManager)
    scheduler.enqueue()
    val first =
        workManager
            .getWorkInfosForUniqueWork(CoachDeliveryScheduler.UNIQUE_WORK_NAME)
            .get()
            .single()
    scheduler.enqueue()
    val jobs = workManager.getWorkInfosForUniqueWork(CoachDeliveryScheduler.UNIQUE_WORK_NAME).get()
    assertEquals(2, jobs.size)
    assertNotEquals(WorkInfo.State.CANCELLED, jobs.single { it.id == first.id }.state)
    assertEquals(WorkInfo.State.BLOCKED, jobs.single { it.id != first.id }.state)
    assertTrue(jobs.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
  }
}
