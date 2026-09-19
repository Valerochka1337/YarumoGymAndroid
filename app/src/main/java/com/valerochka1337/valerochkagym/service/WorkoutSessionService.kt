package com.valerochka1337.valerochkagym.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.valerochka1337.valerochkagym.MainActivity
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.diagnostics.CoachDiagnostics
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.SessionFocus
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutSetMutator
import com.valerochka1337.valerochkagym.domain.currentFocus
import com.valerochka1337.valerochkagym.domain.formatSet
import com.valerochka1337.valerochkagym.domain.lastCompletedFocus
import com.valerochka1337.valerochkagym.domain.parseQuickSetEdit
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.freshAt
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground-сервис активной тренировки. Держит одно постоянное уведомление, которое живёт в двух
 * состояниях:
 * * **рабочее** — «Жим лёжа · подход 3 из 4», текущие вес×повторы, полоса подходов упражнения и
 *   кнопки «−2.5 кг / +2.5 кг / Готово»;
 * * **отдых** — обратный отсчёт, только что закрытый подход и кнопки «+15 с / Пропустить /
 *   Изменить» (последняя открывает инлайн-поле и правит этот подход).
 *
 * Уведомление помечено как Live Update (см. [requestPromotedOngoing]), поэтому система показывает
 * его чипом в статус-баре, а оболочки вроде Fluid Cloud на OxygenOS — в «острове». Отсюда все
 * ограничения формата: стандартный шаблон вместо RemoteViews, без `setColorized` и не больше трёх
 * кнопок — их набор поэтому контекстный.
 *
 * Отсчёт в чипе рисует сама система: `setChronometerCountDown` + `setWhen(endsAtMillis)` тикают без
 * участия приложения, поэтому [RestTimerEngine] и обязан хранить дедлайн по стенным часам.
 *
 * Используется платформенный [Notification.Builder], а не `NotificationCompat`: `ProgressStyle` в
 * androidx.core этой версии ещё нет, а minSdk 36 позволяет звать платформу напрямую.
 *
 * Жизненный цикл: [start] вызывается из UI при старте тренировки (приложение на переднем плане —
 * запуск FGS разрешён). Явный stop не нужен: сервис подписан на
 * [ActiveWorkoutRepository.observeActive] и сам вызывает stopSelf, когда активной тренировки не
 * стало (finish/discard).
 */
@AndroidEntryPoint
class WorkoutSessionService : LifecycleService() {

  @Inject lateinit var activeWorkoutRepository: ActiveWorkoutRepository

  @Inject lateinit var restTimerEngine: RestTimerEngine

  @Inject lateinit var settingsRepository: SettingsRepository

  @Inject lateinit var setMutator: WorkoutSetMutator

  @Inject lateinit var workoutEditor: WorkoutEditor

  @Inject lateinit var heartRateMonitor: HeartRateMonitor

  @Inject lateinit var coachConversation: CoachConversationService

  @Inject lateinit var coachAlertNotifier: CoachAlertNotifier

  private val notificationManager: NotificationManager by lazy {
    getSystemService(NotificationManager::class.java)
  }

  private var currentWorkout: WorkoutFull? = null
  private var currentRest: RestTimerState? = null
  private var accent: AccentColor = AccentColor.DEFAULT
  private var usesConnectedDeviceForegroundType = false
  private var connectedDeviceForegroundPromotionFailed = false

