package com.valerochka1337.valerochkagym.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.MainActivity
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachAlertNotificationFactoryTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun `coach message channel alerts while keeping content private`() {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    CoachAlertNotificationFactory.createChannel(manager)
    val channel = manager.getNotificationChannel(CoachAlertNotificationFactory.CHANNEL_ID)
    assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    assertNotNull(channel.sound)
    assertTrue(channel.shouldVibrate())
    assertFalse(channel.canShowBadge())
    val notification = CoachAlertNotificationFactory.build(context, "workout-one")
    assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
    assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
    assertEquals(CoachAlertNotificationFactory.CHANNEL_ID, notification.channelId)
    assertFalse(notification.extras.toString().contains("workout-one"))
  }

  @Test
  fun `immutable entry keeps exact workout route when another workout alert is built`() {
    val first = CoachAlertNotificationFactory.build(context, "workout/one").contentIntent
    val second = CoachAlertNotificationFactory.build(context, "workout-two").contentIntent
    assertTrue(first.isImmutable)
    assertNotEquals(first, second)
    val intent = shadowOf(first).savedIntent
    assertEquals(MainActivity::class.java.name, intent.component?.className)
    assertEquals(
        GymRoutes.coachChat("workout/one"),
        intent.getStringExtra(MainActivity.EXTRA_DESTINATION),
    )
  }

  @Test
  fun `denied notification permission does not prevent creating the chat entry`() {
    shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertEquals(
        PackageManager.PERMISSION_DENIED,
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
    )
    val notification = CoachAlertNotificationFactory.build(context, "workout")
    assertEquals(
        GymRoutes.coachChat("workout"),
        shadowOf(notification.contentIntent)
            .savedIntent
            .getStringExtra(MainActivity.EXTRA_DESTINATION),
    )
  }
}
