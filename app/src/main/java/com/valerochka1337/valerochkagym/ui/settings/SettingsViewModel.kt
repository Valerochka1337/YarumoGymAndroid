package com.valerochka1337.valerochkagym.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val MIN_REST_SECONDS = 15
private const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
private const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
private const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
private const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60

data class SettingsUiState(val settings: GymSettings? = null)

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val databaseExporter: DatabaseExporter,
    private val clearDataUseCase: ClearDataUseCase,
) : ViewModel() {
  val uiState =
      settingsRepository.settings
          .map { SettingsUiState(it) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  fun toggleLiveCoach(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setLiveCoachEnabled(enabled) }
  }

  /**
   * Меняет отдых по умолчанию на [delta] секунд (обычно ±[REST_STEP_SECONDS]), не ниже минимума.
   */
  fun changeDefaultRest(delta: Int) {
    val current = uiState.value.settings?.defaultRestSeconds ?: return
    val next = (current + delta).coerceAtLeast(MIN_REST_SECONDS)
    if (next == current) return
    viewModelScope.launch { settingsRepository.setDefaultRestSeconds(next) }
  }

  fun toggleSound(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setSoundEnabled(enabled) }
  }

  fun toggleVibration(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setVibrationEnabled(enabled) }
  }

  /** Тактильный отклик интерфейса (GymHaptics); вибрация уведомления таймера — отдельно. */
  fun toggleHaptics(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setHapticsEnabled(enabled) }
  }

  /** Автостарт таймера отдыха после отметки подхода. */
  fun toggleRestAutostart(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setRestAutostart(enabled) }
  }

  fun toggleHeartRateRest(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setHeartRateRestEnabled(enabled) }
  }

  fun changeHeartRateRestThreshold(delta: Int) {
    val current = uiState.value.settings?.heartRateRestThresholdBpm ?: return
    val next =
        (current + delta).coerceIn(
            MIN_HEART_RATE_REST_THRESHOLD_BPM,
            MAX_HEART_RATE_REST_THRESHOLD_BPM,
        )
    if (next == current) return
    viewModelScope.launch { settingsRepository.setHeartRateRestThresholdBpm(next) }
  }

  fun changeHeartRateRestHoldSeconds(delta: Int) {
    val current = uiState.value.settings?.heartRateRestHoldSeconds ?: return
    val next =
        (current + delta).coerceIn(
            MIN_HEART_RATE_REST_HOLD_SECONDS,
            MAX_HEART_RATE_REST_HOLD_SECONDS,
        )
    if (next == current) return
    viewModelScope.launch { settingsRepository.setHeartRateRestHoldSeconds(next) }
  }

  /** Копирует базу в выбранный пользователем документ (SAF) и сообщает итог снэкбаром. */
  fun exportDatabase(target: Uri) {
    viewModelScope.launch {
      val message =
          when (val result = databaseExporter.export(target)) {
            ExportResult.Success -> "База данных экспортирована"
            is ExportResult.Failure -> result.reason
          }
      _messages.send(message)
    }
  }

  /** Стирает историю тренировок (каталог пересевается); настройки не трогаются. */
  fun clearAllData() {
    viewModelScope.launch {
      try {
        clearDataUseCase()
        _messages.send("Данные очищены")
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _messages.send(e.message ?: "Не удалось очистить данные")
      }
    }
  }

  /**
   * Меняет акцент приложения. Иконку в лаунчере переключать отсюда не нужно: за ней следит
   * [com.valerochka1337.valerochkagym.data.appicon.AppIconManager], подписанный на настройку.
   */
  fun setAccent(accent: AccentColor) {
    viewModelScope.launch { settingsRepository.setAccent(accent) }
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { settingsRepository.setThemeMode(mode) }
  }

  fun setPaletteMode(mode: PaletteMode) {
    viewModelScope.launch { settingsRepository.setPaletteMode(mode) }
  }
}