  /**
   * Внутренний приёмник действий уведомления. Не экспортируется.
   *
   * Подход берётся не из intent'а, а пересчитывается по свежему [currentWorkout]: уведомление в
   * шторке может отставать от БД, а нажатие должно применяться к тому подходу, который актуален
   * сейчас.
   */
  private val actionReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
          when (intent?.action) {
            ACTION_REST_ADD_15 -> restTimerEngine.addSeconds(REST_STEP_SECONDS)
            ACTION_REST_SKIP -> restTimerEngine.skip()
            ACTION_SET_STEP_DOWN -> stepCurrentSet(-1)
            ACTION_SET_STEP_UP -> stepCurrentSet(1)
            ACTION_SET_COMPLETE -> completeCurrentSet()
            ACTION_SET_QUICK_EDIT -> applyQuickEdit(intent)
          }
        }
      }

  override fun onCreate() {
    super.onCreate()
    createChannels()
    registerReceiver(
        actionReceiver,
        IntentFilter().apply {
          addAction(ACTION_REST_ADD_15)
          addAction(ACTION_REST_SKIP)
          addAction(ACTION_SET_STEP_DOWN)
          addAction(ACTION_SET_STEP_UP)
          addAction(ACTION_SET_COMPLETE)
          addAction(ACTION_SET_QUICK_EDIT)
        },
        RECEIVER_NOT_EXPORTED,
    )
    coachConversation.attach(lifecycleScope)
    observeHeartRate()
    observeState()
    observeCoachAlerts()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    // Здесь, а не в onCreate: покрывает и первый старт, и повторную доставку intent — так
    // startForeground гарантированно вызывается в отведённое окно после startForegroundService.
    startForeground(
        SESSION_NOTIFICATION_ID,
        buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )
    // NOT_STICKY: после гибели процесса состояние движка/тренировки не восстановить, а sticky
    // рестарт оставил бы «зомби»-уведомление «Тренировка» без реальной сессии.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    coachConversation.detach()
    heartRateMonitor.stop()
    unregisterReceiver(actionReceiver)
    super.onDestroy()
  }

  private fun observeState() {
    val coachTrigger = CoachInitiativeTrigger()
    lifecycleScope.launch {
      while (true) {
        kotlinx.coroutines.delay(60_000)
        currentWorkout?.workout?.id?.let {
          CoachDiagnostics.event("initiative.trigger", "source" to "minute_timer")
          coachConversation.considerInitiative(it)
        }
      }
    }
    // Один collect на все источники уведомления: combine конфлейтит одновременные изменения
    // (закрытие подхода сразу запускает отдых), и уведомление пересобирается один раз, а не
    // по разу на каждый затронутый поток.
    lifecycleScope.launch {
      combine(
              activeWorkoutRepository.observeActive(),
              restTimerEngine.state,
              settingsRepository.settings.map { it.accent }.distinctUntilChanged(),
          ) { workout, rest, accentValue ->
            Triple(workout, rest, accentValue)
          }
          .collect { (workout, rest, accentValue) ->
            val coachInputsChanged = coachTrigger.changed(workout, rest)
            currentRest = rest
            accent = accentValue
            if (workout == null) {
              currentWorkout?.workout?.id?.let(coachConversation::stopWorkout)
              heartRateMonitor.stop()
              stopSelf()
              return@collect
            }
            currentWorkout = workout
            if (coachInputsChanged) {
              lifecycleScope.launch {
                CoachDiagnostics.event("initiative.trigger", "source" to "workout_or_rest_changed")
                coachConversation.considerInitiative(workout.workout.id)
              }
            }
            // Обновляем и во время отдыха: там подписан только что закрытый подход, а его
            // правят кнопкой «Изменить» прямо из этого уведомления.
            updateForegroundNotification()
          }
    }
    lifecycleScope.launch { restTimerEngine.finished.collect { onRestFinished() } }
  }

  /** The content stays private: an alert only tells the athlete that the chat has an update. */
  private fun observeCoachAlerts() {
    lifecycleScope.launch {
      coachConversation.alerts.collect { workoutId -> coachAlertNotifier.show(workoutId) }
    }
  }

  /** Live HR не сохраняем: свежие измерения управляют завершением отдыха по пульсу. */
  private fun observeHeartRate() {
    lifecycleScope.launch {
      heartRateMonitor.reading.collect { reading ->
        val fresh = reading.freshAt(System.currentTimeMillis())
        if (fresh != null) {
          restTimerEngine.onHeartRate(fresh.bpm, fresh.updatedAtMillis)
        } else {
          restTimerEngine.onHeartRateUnavailable()
        }
      }
    }
    lifecycleScope.launch {
      heartRateMonitor.state.collect { state ->
        if (
            state !is HeartRateConnectionState.Idle &&
                state !is HeartRateConnectionState.PermissionRequired
        ) {
          promoteForConnectedDeviceIfAllowed()
        }
      }
    }
  }

  /**
   * Сначала сервис стартует как workout special-use. После выданных Nearby devices-разрешений
   * расширяем тип FGS до connectedDevice, пока GATT/scan живёт вместе с тренировкой.
   */
  private fun promoteForConnectedDeviceIfAllowed() {
    if (
        usesConnectedDeviceForegroundType ||
            connectedDeviceForegroundPromotionFailed ||
            !hasBluetoothPermissions()
    ) {
      return
    }
    try {
      startForeground(
          SESSION_NOTIFICATION_ID,
          buildNotification(),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
              ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
      usesConnectedDeviceForegroundType = true
    } catch (_: RuntimeException) {
      // Отдельные прошивки отклоняют повторную смену FGS type, хотя разрешения уже выданы.
      // Не даём этому убить весь процесс тренировки: останавливаем GATT и показываем ошибку.
      connectedDeviceForegroundPromotionFailed = true
      heartRateMonitor.reportError(
          "Android не разрешил фоновое Bluetooth-подключение. Перезапустите тренировку и попробуйте ещё раз.",
      )
    }
  }

  private fun hasBluetoothPermissions(): Boolean =
      ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
              android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun updateForegroundNotification() {
    notificationManager.notify(SESSION_NOTIFICATION_ID, buildNotification())
  }

  // --- Действия уведомления ---

  /**
   * Шаг ± по главному значению подхода: вес у силовых, длительность у временных, скорость у кардио.
   */
  private fun stepCurrentSet(sign: Int) {
    val focus = currentWorkout?.currentFocus() ?: return
    when (focus.type) {
      ExerciseType.STRENGTH -> setMutator.stepWeight(focus.set.id, sign * WEIGHT_STEP_KG)
      ExerciseType.TIMED -> setMutator.stepDuration(focus.set.id, sign * DURATION_STEP_SECONDS)
      ExerciseType.CARDIO -> setMutator.stepSpeed(focus.set.id, sign * SPEED_STEP_KMH)
    }
  }

  private fun completeCurrentSet() {
    val setId = currentWorkout?.currentFocus()?.set?.id ?: return
    lifecycleScope.launch { workoutEditor.completeSetFromUser(setId) }
  }

  /** Правит только что закрытый подход по строке из инлайн-поля («60x8»). Мусор игнорируется. */
  private fun applyQuickEdit(intent: Intent) {
    val raw =
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
    val focus = currentWorkout?.lastCompletedFocus()
    if (raw != null && focus != null) {
      parseQuickSetEdit(raw, focus.type)?.let { edit ->
        setMutator.edit(focus.set.id) { edit.applyTo(it) }
      }
    }
    // Пересобираем уведомление в любом случае: пока оно не обновилось, система держит поле
    // ввода открытым со спиннером — даже если правка не применилась.
    updateForegroundNotification()
  }

  // --- Сборка уведомлений ---

  private fun buildNotification(): Notification {
    val rest = currentRest
    return if (rest != null) buildRestNotification(rest) else buildWorkNotification()
  }

  private fun baseBuilder(): Notification.Builder =
      Notification.Builder(this, SESSION_CHANNEL_ID)
          .setContentIntent(openActiveWorkoutIntent())
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setCategory(Notification.CATEGORY_WORKOUT)
          // setColor красит иконку и акценты шаблона. setColorized звать нельзя — он запрещён
          // для промотируемых уведомлений и выкинул бы нас из острова.
          .setColor(accent.primary.toArgb())
          .requestPromotedOngoing()

  private fun buildWorkNotification(): Notification {
    val workout = currentWorkout
    val builder = baseBuilder().setSmallIcon(R.drawable.ic_notification_gym)
    workout?.workout?.startedAt?.let { startedAt ->
      // Живое время тренировки без ручных тиков.
      builder.setUsesChronometer(true).setWhen(startedAt).setShowWhen(true)
    }

    val focus = workout?.currentFocus()
    if (focus == null) {
      // Тренировка ещё не загрузилась (первый startForeground) либо все подходы закрыты.
      builder.setContentTitle(workout?.workout?.name ?: DEFAULT_WORKOUT_TITLE)
      if (workout != null) builder.setContentText(ALL_SETS_DONE_TEXT)
      return builder.build()
    }

    builder
        .setContentTitle(
            "${focus.exerciseName} · подход ${focus.setNumber} из ${focus.setsInExercise}"
        )
        // Влезает в чип целиком: он показывает текст полностью только до 7 символов.
        .setShortCriticalText("${focus.setNumber}/${focus.setsInExercise}")
        .setStyle(setsProgressStyle(focus))
    formatSet(focus.set, focus.type)?.let { builder.setContentText(it) }

    val (down, up) = stepLabels(focus.type)
    builder
        .addAction(action(R.drawable.ic_notif_minus, down, ACTION_SET_STEP_DOWN, REQUEST_STEP_DOWN))
        .addAction(action(R.drawable.ic_notif_plus, up, ACTION_SET_STEP_UP, REQUEST_STEP_UP))
        .addAction(
            action(R.drawable.ic_notif_check, COMPLETE_LABEL, ACTION_SET_COMPLETE, REQUEST_COMPLETE)
        )
    return builder.build()
  }

  private fun buildRestNotification(rest: RestTimerState): Notification {
    val workout = currentWorkout
    val done = workout?.lastCompletedFocus()
    val builder =
        baseBuilder()
            .setSmallIcon(R.drawable.ic_rest_timer)
            .setContentTitle(restTitle(workout?.currentFocus(), done))
            .addAction(action(R.drawable.ic_notif_skip, SKIP_LABEL, ACTION_REST_SKIP, REQUEST_SKIP))

    when (rest) {
      is RestTimerState.Timed ->
          builder
              // Система сама тикает отсчёт до дедлайна — и в шторке, и в чипе статус-бара.
              // shortCriticalText здесь намеренно не ставим: он бы вытеснил живое время.
              .setUsesChronometer(true)
              .setChronometerCountDown(true)
              .setWhen(rest.endsAtMillis)
              .setShowWhen(true)
              .setStyle(restProgressStyle(rest))
              .addAction(
                  action(R.drawable.ic_notif_plus, ADD_15_LABEL, ACTION_REST_ADD_15, REQUEST_ADD_15)
              )

      is RestTimerState.HeartRate ->
          builder.setContentText(
              "Ожидание пульса ≤ ${rest.thresholdBpm} BPM",
          )
    }

    if (done != null) {
      val value = formatSet(done.set, done.type)
      if (rest is RestTimerState.Timed) {
        builder.setContentText(listOfNotNull(done.exerciseName, value).joinToString(" · "))
      }
      builder.addAction(quickEditAction(done.type))
    }
    return builder.build()
  }

  /** «Отдых · далее подход 4», а на стыке упражнений — «Отдых · далее Тяга верхнего блока». */
  private fun restTitle(next: SessionFocus?, done: SessionFocus?): String =
      when {
        next == null -> REST_TITLE
        done != null && next.exerciseName == done.exerciseName ->
            "$REST_TITLE · далее подход ${next.setNumber}"
        else -> "$REST_TITLE · далее ${next.exerciseName}"
      }

  /** Полоса подходов текущего упражнения: закрытые — до бегунка, оставшиеся — приглушены. */
  private fun setsProgressStyle(focus: SessionFocus): Notification.ProgressStyle {
    val style =
        Notification.ProgressStyle()
            .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification_gym))
    // currentFocus — первый незакрытый подход упражнения, значит всё до него уже сделано.
    repeat(focus.setsInExercise) {
      style.addProgressSegment(
          Notification.ProgressStyle.Segment(1).setColor(accent.primary.toArgb()),
      )
    }
    return style.setProgress(focus.setNumber - 1)
  }

  /** Одна полоса, заполняющаяся по мере отдыха. */
  private fun restProgressStyle(rest: RestTimerState.Timed): Notification.ProgressStyle =
      Notification.ProgressStyle()
          .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_rest_timer))
          .addProgressSegment(
              Notification.ProgressStyle.Segment(rest.totalSec).setColor(accent.primary.toArgb()),
          )
          .setProgress(rest.totalSec - rest.remainingSec)

  private fun onRestFinished() {
    lifecycleScope.launch {
      val settings = settingsRepository.settings.first()
      // Рингтон открывает медиа-ресурсы — не на главном потоке сервиса.
      if (settings.soundEnabled) withContext(Dispatchers.IO) { playFinishedSound() }
      if (settings.vibrationEnabled) vibrate()
    }
    val notification =
        Notification.Builder(this, REST_DONE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rest_timer)
            .setContentTitle(REST_DONE_TITLE)
            // Mi Fitness не всегда ретранслирует уведомления без непустимой основной строки.
            // Оставляем нейтральную строку вместо прежнего призыва «Пора продолжать».
            .setContentText(REST_DONE_TEXT)
            .setContentIntent(openActiveWorkoutIntent())
            .setColor(accent.primary.toArgb())
            .setAutoCancel(true)
            .setTimeoutAfter(REST_DONE_TIMEOUT_MS)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .build()
    notificationManager.notify(REST_DONE_NOTIFICATION_ID, notification)
  }

  private fun playFinishedSound() {
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
    RingtoneManager.getRingtone(applicationContext, uri)?.play()
  }

  private fun vibrate() {
    val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
  }

  // --- Intent'ы ---

  /**
   * Тап по уведомлению/чипу открывает сразу экран активной тренировки.
   *
   * Именно через extra, а не через URI-deep link: [MainActivity] объявлена `exported="false"` без
   * intent-filter (вход идёт через activity-alias под выбранную иконку), так что навигационный deep
   * link по URI до неё просто не доедет.
   */
  private fun openActiveWorkoutIntent(): PendingIntent {
    val intent =
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
          putExtra(MainActivity.EXTRA_DESTINATION, GymRoutes.ACTIVE_WORKOUT)
        }
    return PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun action(
      icon: Int,
      label: String,
      action: String,
      requestCode: Int,
  ): Notification.Action =
      Notification.Action.Builder(
              Icon.createWithResource(this, icon),
              label,
              broadcastIntent(action, requestCode, PendingIntent.FLAG_IMMUTABLE),
          )
          .build()

  private fun quickEditAction(type: ExerciseType): Notification.Action {
    val remoteInput =
        RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(quickEditHint(type))
            .setAllowFreeFormInput(true)
            .build()
    return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_edit),
            EDIT_LABEL,
            // MUTABLE обязателен: систему нужно пустить дописать в intent результат RemoteInput.
            broadcastIntent(ACTION_SET_QUICK_EDIT, REQUEST_QUICK_EDIT, PendingIntent.FLAG_MUTABLE),
        )
        .addRemoteInput(remoteInput)
        .build()
  }

  private fun broadcastIntent(action: String, requestCode: Int, mutability: Int): PendingIntent {
    val intent = Intent(action).setPackage(packageName)
    return PendingIntent.getBroadcast(
        this,
        requestCode,
        intent,
        mutability or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun createChannels() {
    val session =
        NotificationChannel(
                SESSION_CHANNEL_ID,
                SESSION_CHANNEL_NAME,
                // LOW: без звука, но промотировать в Live Update можно всё, кроме IMPORTANCE_MIN.
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply {
              description = SESSION_CHANNEL_DESC
              setShowBadge(false)
            }
    // Звук/вибрацию канала отключаем — сигнал даём вручную по пользовательским настройкам.
    val restDone =
        NotificationChannel(
                REST_DONE_CHANNEL_ID,
                REST_DONE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            )
            .apply {
              description = REST_DONE_CHANNEL_DESC
              setSound(null, null)
              enableVibration(false)
            }
    notificationManager.createNotificationChannel(session)
    notificationManager.createNotificationChannel(restDone)
    CoachAlertNotificationFactory.createChannel(notificationManager)
  }

  companion object {
    fun start(context: Context) {
      context.startForegroundService(Intent(context, WorkoutSessionService::class.java))
    }

    private const val SESSION_CHANNEL_ID = "workout_session"
    private const val SESSION_CHANNEL_NAME = "Активная тренировка"
    private const val SESSION_CHANNEL_DESC = "Уведомление идущей тренировки и таймера отдыха"
    private const val REST_DONE_CHANNEL_ID = "rest_timer"
    private const val REST_DONE_CHANNEL_NAME = "Окончание отдыха"
    private const val REST_DONE_CHANNEL_DESC = "Сигнал о том, что отдых закончился"
    private const val SESSION_NOTIFICATION_ID = 1001
    private const val REST_DONE_NOTIFICATION_ID = 1002
    private const val ACTION_REST_ADD_15 = "com.valerochka1337.valerochkagym.action.REST_ADD_15"
    private const val ACTION_REST_SKIP = "com.valerochka1337.valerochkagym.action.REST_SKIP"
    private const val ACTION_SET_STEP_DOWN = "com.valerochka1337.valerochkagym.action.SET_STEP_DOWN"
    private const val ACTION_SET_STEP_UP = "com.valerochka1337.valerochkagym.action.SET_STEP_UP"
    private const val ACTION_SET_COMPLETE = "com.valerochka1337.valerochkagym.action.SET_COMPLETE"
    private const val ACTION_SET_QUICK_EDIT =
        "com.valerochka1337.valerochkagym.action.SET_QUICK_EDIT"

    private const val REMOTE_INPUT_KEY = "quick_set_edit"

    private const val REQUEST_OPEN_APP = 0
    private const val REQUEST_ADD_15 = 1
    private const val REQUEST_SKIP = 2
    private const val REQUEST_STEP_DOWN = 3
    private const val REQUEST_STEP_UP = 4
    private const val REQUEST_COMPLETE = 5
    private const val REQUEST_QUICK_EDIT = 6

    private const val REST_STEP_SECONDS = 15
    private const val WEIGHT_STEP_KG = 2.5
    private const val DURATION_STEP_SECONDS = 15
    private const val SPEED_STEP_KMH = 0.5
    private const val VIBRATION_MS = 500L
    private const val REST_DONE_TIMEOUT_MS = 10_000L

    private const val ADD_15_LABEL = "+15 с"
    private const val SKIP_LABEL = "Пропустить"
    private const val COMPLETE_LABEL = "Готово"
    private const val EDIT_LABEL = "Изменить"

    private const val DEFAULT_WORKOUT_TITLE = "Тренировка"
    private const val ALL_SETS_DONE_TEXT = "Все подходы выполнены"
    private const val REST_TITLE = "Отдых"
    private const val REST_DONE_TITLE = "Отдых завершён"
    private const val REST_DONE_TEXT = "Иди работай"
  }
}

/**
 * Просим систему поднять уведомление до Live Update — чип в статус-баре и «остров» оболочки.
 *
 * Запрос ставится экстрой, а не типизированным `setRequestPromotedOngoing`, намеренно. Сеттер
 * появился только в SDK 36.1 и не делает ничего, кроме этой же записи в extras, — а OxygenOS 16 на
 * OnePlus 15 репортит 36.0, но Live Updates поддерживает (в системе есть и разрешение
 * `POST_PROMOTED_NOTIFICATIONS`, и служба `com.oplus.systemui...LIVE_ALERT_SERVICE`). Проверка
 * минорной версии отсекала бы именно то устройство, ради которого фича и делается. На прошивках без
 * поддержки лишняя экстра просто игнорируется.
 */
private fun Notification.Builder.requestPromotedOngoing(): Notification.Builder =
    addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })

/** Значение [Notification.EXTRA_REQUEST_PROMOTED_ONGOING]; константа доступна только с SDK 36.1. */
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

/** Подписи кнопок шага: главное значение подхода зависит от типа упражнения. */
private fun stepLabels(type: ExerciseType): Pair<String, String> =
    when (type) {
      ExerciseType.STRENGTH -> "−2.5 кг" to "+2.5 кг"
      ExerciseType.TIMED -> "−15 с" to "+15 с"
      ExerciseType.CARDIO -> "−0.5 км/ч" to "+0.5 км/ч"
    }

/** Подсказка в инлайн-поле быстрой правки — показывает ожидаемый формат. */
private fun quickEditHint(type: ExerciseType): String =
    when (type) {
      ExerciseType.STRENGTH -> "60x8"
      ExerciseType.TIMED -> "45"
      ExerciseType.CARDIO -> "10x5"
    }
