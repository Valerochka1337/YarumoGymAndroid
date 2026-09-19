package com.valerochka1337.valerochkagym.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.valerochka1337.valerochkagym.MainActivity
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes

/** Notification construction is independent of permission; the durable chat remains the source. */
internal object CoachAlertNotificationFactory {
  const val CHANNEL_ID = "live_coach_messages"
  const val NOTIFICATION_ID = 1003

  fun createChannel(manager: NotificationManager) {
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Чат с тренером", NotificationManager.IMPORTANCE_HIGH)
            .apply {
              description = "Новые сообщения тренера во время активной тренировки"
              enableVibration(true)
              setShowBadge(false)
            },
    )
  }

  fun build(context: Context, workoutId: String): Notification {
    val intent =
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
          data = Uri.parse("valerochkagym://coach/${Uri.encode(workoutId)}")
          putExtra(MainActivity.EXTRA_DESTINATION, GymRoutes.coachChat(workoutId))
        }
    val pending =
        PendingIntent.getActivity(
            context,
            100,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_gym)
        .setContentTitle("Новое сообщение тренера")
        .setContentText("Откройте диалог Live Coach, чтобы прочитать сообщение.")
        .setContentIntent(pending)
        .setAutoCancel(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .build()
  }
}
