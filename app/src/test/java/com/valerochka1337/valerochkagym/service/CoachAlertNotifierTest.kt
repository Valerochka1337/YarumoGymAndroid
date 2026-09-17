package com.valerochka1337.valerochkagym.service

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachAlertNotifierTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val manager = context.getSystemService(NotificationManager::class.java)

  @org.junit.Before
  fun allowNotifications() {
    org.robolectric.Shadows.shadowOf(context)
        .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  }

  @Test
  fun `only the resumed matching chat suppresses alerts and pausing restores delivery`() {
    val notifier = CoachAlertNotifier(context)
    val first = Any()
    val second = Any()
    notifier.chatResumed("w", first)
    notifier.chatResumed("w", second)
    notifier.chatPaused(first)
    notifier.show("w")
    assertTrue(manager.activeNotifications.isEmpty())
    notifier.show("other")
    assertEquals("other", manager.activeNotifications.single().tag)
    notifier.chatPaused(second)
    notifier.show("w")
    assertEquals(setOf("w", "other"), manager.activeNotifications.map { it.tag }.toSet())
  }

  @Test
  fun `denied permission leaves notifications absent without throwing`() {
    org.robolectric.Shadows.shadowOf(context)
        .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    CoachAlertNotifier(context).show("w")
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun `disabled legacy channel remains respected`() {
    manager.createNotificationChannel(
        android.app.NotificationChannel("live_coach", "Coach", NotificationManager.IMPORTANCE_NONE)
    )
    CoachAlertNotifier(context).show("w")
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun `opening a chat clears its alert after process recreation without clearing other chats`() {
    CoachAlertNotifier(context).apply {
      show("first")
      show("second")
    }
    CoachAlertNotifier(context).chatViewed("first")
    assertEquals("second", manager.activeNotifications.single().tag)
  }
}
