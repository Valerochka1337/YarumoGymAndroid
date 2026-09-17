package com.valerochka1337.valerochkagym.service

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Accessed on Main by Activity, chat and the workout service. */
@Singleton
class CoachAlertNotifier @Inject constructor(@ApplicationContext private val context: Context) {
  private val visibleChats = mutableMapOf<Any, String>()
  private val manager
    get() = context.getSystemService(NotificationManager::class.java)

  fun chatResumed(workoutId: String, host: Any) {
    visibleChats[host] = workoutId
    chatViewed(workoutId)
  }

  fun chatPaused(host: Any) {
    visibleChats.remove(host)
  }

  fun show(workoutId: String) {
    if (workoutId in visibleChats.values) return
    if (
        android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
    )
        return
    if (!manager.areNotificationsEnabled()) return
    // Preserve an explicitly disabled old coach channel when introducing message alerts.
    if (
        manager.getNotificationChannel("live_coach")?.importance ==
            NotificationManager.IMPORTANCE_NONE
    )
        return
    CoachAlertNotificationFactory.createChannel(manager)
    manager.notify(
        workoutId,
        CoachAlertNotificationFactory.NOTIFICATION_ID,
        CoachAlertNotificationFactory.build(context, workoutId),
    )
  }

  fun chatViewed(workoutId: String) {
    manager.cancel(workoutId, CoachAlertNotificationFactory.NOTIFICATION_ID)
    // Remove an alert posted by a build that predates workout-specific notification tags.
    manager.cancel(CoachAlertNotificationFactory.NOTIFICATION_ID)
  }
}
