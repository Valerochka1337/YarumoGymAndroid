package com.valerochka1337.valerochkagym

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.haptics.LocalGymHaptics
import com.valerochka1337.valerochkagym.ui.haptics.rememberGymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GuestRoutineShareNavHost
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.navigation.MainScaffold
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  @Inject
  lateinit var coachAlertNotifier: com.valerochka1337.valerochkagym.service.CoachAlertNotifier

  @Inject lateinit var settingsRepository: SettingsRepository

  /**
   * Маршрут, на который просит перейти внешний запуск (уведомление тренировки). Поток, а не простое
   * поле: активность запускается в SINGLE_TOP, так что повторный тап приходит уже в [onNewIntent] к
   * живой композиции.
   */
  private val requestedRoute = MutableStateFlow<String?>(null)
  private val guestShareToken = MutableStateFlow<String?>(null)
  private var dismissedGuestShareToken: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    dismissedGuestShareToken = savedInstanceState?.getString(STATE_DISMISSED_SHARE_TOKEN)
    guestShareToken.value =
        restoredGuestShareToken(
            savedInstanceState?.getString(STATE_ACTIVE_SHARE_TOKEN),
            dismissedGuestShareToken,
        )
    enableEdgeToEdge()
    readRequestedRoute(intent, newDelivery = savedInstanceState == null)
    setContent {
      val settingsFlow = remember { settingsRepository.settings }
      // Пока appearance не прочитан из DataStore, не рисуем промежуточную палитру.
      val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
      val route by requestedRoute.collectAsStateWithLifecycle()
      val shareToken by guestShareToken.collectAsStateWithLifecycle()
      settings?.let { currentSettings ->
        GymTheme(
            themeMode = currentSettings.themeMode,
            paletteMode = currentSettings.paletteMode,
            accent = currentSettings.accent,
        ) {
          CompositionLocalProvider(
              LocalGymHaptics provides
                  rememberGymHaptics(
                      enabled = currentSettings.hapticsEnabled,
                  ),
          ) {
            com.valerochka1337.valerochkagym.ui.account.AccountGate(
                allowGuest = shareToken != null
            ) {
              if (shareToken != null) {
                GuestRoutineShareNavHost(
                    token = shareToken!!,
                    onExit = { dismissGuestShare(shareToken!!) },
                    onOpenImportedRoutine = { id ->
                      dismissGuestShare(shareToken!!)
                      requestedRoute.value = GymRoutes.routineDetail(id)
                    },
                )
              } else {
                MainScaffold(
                    requestedRoute = route,
                    onRequestedRouteHandled = { requestedRoute.value = null },
                )
              }
            }
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    coachAlertNotifier.activityStarted(this)
  }

  override fun onStop() {
    coachAlertNotifier.activityStopped(this)
    super.onStop()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    readRequestedRoute(intent, newDelivery = true)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(STATE_ACTIVE_SHARE_TOKEN, guestShareToken.value)
    outState.putString(STATE_DISMISSED_SHARE_TOKEN, dismissedGuestShareToken)
    super.onSaveInstanceState(outState)
  }

  private fun readRequestedRoute(intent: Intent?, newDelivery: Boolean) {
    // An exported launcher alias may be explicitly targeted by another app. Only the non-exported
    // MainActivity component can carry internal notification navigation or the verified link token.
    if (intent?.component?.className == MainActivity::class.java.name) {
      intent.getStringExtra(EXTRA_ROUTINE_SHARE_TOKEN)?.let { token ->
        if (acceptsGuestShareToken(token, dismissedGuestShareToken, newDelivery)) {
          if (newDelivery) dismissedGuestShareToken = null
          guestShareToken.value = token
        }
      }
      intent.getStringExtra(EXTRA_DESTINATION)?.let { requestedRoute.value = it }
    }
  }

  private fun dismissGuestShare(token: String) {
    dismissedGuestShareToken = token
    guestShareToken.value = null
  }

  companion object {
    /** Маршрут [com.valerochka1337.valerochkagym.ui.navigation.GymRoutes], который надо открыть. */
    const val EXTRA_DESTINATION = "com.valerochka1337.valerochkagym.extra.DESTINATION"
    const val EXTRA_ROUTINE_SHARE_TOKEN =
        "com.valerochka1337.valerochkagym.extra.ROUTINE_SHARE_TOKEN"
    private const val STATE_ACTIVE_SHARE_TOKEN = "active_routine_share_token"
    private const val STATE_DISMISSED_SHARE_TOKEN = "dismissed_routine_share_token"
  }
}

internal fun acceptsGuestShareToken(
    token: String,
    dismissed: String?,
    newDelivery: Boolean = false,
): Boolean = Regex("[A-Za-z0-9_-]{43}").matches(token) && (newDelivery || token != dismissed)

internal fun restoredGuestShareToken(active: String?, dismissed: String?): String? =
    active?.takeIf { it != dismissed }
